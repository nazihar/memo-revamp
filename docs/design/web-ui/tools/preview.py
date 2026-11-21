"""Usage: preview.py File.dc.html out.png '{"props":{...},"state":{...}}' [width height]
Renders a board outside the canvas (renderVals in Node, template expanded here) and screenshots it.
For checking a board by eye only; the canvas remains the real renderer."""
import json, re, subprocess, sys
from pathlib import Path
f = Path('canvas/project') / sys.argv[1]; out = sys.argv[2]; st = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
s = f.read_text()
js = s.split('data-dc-script')[1].split('>', 1)[1].split('</script>')[0]
props = json.loads(re.search(r"data-props='([^']*)'", s).group(1))
pv = props.get('$preview', {}); W = int(sys.argv[4]) if len(sys.argv) > 4 else pv.get('width', 1440); H = int(sys.argv[5]) if len(sys.argv) > 5 else pv.get('height', 900)
defaults = {k: v.get('default') for k, v in props.items() if not k.startswith('$')}
node = f"""class DCLogic {{ setState(x) {{}} }}
{js}
const c = new Component(); c.props = Object.assign({json.dumps(defaults)}, {json.dumps(st.get('props', {}))}); c.state = {json.dumps(st.get('state', {}))};
process.stdout.write(JSON.stringify(c.renderVals()));"""
r = subprocess.run(['node', '-e', node], capture_output=True, text=True)
if r.returncode: sys.exit(r.stderr)
V = json.loads(r.stdout)
tpl = s.split('<x-dc>')[1].split('</x-dc>')[0]
helmet = re.search(r'<helmet>(.*?)</helmet>', tpl, re.S).group(1); tpl = re.sub(r'<helmet>.*?</helmet>', '', tpl, flags=re.S)
def get(path, scope):
    if path in ('true', 'false'): return path == 'true'
    parts = path.split('.'); v = scope.get(parts[0], V.get(parts[0]))
    for k in parts[1:]: v = v.get(k) if isinstance(v, dict) else None
    return v
def block(t, tag, i):
    depth, j = 0, i
    pat = re.compile(rf'<{tag}\b|</{tag}>')
    for m in pat.finditer(t, i):
        depth += 1 if m.group().startswith(f'<{tag}') else -1
        if depth == 0: return m.start(), m.end()
    raise ValueError(tag)
def expand(t, scope):
    outp, pos = [], 0
    while True:
        m = re.compile(r'<sc-(for|if)\b([^>]*)>').search(t, pos)
        if not m: break
        outp.append(t[pos:m.start()]); tag = 'sc-' + m.group(1)
        cs, ce = block(t, tag, m.start()); inner = t[m.end():cs]
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(2)))
        if tag == 'sc-for':
            arr = get(attrs['list'].strip('{}'), scope) or []
            for it in arr: outp.append(expand(inner, dict(scope, **{attrs['as']: it})))
        elif get(attrs['value'].strip('{}'), scope): outp.append(expand(inner, scope))
        pos = ce
    outp.append(t[pos:]); t = ''.join(outp)
    def attr(m):
        v = get(m.group(2), scope)
        if m.group(1) in ('checked', 'disabled', 'selected'): return f' {m.group(1)}' if v is True or v == 'true' else ''
        return f' {m.group(1)}="{"" if v is None else str(v).replace("{", chr(1)).replace("}", chr(2))}"'
    t = re.sub(r' ([\w-]+)="\{\{([\w.]+)\}\}"', attr, t)
    esc = lambda v: str(v).replace('{', '\x01').replace('}', '\x02')  # values are text, never templates
    return re.sub(r'\{\{([\w.]+)\}\}', lambda m: '' if get(m.group(1), scope) is None else esc(get(m.group(1), scope)), t)
html = f'<!doctype html><html><head><meta charset="utf-8">{helmet}</head><body>{expand(tpl, {})}</body></html>'
html = html.replace('\x01', '{').replace('\x02', '}')
tmp = Path(out).with_suffix('.html'); tmp.write_text(html)
shot = f"""const {{chromium}} = require('playwright'); (async () => {{ const b = await chromium.launch(); const p = await b.newPage({{viewport: {{width: {W}, height: {H}}}}});
await p.goto('file://{tmp.resolve()}'); await p.waitForTimeout(400); await p.screenshot({{path: '{out}', fullPage: true}}); await b.close(); }})();"""
r = subprocess.run(['node', '-e', shot], capture_output=True, text=True); print(r.stderr[-500:] or 'ok')
