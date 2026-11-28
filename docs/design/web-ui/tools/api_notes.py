"""Adds the admin pages and the operator console to the one list of API changes the design needs: the "API changes"
note on the Screens page and the same section in NOTES.md. Idempotent: does nothing when the items are already there."""
import json
from pathlib import Path
proj = Path('canvas/project')
ADD = ('\n\nAdmin pages (/api/v1/admin)\nNothing new. Each area note says what it reads; the overview’s “Waiting for you” reads existing lists, in several calls.'
       '\n\nOperator console (/api/v1/platform)'
       '\n12. Organisations list — hasAdmin on each row (today only the single view has it), and status and hasAdmin filters, for the Administrator column and the All / Active / Suspended / No administrator tabs.'
       '\n13. Platform audit — the organisation’s name with each entry that concerns one, so the Object column can name it and link to it.')
MARK = 'Operator console (/api/v1/platform)'

c = proj / 'canvas.json'; d = json.loads(c.read_text())
note = d['notes']['apiChanges']
if MARK not in note['text']:
    note['text'] = note['text'].rstrip() + ADD
    c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':'))); print('canvas note updated')

n = Path('NOTES.md'); s = n.read_text()
if MARK not in s:
    anchor = '\n## Behaviour notes'
    assert s.count(anchor) == 1
    s = s.replace(anchor, ADD.replace('\n\n', '\n\n', 1).rstrip() + '\n' + anchor, 1)
    n.write_text(s); print('NOTES.md updated')
