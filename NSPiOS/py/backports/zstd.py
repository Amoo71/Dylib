"""Entpacken im Stil von compression.zstd (Python 3.14) / backports.zstd über libzstd in NSPiOSNative.framework.

Der Packer braucht nur die Entpack-Seite: decompress(), ZstdDecompressor (Streaming mit max_length, eof,
needs_input, unused_data), ZstdDict und ZstdError.
"""
import ctypes, os

__all__ = ['ZstdError', 'ZstdDict', 'ZstdDecompressor', 'decompress']

_lib = ctypes.CDLL(os.environ['NSPIOS_NATIVE_LIB'])


class _InBuffer(ctypes.Structure):
    _fields_ = [('src', ctypes.c_void_p), ('size', ctypes.c_size_t), ('pos', ctypes.c_size_t)]


class _OutBuffer(ctypes.Structure):
    _fields_ = [('dst', ctypes.c_void_p), ('size', ctypes.c_size_t), ('pos', ctypes.c_size_t)]


_lib.ZSTD_createDCtx.restype = ctypes.c_void_p
_lib.ZSTD_createDCtx.argtypes = []
_lib.ZSTD_freeDCtx.restype = ctypes.c_size_t
_lib.ZSTD_freeDCtx.argtypes = [ctypes.c_void_p]
_lib.ZSTD_DCtx_loadDictionary.restype = ctypes.c_size_t
_lib.ZSTD_DCtx_loadDictionary.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t]
_lib.ZSTD_DCtx_setParameter.restype = ctypes.c_size_t
_lib.ZSTD_DCtx_setParameter.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
_lib.ZSTD_decompressStream.restype = ctypes.c_size_t
_lib.ZSTD_decompressStream.argtypes = [ctypes.c_void_p, ctypes.POINTER(_OutBuffer), ctypes.POINTER(_InBuffer)]
_lib.ZSTD_isError.restype = ctypes.c_uint
_lib.ZSTD_isError.argtypes = [ctypes.c_size_t]
_lib.ZSTD_getErrorName.restype = ctypes.c_char_p
_lib.ZSTD_getErrorName.argtypes = [ctypes.c_size_t]
_lib.ZSTD_DStreamOutSize.restype = ctypes.c_size_t
_lib.ZSTD_getDictID_fromDict.restype = ctypes.c_uint
_lib.ZSTD_getDictID_fromDict.argtypes = [ctypes.c_char_p, ctypes.c_size_t]

_WINDOWLOG_MAX = 100          # ZSTD_d_windowLogMax
_CHUNK = max(int(_lib.ZSTD_DStreamOutSize()), 1 << 17)


class ZstdError(Exception):
    pass


def _check(code):
    if _lib.ZSTD_isError(code):
        raise ZstdError('Unable to decompress zstd data: ' + _lib.ZSTD_getErrorName(code).decode(errors='replace'))
    return code


class ZstdDict:
    def __init__(self, dict_content, /, *, is_raw=False):
        self.dict_content = bytes(dict_content)
        self.is_raw = is_raw
        self.dict_id = 0 if is_raw else int(_lib.ZSTD_getDictID_fromDict(self.dict_content, len(self.dict_content)))

    def __len__(self):
        return len(self.dict_content)

    # wie im Original: als Entpack-Wörterbuch einsetzbar
    @property
    def as_digested_dict(self):
        return (self, 0)

    @property
    def as_undigested_dict(self):
        return (self, 1)

    @property
    def as_prefix(self):
        return (self, 2)


def _dict_of(zstd_dict):
    if zstd_dict is None:
        return None
    if isinstance(zstd_dict, tuple):
        zstd_dict = zstd_dict[0]
    if isinstance(zstd_dict, (bytes, bytearray, memoryview)):
        zstd_dict = ZstdDict(zstd_dict)
    return zstd_dict


class ZstdDecompressor:
    """Entpackt genau einen Frame; danach ist eof wahr und unused_data hält die Bytes dahinter."""

    def __init__(self, zstd_dict=None, options=None):
        self._ctx = _lib.ZSTD_createDCtx()
        if not self._ctx:
            raise MemoryError('ZSTD_createDCtx')
        _check(_lib.ZSTD_DCtx_setParameter(self._ctx, _WINDOWLOG_MAX, 31))
        d = _dict_of(zstd_dict)
        if d is not None:
            _check(_lib.ZSTD_DCtx_loadDictionary(self._ctx, d.dict_content, len(d.dict_content)))
        self._pending = b''        # Eingabe, die noch nicht verbraucht ist
        self._eof = False
        self._unused = b''
        self._needs_input = True

    def __del__(self):
        ctx, self._ctx = getattr(self, '_ctx', None), None
        if ctx:
            _lib.ZSTD_freeDCtx(ctx)

    @property
    def eof(self):
        return self._eof

    @property
    def needs_input(self):
        return self._needs_input

    @property
    def unused_data(self):
        return self._unused

    def decompress(self, data, max_length=-1):
        if self._eof:
            raise EOFError('Already at the end of a zstd frame')
        data = self._pending + bytes(data) if self._pending else bytes(data)
        self._pending = b''
        limit = None if max_length is None or max_length < 0 else int(max_length)
        src = ctypes.create_string_buffer(data, len(data)) if data else None
        inb = _InBuffer(ctypes.cast(src, ctypes.c_void_p) if src is not None else None, len(data), 0)
        parts, total = [], 0
        while True:
            room = _CHUNK if limit is None else min(_CHUNK, limit - total)
            if room <= 0:
                break
            dst = ctypes.create_string_buffer(room)
            outb = _OutBuffer(ctypes.cast(dst, ctypes.c_void_p), room, 0)
            ret = _check(_lib.ZSTD_decompressStream(self._ctx, ctypes.byref(outb), ctypes.byref(inb)))
            if outb.pos:
                parts.append(dst.raw[:outb.pos])
                total += outb.pos
            if ret == 0:                       # Frame vollständig
                self._eof = True
                self._unused = data[inb.pos:]
                self._needs_input = False
                return b''.join(parts)
            if outb.pos < room and inb.pos >= inb.size:
                break                          # Eingabe aufgebraucht, keine Ausgabe mehr möglich
            if outb.pos == 0 and inb.pos >= inb.size:
                break
        self._pending = data[inb.pos:]
        # Weitere Eingabe nötig, wenn alles verbraucht ist und die Ausgabe nicht am Limit hängt
        self._needs_input = not self._pending and (limit is None or total < limit)
        return b''.join(parts)


def _skippable(data, pos):
    if len(data) - pos >= 8 and data[pos + 1:pos + 4] == b'\x2a\x4d\x18' and (data[pos] & 0xF0) == 0x50:
        return 8 + int.from_bytes(data[pos + 4:pos + 8], 'little')
    return 0


def decompress(data, zstd_dict=None, options=None):
    """Alle Frames hintereinander (wie compression.zstd.decompress); Skippable-Frames werden übersprungen."""
    data = bytes(data)
    out, pos = [], 0
    while pos < len(data):
        skip = _skippable(data, pos)
        if skip:
            pos += skip
            continue
        d = ZstdDecompressor(zstd_dict=zstd_dict, options=options)
        out.append(d.decompress(data[pos:]))
        if not d.eof:
            raise ZstdError('Compressed data ended before the end-of-stream marker was reached')
        pos = len(data) - len(d.unused_data)
    return b''.join(out)
