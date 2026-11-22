"""Platzhalter: zstd wird nur für zstd-komprimierte Spieldateien gebraucht (nicht für entpackte Ordner)."""


class ZstdError(Exception):
    pass


class ZstdDecompressor:
    def __init__(self, *_a, **_k):
        raise ZstdError('zstd is not available in NSPiOS yet')


def decompress(*_a, **_k):
    raise ZstdError('zstd is not available in NSPiOS yet')
