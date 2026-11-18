"""Cards sit one step above the page in dark mode: a --card token (light #ffffff, dark #121214) on every board,
used by cards, tables, dialogs, menus, sheets and toasts. Safe to rerun; run it after any script that rebuilds a board."""
import re
from pathlib import Path
proj = Path('canvas/project')
LIGHT, DARK = '#ffffff', '#121214'

def tokens(s):
    s = re.sub(r'(\.th\{color-scheme:light;--bg:#[0-9a-f]{6})(?!;--card)', lambda m: m.group(1) + ';--card:' + LIGHT, s)
    s = re.sub(r'(color-scheme:dark;--bg:#[0-9a-f]{6})(?!;--card)', lambda m: m.group(1) + ';--card:' + DARK, s)
    return s

SURFACE = re.compile(r'border-radius: (10px|12px|14px|16px 16px 0 0)(;|$)')
def surfaces(s):
    def fix(m):
        st = m.group(1)
        if not SURFACE.search(st): return m.group(0)
        if 'background: var(--bg)' in st:
            return 'style="' + st.replace('background: var(--bg)', 'background: var(--card)') + '"'
        if 'background' not in st and re.search(r'border: 1px solid var\(--(border|border-strong)\)', st):
            return 'style="' + st.rstrip('; ') + '; background: var(--card)"'
        return m.group(0)
    head, sep, script = s.partition('<script type="text/x-dc"')
    return re.sub(r'style="([^"{}]*)"', fix, head) + sep + script

if __name__ == '__main__':
    changed = 0
    for p in sorted(proj.glob('*.dc.html')):
        s = p.read_text(); t = surfaces(tokens(s))
        if t != s: p.write_text(t); changed += 1
    for f in ['tools/tokens.css']:
        p = Path(f); p.write_text(tokens(p.read_text()))
    r = proj / 'Rules.dc.html'; s = r.read_text()
    s = re.sub(r"\['--card', '#ffffff', '#[0-9a-f]{6}', 'Cards, tables, menus, dialogs \(set apart by borders\)'\]",
               f"['--card', '#ffffff', '{DARK}', 'Cards, tables, menus, dialogs, sheets and toasts. In dark mode one step above the page'],", s)
    s = s.replace("'Cards, tables, menus, dialogs, sheets and toasts. In dark mode one step above the page'],,", "'Cards, tables, menus, dialogs, sheets and toasts. In dark mode one step above the page'],")
    r.write_text(s)
    print('boards changed:', changed)
