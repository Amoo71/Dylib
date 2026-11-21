"""Passt die Fremdquellen für den Einbau in NSPiOS an (idempotent)."""
import pathlib, sys

root = pathlib.Path(__file__).resolve().parent.parent

def patch(path, old, new, marker):
    p = root / path
    text = p.read_text()
    if marker in text:
        return
    if old not in text:
        sys.exit(f'patch_vendor: Stelle nicht gefunden in {path}: {old[:60]!r}')
    p.write_text(text.replace(old, new))

# capstone: die Bibliothek steckt in NSPiOSNative.framework statt in libcapstone.dylib
patch('py/capstone/__init__.py',
'''for _path in _path_list:
    if _path is None: continue
    _cs = _load_lib(_path)
    if _cs is not None: break
else:
    raise ImportError("ERROR: fail to load the dynamic library.")
''',
'''if os.getenv('NSPIOS_NATIVE_LIB'):  # NSPiOS: capstone is linked into NSPiOSNative.framework
    _cs = ctypes.CDLL(os.environ['NSPIOS_NATIVE_LIB'])
else:
    for _path in _path_list:
        if _path is None: continue
        _cs = _load_lib(_path)
        if _cs is not None: break
    else:
        raise ImportError("ERROR: fail to load the dynamic library.")
''', 'NSPIOS_NATIVE_LIB')

# SPIRV-Cross: CLI als Funktion; exit() würde die ganze App beenden → Exception (in main gefangen)
main = root / 'Vendor/SPIRV-Cross/main.cpp'
text = main.read_text()
if 'nspios_spirv_cross_main' not in text:
    if 'int main(int argc, char *argv[])' not in text:
        sys.exit('patch_vendor: main() in SPIRV-Cross nicht gefunden')
    text = '#include <stdexcept>\n' + text
    text = text.replace('exit(EXIT_FAILURE);', 'throw std::runtime_error("spirv-cross: invalid input or arguments");')
    text = text.replace('int main(int argc, char *argv[])', 'extern "C" int nspios_spirv_cross_main(int argc, char *argv[])')
    main.write_text(text)
print('patch_vendor: ok')
