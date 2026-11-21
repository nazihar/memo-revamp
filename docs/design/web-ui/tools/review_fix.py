import re
from pathlib import Path
proj = Path('canvas/project')
changed = set()
def edit(name, fn):
    p = proj / name; s = p.read_text(); t = fn(s)
    if t != s: p.write_text(t); changed.add(name)

# A. One memo, one cast: the FIN/2026/0311 budget is Nasrin Akter's; Rashed Khan heads Retail Banking; Arif Rahman is the administrator.
FIN = ['Rejected', 'Withdrawn', 'Cancelled', 'Discarded', 'RejectedPhone', 'WithdrawnPhone', 'CancelledPhone', 'DiscardedPhone']
for f in FIN:
    def fa(s, f=f):
        s = s.replace('Sadia Islam', 'Nasrin Akter').replace('>SI<', '>NA<').replace('Mahmud Karim', 'Rashed Khan')
        if f.startswith('Cancelled'): s = s.replace('Kamal Hasan', 'Arif Rahman').replace('>KH<', '>AR<')
        return s
    edit(f + '.dc.html', fa)

# B. The memo waiting in the inbox is a different budget; the watched rejected one was followed after it was submitted.
T2_OLD = "subject: 'Q4 branch expansion budget', ref: 'FIN/2026/0311'"
T2_NEW = "subject: 'Branch refurbishment budget, Chattogram', ref: 'FIN/2026/0326'"
for f in ['Main', 'Phone', 'InboxWatching', 'InboxTeam', 'InboxWatchingPhone', 'InboxTeamPhone', 'Home', 'HomePhone', 'Tasks', 'TasksPhone']:
    def fb(s):
        out = []
        for line in s.split('\n'):
            if T2_OLD in line and "status: 'Rejected'" not in line:
                line = line.replace(T2_OLD, T2_NEW).replace("from: 'Sadia Islam'", "from: 'Nasrin Akter'")
            if "id: 'w6'" in line:
                line = line.replace("since: '4 Nov, 09:40', sinceShort: '4 Nov'", "since: '11 Nov, 09:40', sinceShort: '11 Nov'")
            out.append(line)
        return '\n'.join(out)
    edit(f + '.dc.html', fb)
for src in ['backup-inbox/Main.dc.html', 'backup-inbox/Phone.dc.html']:
    p = Path(src); s = p.read_text(); p.write_text('\n'.join(l.replace(T2_OLD, T2_NEW).replace("from: 'Sadia Islam'", "from: 'Nasrin Akter'") if T2_OLD in l else l for l in s.split('\n')))
p = Path('tools/inbox_tabs.py'); s = p.read_text(); p.write_text(s.replace("since: '4 Nov, 09:40', sinceShort: '4 Nov'", "since: '11 Nov, 09:40', sinceShort: '11 Nov'"))

# C. Status in the sidebar (the app lists it under Work, for everyone), before Organisation.
ICON = '<path d="M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"></path>'
DESK = re.compile(r'( *)<a [^>]*title="Organisation"[^>]*>.*?</a>\n', re.S)
PH = re.compile(r'( *)<a href="OrgPhone\.dc\.html"[^>]*>.*?Organisation\n\s*</a>\n', re.S)
for p in sorted(proj.glob('*.dc.html')):
    s = p.read_text()
    if 'title="Status"' in s or re.search(r'href="StatusPhone\.dc\.html" class="nav-item"', s): continue
    for rx, phone in ((DESK, False), (PH, True)):
        m = rx.search(s)
        if not m: continue
        b = m.group(0).replace(' aria-current="page"', '').replace('; background: var(--active); font-weight: 500', '')
        b = re.sub(r'(<svg[^>]*>).*?(</svg>)', lambda mm: mm.group(1) + ICON + mm.group(2), b, count=1, flags=re.S)
        b = re.sub(r'href="[^"]*"', f'href="{"StatusPhone" if phone else "Status"}.dc.html"', b, count=1)
        b = b.replace('title="Organisation"', 'title="Status"').replace('>Organisation<', '>Status<').replace('Organisation\n', 'Status\n')
        s = s[:m.start()] + b + s[m.start():]
    p.write_text(s); changed.add(p.name)
def mark(s, phone):
    if phone:
        tag = '<a href="StatusPhone.dc.html" class="nav-item" style="display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var(--fg)">'
        assert tag in s
        return s.replace(tag, tag[:-2] + '; background: var(--active); font-weight: 500" aria-current="page">')
    tag = 'title="Status" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg)">'
    assert tag in s
    return s.replace(tag, 'title="Status" aria-current="page" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg); background: var(--active); font-weight: 500">')
edit('Status.dc.html', lambda s: mark(s, False) if 'title="Status" aria-current' not in s else s)
edit('StatusPhone.dc.html', lambda s: mark(s, True) if 'StatusPhone.dc.html" class="nav-item" style="display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var(--fg); background' not in s else s)

# D. A memo page keeps Inbox highlighted, the way Position keeps Organisation.
INBOX_TAG = re.compile(r'<a ([^>]*?)title="Inbox"([^>]*?)style="([^"]*)"')
for f in ['Memo', 'Sent', 'Draft', 'Returned', 'Approved', 'Closed', 'Rejected', 'Withdrawn', 'Cancelled', 'Discarded', 'NewMemo']:
    def fd(s):
        if re.search(r'title="Inbox"[^>]*aria-current', s): return s
        m = INBOX_TAG.search(s); assert m, f
        return s[:m.start()] + f'<a {m.group(1)}title="Inbox" aria-current="page"{m.group(2)}style="{m.group(3)}; background: var(--active); font-weight: 500"' + s[m.end():]
    edit(f + '.dc.html', fd)

# E. Watch, not Follow.
for f in ['Prefs', 'PrefsPhone']:
    edit(f + '.dc.html', lambda s: s.replace('Follow memos I create or act on', 'Watch memos I create or act on'))

# F. No browser validation on the number field.
edit('NewMemo.dc.html', lambda s: s.replace('type="number" min="1" placeholder="12"', 'type="text" inputmode="numeric" placeholder="12"'))

# G. Three dialog widths: 460 (short form), 560 (lists or an editor), 900 (previews, comparisons).
WMAP = {'400': '460', '420': '460', '440': '460', '480': '460', '500': '460', '520': '560'}
DLG = re.compile(r'(<(?:div|form)\b[^>]*role="(?:dialog|alertdialog|\{\{[a-z.]*role\}\})"[^>]*?max-width: )(\d+)(px)')
for p in sorted(proj.glob('*.dc.html')):
    s = p.read_text(); t = DLG.sub(lambda m: m.group(1) + WMAP.get(m.group(2), m.group(2)) + m.group(3), s)
    if t != s: p.write_text(t); changed.add(p.name)
for gen, reps in {'tools/dialogs_gallery.py': [(", 400),", ", 460),"), (", 520),", ", 560),")],
                  'tools/parts.py': [(", 440),", ", 460),"), (", 520),", ", 560),"), ("max-width: {880 if state == \"ready\" else 520}px", "max-width: {880 if state == \"ready\" else 560}px")],
                  'tools/states.py': [("max-width: 420px", "max-width: 460px")]}.items():
    q = Path(gen); s = q.read_text()
    for a, b in reps: s = s.replace(a, b)
    q.write_text(s)

# H. Rules: dialog widths and the phone top-level list.
def fh(s):
    s = s.replace('<li>Dialog: 460px wide, title 18px, description, field, then Cancel and the action on the right.</li>',
                  '<li>Dialog widths: 460px for a short form, 560px when it holds a list or an editor, 900px for previews and comparisons. Title 18px, description, fields, then Cancel and the action on the right.</li>')
    s = s.replace('(Home, Inbox, Search, Notifications, Saved searches, Dashboard, Organisation)', '(Home, Inbox, Search, Notifications, Saved searches, Dashboard, Status, Organisation)')
    return s
edit('Rules.dc.html', fh)

# I. Memo page parts: Kamal Hasan works in Credit Administration, as on the Team tab.
q = Path('tools/parts.py'); s = q.read_text()
s = s.replace("('Kamal Hasan', 'Internal Audit Officer', 'KH')", "('Kamal Hasan', 'Credit Administration Officer', 'KH')").replace("until 31 Dec 2026 · Audit sample", "until 31 Dec 2026 · Prepares the security documents")
q.write_text(s)
print(sorted(changed))
