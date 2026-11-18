import json
from pathlib import Path
proj = Path('canvas/project')
logic = Path('tools/position-logic.js').read_text()
fields = Path('tools/position-fields.html').read_text()
# Desktop
memo = (proj / 'Memo.dc.html').read_text()
marker = '    <!-- Content -->\n'
prefix = memo[:memo.index(marker) + len(marker)].replace('<title>Memo detail</title>', '<title>Position</title>')
assert '<title>Position</title>' in prefix and prefix.count('; background: var(--active); font-weight: 500"') == 1
prefix = prefix.replace('; background: var(--active); font-weight: 500"', '"')
old = 'title="Organisation" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg)">'
assert prefix.count(old) == 1
prefix = prefix.replace(old, 'title="Organisation" aria-current="page" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg); background: var(--active); font-weight: 500">')
body = Path('tools/position-body.html').read_text().replace('/*FIELDS*/', fields).replace('/*LOGIC*/', logic)
(proj / 'Position.dc.html').write_text(prefix + body)
# Phone: head of the imported memo (back-button page), its own body.
imp = (proj / 'ImportedPhone.dc.html').read_text()
head = imp[:imp.index('  <header ')].replace('<title>Imported memo on a phone</title>', '<title>Position on a phone</title>').replace('button{font-family:inherit}', 'button,input{font-family:inherit}')
assert 'Position on a phone' in head and 'button,input' in head
pbody = Path('tools/positionphone-body.html').read_text().replace('/*FIELDS*/', fields.replace('\n        ', '\n      ')).replace('/*LOGIC*/', logic.replace('/*PHONE*/false', 'true'))
(proj / 'PositionPhone.dc.html').write_text(head + pbody)
c = proj / 'canvas.json'; d = json.loads(c.read_text())
d['boards']['Position.dc.html'] = {"x": 0, "y": 28940, "w": 1440, "h": 900, "title": "Position — Desktop", "expand": "fill", "is_interactive": True}
d['boards']['PositionPhone.dc.html'] = {"x": 1520, "y": 28940, "w": 390, "h": 844, "title": "Position — Phone", "is_interactive": True}
for k in ('Position.dc.html', 'PositionPhone.dc.html'):
    if k not in d['order']: d['order'].insert(d['order'].index('Rules.dc.html'), k)
d['notes']['positionTitle'] = {"x": 0, "y": 28680, "text": "Position", "kind": "title1", "maxW": 1910}
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
