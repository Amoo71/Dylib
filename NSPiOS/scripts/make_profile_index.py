"""Spieleliste für NSPiOS aus den nxnative-Profilen (dm/games/<ID>/game.tsv): ID, Name, Grafikweg, Status,
Version, für die das Profil gemacht ist (dezimal, 0 = Basisspiel) und ihr Anzeigename (z. B. 1.4.0).

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

def builds(path):
    out = {}
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        f = line.split('\t')
        if len(f) >= 3 and f[0].strip() == 'build':
            out.setdefault(f[1].strip(), f[2].strip())
    return out

def version_number(v):
    v = (v or '').strip().lstrip('vV')
    return int(v) if v.isdigit() else 0

lines = ['# tid\tname\troute\tstatus\tversion\tversion_name']
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
    pkg = (r.get('package', '').split() or [''])[0]
    ver = version_number(pkg)
    shown = builds(d / 'game.tsv').get(pkg, '') if ver else ''
    if shown.lstrip('vV').isdigit(): shown = ''
    lines.append(f'{d.name.upper()}\t{name}\t{route}\t{status}\t{ver}\t{shown}')
out.write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(f'profile index: {len(lines) - 1} games')
