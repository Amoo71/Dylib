"""Spieleliste für NSPiOS aus den nxnative-Profilen (dm/games/<ID>/game.tsv): ID, Name, Grafikweg, Status.

Status: ok | mac_only (Shader nur mit dem Decoder des Spiels auf einem Mac) | multi_program (Launcher mit mehreren
Programmen, nicht packbar) | gl (OpenGL-Weg, in NSPiOS noch nicht).
"""
import pathlib, sys

src = pathlib.Path(sys.argv[1])          # .../dm/games
out = pathlib.Path(sys.argv[2])

def rows(path):
    d = {}
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        if not line or line.startswith('#') or '\t' not in line:
            continue
        k, v = line.split('\t', 1)
        d.setdefault(k.strip(), v.strip())
    return d

def data_rows(path):
    if not path.is_file():
        return None
    return sum(1 for l in path.read_text(encoding='utf-8', errors='replace').splitlines() if l.strip() and not l.startswith('#'))

lines = ['# tid\tname\troute\tstatus']
for d in sorted(p for p in src.iterdir() if (p / 'game.tsv').is_file()):
    r = rows(d / 'game.tsv')
    if (d / 'vk_capture.tsv').is_file():
        route = 'vulkan-capture'
    elif (d / 'gl_capture.tsv').is_file():
        route = 'gl'
    else:
        n = data_rows(d / 'shader_map.tsv')
        route = 'vulkan' if n is not None and n <= 1 else 'nvn'
    status = 'ok'
    if r.get('needs_mac_shaders', '').strip().lower() not in ('', '0', 'off', 'no', 'false'):
        status = 'mac_only'
    elif len(r.get('programs', '').split()) > 1:
        status = 'multi_program'
    elif route == 'gl':
        status = 'gl'
    name = r.get('name', d.name).replace('\t', ' ')
    lines.append(f'{d.name.upper()}\t{name}\t{route}\t{status}')
out.write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(f'profile index: {len(lines) - 1} games')
