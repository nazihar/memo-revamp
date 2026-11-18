"""Make palette A (Crisp neutral, chosen 2026-11-18) the dark theme on every board, in tokens.css, theme.py and the rules table."""
import re
from pathlib import Path
proj = Path('canvas/project')
def blend(fg, bg, a):
    f = [int(fg[i:i + 2], 16) for i in (1, 3, 5)]; b = [int(bg[i:i + 2], 16) for i in (1, 3, 5)]
    return '#' + ''.join(f'{round(x * a + y * (1 - a)):02x}' for x, y in zip(f, b))
BG = '#09090b'
DARK = {
    'bg': BG, 'fg': '#fafafa', 'subtle': '#121214', 'muted': '#232327', 'hover': '#1c1c1f', 'active': '#27272a', 'chip': '#2e2e33',
    'border': '#27272a', 'border-strong': '#3f3f46', 'muted-fg': '#a1a1aa', 'text-1': '#f4f4f5', 'text-2': '#e4e4e7', 'text-3': '#a1a1aa',
    'primary': '#fafafa', 'primary-fg': '#09090b', 'primary-hover': '#e4e4e7', 'primary-divider': '#a1a1aa', 'primary-muted': '#52525b', 'ring': '#a1a1aa',
    'destructive': '#dc2626', 'destructive-hover': '#ef4444', 'on-destructive': '#ffffff',
    'danger-fg': '#f87171', 'danger-bg': blend('#f87171', BG, 0.12), 'danger-border': blend('#f87171', BG, 0.30),
    'warn-bg': blend('#fbbf24', BG, 0.12), 'warn-fg': '#fbbf24', 'warn-border': blend('#fbbf24', BG, 0.30),
    'ok-bg': blend('#4ade80', BG, 0.12), 'ok-fg': '#4ade80', 'ok-border': blend('#4ade80', BG, 0.30),
    'info-bg': blend('#60a5fa', BG, 0.14), 'info-fg': '#60a5fa',
}
TOK = 'color-scheme:dark;' + ';'.join(f'--{k}:{v}' for k, v in DARK.items())
def fix(s):
    s = re.sub(r'\.th\.dark\{color-scheme:dark;[^}]*\}', '.th.dark{' + TOK + '}', s)
    s = re.sub(r'(@media \(prefers-color-scheme: dark\)\{\.th\.system\{)color-scheme:dark;[^}]*\}\}', lambda m: m.group(1) + TOK + '}}', s)
    s = s.replace('body:has(.th.dark){background:#0a0a0a}', 'body:has(.th.dark){background:' + BG + '}').replace('body:has(.th.system){background:#0a0a0a}', 'body:has(.th.system){background:' + BG + '}')
    return s
n = 0
for p in sorted(proj.glob('*.dc.html')):
    if p.name.startswith('DarkHome') or p.name == 'DarkOptions.dc.html': continue
    s = p.read_text(); t = fix(s)
    if t != s: p.write_text(t); n += 1
t = Path('tools/tokens.css'); t.write_text(fix(t.read_text()))
# theme.py keeps the palette used to generate tokens.css
th = Path('tools/theme.py'); s = th.read_text()
i = s.index('DARK = {'); j = s.index('}\n', i) + 2
s = s[:i] + 'DARK = {  # palette A, Crisp neutral (2026-11-18)\n' + ''.join(f"    '{k}': '{v}',\n" for k, v in DARK.items()) + '}\n' + s[j:]
th.write_text(s)
# Rules: the dark column of the token table
r = proj / 'Rules.dc.html'; s = r.read_text()
ROWS = {"'--background'": BG, "'--foreground'": DARK['fg'], "'--card'": BG, "'--muted'": DARK['muted'], "'--muted-foreground'": DARK['muted-fg'],
        "'--border'": DARK['border'], "'--input'": DARK['border'], "'--primary'": DARK['primary'], "'--primary-foreground'": DARK['primary-fg'],
        "'--sidebar'": DARK['subtle'], "'--ring'": DARK['ring'], "'--status-ok-bg / fg'": DARK['ok-bg'], "'--status-warn-bg / fg'": DARK['warn-bg'],
        "'--status-danger-bg / fg'": DARK['danger-bg'], "'--status-neutral-bg / fg'": DARK['muted']}
for key, val in ROWS.items():
    s = re.sub(r"(\[" + re.escape(key) + r", '#[0-9a-f]{6}', )'#[0-9a-f]{6}'", lambda m: m.group(1) + f"'{val}'", s, count=1)
s = s.replace("(text #166534 / #86efac)", "(text #166534 / #4ade80)").replace("(text #92400e / #fcd34d)", "(text #92400e / #fbbf24)").replace("(text #171717 / #e5e5e5)", "(text #171717 / #f4f4f5)")
r.write_text(s)
print('boards updated:', n)
