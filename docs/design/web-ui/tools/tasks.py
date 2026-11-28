"""Move "Open tasks" to Administration in every menu, then build the Open tasks page."""
import json
import re
from pathlib import Path

proj = Path('canvas/project')

ITEM = re.compile(r'( *)<a [^>]*?(?:title="Open tasks")?[^>]*>\s*<svg[^>]*><path d="m3 17 2 2 4-4">.*?</a>\n', re.S)
moved = []
for f in sorted(proj.glob('*.dc.html')):
    s = f.read_text()
    m = ITEM.search(s)
    if not m:
        continue
    block = m.group(0)
    s = s[:m.start()] + s[m.end():]
    # Desktop sidebar: before Security. Phone drawer: before the Security item too.
    sec = re.search(r' *<a [^>]*>\s*<svg[^>]*><path d="M20 13c0 5-3\.5 7\.5-7\.66 8\.95', s)
    if not sec or sec.start() < m.start():
        raise SystemExit(f'no Security item after Open tasks in {f.name}')
    s = s[:sec.start()] + block + s[sec.start():]
    f.write_text(s)
    moved.append(f.name)
print('moved in', moved)

# Build the page from the shared desktop shell.
memo = (proj / 'Memo.dc.html').read_text()
marker = '    <!-- Content -->\n'
prefix = memo[:memo.index(marker) + len(marker)].replace('<title>Memo detail</title>', '<title>Open tasks</title>')
assert prefix.count('; background: var(--active); font-weight: 500"') == 1
prefix = prefix.replace('; background: var(--active); font-weight: 500"', '"')
old = '<a href="#" class="nav-item" title="Open tasks" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg)">'
assert old in prefix
prefix = prefix.replace(old, old.replace('title="Open tasks" style="', 'title="Open tasks" aria-current="page" style="').replace('color: var(--fg)">', 'color: var(--fg); background: var(--active); font-weight: 500">'))
(proj / 'Tasks.dc.html').write_text(prefix + Path('tools/tasks-body.html').read_text())

c = proj / 'canvas.json'
d = json.loads(c.read_text())
d['boards']['Tasks.dc.html'] = {"x": 0, "y": 17180, "w": 1440, "h": 1000, "title": "Open tasks — Desktop", "expand": "fill", "is_interactive": True}
d['order'].insert(d['order'].index('Rules.dc.html'), 'Tasks.dc.html')
d['notes']['tasksTitle'] = {"x": 0, "y": 16920, "text": "Open tasks (administration)", "kind": "title1", "maxW": 1910}
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
