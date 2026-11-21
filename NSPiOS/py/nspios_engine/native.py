"""Zugriff auf die nativen Bibliotheken in NSPiOSNative.framework (SPIRV-Cross, lz4, capstone)."""
import ctypes, os, tempfile

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
