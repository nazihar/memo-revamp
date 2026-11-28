"""Closed memo (initiator closed an approved memo), and the Close action on the Approved page for the initiator."""
import json, re
from pathlib import Path
proj = Path('canvas/project')
def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:90], s.count(a)); return s.replace(a, b)

ARCHIVE = '<rect width="20" height="5" x="2" y="3" rx="1"></rect><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"></path><path d="M10 12h4"></path>'
REMARK = 'Facility set up in core banking and the limit letter signed on 18 Nov. Nothing left to do on this memo.'
APPROVED = (proj / 'Approved.dc.html').read_text()
APPROVEDP = (proj / 'ApprovedPhone.dc.html').read_text()
MEMO = (proj / 'Memo.dc.html').read_text()
MEMOP = (proj / 'MemoPhone.dc.html').read_text()

# ---------- Closed desktop
s = APPROVED
s = rep(s, '<title>Approved memo</title>', '<title>Closed memo</title>')
s = rep(s, '''          <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"></path></svg>
          Inbox
        </a>''', '''          <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"></path></svg>
          Closed
        </a>''')
s = rep(s, '''<span style="display: inline-flex; align-items: center; gap: 4px; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--ok-border); background: var(--ok-bg); color: var(--ok-fg); font-size: 12px; font-weight: 500">
                <svg aria-hidden="true" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"></path></svg>
                Approved''', f'''<span style="display: inline-flex; align-items: center; gap: 4px; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); background: var(--muted); color: var(--text-2); font-size: 12px; font-weight: 500">
                <svg aria-hidden="true" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">{ARCHIVE}</svg>
                Closed''')
a = s.index('      <!-- Outcome strip -->'); b = s.index('      <div style="display: flex; flex-wrap: wrap; align-items: flex-start; gap: 20px">', a)
s = s[:a] + f'''      <!-- Outcome strip -->
      <div role="status" style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 12px 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--subtle); color: var(--text-1); font-size: 13px">
        <span style="display: inline-flex; align-items: center; gap: 8px; font-weight: 600; color: var(--fg)">
          <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{ARCHIVE}</svg>
          Closed on 18 Nov 2026, 16:40
        </span>
        <span>by you, Tanvir Ahmed · approved earlier that day by Abdul Kader, Managing Director</span>
        <span aria-hidden="true">·</span>
        <span>Its work is done; nobody can act on it again.</span>
      </div>

''' + s[b:]
s = rep(s, '          <!-- Final record -->\n', f'''          <!-- Closing remark -->
          <section aria-labelledby="c-rem-h" style="border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 10px">
            <h2 id="c-rem-h" style="margin: 0; font-size: 14px; font-weight: 600">Closing remark</h2>
            <blockquote style="margin: 0; padding-left: 12px; border-left: 2px solid var(--border-strong); font-size: 15px; line-height: 24px; color: var(--fg)">{REMARK}</blockquote>
            <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 13px">
              <span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: 10px; font-weight: 600">TA</span>
              <span style="font-weight: 500">Tanvir Ahmed</span>
              <span style="color: var(--muted-fg)">Relationship Manager · 18 Nov, 16:40</span>
            </span>
          </section>

          <!-- Final record -->
''')
s = rep(s, '<dt style="color: var(--muted-fg)">Initiator</dt><dd style="margin: 0">Tanvir Ahmed</dd>', '<dt style="color: var(--muted-fg)">Initiator</dt><dd style="margin: 0">Tanvir Ahmed (you)</dd>')
s = rep(s, "    const history = [\n", "    const history = [\n      { time: '18 Nov 16:40', who: 'Tanvir Ahmed', what: 'closed the memo', round: 2 },\n")
s = rep(s, "      { title: 'Round 2', hint: 'Final round', notes: [\n", f"      {{ title: 'Round 2', hint: 'Final round', notes: [\n        note('Tanvir Ahmed', 'Relationship Manager', 'Closed', '18 Nov, 16:40', '{REMARK}'),\n")
(proj / 'Closed.dc.html').write_text(s)

# ---------- Closed phone
s = APPROVEDP
s = rep(s, '<title>Approved memo on a phone</title>', '<title>Closed memo on a phone</title>')
s = rep(s, 'aria-label="Back to Inbox"', 'aria-label="Back to Closed"')
s = rep(s, '<span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--ok-border); background: var(--ok-bg); color: var(--ok-fg); font-size: 12px; font-weight: 500">Approved</span>',
           '<span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); background: var(--muted); color: var(--text-2); font-size: 12px; font-weight: 500">Closed</span>')
s = rep(s, '<span style="font-size: 13px; color: var(--muted-fg)">Approved 18 Nov by Abdul Kader, Managing Director</span>', '<span style="font-size: 13px; color: var(--muted-fg)">Closed 18 Nov by you · approved the same day by Abdul Kader, Managing Director. Nobody can act on it again.</span>')
s = rep(s, '    <!-- Final record -->\n', f'''    <section aria-labelledby="pc-rem" style="padding: 12px; border: 1px solid var(--border); border-radius: 10px; display: flex; flex-direction: column; gap: 8px">
      <h2 id="pc-rem" style="margin: 0; font-size: 14px; font-weight: 600">Closing remark</h2>
      <blockquote style="margin: 0; padding-left: 10px; border-left: 2px solid var(--border-strong); font-size: 15px; line-height: 22px">{REMARK}</blockquote>
      <span style="font-size: 12px; color: var(--muted-fg)">Tanvir Ahmed (you) · 18 Nov, 16:40</span>
    </section>

    <!-- Final record -->
''')
s = rep(s, "{ title: 'History', value: 'Final record made 18 Nov, 11:07' }", "{ title: 'History', value: 'Closed 18 Nov, 16:40' }")
s = rep(s, "{ title: 'Note sheet', value: '8 notes over 2 rounds' },", "{ title: 'Note sheet', value: '9 notes over 2 rounds' },")
(proj / 'ClosedPhone.dc.html').write_text(s)

# ---------- Approved: the initiator can close it
CLOSE_DLG = {'title': 'Close CR/2026/0142?', 'desc': 'Its work is done. It stays readable and keeps its number; nobody can act on it again.'}
if 'openCloseMemo' not in APPROVED:
    s = APPROVED
    s = rep(s, '''            <button type="button" class="ghost" aria-label="More options for this memo" style="display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px;''',
               '''            <sc-if value="{{isInitiator}}" hint-placeholder-val="{{false}}">
              <button type="button" class="ghost" onClick="{{openCloseMemo}}" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 13px; font-weight: 500; color: var(--fg); cursor: pointer; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">
                <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">''' + ARCHIVE + '''</svg>
                Close memo
              </button>
            </sc-if>
            <button type="button" class="ghost" aria-label="More options for this memo" style="display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px;''')
    dlg = MEMO[MEMO.index('  <!-- Decision dialog -->'):MEMO.index('</div>\n</x-dc>')]
    dlg = dlg.replace('<!-- Decision dialog -->', '<!-- Close dialog -->')
    i = s.index('</div>\n</x-dc>'); s = s[:i] + dlg + s[i:]
    s = rep(s, '"$preview":{"width":1440', '"viewer":{"editor":"enum","options":["approver","initiator"],"default":"approver"},"$preview":{"width":1440')
    s = rep(s, "      followLabel: following ? 'Watching' : 'Watch',\n", f"""      isInitiator: this.props.viewer === 'initiator',
      openCloseMemo: () => this.setState({{ dialog: 'close' }}),
      dialogOpen: st.dialog === 'close',
      dlg: {{ title: '{CLOSE_DLG['title']}', desc: '{CLOSE_DLG['desc']}', label: 'Remark (optional)', placeholder: 'What was done, for the record…', confirm: 'Close memo', btnClass: 'primary', btnStyle: 'height: 36px; padding: 0 14px; border: 0; border-radius: 8px; background: var(--primary); font-size: 14px; font-weight: 500; color: var(--primary-fg); cursor: pointer' }},
      closeDialog: () => this.setState({{ dialog: '' }}),
      dialogKey: (ev) => {{ if (ev && ev.key === 'Escape') this.setState({{ dialog: '' }}); }},
      followLabel: following ? 'Watching' : 'Watch',
""")
    (proj / 'Approved.dc.html').write_text(s)
if 'openCloseMemo' not in APPROVEDP:
    s = APPROVEDP
    s = rep(s, '    <!-- Final record -->\n', '''    <sc-if value="{{isInitiator}}" hint-placeholder-val="{{false}}">
      <section aria-labelledby="pa-close" style="padding: 12px; border: 1px solid var(--border); border-radius: 10px; display: flex; flex-direction: column; gap: 10px">
        <span style="display: flex; flex-direction: column; gap: 2px">
          <h2 id="pa-close" style="margin: 0; font-size: 14px; font-weight: 600">Done with it?</h2>
          <span style="font-size: 13px; color: var(--muted-fg)">Close it when its work is done. It stays readable and keeps its number.</span>
        </span>
        <button type="button" class="ghost" onClick="{{openCloseMemo}}" style="height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 15px; font-weight: 500; color: var(--fg)">Close memo</button>
      </section>
    </sc-if>

    <!-- Final record -->
''')
    dlg = MEMOP[MEMOP.index('  <!-- Decision sheet -->'):MEMOP.index('</div>\n</x-dc>')].replace('<!-- Decision sheet -->', '<!-- Close sheet -->')
    i = s.index('</div>\n</x-dc>'); s = s[:i] + dlg + s[i:]
    s = rep(s, '"$preview":{"width":390', '"viewer":{"editor":"enum","options":["approver","initiator"],"default":"approver"},"$preview":{"width":390')
    s = rep(s, "      stamps: stamps,\n", f"""      stamps: stamps,
      isInitiator: this.props.viewer === 'initiator',
      openCloseMemo: () => this.setState({{ dialog: 'close' }}),
      dialogOpen: !!(this.state && this.state.dialog === 'close'),
      dlg: {{ title: '{CLOSE_DLG['title']}', desc: '{CLOSE_DLG['desc']}', label: 'Remark (optional)', placeholder: 'What was done, for the record…', confirm: 'Close memo', btnStyle: 'height: 44px; border: 0; border-radius: 8px; background: var(--primary); font-size: 15px; font-weight: 500; color: var(--primary-fg)' }},
      closeDialog: () => this.setState({{ dialog: '' }}),
      dialogKey: (ev) => {{ if (ev && ev.key === 'Escape') this.setState({{ dialog: '' }}); }},
""")
    (proj / 'ApprovedPhone.dc.html').write_text(s)

c = proj / 'canvas.json'; d = json.loads(c.read_text())
y = 36840
d['boards']['Closed.dc.html'] = {"x": 0, "y": y, "w": 1440, "h": 1000, "title": "Closed memo — Desktop", "expand": "fill", "is_interactive": True}
d['boards']['ClosedPhone.dc.html'] = {"x": 1520, "y": y, "w": 390, "h": 844, "title": "Closed memo — Phone", "is_interactive": True}
d['notes']['closedTitle'] = {"x": 0, "y": y - 260, "text": "Closed memo", "kind": "title1", "maxW": 1910}
for f in ('Closed.dc.html', 'ClosedPhone.dc.html'):
    if f not in d['order']: d['order'].insert(d['order'].index('Rules.dc.html'), f)
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
