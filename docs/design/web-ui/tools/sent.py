"""The initiator's own memo while it is in review: who has it now, Recall while nobody has acted, Withdraw."""
import json, re
from pathlib import Path
proj = Path('canvas/project')

def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:90], s.count(a)); return s.replace(a, b)

ROUTE_JS = """    const recall = this.props.recall === 'available';
    const routeDefs = recall ? [
      { state: 'current', title: 'Head of Corporate Banking', who: 'Mahmud Karim', status: 'Has it since 14 Nov, 16:05' },
      { state: 'upcoming', title: 'Credit risk analysts · any one', who: 'Sadia Islam, Imran Hossain', status: 'Not yet' },
      { state: 'upcoming', title: 'Head of Credit', who: 'Farhana Rahman', status: 'Not yet' },
      { state: 'upcoming', title: 'Chief Risk Officer', who: 'Shirin Akter', status: 'Not yet' },
      { state: 'upcoming', title: 'Managing Director', who: 'Position holder', status: 'Not yet' }
    ] : [
      { state: 'done', title: 'Head of Corporate Banking', who: 'Mahmud Karim', status: 'Recommended · 15 Nov, 10:22' },
      { state: 'done', title: 'Credit risk analysts · any one', who: 'Sadia Islam, Imran Hossain', status: 'Recommended by Sadia Islam · 16 Nov, 13:58' },
      { state: 'current', title: 'Head of Credit', who: 'Farhana Rahman', status: 'Has it · overdue 1 day' },
      { state: 'upcoming', title: 'Chief Risk Officer', who: 'Shirin Akter', status: 'Not yet' },
      { state: 'upcoming', title: 'Managing Director', who: 'Position holder', status: 'Not yet' }
    ];
"""

def dialogs_js(phone):
    h = '44px' if phone else '36px'; fs = '15px' if phone else '14px'
    pad = '' if phone else 'padding: 0 14px; '
    return f"""    const primaryBtn = 'height: {h}; {pad}border: 0; border-radius: 8px; background: var(--primary); font-size: {fs}; font-weight: 500; color: var(--primary-fg); cursor: pointer';
    const dangerBtn = 'height: {h}; {pad}border: 0; border-radius: 8px; background: var(--destructive); font-size: {fs}; font-weight: 500; color: var(--on-destructive); cursor: pointer';
    const dialogs = {{
      recall: {{ title: 'Recall CR/2026/0142?', desc: 'Nobody has acted on it yet. It comes back to you to change and resubmit.', label: 'Remark (optional)', placeholder: 'Why you are recalling it…', confirm: 'Recall', btnStyle: primaryBtn, btnClass: 'primary' }},
      withdraw: {{ title: 'Withdraw this memo?', desc: 'The approval ends for good.', label: 'Remark (optional)', placeholder: 'Why you are withdrawing it…', confirm: 'Withdraw', btnStyle: dangerBtn, btnClass: 'danger' }}
    }};
"""

CARD_VALS = """      recallOk: recall, recallNo: !recall,
      wait: recall
        ? { step: 'Step 1 of 5 · round 2', initials: 'MK', name: 'Mahmud Karim', position: 'Head of Corporate Banking', since: 'Has it since 14 Nov, 16:05', overdue: false, dueText: 'Due 19 Nov' }
        : { step: 'Step 3 of 5 · round 2', initials: 'FR', name: 'Farhana Rahman', position: 'Head of Credit', since: 'Has it since 16 Nov, 13:58', overdue: true, dueText: 'Overdue 1 day' },
      openRecall: () => this.setState({ dialog: 'recall', moreOpen: false }),
      openWithdraw: () => this.setState({ dialog: 'withdraw', moreOpen: false }),
"""

def card(phone):
    if phone:
        return '''      <div style="display: flex; flex-direction: column; gap: 8px; margin-top: 4px; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: 10px">
        <span style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
          <span style="font-size: 13px; font-weight: 600">Waiting for approval</span>
          <span style="font-size: 12px; color: var(--muted-fg)">{{wait.step}}</span>
        </span>
        <span style="display: flex; align-items: center; gap: 10px">
          <span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: 12px; font-weight: 600; flex-shrink: 0">{{wait.initials}}</span>
          <span style="flex: 1; min-width: 0; display: flex; flex-direction: column">
            <span style="font-size: 14px; font-weight: 500">{{wait.name}}</span>
            <span style="font-size: 12px; color: var(--muted-fg)">{{wait.position}} · {{wait.since}}</span>
          </span>
          <sc-if value="{{wait.overdue}}" hint-placeholder-val="{{true}}"><span style="flex-shrink: 0; display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--danger-border); background: var(--danger-bg); color: var(--danger-fg); font-size: 12px; font-weight: 500">{{wait.dueText}}</span></sc-if>
        </span>
      </div>
'''
    return '''          <section aria-labelledby="task-h" style="position: relative; border: 1px solid var(--border-strong); border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 12px; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">
            <div style="display: flex; align-items: flex-start; justify-content: space-between; gap: 8px">
              <div style="display: flex; flex-direction: column; gap: 2px">
                <h2 id="task-h" style="margin: 0; font-size: 14px; font-weight: 600">Waiting for approval</h2>
                <span style="font-size: 13px; color: var(--muted-fg)">{{wait.step}}</span>
              </div>
              <sc-if value="{{wait.overdue}}" hint-placeholder-val="{{true}}"><span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--danger-border); background: var(--danger-bg); color: var(--danger-fg); font-size: 12px; font-weight: 500; white-space: nowrap">{{wait.dueText}}</span></sc-if>
              <sc-if value="{{recallOk}}" hint-placeholder-val="{{false}}"><span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); color: var(--text-2); font-size: 12px; font-weight: 500; white-space: nowrap">{{wait.dueText}}</span></sc-if>
            </div>
            <div style="display: flex; align-items: center; gap: 10px">
              <span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: 12px; font-weight: 600; flex-shrink: 0">{{wait.initials}}</span>
              <span style="display: flex; flex-direction: column; min-width: 0">
                <span style="font-size: 14px; font-weight: 500">{{wait.name}}</span>
                <span style="font-size: 12px; color: var(--muted-fg)">{{wait.position}} · {{wait.since}}</span>
              </span>
            </div>
            <div style="display: flex; flex-direction: column; gap: 8px; padding-top: 12px; border-top: 1px solid var(--hover)">
              <sc-if value="{{recallOk}}" hint-placeholder-val="{{false}}">
                <button type="button" class="ghost" onClick="{{openRecall}}" style="height: 36px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 14px; font-weight: 500; color: var(--fg); cursor: pointer">Recall to change it</button>
                <span style="font-size: 12px; color: var(--muted-fg)">Nobody has acted in this round yet. Recall it to change and resubmit.</span>
              </sc-if>
              <button type="button" class="ghost" onClick="{{openWithdraw}}" style="height: 36px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 14px; font-weight: 500; color: var(--danger-fg); cursor: pointer">Withdraw</button>
              <sc-if value="{{recallNo}}" hint-placeholder-val="{{true}}">
                <span style="font-size: 12px; color: var(--muted-fg)">Approvers have already acted in this round, so it can no longer be recalled. Withdrawing ends the approval for good.</span>
              </sc-if>
            </div>
          </section>
'''

ROUNDS_FILTER = """    if (recall) {
      rounds[0].notes = rounds[0].notes.filter((n) => n.action === 'Resubmitted');
      history.splice(0, 2);
    }
"""

# ---------- desktop
s = (proj / 'Memo.dc.html').read_text()
s = rep(s, re.search(r'<title>[^<]*</title>', s).group(0), '<title>Your memo in review</title>')
s = rep(s, '''          <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"></path></svg>
          Inbox
        </a>''', '''          <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"></path></svg>
          Sent
        </a>''')
a = s.index('          <section aria-labelledby="task-h"'); b = s.index('          <section aria-labelledby="route-h"')
s = s[:a] + card(False) + '\n' + s[b:]
s = rep(s, '<aside aria-label="Task and approvals"', '<aside aria-label="Where it is and approvals"')
s = rep(s, '''              <dt style="color: var(--muted-fg)">Initiator</dt>
              <dd style="margin: 0">Tanvir Ahmed</dd>''', '''              <dt style="color: var(--muted-fg)">Initiator</dt>
              <dd style="margin: 0">Tanvir Ahmed (you)</dd>''')
a = s.index('    const statusBase ='); b = s.index('    const route = routeDefs.map(')
s = s[:a] + "    const statusBase = 'font-size: 12px; line-height: 16px; margin-top: 2px; ';\n" + ROUTE_JS + s[b:]
a = s.index('    const primaryBtn ='); b = s.index('    const closeDialog =')
s = s[:a] + dialogs_js(False) + s[b:]
s = rep(s, "    const history = [", "    const history = [", 1)
s = rep(s, "    const statusBase =", ROUNDS_FILTER.replace('    if (recall)', '    if (this.props.recall === \'available\')') + "    const statusBase =")
s = rep(s, """      openApprove: () => this.setState({ dialog: 'approve', moreOpen: false }),
      openReturn: () => this.setState({ dialog: 'ret', moreOpen: false }),
      openReject: () => this.setState({ dialog: 'reject', moreOpen: false }),
""", CARD_VALS)
s = rep(s, 'dlg: dialogs[dialog] || dialogs.approve,', 'dlg: dialogs[dialog] || dialogs.withdraw,')
s = rep(s, "statusBase + (s.state === 'current' ? 'color: var(--danger-fg); font-weight: 500' : 'color: var(--muted-fg)')",
           "statusBase + (s.state === 'current' ? (recall ? 'color: var(--fg); font-weight: 500' : 'color: var(--danger-fg); font-weight: 500') : 'color: var(--muted-fg)')")
s = rep(s, '"$preview":{"width":1440,"height":1000}', '"recall":{"editor":"enum","options":["not possible","available"],"default":"not possible"},"$preview":{"width":1440,"height":1000}')
s = rep(s, '<span style="font-size: 12px; color: var(--muted-fg)">The remark goes on the note sheet with your name and position.</span>', '<span style="font-size: 12px; color: var(--muted-fg)">The remark goes on the note sheet with your name and position.</span>')
(proj / 'Sent.dc.html').write_text(s)

# ---------- phone
s = (proj / 'MemoPhone.dc.html').read_text()
s = rep(s, re.search(r'<title>[^<]*</title>', s).group(0), '<title>Your memo in review on a phone</title>')
s = rep(s, 'aria-label="Back to Inbox"', 'aria-label="Back to Sent"')
s = rep(s, '<span style="font-size: 13px; color: var(--muted-fg)">Credit proposal · Round 2 · From Tanvir Ahmed</span>', '<span style="font-size: 13px; color: var(--muted-fg)">Credit proposal · Round 2 · Your memo</span>')
a = s.index('      <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 4px; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: 10px">')
b = s.index('    </div>\n\n    <div class="tabs"', a)
s = s[:a] + card(True) + s[b:]
# Action bar: Recall (when possible) and Withdraw
a = s.index('  <!-- Action bar -->'); b = s.index('  <!-- Decision sheet -->')
s = s[:a] + '''  <!-- Action bar -->
  <div role="toolbar" aria-label="Your memo" style="flex-shrink: 0; display: flex; flex-direction: column; gap: 6px; padding: 10px 16px; border-top: 1px solid var(--border); background: var(--bg)">
    <div style="display: flex; gap: 8px">
      <sc-if value="{{recallOk}}" hint-placeholder-val="{{false}}">
        <button type="button" onClick="{{openRecall}}" style="flex: 1; height: 44px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 15px; font-weight: 500">Recall to change it</button>
      </sc-if>
      <button type="button" class="ghost" onClick="{{openWithdraw}}" style="flex: 1; height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 15px; font-weight: 500; color: var(--danger-fg)">Withdraw</button>
    </div>
    <sc-if value="{{recallNo}}" hint-placeholder-val="{{true}}"><span style="font-size: 12px; color: var(--muted-fg); text-align: center">Approvers have acted in this round, so it can no longer be recalled.</span></sc-if>
  </div>

''' + s[b:]
a = s.index('    const routeDefs = ['); b = s.index('    const route = routeDefs.map(')
s = s[:a] + ROUTE_JS.replace("Has it since 14 Nov, 16:05", "Has it since 14 Nov").replace("Recommended · 15 Nov, 10:22", "Recommended · 15 Nov").replace("Recommended by Sadia Islam · 16 Nov, 13:58", "Recommended by Sadia Islam · yesterday") + s[b:]
s = rep(s, "    const moreItems = [", ROUNDS_FILTER + "    const moreItems = [")
a = s.index('    const primaryBtn ='); b = s.index('    const closeDialog =')
s = s[:a] + dialogs_js(True) + s[b:]
s = rep(s, """      openApprove: () => this.setState({ dialog: 'approve', moreOpen: false }),
      openReturn: () => this.setState({ dialog: 'ret', moreOpen: false }),
      openReject: () => this.setState({ dialog: 'reject', moreOpen: false }),
""", CARD_VALS.replace("Has it since 14 Nov, 16:05", "since 14 Nov").replace("Has it since 16 Nov, 13:58", "since yesterday"))
s = rep(s, 'dlg: dialogs[dialog] || dialogs.approve,', 'dlg: dialogs[dialog] || dialogs.withdraw,')
s = rep(s, "statusStyle: 'font-size: 12px; line-height: 16px; margin-top: 2px; ' + (s.state === 'current' ? 'color: var(--danger-fg); font-weight: 500' : 'color: var(--muted-fg)')",
           "statusStyle: 'font-size: 12px; line-height: 16px; margin-top: 2px; ' + (s.state === 'current' ? (recall ? 'color: var(--fg); font-weight: 500' : 'color: var(--danger-fg); font-weight: 500') : 'color: var(--muted-fg)')")
s = rep(s, '"$preview":{"width":390,"height":844}', '"recall":{"editor":"enum","options":["not possible","available"],"default":"not possible"},"$preview":{"width":390,"height":844}')
(proj / 'SentPhone.dc.html').write_text(s)

c = proj / 'canvas.json'; d = json.loads(c.read_text())
y = 25000 - 25000 + 35220
d['boards']['Sent.dc.html'] = {"x": 0, "y": y, "w": 1440, "h": 1000, "title": "Your memo in review — Desktop", "expand": "fill", "is_interactive": True}
d['boards']['SentPhone.dc.html'] = {"x": 1520, "y": y, "w": 390, "h": 844, "title": "Your memo in review — Phone", "is_interactive": True}
d['notes']['sentTitle'] = {"x": 0, "y": y - 260, "text": "Your memo in review (initiator)", "kind": "title1", "maxW": 1910}
for f in ('Sent.dc.html', 'SentPhone.dc.html'):
    if f not in d['order']: d['order'].insert(d['order'].index('Rules.dc.html'), f)
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
