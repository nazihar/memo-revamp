"""Usage: smoke.py File.dc.html [state-json ...] — tag balance + renderVals run + every {{hole}} resolves."""
import json, re, subprocess, sys
from html.parser import HTMLParser
from pathlib import Path
f = Path('canvas/project') / sys.argv[1]; s = f.read_text()
VOID = {'meta','link','input','br','img','hr','source','col','path','circle','rect','polyline','line'}
class P(HTMLParser):
    def __init__(s2): super().__init__(); s2.st = []; s2.err = []
    def handle_starttag(s2, t, a):
        if t not in VOID: s2.st.append(t)
    def handle_startendtag(s2, t, a): pass
    def handle_endtag(s2, t):
        if t in VOID: return
        if not s2.st or s2.st[-1] != t: s2.err.append((t, s2.st[-3:], s2.getpos())); 
        else: s2.st.pop()
p = P(); p.feed(s.split('<script type="text/x-dc"')[0])
print('tags:', 'OK' if not p.err else p.err[:3], 'open:', p.st)
js = s.split('data-dc-script')[1].split('>', 1)[1].split('</script>')[0]
props = json.loads(re.search(r"data-props='([^']*)'", s).group(1))
defaults = {k: v.get('default') for k, v in props.items() if not k.startswith('$')}
holes = sorted(set(re.findall(r'\{\{\s*([\w.]+)\s*\}\}', s.split('<script type="text/x-dc"')[0])))
loops = dict(re.findall(r'<sc-for list="\{\{([\w.]+)\}\}" as="(\w+)"', s))
states = [json.loads(a) for a in sys.argv[2:]] or [{}]
node = f"""
class DCLogic {{ setState(x) {{ this.state = Object.assign({{}}, this.state, x); }} }}
{js}
const holes = {json.dumps(holes)}, loops = {json.dumps(loops)};
for (const st of {json.dumps(states)}) {{
  const c = new Component(); c.props = Object.assign({json.dumps(defaults)}, st.props || {{}}); c.state = st.state || {{}};
  const v = c.renderVals();
  const alias = {{}}; for (const [l, a] of Object.entries(loops)) {{ const arr = l.split('.').reduce((o, k) => o && o[k], v); alias[a] = Array.isArray(arr) && arr.length ? arr[0] : undefined; }}
  const miss = holes.filter((h) => {{ const [r, ...rest] = h.split('.'); const base = r in alias ? alias[r] : v[r]; if (r in alias && base === undefined) return false; return rest.reduce((o, k) => o == null ? undefined : o[k], base) === undefined && !(rest.length === 0 && r in v); }});
  console.log(JSON.stringify(st).slice(0, 60), 'missing:', miss.join(',') || 'none');
}}
"""
r = subprocess.run(['node', '-e', node], capture_output=True, text=True); print(r.stdout, r.stderr[-800:])
