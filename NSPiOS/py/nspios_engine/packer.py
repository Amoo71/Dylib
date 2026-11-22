"""On-Device-Packer: führt nxn_pack.py (ns2ios-shell) im eingebetteten Interpreter aus.

iOS erlaubt keine Unterprozesse. Der Packer startet aber seine Teilschritte als `[python, skript.py, ...]` und ruft
spirv-cross auf. Beides wird hier umgeleitet: Python-Skripte laufen im selben Interpreter (wie ein eigener Prozess:
eigene argv/env/cwd/Ausgabe, frisch importierte Hilfsmodule), spirv-cross läuft in NSPiOSNative.framework.
"""
import contextlib, io, os, runpy, signal, subprocess, sys, traceback
from pathlib import Path

# sys.executable bleibt unverändert: Python auf iOS findet seine nativen Module (Frameworks) relativ dazu.
# Der Packer ruft `[sys.executable, skript.py, ...]` auf – genau das wird erkannt und im Interpreter ausgeführt.
SPIRV = '/nspios/bin/spirv-cross'   # SPIRV_CROSS für die Shader-Werkzeuge

_installed = False
_real_run = subprocess.run


class Packed(Exception):
    pass


def _install():
    """Einmalig: Unterprozesse umleiten, Signale tolerant machen, Umgebung setzen."""
    global _installed
    if _installed:
        return
    _installed = True
    os.environ['SPIRV_CROSS'] = SPIRV
    os.environ['DM_REWRITE_J'] = '1'       # keine fork-Worker (auf iOS nicht möglich)
    os.environ.setdefault('J', '4')

    real_signal = signal.signal

    def tolerant_signal(sig, handler):
        try:
            return real_signal(sig, handler)
        except ValueError:                  # nicht im Haupt-Thread: Signale gibt es hier ohnehin nicht
            return None
    signal.signal = tolerant_signal

    # RomFS-Dateien hart verlinken statt kopieren: der Packer kopiert eine entpackte RomFS sonst komplett in die App
    # (Gigabytes doppelt). Nur Dateien unter .../romfs/, die nichts danach an Ort und Stelle ändert.
    import shutil
    real_copy2 = shutil.copy2

    def link_or_copy2(src, dst, *, follow_symlinks=True):
        s = os.fspath(src)
        marker = os.sep + 'romfs' + os.sep
        if marker in s or marker in os.fspath(dst):
            target = os.path.join(dst, os.path.basename(s)) if os.path.isdir(dst) else os.fspath(dst)
            try:
                if os.path.lexists(target):
                    os.remove(target)
                os.link(os.path.realpath(s), target)
                return target
            except OSError:
                pass
        return real_copy2(src, dst, follow_symlinks=follow_symlinks)
    shutil.copy2 = link_or_copy2

    # copytree bindet copy2 schon als Standardwert beim Definieren – dort ebenfalls umleiten
    real_copytree = shutil.copytree

    def copytree(src, dst, symlinks=False, ignore=None, copy_function=None,
                 ignore_dangling_symlinks=False, dirs_exist_ok=False):
        if copy_function is None or copy_function is real_copy2:
            copy_function = link_or_copy2
        return real_copytree(src, dst, symlinks, ignore, copy_function, ignore_dangling_symlinks, dirs_exist_ok)
    shutil.copytree = copytree

    subprocess.run = _run
    subprocess.call = lambda *a, **k: _run(*a, **k).returncode
    subprocess.check_call = lambda *a, **k: _run(*a, check=True, **k).returncode
    subprocess.check_output = lambda *a, **k: _run(*a, check=True, stdout=subprocess.PIPE, **k).stdout

    class _NoPopen:
        def __init__(self, cmd, *a, **k):
            raise FileNotFoundError(f'NSPiOS: no external programs on iOS ({cmd[0] if cmd else "?"})')
    subprocess.Popen = _NoPopen


def _argv(cmd):
    if isinstance(cmd, (list, tuple)):
        return [os.fspath(c) if not isinstance(c, str) else c for c in cmd]
    return str(cmd).split()


def _run(cmd, *args, stdin=None, input=None, stdout=None, stderr=None, capture_output=False, cwd=None, env=None,
         text=None, encoding=None, errors=None, universal_newlines=None, check=False, timeout=None, **_):
    argv = _argv(cmd)
    if not argv:
        raise FileNotFoundError('empty command')
    prog = argv[0]
    base = os.path.basename(prog).lower()
    as_text = bool(text or encoding or errors or universal_newlines)
    if capture_output:
        stdout = stderr = subprocess.PIPE

    if prog == sys.executable or base.startswith('python'):
        rc, out, err = _run_python(argv[1:], cwd, env, stdout, stderr)
    elif prog == SPIRV or base in ('spirv-cross', 'spirv-cross.exe'):
        from nspios_engine import native
        rc, out = native.spirv_cross(argv[1:])
        err = ''
        if stdout not in (subprocess.PIPE, None) and hasattr(stdout, 'write'):
            stdout.write(out); out = ''
        elif stdout is None:
            sys.stdout.write(out); out = ''
    else:
        raise FileNotFoundError(f'NSPiOS: the program {prog!r} is not available on iOS')

    if not as_text:
        out = out.encode() if isinstance(out, str) else out
        err = err.encode() if isinstance(err, str) else err
    result = subprocess.CompletedProcess(argv, rc,
                                         out if stdout == subprocess.PIPE else None,
                                         err if stderr == subprocess.PIPE else None)
    if check and rc:
        raise subprocess.CalledProcessError(rc, argv, result.stdout, result.stderr)
    return result


def _run_python(args, cwd, env, stdout, stderr):
    """Ein Python-„Prozess“ im selben Interpreter. Gibt (Rückgabewert, stdout, stderr) zurück."""
    code = None
    i = 0
    while i < len(args) and args[i].startswith('-'):
        if args[i] == '-c':
            code = args[i + 1]
            i += 2
            break
        i += 2 if args[i] in ('-X', '-W') else 1
    script_args = args[i:]

    out_buf = io.StringIO() if stdout == subprocess.PIPE else None
    err_buf = io.StringIO() if stderr == subprocess.PIPE else None
    out_target = out_buf or (stdout if hasattr(stdout, 'write') else sys.stdout)
    if stderr == subprocess.STDOUT:
        err_target = out_target
    else:
        err_target = err_buf or (stderr if hasattr(stderr, 'write') else sys.stderr)

    saved_argv, saved_path, saved_cwd = sys.argv[:], sys.path[:], os.getcwd()
    saved_env = dict(os.environ)
    saved_modules = set(sys.modules)
    rc = 0
    try:
        if env is not None:
            os.environ.clear()
            os.environ.update({k: str(v) for k, v in env.items()})
            os.environ.setdefault('NSPIOS_NATIVE_LIB', saved_env.get('NSPIOS_NATIVE_LIB', ''))
        if cwd:
            os.chdir(cwd)
        with contextlib.redirect_stdout(out_target), contextlib.redirect_stderr(err_target):
            try:
                if code is not None:
                    sys.argv = ['-c'] + script_args
                    exec(compile(code, '<string>', 'exec'), {'__name__': '__main__'})
                else:
                    script = os.path.abspath(script_args[0])
                    sys.argv = [script] + script_args[1:]
                    sys.path.insert(0, os.path.dirname(script))
                    runpy.run_path(script, run_name='__main__')
            except SystemExit as e:
                if e.code is None:
                    rc = 0
                elif isinstance(e.code, int):
                    rc = e.code
                else:
                    print(e.code, file=sys.stderr)
                    rc = 1
            except BaseException:
                traceback.print_exc()
                rc = 1
    finally:
        sys.argv, sys.path[:] = saved_argv, saved_path
        os.chdir(saved_cwd)
        if env is not None:
            os.environ.clear()
            os.environ.update(saved_env)
        # Hilfsmodule des „Prozesses“ wieder vergessen, damit der nächste sie frisch lädt
        for name in list(sys.modules):
            if name not in saved_modules:
                del sys.modules[name]
    return rc, (out_buf.getvalue() if out_buf else ''), (err_buf.getvalue() if err_buf else '')


def prepare(args):
    """Kopiert den gebündelten Packer in einen beschreibbaren Ordner (er schreibt in seine Profile)."""
    import shutil
    src, dst = Path(args['bundle']), Path(args['dest'])
    tag = (src / 'NSPIOS_PACKER_TAG').read_text().strip() if (src / 'NSPIOS_PACKER_TAG').is_file() else '?'
    stamp = dst / 'NSPIOS_PACKER_TAG'
    if not (stamp.is_file() and stamp.read_text().strip() == tag):
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
    games = dst / 'dm' / 'games'
    if not any((d / 'game.tsv').is_file() for d in games.iterdir()):
        raise RuntimeError('no game profiles in the packer')
    return {'packer': str(dst), 'tag': tag}


def pack(args):
    """Packt ein Spiel aus einem entpackten Ordner (base/exefs, base/romfs).

    args: packer (Ordner mit scripts/nxn_pack.py), shell (Shell-ZIP), dump (Spielordner), log (Logdatei),
          optional no_shaders (bool), vk_capture (Ordner), version (z. B. v0)
    Rückgabe: {"app": Pfad der fertigen .app, "work": Arbeitsordner, "rc": 0}
    """
    _install()
    packer = Path(args['packer'])
    script = packer / 'scripts' / 'nxn_pack.py'
    argv = [str(args['shell']), str(args['dump']), '--stop-after', 'assemble', '--keep-work']
    if args.get('no_shaders'):
        argv.append('--no-shaders')
    if args.get('vk_capture'):
        argv += ['--vk-capture', str(args['vk_capture'])]
    if args.get('version'):
        argv += ['-p', str(args['version'])]

    log = Path(args['log'])
    log.parent.mkdir(parents=True, exist_ok=True)
    with open(log, 'w', encoding='utf-8', errors='replace') as f:
        rc, _, _ = _run_python([str(script)] + argv, None, None, f, subprocess.STDOUT)
    if rc != 0:
        tail = log.read_text(encoding='utf-8', errors='replace').strip().splitlines()[-25:]
        raise Packed('\n'.join(tail) or f'nxn_pack.py exit {rc}')

    stage = Path(args['dump'])
    apps = sorted(stage.glob('*_pack/stage/Payload/*.app'))
    if not apps:
        raise Packed('the packer finished but left no app tree')
    return {'app': str(apps[-1]), 'work': str(apps[-1].parents[2]), 'rc': rc}


def smoke(args):
    """CI-Probe im Simulator: Fake-Spielordner (Titel-ID von Galaxy 2), Packer bis nach dem Entpack-Schritt."""
    import struct, tempfile
    _install()
    root = Path(tempfile.mkdtemp(prefix='nspios-smoke-'))
    exefs, romfs = root / 'base' / 'exefs', root / 'base' / 'romfs'
    exefs.mkdir(parents=True)
    (romfs / 'data').mkdir(parents=True)
    npdm = bytearray(0xC0)
    npdm[0:4] = b'META'
    struct.pack_into('<II', npdm, 0x70, 0x80, 0x40)
    npdm[0x80:0x84] = b'ACI0'
    struct.pack_into('<Q', npdm, 0x90, 0x0100FD8022DAA000)
    (exefs / 'main.npdm').write_bytes(bytes(npdm))
    for m in ('main', 'sdk', 'subsdk0'):
        (exefs / m).write_bytes(b'NSO0' + bytes(0x100))
    (romfs / 'hagi.config').write_text('smoke\n')
    (romfs / 'data' / 'a.bin').write_bytes(bytes(4096))

    log = root / 'smoke.log'
    script = Path(args['packer']) / 'scripts' / 'nxn_pack.py'
    with open(log, 'w', encoding='utf-8', errors='replace') as f:
        rc, _, _ = _run_python([str(script), str(args['shell']), str(root), '--stop-after', 'extract', '--keep-work'],
                               None, None, f, subprocess.STDOUT)
    tail = log.read_text(encoding='utf-8', errors='replace').strip().splitlines()[-12:]
    return {'rc': rc, 'log': '\n'.join(tail)}
