"""lz4.block-kompatible Funktionen (decompress/compress) über die native lz4-Bibliothek."""
import ctypes, os, struct

_lib = ctypes.CDLL(os.environ['NSPIOS_NATIVE_LIB'])
_lib.LZ4_decompress_safe.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
_lib.LZ4_decompress_safe.restype = ctypes.c_int
_lib.LZ4_compress_default.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
_lib.LZ4_compress_default.restype = ctypes.c_int
_lib.LZ4_compressBound.argtypes = [ctypes.c_int]
_lib.LZ4_compressBound.restype = ctypes.c_int


class LZ4BlockError(Exception):
    pass


def decompress(source, uncompressed_size=-1, return_bytearray=False, dict=None):
    source = bytes(source)
    if uncompressed_size < 0:
        if len(source) < 4:
            raise LZ4BlockError('input too short')
        uncompressed_size = struct.unpack('<I', source[:4])[0]
        source = source[4:]
    dst = ctypes.create_string_buffer(max(uncompressed_size, 1))
    n = _lib.LZ4_decompress_safe(source, dst, len(source), uncompressed_size)
    if n < 0:
        raise LZ4BlockError(f'Decompression failed: corrupt input or insufficient space in destination buffer. Error code: {-n}')
    data = dst.raw[:n]
    return bytearray(data) if return_bytearray else data


def compress(source, mode='default', store_size=True, **_):
    source = bytes(source)
    bound = _lib.LZ4_compressBound(len(source))
    dst = ctypes.create_string_buffer(max(bound, 1))
    n = _lib.LZ4_compress_default(source, dst, len(source), bound)
    if n <= 0:
        raise LZ4BlockError('Compression failed')
    data = dst.raw[:n]
    return (struct.pack('<I', len(source)) + data) if store_size else data
