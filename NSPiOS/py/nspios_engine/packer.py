"""On-Device-Packer: führt nxn_pack.py (ns2ios-shell) im eingebetteten Interpreter aus.

iOS erlaubt keine Unterprozesse. Der Packer startet aber seine Teilschritte als `[python, skript.py, ...]` und ruft
spirv-cross auf. Beides wird hier umgeleitet: Python-Skripte laufen im selben Interpreter (wie ein eigener Prozess:
eigene argv/env/cwd/Ausgabe, frisch importierte Hilfsmodule), spirv-cross läuft in NSPiOSNative.framework.
"""
import contextlib, io, os, runpy, signal, subprocess, sys, traceback
from pathlib import Path

# sys.executable bleibt unverändert: Python auf iOS findet seine nativen Module (Frameworks) relativ dazu.
# Der Packer ruft `[sys.executable, skript.py, ...]` auf – genau das wird erkannt und im Interpreter ausgeführt.
SPIRV = '/nspios/bin/spirv-cross'   # SPIRV_CROSS für die Shader-Werkzeuge (_install legt eine echte Platzhalterdatei an)

_installed = False
_real_run = subprocess.run


class Packed(Exception):
    pass


class _Exec(BaseException):
    """os.exec* in einem eingebetteten „Prozess“: Befehl und Umgebung für den Neustart."""
    def __init__(self, argv, env):
        super().__init__(argv)
        self.argv, self.env = argv, env


class _Result:
    """Das, was Pool.apply_async/map_async zurückgeben, über einem Future."""
    def __init__(self, future, one=True):
        self._f, self._one = future, one

    def get(self, timeout=None):
        return self._f.result(timeout)

    def wait(self, timeout=None):
        import concurrent.futures as cf
        cf.wait([self._f], timeout)

    def ready(self):
        return self._f.done()

    def successful(self):
        return self._f.done() and self._f.exception() is None


class ThreadPoolAsMPPool:
    """multiprocessing.Pool mit Threads: gleiche Aufrufe, gleiche Reihenfolge der Ergebnisse."""
    def __init__(self, processes=None, initializer=None, initargs=(), maxtasksperchild=None, context=None):
        import concurrent.futures as cf
        self._ex = cf.ThreadPoolExecutor(max_workers=processes or int(os.environ.get('J', '4')),
                                         initializer=initializer, initargs=initargs)
        self._closed = False

    def apply(self, func, args=(), kwds={}):
        return self._ex.submit(func, *args, **kwds).result()

    def apply_async(self, func, args=(), kwds={}, callback=None, error_callback=None):
        f = self._ex.submit(func, *args, **kwds)
        if callback or error_callback:
            def done(fut):
                e = fut.exception()
                if e is None:
                    if callback: callback(fut.result())
                elif error_callback:
                    error_callback(e)
            f.add_done_callback(done)
        return _Result(f)

    def map(self, func, iterable, chunksize=None):
        return list(self._ex.map(func, iterable))

    def map_async(self, func, iterable, chunksize=None, callback=None, error_callback=None):
        items = list(iterable)
        return self.apply_async(self.map, (func, items),
                                callback=callback, error_callback=error_callback)

    def imap(self, func, iterable, chunksize=1):
        return self._ex.map(func, iterable)

    def imap_unordered(self, func, iterable, chunksize=1):
        import concurrent.futures as cf
        fs = [self._ex.submit(func, x) for x in iterable]
        return (f.result() for f in cf.as_completed(fs))

    def starmap(self, func, iterable, chunksize=None):
        return [r for r in self._ex.map(lambda a: func(*a), iterable)]

    def starmap_async(self, func, iterable, chunksize=None, callback=None, error_callback=None):
        items = list(iterable)
        return self.apply_async(self.starmap, (func, items), callback=callback, error_callback=error_callback)

    def close(self):
        self._closed = True

    def terminate(self):
        self._ex.shutdown(wait=False, cancel_futures=True)

    def join(self):
        self._ex.shutdown(wait=True)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.terminate()   # wie multiprocessing.Pool: __exit__ beendet den Pool


def _make_spirv_stub():
    """Platzhalterdatei für spirv-cross: Werkzeug-Prüfungen (which, Fingerabdruck per Datei-Hash) brauchen eine echte
    Datei. Ausgeführt wird sie nie – Aufrufe dieses Pfads leitet _run in NSPiOSNative.framework um."""
    global SPIRV
    import tempfile
    from . import native
    d = os.path.join(tempfile.gettempdir(), 'nspios-bin')
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, 'spirv-cross')
    body = '#!/bin/sh\n# spirv-cross %s\n' % native.REVISION
    try:
        with open(path) as f:
            same = f.read() == body
    except OSError:
        same = False
    if not same:
        with open(path, 'w') as f:
            f.write(body)
    try:
        os.chmod(path, 0o755)
    except OSError:
        pass
    SPIRV = path


def _install():
    """Einmalig: Unterprozesse umleiten, Signale tolerant machen, Umgebung setzen."""
    global _installed
    if _installed:
        return
    _installed = True
    _make_spirv_stub()
    os.environ['SPIRV_CROSS'] = SPIRV
    os.environ.setdefault('JT', '4')       # Threads der Shader-Übersetzung (Standard wäre 2× Kerne)
    os.environ['DM_REWRITE_J'] = '1'       # keine fork-Worker (auf iOS nicht möglich)
    os.environ['DMSH_PROCS'] = '0'         # dmsh_build.py: Shader in Threads statt Prozessen fertigstellen
    os.environ['NXN_METAL_TOOLS'] = 'off'  # kein Metal-Compiler: Quellen ins Paket, das Gerät kompiliert beim 1. Start
    os.environ['NSPIOS_ONE_LIBRARY'] = '1' # Reflexion ohne entry_u_side (keine <key>_bx, siehe scripts/patch_vendor.py)
    os.environ.setdefault('J', '4')

    # Prozess-Pools (shader_split.py u. a.) → Thread-Pools: iOS startet keine Zusatzprozesse.
    import concurrent.futures as cf

    class ThreadPoolAsProcessPool(cf.ThreadPoolExecutor):
        def __init__(self, max_workers=None, mp_context=None, initializer=None, initargs=(), **_):
            super().__init__(max_workers=max_workers or int(os.environ.get('J', '4')),
                             initializer=initializer, initargs=initargs)
    cf.ProcessPoolExecutor = ThreadPoolAsProcessPool

    # multiprocessing.Pool (c1_guard.py, patch_nso.py) → Threads. Auch multiprocessing.pool.ThreadPool ginge nicht:
    # es legt intern Prozess-Sperren an, und dafür fehlt _multiprocessing auf iOS.
    import multiprocessing, multiprocessing.context
    from . import mp_threads
    mp_threads.install(ThreadPoolAsMPPool)

    # os.execv*/execve: ein Skript, das sich selbst neu startet (patch_nso.py bei großem Code), würde NSPiOS ersetzen.
    # Stattdessen bricht der „Prozess“ ab und _run_python führt den neuen Befehl im Interpreter aus.
    def exec_instead(path, argv, env=None):
        raise _Exec(list(argv), dict(env) if env is not None else None)
    for name in ('execv', 'execve', 'execvp', 'execvpe'):
        setattr(os, name, exec_instead)

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

    # spirv-cross ist kein Programm im PATH, sondern läuft im Prozess: die Suche des Packers darauf umlenken
    real_which = shutil.which

    def which(cmd, mode=os.F_OK | os.X_OK, path=None):
        if os.path.basename(str(cmd)).lower() in ('spirv-cross', 'spirv-cross.exe'):
            return SPIRV
        return real_which(cmd, mode, path)
    shutil.which = which

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
    elif base in ('dmsh_xlate', 'dmsh_xlate.exe'):
        from nspios_engine import native
        rc, out, err = native.dmsh_xlate(argv[1:])
        for text, target in ((out, stdout), (err, stderr)):
            if text and target not in (subprocess.PIPE, None) and hasattr(target, 'write'):
                target.write(text)
        if stdout is None and out:
            sys.stdout.write(out)
        if stderr is None and err:
            sys.stderr.write(err)
    elif prog == SPIRV or base in ('spirv-cross', 'spirv-cross.exe'):
        from nspios_engine import native
        rc, out = native.spirv_cross(argv[1:])
        err = ''
        if stdout not in (subprocess.PIPE, None) and hasattr(stdout, 'write'):
            stdout.write(out); out = ''
        elif stdout is None:
            sys.stdout.write(out); out = ''
    else:
        res = _builtin_tool(base, argv, input, cwd)
        if res is None:
            raise FileNotFoundError(f'NSPiOS: the program {prog!r} is not available on iOS')
        rc, out, err = res
        out = _deliver(out, stdout, sys.stdout, stdout)
        err = _deliver(err, stderr, sys.stderr, stdout)

    if not as_text:
        out = out.encode() if isinstance(out, str) else out
        err = err.encode() if isinstance(err, str) else err
    result = subprocess.CompletedProcess(argv, rc,
                                         out if stdout == subprocess.PIPE else None,
                                         err if stderr == subprocess.PIPE else None)
    if check and rc:
        raise subprocess.CalledProcessError(rc, argv, result.stdout, result.stderr)
    return result


def _deliver(data, target, console, stdout_target):
    """Ausgabe eines eingebauten Befehls dorthin, wohin ein echter Prozess sie geschrieben hätte. → was in PIPE bleibt."""
    if not data or target == subprocess.PIPE:
        return data
    if target == subprocess.STDOUT:
        target = stdout_target
        if target == subprocess.PIPE:
            return data
    if target == subprocess.DEVNULL:
        return b'' if isinstance(data, bytes) else ''
    if target is None:
        target = console
    if isinstance(target, int):
        os.write(target, data if isinstance(data, bytes) else data.encode())
    else:
        try:
            target.write(data)
        except TypeError:
            target.write(data.decode(errors='replace') if isinstance(data, bytes) else data.encode())
    return b'' if isinstance(data, bytes) else ''


def _builtin_tool(base, argv, input, cwd):
    """Kleine Unix-Befehle, die der Packer aufruft, im Prozess nachgebildet. → (rc, stdout, stderr) oder None."""
    import shutil
    here = lambda p: p if os.path.isabs(p) or not cwd else os.path.join(cwd, p)
    opts = [a for a in argv[1:] if a.startswith('-')]
    args = [a for a in argv[1:] if not a.startswith('-')]
    if base in ('zstd', 'zstd.exe', 'unzstd', 'zstdcat'):
        if base == 'zstd' and not ({'-d', '--decompress', '-dc', '-cd', '-dcq'} & set(opts)):
            return None                                   # Packen braucht der Packer nicht
        from backports import zstd as z
        try:
            if input is not None:
                data = bytes(input)
            else:
                data = b''.join(open(here(a), 'rb').read() for a in args)
            return 0, z.decompress(data), ''
        except (z.ZstdError, OSError) as e:
            return 1, b'', 'zstd: %s\n' % e
    if base == 'cp':
        if len(args) < 2:
            return 1, '', 'cp: missing operand\n'
        srcs, dst = [here(a) for a in args[:-1]], here(args[-1])
        recursive = any(o in ('-R', '-r', '-a') or ('R' in o and not o.startswith('--')) for o in opts)
        try:
            for src in srcs:
                target = os.path.join(dst, os.path.basename(src.rstrip('/'))) if os.path.isdir(dst) else dst
                if os.path.isdir(src):
                    if not recursive:
                        return 1, '', 'cp: %s is a directory\n' % src
                    shutil.copytree(src, target, symlinks=True, dirs_exist_ok=True)
                else:
                    shutil.copy2(src, target)
            return 0, '', ''
        except OSError as e:
            return 1, '', 'cp: %s\n' % e
    if base == 'xattr':
        return 1, '', 'xattr: no such xattr\n'            # iOS-Dateien tragen keine Packer-Attribute
    if base == 'sysctl':
        name = args[0] if args else ''
        val = {'hw.optional.arm64': '1', 'hw.ncpu': str(os.cpu_count() or 4), 'hw.logicalcpu': str(os.cpu_count() or 4),
               'hw.physicalcpu': str(os.cpu_count() or 4), 'hw.perflevel0.physicalcpu': str(os.cpu_count() or 4)}.get(name)
        if val is None and name == 'hw.memsize':
            try:
                val = str(os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES'))
            except (ValueError, OSError, AttributeError):
                val = str(6 << 30)
        if val is None:
            return 1, '', 'sysctl: unknown oid %r\n' % name
        return 0, (val if '-n' in opts else '%s: %s' % (name, val)) + '\n', ''
    if base == 'which':
        hits = [shutil.which(a) for a in args]
        return (0 if hits and all(hits) else 1), ''.join(h + '\n' for h in hits if h), ''
    if base == 'uname':
        return 0, ('arm64' if '-m' in opts or '-p' in opts else 'Darwin') + '\n', ''
    if base == 'true':
        return 0, '', ''
    if base == 'false':
        return 1, '', ''
    if base == 'ln':
        if len(args) != 2:
            return 1, '', 'ln: usage\n'
        try:
            dst = here(args[1])
            if os.path.isdir(dst) and not os.path.islink(dst):
                dst = os.path.join(dst, os.path.basename(args[0]))
            if '-f' in ''.join(opts) and os.path.lexists(dst):
                os.unlink(dst)
            (os.symlink if 's' in ''.join(opts) else os.link)(args[0] if 's' in ''.join(opts) else here(args[0]), dst)
            return 0, '', ''
        except OSError as e:
            return 1, '', 'ln: %s\n' % e
    return None


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
    saved_sub = (subprocess.run, subprocess.Popen, subprocess.call, subprocess.check_call, subprocess.check_output)
    saved_env = dict(os.environ)
    saved_modules = set(sys.modules)
    rc = 0
    again = None
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
            except _Exec as e:
                again = e
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
        (subprocess.run, subprocess.Popen, subprocess.call, subprocess.check_call, subprocess.check_output) = saved_sub
        os.chdir(saved_cwd)
        if env is not None:
            os.environ.clear()
            os.environ.update(saved_env)
        # Hilfsmodule des „Prozesses“ wieder vergessen, damit der nächste sie frisch lädt
        for name in list(sys.modules):
            if name not in saved_modules:
                del sys.modules[name]
    out_text, err_text = (out_buf.getvalue() if out_buf else ''), (err_buf.getvalue() if err_buf else '')
    if again is not None:
        prog = again.argv[0] if again.argv else ''
        if not (prog == sys.executable or os.path.basename(prog).lower().startswith('python')):
            return 1, out_text, err_text + 'NSPiOS: exec of %r is not possible on iOS\n' % prog
        rc, o2, e2 = _run_python(again.argv[1:], cwd, again.env if again.env is not None else env, stdout, stderr)
        return rc, out_text + o2, err_text + e2
    return rc, out_text, err_text


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


# Stand der Shader-Bibliotheken, die NSPiOS selbst richtigstellt (entry_u_side, c1_full/cb_extent nach dem Abbruch in c1_guard).
# Ein Arbeitsordner von einem älteren Stand wird neu übersetzt (der Shader-Cache bleibt, das geht schnell).
SHADER_FIX = '2'


def _refresh_stale_shaders(dump):
    import shutil
    for work in Path(dump).glob('*_pack'):
        stamp = work / 'NSPIOS_SHADER_FIX'
        if stamp.is_file() and stamp.read_text().strip() == SHADER_FIX:
            continue
        shutil.rmtree(work / 'build' / 'ios' / 'dmsh_b', ignore_errors=True)
        cache = work / 'shader' / 'cache'
        for old in list(cache.glob('b-*')) if cache.is_dir() else []:
            if old.is_dir():
                shutil.rmtree(old, ignore_errors=True)
            else:
                old.unlink(missing_ok=True)


def _mark_shaders(dump):
    for work in Path(dump).glob('*_pack'):
        try:
            (work / 'NSPIOS_SHADER_FIX').write_text(SHADER_FIX + '\n')
        except OSError:
            pass


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

    if not args.get('no_shaders'):
        _refresh_stale_shaders(args['dump'])
    log = Path(args['log'])
    log.parent.mkdir(parents=True, exist_ok=True)
    with open(log, 'w', encoding='utf-8', errors='replace') as f:
        rc, _, _ = _run_python([str(script)] + argv, None, None, f, subprocess.STDOUT)
    if rc == 0 and not args.get('no_shaders'):
        _mark_shaders(args['dump'])
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
    if rc != 0:
        return {'rc': rc, 'log': '\n'.join(tail)}

    import shutil
    if shutil.which('spirv-cross') != SPIRV:
        return {'rc': 3, 'log': 'spirv-cross is not found through shutil.which'}
    import multiprocessing
    with multiprocessing.Pool() as pool:   # c1_guard.py: ohne _multiprocessing nur mit dem Thread-Ersatz
        if pool.map(abs, [-1, -2], chunksize=64) != [1, 2]:
            return {'rc': 3, 'log': 'multiprocessing.Pool replacement is broken'}
    v = multiprocessing.get_context('fork').Value('i', 0)
    with v.get_lock():
        v.value += 1
    # zstd (Spieldateien), eingebaute Befehle und ein Skript, das sich per os.execve neu startet
    from backports import zstd
    frame = bytes.fromhex('28b52ffd60e0009d0000604e5350694f53207a737464200100d1574b12')
    if zstd.decompress(frame) != b'NSPiOS zstd ' * 40:
        return {'rc': 3, 'log': 'zstd decompress is broken'}
    if subprocess.run(['zstd', '-d', '-c', '-q'], input=frame, capture_output=True).stdout != b'NSPiOS zstd ' * 40:
        return {'rc': 3, 'log': 'the zstd command is broken'}
    import tempfile
    tdir = Path(tempfile.mkdtemp(prefix='nspios-smoke-'))
    (tdir / 'again.py').write_text("import os, sys\n"
                                   "if 'AGAIN' not in os.environ:\n"
                                   "    os.execve(sys.executable, [sys.executable] + sys.argv, dict(os.environ, AGAIN='1'))\n"
                                   "sys.exit(7)\n")
    if subprocess.run([sys.executable, str(tdir / 'again.py')], capture_output=True).returncode != 7:
        return {'rc': 3, 'log': 'os.execve re-run is broken'}
    if subprocess.run(['cp', '-c', str(tdir / 'again.py'), str(tdir / 'copy.py')]).returncode or not (tdir / 'copy.py').is_file():
        return {'rc': 3, 'log': 'the cp command is broken'}
    if subprocess.run(['sysctl', '-n', 'hw.optional.arm64'], capture_output=True, text=True).stdout.strip() != '1':
        return {'rc': 3, 'log': 'the sysctl command is broken'}
    import shutil as _sh
    _sh.rmtree(tdir, ignore_errors=True)

    # Shader-Übersetzung wie im Fertigbau: vk_shaders.py mit einer „Aufnahme“ aus einem Test-Shader
    from nspios_engine.selftest import _minimal_compute_spirv
    cap = root / 'vkcap'
    cap.mkdir()
    (cap / '0000000000000001.spv').write_bytes(_minimal_compute_spirv())
    out = root / 'vkout'
    vk = Path(args['packer']) / 'dm' / 'gfx' / 'tools' / 'vk_shaders.py'
    vlog = root / 'vk.log'
    with open(vlog, 'w', encoding='utf-8', errors='replace') as f:
        vrc, _, _ = _run_python([str(vk), str(romfs), str(cap), str(out), '--source-only'],
                                None, None, f, subprocess.STDOUT)
    vtail = vlog.read_text(encoding='utf-8', errors='replace').strip().splitlines()[-12:]
    table = out / 'vk' / 'vk_shaders.tsv'
    if vrc == 0 and not table.is_file():
        vrc = 2
        vtail.append('no vk_shaders.tsv written')
    return {'rc': vrc, 'log': '\n'.join(tail + ['--- vk_shaders.py ---'] + vtail)}
