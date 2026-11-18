"""Reading pane is a per-person switch, off by default (decided 2026-11-18)."""
import json
from pathlib import Path
proj = Path('canvas/project')
def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:100], s.count(a)); return s.replace(a, b)

def switch(on, href):
    track = 'background: var(--primary)' if on else 'background: var(--chip)'
    knob = 'transform: translateX(14px)' if on else 'transform: translateX(0)'
    return f'''<a href="{href}" role="switch" aria-checked="{'true' if on else 'false'}" class="ghost" title="Show the selected memo beside the list (wide screens)" style="display: inline-flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 8px; color: var(--fg); font-size: 13px; font-weight: 500; text-decoration: none">
              <span aria-hidden="true" style="position: relative; display: inline-flex; align-items: center; width: 32px; height: 18px; padding: 2px; box-sizing: border-box; border-radius: 9px; {track}"><span style="width: 14px; height: 14px; border-radius: 50%; background: var(--bg); box-shadow: 0 1px 2px rgba(0,0,0,0.2); transition: transform 0.15s; {knob}"></span></span>
              Reading pane
            </a>'''

TOOLBAR_END = '''              <span style="color: var(--muted-fg); font-weight: 400">Sort:</span> Due first
            </button>
'''
for name in ['Main', 'InboxWatching', 'InboxTeam']:
    p = proj / f'{name}.dc.html'; s = p.read_text()
    if 'role="switch"' in s: continue
    s = rep(s, TOOLBAR_END, TOOLBAR_END + '            ' + switch(False, 'InboxReading.dc.html') + '\n')
    p.write_text(s); print('switch off', name)

p = proj / 'InboxReading.dc.html'; s = p.read_text()
if 'role="switch"' not in s:
    a = '        <a href="NewMemo.dc.html" style="display: inline-flex; align-items: center; gap: 6px; height: 32px'
    i = s.index(a)
    s = s[:i] + '        <div style="display: flex; align-items: center; gap: 8px">\n            ' + switch(True, 'Main.dc.html') + '\n' + s[i:]
    j = s.index('</a>', i + 200) + len('</a>')
    s = s[:j] + '\n        </div>' + s[j:]
    p.write_text(s); print('switch on InboxReading')

c = proj / 'canvas.json'; d = json.loads(c.read_text())
d['boards']['InboxReading.dc.html']['title'] = 'Inbox with the reading pane switched on — Desktop, 1400px and wider'
d['notes']['InboxReadingTitle']['text'] = 'Inbox · reading pane (switched on)'
d['notes']['InboxReadingPhone']['text'] = 'No phone version. The reading pane is off by default; each person can switch it on from the My tasks toolbar, and it is offered only at 1400px and wider. On phones and narrower screens a memo always opens as its own page.'
beh = d['notes']['behaviour']['text']
old = '\n• Reading pane: at 1400px and wider, the Inbox shows the list and the selected memo side by side, with the same decision buttons. Narrower screens and phones keep list → page.'
assert old in beh
d['notes']['behaviour']['text'] = beh.replace(old, '\n• Reading pane: off by default. A “Reading pane” switch in the My tasks toolbar (shown at 1400px and wider) turns it on; the choice is remembered per person. When on, the list and the selected memo sit side by side with the same decision buttons. Links from notifications, search and Home still open the full page; narrower screens and phones always use list → page.')
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))

r = proj / 'Rules.dc.html'; s = r.read_text()
old = '<li>At 1400px and wider the Inbox gets a reading pane: list on the left (380px), the selected memo on the right with its decision bar. Below that, and on phones, a memo opens as its own page.</li>'
if old in s:
    s = s.replace(old, '<li>Reading pane: a per-person switch in the My tasks toolbar, off by default and offered only at 1400px and wider. When on, the list sits on the left (380px) and the selected memo on the right with its decision bar. Below that width, and on phones, a memo opens as its own page.</li>')
    r.write_text(s)
print('ok')
