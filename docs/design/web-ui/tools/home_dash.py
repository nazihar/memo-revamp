"""Home and the Approvals dashboard, each with one job (2026-11-21).

- Home is where everyone starts and acts. Its "At a glance" counts gain the age of what waits (from the dashboard's
  `scope=me` figures). People who oversee a unit, or hold a dashboard grant, also get a "Your unit" card: what waits in
  the unit, how old it is, who is most overdue, and a link to the dashboard for that unit.
- The dashboard stays a full report, shown in the sidebar only to those people. It opens on their first unit; Me is
  listed last. Overdue › View list goes to the Team tab of the Inbox for a unit.
- Home gets a `viewer` tweak: `head` (Farhana Rahman, Head of Credit) or `approver` (Imran Hossain, who oversees no
  unit and has no dashboard grant: no "Your unit" card, no Dashboard in the sidebar).
- Every sidebar's Dashboard link, and the desktop Saved searches link, pointed nowhere (href="#"); they now open the
  page.
- Farhana has 8 tasks waiting on Home and in the Inbox badge; the dashboard said 9. It now says 8.

Idempotent: each step checks whether it is already done.
"""
import json, re
from pathlib import Path
proj = Path('canvas/project')
changed = set()
def save(p, s, o):
    if s != o: p.write_text(s); changed.add(p.name)

# ---------------------------------------------------------------- every board: the dead sidebar links
for p in sorted(proj.glob('*.dc.html')):
    s = o = p.read_text(); phone = p.name.endswith('Phone.dc.html')
    if not phone:
        if p.name != 'Dashboard.dc.html':
            s = s.replace('<a href="#" class="nav-item" title="Dashboard"', '<a href="Dashboard.dc.html" class="nav-item" title="Dashboard"')
        if p.name != 'Saved.dc.html':
            s = s.replace('<a href="#" class="nav-item" title="Saved searches"', '<a href="Saved.dc.html" class="nav-item" title="Saved searches"')
    elif p.name != 'DashboardPhone.dc.html':
        s = re.sub(r'<a href="#"( class="nav-item"[^>]*>(?:(?!</a>).)*?\n\s*Dashboard\n\s*</a>)', r'<a href="DashboardPhone.dc.html"\1', s, flags=re.S)
    save(p, s, o)

# ---------------------------------------------------------------- the dashboard: one count, unit first, View list
for name in ('Dashboard.dc.html', 'DashboardPhone.dc.html'):
    p = proj / name; s = o = p.read_text()
    s = s.replace("['Farhana Rahman', 9, 2]", "['Farhana Rahman', 8, 2]").replace("['2 others', 2, 0]", "['2 others', 3, 0]")
    s = s.replace('<option value="unit">Credit Risk</option>\n            <option value="me">Me</option>\n            <option value="cb">Corporate Banking</option>',
                  '<option value="unit">Credit Risk</option>\n            <option value="cb">Corporate Banking</option>\n            <option value="me">Me</option>')
    s = s.replace('<option>Credit Risk</option><option>Me</option><option>Corporate Banking</option>', '<option>Credit Risk</option><option>Corporate Banking</option><option>Me</option>')
    s = s.replace('<a href="Main.dc.html" style="font-size: 12px; font-weight: 500; color: var(--fg)">View list</a>',
                  '<a href="InboxTeam.dc.html" style="font-size: 12px; font-weight: 500; color: var(--fg)">View list</a>')
    save(p, s, o)

# ---------------------------------------------------------------- Home: shared data
UNIT_JS = r"""
    // Your unit: GET /api/v1/dashboards/approvals?scope=unit:<first unit> (pendingByAge, overdue, pendingByPerson are "now").
    const AGE = [['0–2', 14, 'var(--border-strong)'], ['3–5', 11, 'var(--muted-fg)'], ['6–10', 8, 'var(--warn-fg)'], ['Over 10', 5, 'var(--danger-fg)']];
    const ageTotal = AGE.reduce((n, a) => n + a[1], 0);
    const unit = { name: 'Credit Risk', waiting: String(ageTotal), overdue: '7', old: '5',
      segs: AGE.map((a, i) => ({ style: 'display: block; height: 100%; width: ' + (a[1] / ageTotal * 100).toFixed(2) + '%; background: ' + a[2] + (i > 0 ? '; border-left: 2px solid var(--card)' : '') })),
      legend: AGE.map((a) => ({ label: a[0] + ' days', value: String(a[1]), dot: 'width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; background: ' + a[2] })),
      worst: 'Sadia Islam', worstText: '3 overdue of 6 waiting', more: 'Corporate Banking', hasMore: true };
"""
IMRAN = {
    'tasks': """[
      { subject: 'Limit increase for Rupali Foods', ref: 'CR/2026/0158', action: 'Recommend', due: 'Overdue 1 day', kind: 'overdue' },
      { subject: 'Covenant waiver: Padma Textiles', ref: 'CR/2026/0151', action: 'Give opinion', due: 'Due tomorrow', kind: 'soon' },
      { subject: 'Working capital line for Surma Tea Estates', ref: 'CR/2026/0155', action: 'Recommend', due: 'Due in 3 days', kind: 'later' },
      { subject: 'Collateral revaluation, Chattogram branch', ref: 'CR/2026/0149', action: 'Recommend', due: 'Due in 5 days', kind: 'later' }
    ]""",
    'mine': """[
      { subject: 'Site visit report: Rupali Foods', where: 'RSK/2026/0047 · waiting for Farhana Rahman', status: 'In review', href: '#' },
      { subject: 'Exposure note: Meghna Agro group', where: 'Not submitted · changed yesterday', status: 'Draft', href: 'Draft.dc.html' }
    ]""",
    'notes': """[
      { text: 'Memo CR/2026/0158 is overdue', time: '08:00', unread: true, urgent: true },
      { text: 'Farhana Rahman opened your memo RSK/2026/0047', time: 'Yesterday', unread: true },
      { text: 'New sign-in to your account from Edge on Windows', time: 'Mon', unread: false }
    ]""",
    'saved': "['Rupali Foods — everything', 'My recommendations this month']",
}

# ---------------------------------------------------------------- Home, desktop
p = proj / 'Home.dc.html'; s = o = p.read_text()
if '{{me.name}}' not in s:
    s = s.replace('"theme":{"editor":"enum","options":["system","light","dark"],"default":"system"},',
                  '"theme":{"editor":"enum","options":["system","light","dark"],"default":"system"},"viewer":{"editor":"enum","options":["head","approver"],"default":"head"},', 1)
    s = s.replace('aria-label="Account menu, Farhana Rahman"', 'aria-label="Account menu, {{me.name}}"', 1)
    s = s.replace('font-size: 11px; flex-shrink: 0">FR</span>', 'font-size: 11px; flex-shrink: 0">{{me.initials}}</span>', 1)
    s = s.replace('line-height: 16px">Farhana Rahman</span>', 'line-height: 16px">{{me.name}}</span>', 1)
    s = s.replace('color: var(--muted-fg)">f.rahman@[domain]</span>', 'color: var(--muted-fg)">{{me.email}}</span>', 1)
    s = s.replace('>Good morning, Farhana</h1>', '>Good morning, {{me.first}}</h1>', 1)
    s = s.replace('font-variant-numeric: tabular-nums">8</span>', 'font-variant-numeric: tabular-nums">{{inboxCount}}</span>', 1)
    i = s.index('<a href="Dashboard.dc.html" class="nav-item" title="Dashboard"'); j = s.index('</a>', i) + 4
    s = s[:i] + '<sc-if value="{{showDash}}" hint-placeholder-val="{{true}}">' + s[i:j] + '</sc-if>' + s[j:]
    card = '''
          <!-- Your unit: only for people who oversee a unit or hold a dashboard grant -->
          <sc-if value="{{showDash}}" hint-placeholder-val="{{true}}">
          <section aria-labelledby="h-unit" style="display: flex; flex-direction: column; gap: 8px">
            <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 8px">
              <h2 id="h-unit" style="margin: 0; font-size: 14px; font-weight: 600">Your unit</h2>
              <a href="Dashboard.dc.html" style="font-size: 13px; font-weight: 500; color: var(--fg)">Open the dashboard</a>
            </div>
            <div style="display: flex; flex-direction: column; gap: 12px; padding: 14px 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--card)">
              <span style="display: flex; align-items: baseline; justify-content: space-between; gap: 8px"><span style="font-weight: 600">{{unit.name}}</span><span style="font-size: 12px; color: var(--muted-fg)">Waiting now</span></span>
              <dl style="margin: 0; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px">
                <div style="display: flex; flex-direction: column; gap: 2px"><dt style="font-size: 12px; color: var(--muted-fg)">Waiting</dt><dd style="margin: 0; font-size: 20px; line-height: 26px; font-weight: 600; font-variant-numeric: tabular-nums">{{unit.waiting}}</dd></div>
                <div style="display: flex; flex-direction: column; gap: 2px"><dt style="font-size: 12px; color: var(--muted-fg)">Overdue</dt><dd style="margin: 0; font-size: 20px; line-height: 26px; font-weight: 600; font-variant-numeric: tabular-nums; color: var(--danger-fg)">{{unit.overdue}}</dd></div>
                <div style="display: flex; flex-direction: column; gap: 2px"><dt style="font-size: 12px; color: var(--muted-fg)">Over 10 days</dt><dd style="margin: 0; font-size: 20px; line-height: 26px; font-weight: 600; font-variant-numeric: tabular-nums">{{unit.old}}</dd></div>
              </dl>
              <div style="display: flex; flex-direction: column; gap: 6px">
                <span aria-hidden="true" style="display: flex; height: 8px; border-radius: 4px; overflow: hidden; background: var(--muted)"><sc-for list="{{unit.segs}}" as="g" hint-placeholder-count="4"><span style="{{g.style}}"></span></sc-for></span>
                <ul aria-label="Waiting by working days" style="list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 12px; color: var(--muted-fg)"><sc-for list="{{unit.legend}}" as="lg" hint-placeholder-count="4"><li style="display: inline-flex; align-items: center; gap: 5px"><span aria-hidden="true" style="{{lg.dot}}"></span>{{lg.label}} <strong style="font-weight: 600; color: var(--fg); font-variant-numeric: tabular-nums">{{lg.value}}</strong></li></sc-for></ul>
              </div>
              <a href="Dashboard.dc.html" class="hrow" style="display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 0 -16px -14px; padding: 10px 16px; border-top: 1px solid var(--hover); font-size: 13px; color: var(--fg); text-decoration: none">
                <span>Most overdue: <strong style="font-weight: 600">{{unit.worst}}</strong>, {{unit.worstText}}</span>
              </a>
            </div>
            <sc-if value="{{unit.hasMore}}" hint-placeholder-val="{{true}}"><span style="font-size: 12px; color: var(--muted-fg)">You also see <a href="Dashboard.dc.html" style="color: var(--fg)">{{unit.more}}</a> in the dashboard.</span></sc-if>
          </section>
          </sc-if>
'''
    a = s.index('<aside aria-label="Updates"'); a = s.index('>', a) + 1
    s = s[:a] + card + s[a:]
    # data: the two viewers
    s = s.replace("    const tiles = [\n      { label: 'Waiting for you', value: '8', sub: 'Open Inbox', href: 'Main.dc.html' },\n      { label: 'Overdue', value: '2', sub: 'Show them', href: 'Main.dc.html', critical: true },\n      { label: 'Your memos in review', value: '3', sub: 'Sent', href: 'Main.dc.html' },\n      { label: 'Returned to you', value: '1', sub: 'Change and resubmit', href: 'Returned.dc.html', warn: true }\n    ]",
                  "    const HEAD = (this.props.viewer || 'head') === 'head';\n"
                  "    // The age line: GET /api/v1/dashboards/approvals?scope=me (pendingByAge, the oldest bucket with tasks in it).\n"
                  "    const tiles = (HEAD ? [\n      { label: 'Waiting for you', value: '8', sub: 'Oldest: 6–10 working days', href: 'Main.dc.html' },\n      { label: 'Overdue', value: '2', sub: 'Show them', href: 'Main.dc.html', critical: true },\n      { label: 'Your memos in review', value: '3', sub: 'Sent', href: 'Main.dc.html' },\n      { label: 'Returned to you', value: '1', sub: 'Change and resubmit', href: 'Returned.dc.html', warn: true }\n    ] : [\n"
                  "      { label: 'Waiting for you', value: '6', sub: 'Oldest: 3–5 working days', href: 'Main.dc.html' },\n      { label: 'Overdue', value: '1', sub: 'Show it', href: 'Main.dc.html', critical: true },\n      { label: 'Your memos in review', value: '1', sub: 'Sent', href: 'Main.dc.html' },\n      { label: 'Returned to you', value: '0', sub: 'Nothing to change', href: 'Main.dc.html' }\n    ])", 1)
    for key, tail in (('tasks', '].map((t, i)'), ('mine', '].map((m, i)'), ('notes', '].map((n, i)')):
        i = s.index(f'    const {key} = [') + len(f'    const {key} = '); j = s.index(tail, i) + 1
        s = s[:i] + '(HEAD ? ' + s[i:j] + ' : ' + IMRAN[key] + ')' + s[j:]
    i = s.index("    const saved = ['Meghna Agro") + len('    const saved = '); j = s.index('].map(', i) + 1
    s = s[:i] + '(HEAD ? ' + s[i:j] + ' : ' + IMRAN['saved'] + ')' + s[j:]
    s = s.replace("    const closeNav = () => this.setState({ navOpen: false });", UNIT_JS + "    const closeNav = () => this.setState({ navOpen: false });", 1)
    s = s.replace("      tiles: tiles, tasks: tasks, mine: mine, notes: notes, saved: saved,",
                  "      tiles: tiles, tasks: tasks, mine: mine, notes: notes, saved: saved, unit: unit, showDash: HEAD, inboxCount: HEAD ? '8' : '6',\n"
                  "      me: HEAD ? { name: 'Farhana Rahman', first: 'Farhana', initials: 'FR', email: 'f.rahman@[domain]' } : { name: 'Imran Hossain', first: 'Imran', initials: 'IH', email: 'i.hossain@[domain]' },", 1)
    s = s.replace('<span style="font-size: 12px; color: var(--muted-fg)">3 more in your Inbox, none due this week.</span>', '<span style="font-size: 12px; color: var(--muted-fg)">{{moreText}}</span>', 1)
    s = s.replace("unit: unit, showDash: HEAD,", "unit: unit, showDash: HEAD, moreText: HEAD ? '3 more in your Inbox, none due this week.' : '2 more in your Inbox, none due this week.',", 1)
    save(p, s, o)

# ---------------------------------------------------------------- Home, phone
p = proj / 'HomePhone.dc.html'; s = o = p.read_text()
if '{{me.name}}' not in s:
    s = s.replace('"theme":{"editor":"enum","options":["system","light","dark"],"default":"system"},',
                  '"theme":{"editor":"enum","options":["system","light","dark"],"default":"system"},"viewer":{"editor":"enum","options":["head","approver"],"default":"head"},', 1)
    s = s.replace('aria-label="Account menu, Farhana Rahman"', 'aria-label="Account menu, {{me.name}}"', 1)
    s = s.replace('font-weight: 500; font-size: 11px">FR</span>', 'font-weight: 500; font-size: 11px">{{me.initials}}</span>', 1)
    s = s.replace('>Good morning, Farhana</h1>', '>Good morning, {{me.first}}</h1>', 1)
    s = s.replace('<span style="font-size: 12px; color: var(--text-3)">8</span>', '<span style="font-size: 12px; color: var(--text-3)">{{inboxCount}}</span>', 1)
    s = re.sub(r'(<a href="DashboardPhone.dc.html" class="nav-item"(?:(?!</a>).)*?</a>)', r'<sc-if value="{{showDash}}" hint-placeholder-val="{{true}}">\1</sc-if>', s, count=1, flags=re.S)
    i = s.index('<nav aria-label="At a glance"'); j = s.index('</nav>', i) + len('</nav>')
    s = s[:j] + '\n    <p style="margin: -4px 0 0; font-size: 12px; color: var(--muted-fg)">{{ageLine}}</p>' + s[j:]
    card = '''
    <sc-if value="{{showDash}}" hint-placeholder-val="{{true}}">
    <section aria-labelledby="ph-unit" style="display: flex; flex-direction: column; gap: 8px">
      <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 8px">
        <h2 id="ph-unit" style="margin: 0; font-size: 14px; font-weight: 600">Your unit · {{unit.name}}</h2>
        <a href="DashboardPhone.dc.html" style="display: inline-flex; align-items: center; min-height: 44px; margin: -12px 0; font-size: 13px; font-weight: 500; color: var(--fg)">Dashboard</a>
      </div>
      <a href="DashboardPhone.dc.html" style="display: flex; flex-direction: column; gap: 10px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); color: var(--fg); text-decoration: none">
        <span style="font-size: 14px"><strong style="font-weight: 600; font-variant-numeric: tabular-nums">{{unit.waiting}}</strong> waiting · <strong style="font-weight: 600; color: var(--danger-fg); font-variant-numeric: tabular-nums">{{unit.overdue}}</strong> overdue · <strong style="font-weight: 600; font-variant-numeric: tabular-nums">{{unit.old}}</strong> over 10 days</span>
        <span aria-hidden="true" style="display: flex; height: 8px; border-radius: 4px; overflow: hidden; background: var(--muted)"><sc-for list="{{unit.segs}}" as="g" hint-placeholder-count="4"><span style="{{g.style}}"></span></sc-for></span>
        <span style="font-size: 13px; color: var(--text-2)">Most overdue: <strong style="font-weight: 600; color: var(--fg)">{{unit.worst}}</strong>, {{unit.worstText}}</span>
      </a>
    </section>
    </sc-if>
'''
    m = s.index('    <section aria-labelledby="ph-mine"')
    s = s[:m] + card.lstrip('\n') + '\n' + s[m:]
    s = s.replace("    const tiles = [\n      { label: 'Waiting for you', value: '8', href: 'Phone.dc.html' },\n      { label: 'Overdue', value: '2', href: 'Phone.dc.html', color: 'color: var(--danger-fg)' },\n      { label: 'In review', value: '3', href: 'Phone.dc.html' },\n      { label: 'Returned to you', value: '1', href: 'ReturnedPhone.dc.html', color: 'color: var(--warn-fg)' }\n    ]",
                  "    const HEAD = (this.props.viewer || 'head') === 'head';\n    const tiles = (HEAD ? [\n      { label: 'Waiting for you', value: '8', href: 'Phone.dc.html' },\n      { label: 'Overdue', value: '2', href: 'Phone.dc.html', color: 'color: var(--danger-fg)' },\n      { label: 'In review', value: '3', href: 'Phone.dc.html' },\n      { label: 'Returned to you', value: '1', href: 'ReturnedPhone.dc.html', color: 'color: var(--warn-fg)' }\n    ] : [\n"
                  "      { label: 'Waiting for you', value: '6', href: 'Phone.dc.html' },\n      { label: 'Overdue', value: '1', href: 'Phone.dc.html', color: 'color: var(--danger-fg)' },\n      { label: 'In review', value: '1', href: 'Phone.dc.html' },\n      { label: 'Returned to you', value: '0', href: 'Phone.dc.html' }\n    ])", 1)
    PH = {'tasks': """[
      { subject: 'Limit increase for Rupali Foods', ref: 'CR/2026/0158', action: 'Recommend', due: 'Overdue 1d', s: 'background: var(--destructive); color: var(--on-destructive)' },
      { subject: 'Covenant waiver: Padma Textiles', ref: 'CR/2026/0151', action: 'Give opinion', due: 'Tomorrow', s: 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)' },
      { subject: 'Working capital line for Surma Tea Estates', ref: 'CR/2026/0155', action: 'Recommend', due: 'In 3 days', s: 'border: 1px solid var(--border); color: var(--text-2)' }
    ]""", 'mine': """[
      { subject: 'Site visit report: Rupali Foods', where: 'Waiting for Farhana Rahman', status: 'In review', href: '#', s: 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)' },
      { subject: 'Exposure note: Meghna Agro group', where: 'Not submitted', status: 'Draft', href: 'DraftPhone.dc.html', s: 'border: 1px solid var(--border); color: var(--text-2)' }
    ]""", 'notes': IMRAN['notes']}
    for key, tail in (('tasks', '].map((t, i)'), ('mine', '].map((m, i)'), ('notes', '].map((n, i)')):
        if f'    const {key} = [' not in s: continue
        i = s.index(f'    const {key} = [') + len(f'    const {key} = '); j = s.index(tail, i) + 1
        s = s[:i] + '(HEAD ? ' + s[i:j] + ' : ' + PH[key] + ')' + s[j:]
    s = s.replace("    const closeNav = () => this.setState({ navOpen: false });", "    const closeNav = () => this.setState({ navOpen: false });" + UNIT_JS, 1)
    s = re.sub(r"(\n\s*return \{)", r"\1\n      unit: unit, showDash: HEAD, inboxCount: HEAD ? '8' : '6', ageLine: HEAD ? 'Oldest waiting: 6–10 working days.' : 'Oldest waiting: 3–5 working days.',\n      me: HEAD ? { name: 'Farhana Rahman', first: 'Farhana', initials: 'FR' } : { name: 'Imran Hossain', first: 'Imran', initials: 'IH' },", s, count=1)
    save(p, s, o)

# ---------------------------------------------------------------- the canvas note beside Home
c = proj / 'canvas.json'; d = json.loads(c.read_text())
home = d['boards']['Home.dc.html']
if 'homeDashNote' not in d['notes']:
    d['notes']['homeDashNote'] = {'x': 2720, 'y': home['y'], 'w': 620, 'size': 24, 'fill': 'blue', 'text':
        'Home and Dashboard: one job each — implementation\n\n'
        '• Home is where everyone starts and acts. The Dashboard is a report for people who oversee a unit. They are not merged: Home loads on every sign-in and must stay fast and short; the report needs scope and period controls that do not belong on Home.\n'
        '• Home › At a glance: the counts come from the Inbox as today. The “Oldest: 6–10 working days” line under Waiting for you is the oldest non-empty bucket of pendingByAge from GET /api/v1/dashboards/approvals?scope=me. If that call fails or is slow, show the counts without the line.\n'
        '• Home › Your unit (right column; on phones after Needs your decision): shown when the person has a unit scope, that is useScopeOptions() returns a unit (a head seat from the team view, or a memo.dashboard.read grant). It calls the same endpoint with scope=unit:<first unit>; pendingByAge, overdue and pendingByPerson are figures for now, so no period is sent. Waiting = sum of pendingByAge; Over 10 days = its last bucket; Most overdue = pendingByPerson with the most overdue (then most pending, then name); with nobody overdue the line says “Nobody is overdue.”. With more units: “You also see … in the dashboard”, each a link to that scope. The card loads on its own (skeleton, then the section error from Page states); Home never waits for it.\n'
        '• Links: Open the dashboard and the card go to /dashboards/approvals?scope=unit:<id>.\n'
        '• Sidebar: Dashboard shows only to people with a unit scope (navigation.ts audience: a new kind computed from useTeamAccess() and the memo.dashboard.read grant, instead of signedIn). The address still works for anyone and shows Me.\n'
        '• Dashboard: with no scope in the address and a unit available, open on the first unit (replace the address with scope=unit:<id>); Me is listed last. Overdue › View list goes to Inbox › Team for a unit, to My tasks for Me.\n'
        '• Tweak viewer on Home: head (Farhana Rahman, Head of Credit) or approver (Imran Hossain: no card, no Dashboard in the sidebar).\n'
        '• API: nothing new.'}
    if 'noteOrder' in d and 'homeDashNote' not in d['noteOrder']: d['noteOrder'].append('homeDashNote')
    c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':'))); changed.add('canvas.json')
if d['boards']['Home.dc.html']['h'] < 1140:
    d['boards']['Home.dc.html']['h'] = 1140; c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':'))); changed.add('canvas.json')
if d['notes']['homeDashNote']['x'] != 2720:
    d['notes']['homeDashNote']['x'] = 2720; c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':'))); changed.add('canvas.json')
print(len(changed), 'files changed:', ' '.join(sorted(changed)[:8]), '…' if len(changed) > 8 else '')

# ---------------------------------------------------------------- Home: an approver has no Administration group either
for name, phone in (('Home.dc.html', False), ('HomePhone.dc.html', True)):
    p = proj / name; s = o = p.read_text()
    if '{{showAdmin}}' in s: continue
    if phone:
        i = s.index('<div style="height: 32px; display: flex; align-items: center; padding: 8px 8px 0; font-size: 12px; font-weight: 500; color: var(--muted-fg)">Administration</div>')
        j = s.index('</a>', s.index('<a href="AuditPhone.dc.html" class="nav-item"', i)) + 4
    else:
        k = s.index('>Administration</div>'); i = s.rfind('<div style="display: flex; flex-direction: column; padding: 8px 0 0">', 0, k)
        j = s.index('</div>', s.index('<a href="Audit.dc.html" class="nav-item"', i)) + 6
    s = s[:i] + '<sc-if value="{{showAdmin}}" hint-placeholder-val="{{true}}">' + s[i:j] + '</sc-if>' + s[j:]
    s = s.replace("unit: unit, showDash: HEAD,", "unit: unit, showDash: HEAD, showAdmin: HEAD,", 1)
    save(p, s, o)
print(len(changed), 'files changed in total')
