"""Temporary: the Home screen in each dark palette, on its own canvas page next to the comparison board.
Remove with: python3 tools/dark_preview.py --remove (after a palette is chosen)."""
import json, re, sys
from pathlib import Path
proj = Path('canvas/project')
def blend(fg, bg, a):
    f = [int(fg[i:i + 2], 16) for i in (1, 3, 5)]; b = [int(bg[i:i + 2], 16) for i in (1, 3, 5)]
    return '#' + ''.join(f'{round(x * a + y * (1 - a)):02x}' for x, y in zip(f, b))
def status(bg, ok, warn, danger, info, dest, dest_hover, ta=0.12, ba=0.30):
    return {'destructive': dest, 'destructive-hover': dest_hover, 'on-destructive': '#ffffff',
            'danger-fg': danger, 'danger-bg': blend(danger, bg, ta), 'danger-border': blend(danger, bg, ba),
            'warn-fg': warn, 'warn-bg': blend(warn, bg, ta), 'warn-border': blend(warn, bg, ba),
            'ok-fg': ok, 'ok-bg': blend(ok, bg, ta), 'ok-border': blend(ok, bg, ba),
            'info-fg': info, 'info-bg': blend(info, bg, ta + 0.02)}
P = {
 'A': dict(name='A · Crisp neutral', bg='#09090b', fg='#fafafa', subtle='#121214', muted='#232327', hover='#1c1c1f', active='#27272a', chip='#2e2e33',
           border='#27272a', **{'border-strong': '#3f3f46', 'muted-fg': '#a1a1aa', 'text-1': '#f4f4f5', 'text-2': '#e4e4e7', 'text-3': '#a1a1aa',
           'primary': '#fafafa', 'primary-fg': '#09090b', 'primary-hover': '#e4e4e7', 'primary-divider': '#a1a1aa', 'primary-muted': '#52525b', 'ring': '#a1a1aa'},
           **status('#09090b', '#4ade80', '#fbbf24', '#f87171', '#60a5fa', '#dc2626', '#ef4444')),
 'B': dict(name='B · Midnight', bg='#0b1120', fg='#f8fafc', subtle='#0f172a', muted='#1e293b', hover='#162033', active='#1e293b', chip='#273449',
           border='#1e293b', **{'border-strong': '#334155', 'muted-fg': '#94a3b8', 'text-1': '#e2e8f0', 'text-2': '#cbd5e1', 'text-3': '#94a3b8',
           'primary': '#2563eb', 'primary-fg': '#ffffff', 'primary-hover': '#1d4ed8', 'primary-divider': '#93c5fd', 'primary-muted': '#dbeafe', 'ring': '#60a5fa'},
           **status('#0b1120', '#4ade80', '#fbbf24', '#f87171', '#60a5fa', '#dc2626', '#ef4444', 0.12, 0.32)),
 'B2': dict(name='B2 · Midnight, no accent', bg='#0b1120', fg='#f8fafc', subtle='#0f172a', muted='#1e293b', hover='#162033', active='#1e293b', chip='#273449',
           border='#1e293b', **{'border-strong': '#334155', 'muted-fg': '#94a3b8', 'text-1': '#e2e8f0', 'text-2': '#cbd5e1', 'text-3': '#94a3b8',
           'primary': '#f1f5f9', 'primary-fg': '#0b1120', 'primary-hover': '#e2e8f0', 'primary-divider': '#94a3b8', 'primary-muted': '#475569', 'ring': '#94a3b8'},
           **status('#0b1120', '#4ade80', '#fbbf24', '#f87171', '#60a5fa', '#dc2626', '#ef4444', 0.12, 0.32)),
 'C': dict(name='C · Neutral with an accent', bg='#0c0c0f', fg='#fafafa', subtle='#141418', muted='#232329', hover='#1c1c21', active='#1e1b4b', chip='#2e2e35',
           border='#26262c', **{'border-strong': '#3f3f46', 'muted-fg': '#a1a1aa', 'text-1': '#f4f4f5', 'text-2': '#e4e4e7', 'text-3': '#a1a1aa',
           'primary': '#4f46e5', 'primary-fg': '#ffffff', 'primary-hover': '#4338ca', 'primary-divider': '#a5b4fc', 'primary-muted': '#e0e7ff', 'ring': '#818cf8'},
           **status('#0c0c0f', '#4ade80', '#fbbf24', '#f87171', '#818cf8', '#dc2626', '#ef4444')),
}
ORDER = ['bg', 'fg', 'subtle', 'muted', 'hover', 'active', 'chip', 'border', 'border-strong', 'muted-fg', 'text-1', 'text-2', 'text-3', 'primary', 'primary-fg',
         'primary-hover', 'primary-divider', 'primary-muted', 'ring', 'destructive', 'destructive-hover', 'on-destructive', 'danger-fg', 'danger-bg', 'danger-border',
         'warn-bg', 'warn-fg', 'warn-border', 'ok-bg', 'ok-fg', 'ok-border', 'info-bg', 'info-fg']
FILES = ['DarkHomeCurrent.dc.html'] + [f'DarkHome{k}.dc.html' for k in P] + ['DarkHomeD.dc.html']  # D was dropped; listed so --remove also clears it
c = proj / 'canvas.json'; d = json.loads(c.read_text())

if '--remove' in sys.argv:
    for f in FILES + ['DarkOptions.dc.html']:
        d['boards'].pop(f, None); (proj / f).unlink(missing_ok=True)
        if f in d['order']: d['order'].remove(f)
    d['pages'] = [p for p in d['pages'] if p['id'] != 'dark']
    c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':'))); print('removed'); sys.exit()

home = (proj / 'Home.dc.html').read_text()
def variant(key):
    s = home.replace('"theme":{"editor":"enum","options":["system","light","dark"],"default":"system"}', '"theme":{"editor":"enum","options":["system","light","dark"],"default":"dark"}')
    if key == 'Current':
        return s.replace('<title>', '<title>Dark (current): ', 1)
    p = P[key]
    tokens = 'color-scheme:dark;' + ';'.join(f'--{k}:{p[k]}' for k in ORDER)
    s = re.sub(r'^\.th\.dark\{[^}]*\}$', '.th.dark{' + tokens + '}', s, count=1, flags=re.M)
    s = re.sub(r'^@media \(prefers-color-scheme: dark\)\{\.th\.system\{[^}]*\}\}$', '@media (prefers-color-scheme: dark){.th.system{' + tokens + '}}', s, count=1, flags=re.M)
    s = s.replace('body:has(.th.dark){background:#0a0a0a}', 'body:has(.th.dark){background:' + p['bg'] + '}').replace('body:has(.th.system){background:#0a0a0a}', 'body:has(.th.system){background:' + p['bg'] + '}')
    return s.replace('<title>', f'<title>Dark ({p["name"]}): ', 1)

if not any(p['id'] == 'dark' for p in d['pages']):
    d['pages'].append({'id': 'dark', 'name': 'Dark mode options'})
d['boards']['DarkOptions.dc.html'].update({'page': 'dark', 'x': 0, 'y': 0})
titles = {'Current': 'Home in dark · current'} | {k: f'Home in dark · {P[k]["name"]}' for k in P}
for i, key in enumerate(['Current'] + list(P)):
    f = f'DarkHome{key}.dc.html'
    (proj / f).write_text(variant(key))
    d['boards'][f] = {'x': 1540 + (i % 2) * 1540, 'y': (i // 2) * 1160, 'w': 1440, 'h': 1000, 'title': titles[key], 'expand': 'fill', 'is_interactive': True, 'page': 'dark'}
    if f not in d['order']: d['order'].append(f)
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok', FILES)
