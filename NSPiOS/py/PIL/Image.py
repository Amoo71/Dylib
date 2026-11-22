"""Pillow ist auf iOS nicht verfügbar; der Packer fängt den Fehler ab (Icon setzt NSPiOS)."""
LANCZOS = 1


def open(*_a, **_k):
    raise OSError('Pillow is not available on iOS (NSPiOS writes the app icon itself)')
