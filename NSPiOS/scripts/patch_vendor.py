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
# ns2ios-shell: der Ordner-Weg liest die Titel-ID klein (main.npdm), alle Profile und der NSP-Weg schreiben sie groß.
# Auf einem Dateisystem mit Groß-/Kleinschreibung (iOS) findet der Packer das Profil sonst nicht.
if (root / 'py/packer/scripts/nxn_pack.py').is_file():
    patch('py/packer/scripts/nxn_pack.py',
          "    return '%016x' % struct.unpack_from('<Q', d, off + 0x10)[0]",
          "    return '%016X' % struct.unpack_from('<Q', d, off + 0x10)[0]   # NSPiOS: upper case, as the profile folders",
          'NSPiOS: upper case')

# ns2ios-shell, Weg ohne Apple-Compiler (nometal_run.py): Es gibt keine Seitenbibliothek <key>_bx; der typisierte Einstieg
# (main0_u, Ganzzahl-G-Buffer z. B. Luigi's Mansion 3) steckt in der einen <key>_b-Bibliothek. dmsh_build.py schreibt in die
# Reflexion trotzdem entry_u_side, und die Shell sucht ihn dann nur in <key>_bx: jeder G-Buffer-Shader fällt aus (keine Texturen).
# dmsh_mtlcache.py gehört nicht zur Übersetzungskette (dmsh_chain.FILES): die Kettenversion bleibt gleich.
if (root / 'py/packer/dm/gfx/tools/dmsh_mtlcache.py').is_file():
    patch('py/packer/dm/gfx/tools/dmsh_mtlcache.py',
          'def _replace(src, dst, tries=40):\n',
          'def _nspios_one_library(path):\n'
          '    """NSPiOS: no side library on this route, the typed-output entry point is in the base library (see patch_vendor.py)."""\n'
          '    try:\n'
          '        with open(path, encoding="utf-8") as f: value = json.load(f)\n'
          '    except (OSError, ValueError): return\n'
          '    if isinstance(value, dict) and value.pop("entry_u_side", None) is not None:\n'
          '        with open(path, "w", encoding="utf-8") as f: json.dump(value, f, ensure_ascii=False, indent=1)\n'
          '\n\n'
          'def _replace(src, dst, tries=40):\n'
          '    if str(dst).endswith("_b.json") and os.environ.get("NSPIOS_ONE_LIBRARY") == "1": _nspios_one_library(src)\n',
          'NSPiOS: no side library')

print('patch_vendor: ok')
