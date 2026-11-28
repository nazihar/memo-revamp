"""Turn the hard-coded light colours of each artboard into theme tokens with a dark set."""
import re
import sys
from pathlib import Path

LIGHT = {
    'bg': '#ffffff', 'fg': '#0a0a0a', 'subtle': '#fafafa', 'muted': '#f5f5f5', 'hover': '#f0f0f0',
    'active': '#ebebeb', 'chip': '#e5e5e5', 'border': '#e5e5e5', 'border-strong': '#d4d4d4',
    'muted-fg': '#737373', 'text-1': '#262626', 'text-2': '#404040', 'text-3': '#525252',
    'primary': '#171717', 'primary-fg': '#fafafa', 'primary-hover': '#262626', 'primary-divider': '#404040',
    'primary-muted': '#a3a3a3', 'ring': '#a3a3a3', 'destructive': '#dc2626', 'destructive-hover': '#b91c1c',
    'on-destructive': '#ffffff', 'danger-fg': '#b91c1c', 'danger-bg': '#fef2f2', 'danger-border': '#fecaca',
    'warn-bg': '#fffbeb', 'warn-fg': '#92400e', 'warn-border': '#fcd34d', 'ok-bg': '#f0fdf4', 'ok-fg': '#166534',
    'ok-border': '#bbf7d0', 'info-bg': '#eff6ff', 'info-fg': '#1d4ed8',
}
DARK = {  # palette A, Crisp neutral (2026-11-18)
    'bg': '#09090b',
    'fg': '#fafafa',
    'subtle': '#121214',
    'muted': '#232327',
    'hover': '#1c1c1f',
    'active': '#27272a',
    'chip': '#2e2e33',
    'border': '#27272a',
    'border-strong': '#3f3f46',
    'muted-fg': '#a1a1aa',
    'text-1': '#f4f4f5',
    'text-2': '#e4e4e7',
    'text-3': '#a1a1aa',
    'primary': '#fafafa',
    'primary-fg': '#09090b',
    'primary-hover': '#e4e4e7',
    'primary-divider': '#a1a1aa',
    'primary-muted': '#52525b',
    'ring': '#a1a1aa',
    'destructive': '#dc2626',
    'destructive-hover': '#ef4444',
    'on-destructive': '#ffffff',
    'danger-fg': '#f87171',
    'danger-bg': '#261517',
    'danger-border': '#51282a',
    'warn-bg': '#261f0e',
    'warn-fg': '#fbbf24',
    'warn-border': '#524012',
    'ok-bg': '#112319',
    'ok-fg': '#4ade80',
    'ok-border': '#1c492e',
    'info-bg': '#151f2c',
    'info-fg': '#60a5fa',
}

BG = {'ffffff': 'bg', 'fafafa': 'subtle', 'f5f5f5': 'muted', 'f0f0f0': 'hover', 'ebebeb': 'active',
      'e5e5e5': 'chip', '171717': 'primary', '262626': 'primary-hover', '404040': 'primary-divider',
      'dc2626': 'destructive', 'b91c1c': 'destructive-hover', 'fffbeb': 'warn-bg', 'f0fdf4': 'ok-bg',
      'fef2f2': 'danger-bg', 'eff6ff': 'info-bg'}
TEXT = {'0a0a0a': 'fg', '171717': 'fg', '262626': 'text-1', '404040': 'text-2', '525252': 'text-3',
        '737373': 'muted-fg', 'a3a3a3': 'primary-muted', 'fafafa': 'primary-fg', 'ffffff': 'on-destructive',
        'b91c1c': 'danger-fg', '92400e': 'warn-fg', '166534': 'ok-fg', '1d4ed8': 'info-fg'}
LINE = {'e5e5e5': 'border', 'd4d4d4': 'border-strong', '171717': 'primary', 'fcd34d': 'warn-border',
        'bbf7d0': 'ok-border', 'fecaca': 'danger-border', 'ffffff': 'bg', '262626': 'primary-hover',
        'a3a3a3': 'ring', 'f0f0f0': 'hover'}


def block(vals):
    return ';'.join(f'--{k}:{v}' for k, v in vals.items())


TOKENS = (
    f'.th{{color-scheme:light;{block(LIGHT)}}}\n'
    f'.th.dark{{color-scheme:dark;{block(DARK)}}}\n'
    f'@media (prefers-color-scheme: dark){{.th.system{{color-scheme:dark;{block(DARK)}}}}}\n'
    'body:has(.th.dark){background:#0a0a0a}\n'
    '@media (prefers-color-scheme: dark){body:has(.th.system){background:#0a0a0a}}\n'
)


def sub_map(text, pattern, table, label):
    def repl(m):
        hexv = m.group(2).lower()
        if hexv not in table:
            return m.group(0)
        return f'{m.group(1)}var(--{table[hexv]})'
    return re.sub(pattern, repl, text)


def svg_stroke(text):
    def repl(m):
        tag = m.group(0)
        sm = re.search(r'stroke="#([0-9a-fA-F]{6})"', tag)
        if not sm or sm.group(1).lower() not in TEXT:
            return tag
        var = f'var(--{TEXT[sm.group(1).lower()]})'
        tag = tag.replace(sm.group(0), 'stroke="currentColor"')
        if ' style="' in tag:
            tag = tag.replace(' style="', f' style="color: {var}; ', 1)
        else:
            tag = tag.replace('<svg', f'<svg style="color: {var}"', 1)
        return tag
    return re.sub(r'<svg\b[^>]*>', repl, text)


def convert(path: Path, with_menu: bool):
    src = path.read_text()
    head, sep, rest = src.partition('</helmet>')
    # Tokens go at the top of the helmet stylesheet.
    head = head.replace('<style>\n', '<style>\n' + TOKENS, 1)
    head = re.sub(r'body\{margin:0;background:#ffffff\}', 'body{margin:0;background:#ffffff}', head)
    src = head + sep + rest
    # Root element of the artboard gets the theme class.
    src = re.sub(r'(</helmet>\s*<div )', r'\1class="th {{themeClass}}" ', src, count=1)
    # Colours.
    src = sub_map(src, r'((?<![-\w])background:\s?)#([0-9a-fA-F]{6})', BG, 'bg')
    src = sub_map(src, r'((?<![-\w])color:\s?)#([0-9a-fA-F]{6})', TEXT, 'text')
    src = sub_map(src, r'((?:solid|dashed) )#([0-9a-fA-F]{6})', LINE, 'line')
    src = svg_stroke(src)
    # Route connector colours built in script.
    src = src.replace("'#171717' : '#e5e5e5')", "'var(--primary)' : 'var(--chip)')")
    # The page body behind the artboard follows the theme too (restore the literal body rule).
    src = src.replace('body{margin:0;background:var(--bg)}', 'body{margin:0;background:#ffffff}')
    # Theme tweak and class.
    src = src.replace("data-props='{", "data-props='{\"theme\":{\"editor\":\"enum\",\"options\":[\"system\",\"light\",\"dark\"],\"default\":\"system\"},", 1)
    script_start = src.index('data-dc-script')
    idx = src.rindex('return {')
    assert idx > script_start
    theme_expr = "(this.state && this.state.theme) || this.props.theme || 'system'"
    src = src[:idx] + 'return {\n      themeClass: ' + theme_expr + ',' + src[idx + len('return {'):]
    path.write_text(src)


if __name__ == '__main__':
    root = Path(sys.argv[1])
    for name in sys.argv[2:]:
        convert(root / name, name == 'Main.dc.html')
        print('converted', name)
