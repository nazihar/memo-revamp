"""The select rule, and "use the shadcn component, never hand-build on Radix or Base UI", on the Design rules page
(section 1) and in the build note (canvas note and NOTES.md). Idempotent."""
import json
from pathlib import Path
proj = Path('canvas/project')
DT = '<dt style="color: var(--text-3)">'
DD = '<dd style="margin: 0; font-family: \'Geist Mono\', ui-monospace, monospace; font-size: 12px; color: var(--fg)">'
row = lambda t, d: f'<div style="display: contents">{DT}{t}</dt>{DD}{d}</dd></div>'

p = proj / 'Rules.dc.html'; s = o = p.read_text()
OLD_FIXED = row('Fixed choices', 'Select · RadioGroup · Checkbox · Switch')
OLD_PICK = row('Pick a person, position or unit', 'Combobox (Popover + Command)')
if OLD_FIXED in s:
    s = s.replace(OLD_FIXED, row('Fixed choices, up to about 10', 'Select · RadioGroup (2–4 that stay visible) · Checkbox · Switch')
                  + row('Long or growing lists: over about 10, or the organisation’s own data (time zones, memo types, workflows, roles, units, versions)', 'Combobox (Popover + Command, type to filter); a Drawer on phones'), 1)
    s = s.replace(OLD_PICK, row('Pick a person, position or unit', 'Combobox that searches the directory as you type (Command without its own filtering)'), 1)
OLD_ADD = 'The app already has Button, Badge, Dialog, DropdownMenu, Field and Toast; add the rest.'
NEW_ADD = (OLD_ADD + ' Never hand-build a control on Radix or Base UI primitives when shadcn has it: add the shadcn component and adjust its file. '
           'The app’s Select today wraps the native select: replace it with shadcn Select, and add Combobox for the long lists.')
if NEW_ADD not in s: s = s.replace(OLD_ADD, NEW_ADD, 1)
if s != o: p.write_text(s); print('Rules updated')

ADD = (' Add each one with the shadcn CLI (npx shadcn@latest add <name>) and adjust the generated file in components/ui; never hand-build a control on Radix or Base UI primitives when shadcn has it. '
       'Selects: shadcn Select for a fixed list of up to about 10 options; shadcn Combobox (Popover + Command, type to filter; a Drawer on phones) for more than about 10, or any list that grows with the organisation’s data; '
       'the people, position and unit picker is a Combobox that searches the directory as you type.')
MARK = 'never hand-build a control on Radix'
c = proj / 'canvas.json'; d = json.loads(c.read_text())
t = d['notes']['buildNote']['text']
if MARK not in t:
    anchor = 'do not copy their inline styles.'
    assert anchor in t
    d['notes']['buildNote']['text'] = t.replace(anchor, anchor + ADD, 1)
    c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':'))); print('build note updated')
n = Path('NOTES.md'); s = n.read_text()
if MARK not in s:
    anchor = 'do not copy their inline styles.'
    i = s.index('## Build note'); j = s.index(anchor, i) + len(anchor)
    n.write_text(s[:j] + ADD + s[j:]); print('NOTES.md updated')
c = proj / 'canvas.json'; d = json.loads(c.read_text())
if d['boards']['Rules.dc.html']['h'] < 7900:
    d['boards']['Rules.dc.html']['h'] = 7900; c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':'))); print('Rules board 7900px')
