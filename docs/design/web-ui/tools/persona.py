"""One-time fix (idempotent): who is signed in, and what the sidebar shows them.

- User boards (the Screens page) are seen by Farhana Rahman, Head of Credit. Her roles are Auditor and Dashboard viewer,
  so the app shows her only Overview and Audit under Administration (app/navigation.ts: Overview needs any admin
  permission, audit.read is one; Audit needs audit.read). Her sidebar and phone drawer are trimmed to those two.
- Admin boards are seen by Arif Rahman, an organisation administrator: the top bar of the Open tasks boards (the shell
  the other admin boards are built from, see kit.desk_pre / kit.phone_parts) shows him.
- Inbox phone boards use the 18px page title like every other phone page.
"""
import json, re
from pathlib import Path
proj = Path('canvas/project')
d = json.loads((proj / 'canvas.json').read_text())
page = {k: v.get('page', 'screens') for k, v in d['boards'].items()}

ARCHIVE = '<rect width="20" height="5" x="2" y="3" rx="1"></rect><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"></path><path d="M10 12h4"></path>'
def audit_desk(overview):
    a = re.sub(r'href="AdminOverview.dc.html"', 'href="Audit.dc.html"', overview).replace('title="Overview"', 'title="Audit"').replace('<span class="lbl">Overview</span>', '<span class="lbl">Audit</span>')
    return re.sub(r'(<svg[^>]*>).*?(</svg>)', lambda m: m.group(1) + ARCHIVE + m.group(2), a, count=1, flags=re.S)
def audit_phone(overview):
    a = overview.replace('href="AdminOverviewPhone.dc.html"', 'href="AuditPhone.dc.html"').replace('\n        Overview\n', '\n        Audit\n')
    return re.sub(r'(<svg[^>]*>).*?(</svg>)', lambda m: m.group(1) + ARCHIVE + m.group(2), a, count=1, flags=re.S)

changed = []
for p in sorted(proj.glob('*.dc.html')):
    if page.get(p.name) != 'screens': continue
    s = o = p.read_text()
    # desktop sidebar: everything after the Administration label up to the end of the group
    m = re.search(r'(>Administration</div>\n)((?:\s*<a [^>]*class="nav-item".*?</a>)+)(\n\s*</div>\n\s*</nav>)', s, re.S)
    if m and 'title="Users"' in m.group(2):
        items = re.findall(r'\s*<a [^>]*class="nav-item".*?</a>', m.group(2), re.S)
        ov = next(i for i in items if 'title="Overview"' in i)
        s = s[:m.start(2)] + ov + audit_desk(ov) + s[m.end(2):]
    # phone drawer
    m = re.search(r'(>Administration</div>\n)((?:\s*<a [^>]*class="nav-item".*?</a>)+)(\n\s*</nav>)', s, re.S)
    if m and 'UsersPhone.dc.html' in m.group(2):
        items = re.findall(r'\s*<a [^>]*class="nav-item".*?</a>', m.group(2), re.S)
        ov = next(i for i in items if 'AdminOverviewPhone.dc.html' in i)
        s = s[:m.start(2)] + ov + audit_phone(ov) + s[m.end(2):]
    if p.name in ('InboxTeamPhone.dc.html', 'InboxWatchingPhone.dc.html', 'Phone.dc.html'):
        s = re.sub(r'(<h1 style="[^"]*?)font-size: 20px', r'\1font-size: 18px', s)
    if s != o:
        p.write_text(s); changed.append(p.name)

# The admin shell (Open tasks) is seen by Arif Rahman
ARIF = [('aria-label="Account menu, Farhana Rahman"', 'aria-label="Account menu, Arif Rahman"'),
        ('font-size: 11px; flex-shrink: 0">FR</span>', 'font-size: 11px; flex-shrink: 0">AR</span>'),
        ('font-weight: 500; font-size: 11px">FR</span>', 'font-weight: 500; font-size: 11px">AR</span>'),
        ('line-height: 16px">Farhana Rahman</span>', 'line-height: 16px">Arif Rahman</span>'),
        ('color: var(--muted-fg)">f.rahman@[domain]</span>', 'color: var(--muted-fg)">a.rahman@[domain]</span>')]
for name in ('Tasks.dc.html', 'TasksPhone.dc.html'):
    p = proj / name; s = o = p.read_text()
    for a, b in ARIF: s = s.replace(a, b)
    if s != o: p.write_text(s); changed.append(name)
print(len(changed), 'boards changed')
