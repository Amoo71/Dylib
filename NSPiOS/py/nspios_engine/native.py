"""Zugriff auf die nativen Bibliotheken in NSPiOSNative.framework (SPIRV-Cross, lz4, capstone)."""
import ctypes, os, tempfile, threading

_lib = ctypes.CDLL(os.environ['NSPIOS_NATIVE_LIB'])

_lib.nspios_spirv_cross_main.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
_lib.nspios_spirv_cross_main.restype = ctypes.c_int

REVISION = 'vulkan-sdk-1.4.357.0 (NSPiOS, in-process)'


def spirv_cross(args):
    """Führt die spirv-cross-CLI im Prozess aus. Gibt (Rückgabewert, Ausgabe) zurück.

    Ohne --output wird in eine Temp-Datei geschrieben und deren Inhalt als „stdout“ geliefert,
    damit parallele Aufrufe sich nicht die Standardausgabe teilen.
    """
    args = [str(a) for a in args]
    if '--revision' in args:
        return 0, REVISION + '\n'
    tmp = None
    if '--output' not in args:
        fd, tmp = tempfile.mkstemp(suffix='.spvc')
        os.close(fd)
        args += ['--output', tmp]
    argv = [b'spirv-cross'] + [a.encode() for a in args]
    arr = (ctypes.c_char_p * len(argv))(*argv)
    rc = _lib.nspios_spirv_cross_main(len(argv), arr)
    out = ''
    if tmp:
        try:
            with open(tmp, encoding='utf-8', errors='replace') as f:
                out = f.read()
        finally:
            os.remove(tmp)
    return rc, out


def spirv_to_msl(spirv: bytes, extra=()):
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, 'in.spv')
        with open(src, 'wb') as f:
            f.write(spirv)
        rc, out = spirv_cross([src, '--msl', *extra])
        if rc != 0:
            raise RuntimeError(f'spirv-cross failed ({rc})')
        return out


# --- nxnatives Shader-Übersetzer (dmsh_xlate, NativeAOT) -------------------------------------------------------------

_xlate = None
_xlate_lock = threading.Lock()


def xlate_available():
    p = os.environ.get('NSPIOS_XLATE_LIB', '')
    return bool(p) and os.path.exists(p)


def dmsh_xlate(args):
    """Führt dmsh_xlate im Prozess aus (wie die CLI). Gibt (Rückgabewert, stdout, stderr) zurück.

    Der Übersetzer schreibt auf die Datei-Deskriptoren 1/2; die werden für die Dauer des Aufrufs in Temp-Dateien
    umgeleitet (seriell, damit sich Aufrufe nicht die Ausgabe teilen).
    """
    global _xlate
    if not xlate_available():
        return 127, '', 'NSPiOS: the shader translator (NSPiOSXlate.framework) is missing from this build\n'
    if _xlate is None:
        _xlate = ctypes.CDLL(os.environ['NSPIOS_XLATE_LIB'])
        _xlate.nspios_dmsh_xlate_main.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
        _xlate.nspios_dmsh_xlate_main.restype = ctypes.c_int
    argv = [str(a).encode() for a in args]
    arr = (ctypes.c_char_p * max(len(argv), 1))(*argv)
    with _xlate_lock:
        out_fd, out_path = tempfile.mkstemp(suffix='.out')
        err_fd, err_path = tempfile.mkstemp(suffix='.err')
        import sys
        for stream in (sys.stdout, sys.stderr):
            try:
                stream.flush()
            except Exception:
                pass
        saved_out, saved_err = os.dup(1), os.dup(2)
        try:
            os.dup2(out_fd, 1)
            os.dup2(err_fd, 2)
            rc = _xlate.nspios_dmsh_xlate_main(len(argv), arr)
        finally:
            os.dup2(saved_out, 1)
            os.dup2(saved_err, 2)
            os.close(saved_out)
            os.close(saved_err)
            os.close(out_fd)
            os.close(err_fd)
        with open(out_path, encoding='utf-8', errors='replace') as f:
            out = f.read()
        with open(err_path, encoding='utf-8', errors='replace') as f:
            err = f.read()
        os.remove(out_path)
        os.remove(err_path)
    return rc, out, err
