"""Withdrawn, Cancelled and Discarded memos: the Rejected layout with each state's own outcome, reason and next step."""
import json, re
from pathlib import Path
proj = Path('canvas/project')
REJ = (proj / 'Rejected.dc.html').read_text()
REJP = (proj / 'RejectedPhone.dc.html').read_text()

NEUTRAL_BADGE = 'border: 1px solid var(--border); background: var(--muted); color: var(--text-2)'
NEUTRAL_STRIP = 'border: 1px solid var(--border); border-radius: 10px; background: var(--subtle); color: var(--text-1)'
ICONS = {
    'withdrawn': '<path d="M9 14 4 9l5-5"></path><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"></path>',
    'cancelled': '<circle cx="12" cy="12" r="10"></circle><path d="m4.9 4.9 14.2 14.2"></path>',
    'discarded': '<path d="M3 6h18"></path><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"></path><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"></path>',
}
STATES = {
    'withdrawn': dict(
        file='Withdrawn', label='Withdrawn', title='Withdrawn memo',
        when='Withdrawn on 14 Nov 2026, 09:20', by='by you, Sadia Islam, while it waited at step 2 of 4', tail='The approval ended for good.',
        phoneLine='Withdrawn 14 Nov by you, while it waited at step 2 of 4',
        reasonH='Reason for withdrawing',
        reason='Finance asked for the Rajshahi site to be replaced with Bogura. I will start a new memo once the new site study is in, so this one does not hold up the approvers.',
        who=('SI', 'Sadia Islam', 'Budget analyst · 14 Nov, 09:20 · on document version 2'), phoneWho='Sadia Islam (you) · 14 Nov, 09:20',
        nextH='Trying again', next='A withdrawn memo cannot be resubmitted. Start a new memo; it is linked as a follow-up of FIN/2026/0311, so readers see why this one stopped.',
        phoneNext='A withdrawn memo cannot be resubmitted. A new memo is linked as a follow-up of this one.',
        cta='Start a follow-up memo', docCap='Version 2 · the version that was in review',
        stopStatus='Was waiting · withdrawn 14 Nov, 09:20', stopPhone='Withdrawn 14 Nov', record='withdrawn',
        note=("Sadia Islam", "Budget analyst", "Withdrew", "14 Nov, 09:20"),
        hist=("14 Nov 09:20", "Sadia Islam", "withdrew; the approval ended for good"),
        moreHist='Submitted 11 Nov · withdrawn 14 Nov'),
    'cancelled': dict(
        file='Cancelled', label='Cancelled', title='Cancelled memo',
        when='Cancelled on 16 Nov 2026, 17:45', by='by Kamal Hasan, administrator, while it waited at step 2 of 4', tail='Nobody can act on it again.',
        phoneLine='Cancelled 16 Nov by Kamal Hasan, administrator, at step 2 of 4',
        reasonH='Reason for cancelling',
        reason='Duplicate of FIN/2026/0309, which carries the same request and is further along. Cancelled at the request of the Head of Retail Banking so that only one is decided.',
        who=('KH', 'Kamal Hasan', 'Administrator · 16 Nov, 17:45'), phoneWho='Kamal Hasan, administrator · 16 Nov, 17:45',
        nextH='If the request still stands', next='A cancelled memo cannot be resubmitted. Start a new memo; it is linked as a follow-up of FIN/2026/0311, so readers see why this one stopped.',
        phoneNext='A cancelled memo cannot be resubmitted. A new memo is linked as a follow-up of this one.',
        cta='Start a follow-up memo', docCap='Version 2 · the version that was in review',
        stopStatus='Was waiting · cancelled 16 Nov, 17:45', stopPhone='Cancelled 16 Nov', record='cancelled',
        note=("Kamal Hasan", "Administrator", "Cancelled", "16 Nov, 17:45"),
        hist=("16 Nov 17:45", "Kamal Hasan", "cancelled the approval; nobody can act on it again"),
        moreHist='Submitted 11 Nov · cancelled 16 Nov'),
    'discarded': dict(
        file='Discarded', label='Discarded', title='Discarded draft',
        when='Discarded on 13 Nov 2026, 16:10', by='by you, Sadia Islam', tail='It was never submitted, so it has no number and no approvals.',
        phoneLine='Discarded 13 Nov by you. It was never submitted.',
        nextH='If you still need it', next='A discarded draft cannot be submitted, restored or linked. Start a new memo and copy what you need from this document.',
        phoneNext='A discarded draft cannot be submitted, restored or linked. Start a new memo instead.',
        cta='New memo', docCap='Version 2 · the last version saved', record='discarded',
        moreHist='Created 10 Nov · discarded 13 Nov'),
}

def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:90], s.count(a)); return s.replace(a, b)

def desktop(k, c):
    s = REJ
    s = rep(s, '<title>Rejected memo</title>' if '<title>Rejected memo</title>' in s else re.search(r'<title>[^<]*</title>', s).group(0), f'<title>{c["title"]}</title>')
    # Header badge
    s = rep(s, '''<span style="display: inline-flex; align-items: center; gap: 4px; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--danger-border); background: var(--danger-bg); color: var(--danger-fg); font-size: 12px; font-weight: 500">
                <svg aria-hidden="true" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg>
                Rejected''', f'''<span style="display: inline-flex; align-items: center; gap: 4px; height: 22px; padding: 0 8px; border-radius: 6px; {NEUTRAL_BADGE}; font-size: 12px; font-weight: 500">
                <svg aria-hidden="true" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">{ICONS[k]}</svg>
                {c["label"]}''')
    if k == 'discarded':
        s = rep(s, '''              <span style="font-family: 'Geist Mono', ui-monospace, monospace; color: var(--text-2)">FIN/2026/0311</span>
              <span aria-hidden="true">·</span>
              <span>Budget request</span>
              <span aria-hidden="true">·</span>
              <span>Round 1</span>''', '''              <span>Not numbered</span>
              <span aria-hidden="true">·</span>
              <span>Budget request</span>''')
    # Outcome strip
    a = s.index('      <!-- Outcome strip -->'); b = s.index('      <div style="display: flex; flex-wrap: wrap; align-items: flex-start; gap: 20px">', a)
    s = s[:a] + f'''      <!-- Outcome strip -->
      <div role="status" style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 12px 16px; {NEUTRAL_STRIP}; font-size: 13px">
        <span style="display: inline-flex; align-items: center; gap: 8px; font-weight: 600; color: var(--fg)">
          <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{ICONS[k]}</svg>
          {c["when"]}
        </span>
        <span>{c["by"]}</span>
        <span aria-hidden="true">·</span>
        <span>{c["tail"]}</span>
      </div>

''' + s[b:]
    # Reason
    a = s.index('          <!-- Reason -->'); b = s.index('          <!-- What next -->')
    if k == 'discarded':
        s = s[:a] + s[b:]
    else:
        ini, name, meta = c['who']
        s = s[:a] + f'''          <!-- Reason -->
          <section aria-labelledby="r-reason-h" style="border: 1px solid var(--border-strong); border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">
            <h2 id="r-reason-h" style="margin: 0; font-size: 14px; font-weight: 600">{c["reasonH"]}</h2>
            <blockquote style="margin: 0; padding-left: 12px; border-left: 2px solid var(--border-strong); font-size: 15px; line-height: 24px; color: var(--fg)">{c["reason"]}</blockquote>
            <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 13px">
              <span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: 10px; font-weight: 600">{ini}</span>
              <span style="font-weight: 500">{name}</span>
              <span style="color: var(--muted-fg)">{meta}</span>
            </span>
          </section>

''' + s[b:]
    s = rep(s, '<h2 id="r-next-h" style="margin: 0; font-size: 14px; font-weight: 600">Trying again</h2>', f'<h2 id="r-next-h" style="margin: 0; font-size: 14px; font-weight: 600">{c["nextH"]}</h2>')
    s = rep(s, 'A rejected memo cannot be resubmitted. Start a new memo; it is linked as a follow-up of FIN/2026/0311, so readers see this decision and its reason.', c['next'])
    s = rep(s, '              Start a follow-up memo\n', f'              {c["cta"]}\n')
    s = rep(s, 'Version 2 · the version that was rejected', c['docCap'])
    # Route: a neutral "stopped" mark next to the rejected one
    stopped = '''                    <sc-if value="{{s.stopped}}" hint-placeholder-val="{{false}}">
                      <span style="display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; box-sizing: border-box; border-radius: 50%; border: 1.5px solid var(--text-3); color: var(--text-3)">
                        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round"><path d="M5 12h14"></path></svg>
                      </span>
                    </sc-if>
'''
    s = rep(s, '                    <sc-if value="{{s.skipped}}" hint-placeholder-val="{{true}}">', stopped + '                    <sc-if value="{{s.skipped}}" hint-placeholder-val="{{true}}">')
    if k == 'discarded':
        s = rep(s, '<span style="font-size: 12px; color: var(--muted-fg)">Stopped at step 2</span>', '<span style="font-size: 12px; color: var(--muted-fg)">Never submitted</span>')
        s = rep(s, '<h2 id="r-route-h" style="margin: 0; font-size: 14px; font-weight: 600">Approvers</h2>', '<h2 id="r-route-h" style="margin: 0; font-size: 14px; font-weight: 600">Planned approvers</h2>')
        s = rep(s, 'Kept as it stood when it was rejected: document, note sheet, attachments and history stay readable and cannot be changed.', 'Kept as it stood when it was discarded: document, note sheet, attachments and history stay readable for you and cannot be changed.')
        s = rep(s, '<dt style="color: var(--muted-fg)">Can read</dt><dd style="margin: 0">5 people · 1 copy</dd>', '<dt style="color: var(--muted-fg)">Can read</dt><dd style="margin: 0">Only you</dd>')
        # A discarded draft cannot be linked
        a = s.index('              <div style="padding: 10px 16px; border-top: 1px solid var(--hover)">\n                <button type="button" class="ghost"')
        b = s.index('              </div>\n', a) + len('              </div>\n')
        s = s[:a] + s[b:]
    else:
        s = rep(s, 'Kept as it stood when it was rejected:', f'Kept as it stood when it was {c["record"]}:')
    s = rep(s, '<aside aria-label="Where it stopped"', '<aside aria-label="' + ('Approvers and access' if k == 'discarded' else 'Where it stopped') + '"')
    # Script data
    js_a = s.index('    const rounds = ['); js_b = s.index('    const route = defs.map(')
    if k == 'discarded':
        data = '''    const rounds = [
      { title: 'Draft', hint: 'Never submitted', notes: [
        note('Sadia Islam', 'Budget analyst', 'Remark', '11 Nov, 16:40', 'Waiting for the second fit-out quote before submitting.')
      ]}
    ];
    const attachments = [
      { title: 'Site footfall study.pdf', meta: 'PDF · 2.4 MB · version 1', restricted: false },
      { title: 'Fit-out quotes.xlsx', meta: 'Excel · 64 KB · version 1', restricted: false }
    ].map((f) => Object.assign({}, f, { downloadLabel: 'Download ' + f.title }));
    const links = [];
    const history = [
      { time: '13 Nov 16:10', who: 'Sadia Islam', what: 'discarded the draft', round: 1 },
      { time: '11 Nov 16:40', who: 'Sadia Islam', what: 'added a remark', round: 1 },
      { time: '11 Nov 11:02', who: 'Sadia Islam', what: 'saved document version 2', round: 1 },
      { time: '10 Nov 15:30', who: 'Sadia Islam', what: 'created the draft', round: 1 }
    ];
    const defs = [
      { state: 'skipped', title: 'Head of Retail Banking', who: 'Mahmud Karim', status: 'Planned' },
      { state: 'skipped', title: 'Chief Financial Officer', who: 'Rafiq Chowdhury', status: 'Planned' },
      { state: 'skipped', title: 'Chief Risk Officer', who: 'Shirin Akter', status: 'Planned' },
      { state: 'skipped', title: 'Managing Director', who: 'Abdul Kader', status: 'Planned' }
    ];
'''
    else:
        n = c['note']; h = c['hist']
        data = f'''    const rounds = [
      {{ title: 'Round 1', hint: 'The only round', notes: [
        note('{n[0]}', '{n[1]}', '{n[2]}', '{n[3]}', '{c["reason"]}'),
        note('Mahmud Karim', 'Head of Retail Banking', 'Recommended', '12 Nov, 10:15', 'Supported. The Rajshahi footfall estimate looks optimistic; Finance should check it.'),
        note('Sadia Islam', 'Budget analyst', 'Remark', '11 Nov, 16:40', 'Added fit-out quotes from two vendors.'),
        note('Sadia Islam', 'Budget analyst', 'Submitted', '11 Nov, 09:05', 'Budget for two new branches in Sylhet and Rajshahi to meet FY2026 deposit targets.')
      ]}}
    ];
    const attachments = [
      {{ title: 'Site footfall study.pdf', meta: 'PDF · 2.4 MB · version 1', restricted: false }},
      {{ title: 'Fit-out quotes.xlsx', meta: 'Excel · 64 KB · version 1', restricted: false }}
    ].map((f) => Object.assign({{}}, f, {{ downloadLabel: 'Download ' + f.title }}));
    const links = [];
    const history = [
      {{ time: '{h[0]}', who: '{h[1]}', what: '{h[2]}', round: 1 }},
      {{ time: '12 Nov 10:15', who: 'Mahmud Karim', what: 'recommended', round: 1 }},
      {{ time: '11 Nov 16:40', who: 'Sadia Islam', what: 'added a remark', round: 1 }},
      {{ time: '11 Nov 09:05', who: 'Sadia Islam', what: 'submitted; numbered FIN/2026/0311', round: 1 }},
      {{ time: '10 Nov 15:30', who: 'Sadia Islam', what: 'created the draft', round: 1 }}
    ];
    const defs = [
      {{ state: 'done', title: 'Head of Retail Banking', who: 'Mahmud Karim', status: 'Recommended · 12 Nov, 10:15' }},
      {{ state: 'stopped', title: 'Chief Financial Officer', who: 'Rafiq Chowdhury', status: '{c["stopStatus"]}' }},
      {{ state: 'skipped', title: 'Chief Risk Officer', who: 'Shirin Akter', status: 'Not reached' }},
      {{ state: 'skipped', title: 'Managing Director', who: 'Abdul Kader', status: 'Not reached' }}
    ];
'''
    s = s[:js_a] + data + s[js_b:]
    s = rep(s, "done: s.state === 'done', rejected: s.state === 'rejected', skipped: s.state === 'skipped',", "done: s.state === 'done', rejected: s.state === 'rejected', stopped: s.state === 'stopped', skipped: s.state === 'skipped',")
    s = rep(s, "statusStyle: 'font-size: 12px; line-height: 16px; margin-top: 2px; ' + (s.state === 'rejected' ? 'color: var(--danger-fg); font-weight: 500' : 'color: var(--muted-fg)')",
               "statusStyle: 'font-size: 12px; line-height: 16px; margin-top: 2px; ' + (s.state === 'stopped' ? 'color: var(--text-1); font-weight: 500' : 'color: var(--muted-fg)')")
    s = rep(s, "{ title: 'Note sheet', value: '4 notes · last: Rafiq Chowdhury rejected, 17 Nov' },", "{ title: 'Note sheet', value: '" + ('1 note' if k == 'discarded' else '4 notes') + "' },")
    s = rep(s, "{ title: 'History', value: 'Created 10 Nov · submitted 11 Nov · rejected 17 Nov' }", "{ title: 'History', value: '" + c['moreHist'] + "' }")
    if k == 'discarded':
        s = rep(s, '<span style="flex-shrink: 0; font-size: 12px; color: var(--muted-fg)">Round {{h.round}}</span>', '')
    if k == 'discarded':
        s = rep(s, "{ id: 'notes', label: 'Note sheet', count: 4 },", "{ id: 'notes', label: 'Note sheet', count: 1 },")
        s = rep(s, "linksEmpty: 'No linked memos yet. A follow-up memo appears here once you start one.',", "linksEmpty: 'A discarded draft cannot be linked to other memos.',")
        s = rep(s, 'Newest first. Read-only: nothing can be added to a finished memo.', 'Read-only: nothing can be added to a discarded draft.')
    return s

def phone(k, c):
    s = REJP
    s = rep(s, re.search(r'<title>[^<]*</title>', s).group(0), f'<title>{c["title"]} on a phone</title>')
    s = rep(s, '<span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--danger-border); background: var(--danger-bg); color: var(--danger-fg); font-size: 12px; font-weight: 500">Rejected</span>',
               f'<span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; {NEUTRAL_BADGE}; font-size: 12px; font-weight: 500">{c["label"]}</span>')
    s = rep(s, 'Rejected 17 Nov by Rafiq Chowdhury, Chief Financial Officer, at step 2 of 4', c['phoneLine'])
    if k == 'discarded':
        s = rep(s, '<span style="flex: 1; font-family: \'Geist Mono\', ui-monospace, monospace; font-size: 14px; font-weight: 500">FIN/2026/0311</span>', '<span style="flex: 1; font-size: 15px; font-weight: 500">Discarded draft</span>')
    a = s.index('    <section aria-labelledby="pr-reason"'); b = s.index('    <section aria-labelledby="pr-next"')
    if k == 'discarded':
        s = s[:a] + s[b:]
    else:
        s = s[:a] + f'''    <section aria-labelledby="pr-reason" style="padding: 12px; border: 1px solid var(--border-strong); border-radius: 10px; display: flex; flex-direction: column; gap: 8px">
      <h2 id="pr-reason" style="margin: 0; font-size: 14px; font-weight: 600">{c["reasonH"]}</h2>
      <blockquote style="margin: 0; padding-left: 10px; border-left: 2px solid var(--border-strong); font-size: 15px; line-height: 22px">{c["reason"]}</blockquote>
      <span style="font-size: 12px; color: var(--muted-fg)">{c["phoneWho"]}</span>
    </section>

''' + s[b:]
    s = rep(s, '<h2 id="pr-next" style="margin: 0; font-size: 14px; font-weight: 600">Trying again</h2>', f'<h2 id="pr-next" style="margin: 0; font-size: 14px; font-weight: 600">{c["nextH"]}</h2>')
    s = rep(s, 'A rejected memo cannot be resubmitted. A new memo is linked as a follow-up of this one, so readers see this decision.', c['phoneNext'])
    s = rep(s, '>Start a follow-up memo</a>', f'>{c["cta"]}</a>')
    s = rep(s, 'Kept as it stood when rejected. Only approved memos get a final record PDF.', f'Kept as it stood when {c["record"]}. Only approved memos get a final record PDF.')
    if k == 'discarded':
        s = rep(s, '<h2 id="pr-route" style="margin: 0 0 4px; font-size: 14px; font-weight: 600">Approvers</h2>', '<h2 id="pr-route" style="margin: 0 0 4px; font-size: 14px; font-weight: 600">Planned approvers</h2>')
        s = rep(s, """      { state: 'done', title: 'Head of Retail Banking', who: 'Mahmud Karim', status: 'Recommended 12 Nov' },
      { state: 'rejected', title: 'Chief Financial Officer', who: 'Rafiq Chowdhury', status: 'Rejected 17 Nov' },
      { state: 'skipped', title: 'Chief Risk Officer', who: 'Shirin Akter', status: 'Not reached' },
      { state: 'skipped', title: 'Managing Director', who: 'Abdul Kader', status: 'Not reached' }""", """      { state: 'skipped', title: 'Head of Retail Banking', who: 'Mahmud Karim', status: 'Planned' },
      { state: 'skipped', title: 'Chief Financial Officer', who: 'Rafiq Chowdhury', status: 'Planned' },
      { state: 'skipped', title: 'Chief Risk Officer', who: 'Shirin Akter', status: 'Planned' },
      { state: 'skipped', title: 'Managing Director', who: 'Abdul Kader', status: 'Planned' }""")
        s = rep(s, "{ title: 'Note sheet', value: '4 notes' },", "{ title: 'Note sheet', value: '1 note' },")
    else:
        s = rep(s, "{ state: 'rejected', title: 'Chief Financial Officer', who: 'Rafiq Chowdhury', status: 'Rejected 17 Nov' },", f"{{ state: 'stopped', title: 'Chief Financial Officer', who: 'Rafiq Chowdhury', status: '{c['stopPhone']}' }},")
    s = rep(s, "{ title: 'History', value: 'Submitted 11 Nov · rejected 17 Nov' }", f"{{ title: 'History', value: '{c['moreHist']}' }}")
    s = rep(s, "mark: s.state === 'done' ? '✓' : (s.state === 'rejected' ? '✕' : ''),", "mark: s.state === 'done' ? '✓' : (s.state === 'rejected' ? '✕' : (s.state === 'stopped' ? '–' : '')),")
    s = rep(s, "(s.state === 'rejected' ? 'background: var(--destructive); color: var(--on-destructive)' : 'border: 1.5px dashed var(--border-strong)')",
               "(s.state === 'rejected' ? 'background: var(--destructive); color: var(--on-destructive)' : (s.state === 'stopped' ? 'border: 1.5px solid var(--text-3); color: var(--text-3)' : 'border: 1.5px dashed var(--border-strong)'))")
    s = rep(s, "statusStyle: 'font-size: 12px; ' + (s.state === 'rejected' ? 'color: var(--danger-fg); font-weight: 500' : 'color: var(--muted-fg)')",
               "statusStyle: 'font-size: 12px; ' + (s.state === 'stopped' ? 'color: var(--text-1); font-weight: 500' : 'color: var(--muted-fg)')")
    return s

c = proj / 'canvas.json'; d = json.loads(c.read_text())
y0 = 30360
for i, (k, cfg) in enumerate(STATES.items()):
    (proj / f'{cfg["file"]}.dc.html').write_text(desktop(k, cfg))
    (proj / f'{cfg["file"]}Phone.dc.html').write_text(phone(k, cfg))
    y = y0 + i * 1620
    d['boards'][f'{cfg["file"]}.dc.html'] = {"x": 0, "y": y, "w": 1440, "h": 1000, "title": f'{cfg["title"]} — Desktop', "expand": "fill", "is_interactive": True}
    d['boards'][f'{cfg["file"]}Phone.dc.html'] = {"x": 1520, "y": y, "w": 390, "h": 844, "title": f'{cfg["title"]} — Phone', "is_interactive": True}
    d['notes'][f'{k}Title'] = {"x": 0, "y": y - 260, "text": cfg['title'][0].upper() + cfg['title'][1:], "kind": "title1", "maxW": 1910}
    for f in (f'{cfg["file"]}.dc.html', f'{cfg["file"]}Phone.dc.html'):
        if f not in d['order']: d['order'].insert(d['order'].index('Rules.dc.html'), f)
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
