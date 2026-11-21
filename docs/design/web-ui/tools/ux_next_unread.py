"""UX additions: approve and open the next task, 'Since you last looked', unread markers, and the Inbox reading pane.
One-time edit of Memo, MemoPhone and the six Inbox boards; builds InboxReading. Skips anything already done."""
import json, re
from pathlib import Path
proj = Path('canvas/project')
def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:100], s.count(a)); return s.replace(a, b)
def svg(p, s=16, style=''): return f'<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"{f" style={chr(34)}{style}{chr(34)}" if style else ""}>{p}</svg>'
I = {
 'repeat': '<path d="m17 2 4 4-4 4"></path><path d="M3 11v-1a4 4 0 0 1 4-4h14"></path><path d="m7 22-4-4 4-4"></path><path d="M21 13v1a4 4 0 0 1-4 4H3"></path>',
 'file': '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"></path><path d="M14 2v4a2 2 0 0 0 2 2h4"></path>',
 'clip': '<path d="m16 6-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551"></path>',
 'note': '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>',
 'left': '<path d="m15 18-6-6 6-6"></path>', 'right': '<path d="m9 18 6-6-6-6"></path>',
 'eye': '<path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"></path><circle cx="12" cy="12" r="3"></circle>',
 'expand': '<path d="M15 3h6v6"></path><path d="M10 14 21 3"></path><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path>',
}
SR = 'position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)'
NEWCHIP = '<span style="display: inline-flex; align-items: center; height: 18px; padding: 0 6px; border-radius: 6px; background: var(--info-bg); color: var(--info-fg); font-size: 11px; font-weight: 600">New</span>'
DOT = f'<span aria-hidden="true" style="width: 8px; height: 8px; border-radius: 50%; background: var(--info-fg); flex-shrink: 0"></span><span style="{SR}">Unread: </span>'

def since_card(phone, go_notes='{{goNotes}}'):
    def item(icon, main, sub, action, click=None):
        btn = ''
        if action:
            on = f' onClick="{click}"' if click else ''
            btn = f'<button type="button" class="ghost"{on} style="flex-shrink: 0; height: {"40px" if phone else "28px"}; padding: 0 {10 if phone else 8}px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">{action}</button>'
        return f'''<div style="display: flex; align-items: {"flex-start" if phone else "center"}; gap: 10px; padding: 8px 0; border-top: 1px solid var(--hover)">
                  <span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 6px; background: var(--info-bg); color: var(--info-fg); flex-shrink: 0">{svg(I[icon], 14)}</span>
                  <span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px"><span style="font-size: 13px; font-weight: 500">{main}</span>{f'<span style="font-size: 12px; color: var(--muted-fg)">{sub}</span>' if sub else ''}</span>
                  {btn}
                </div>'''
    return f'''<section aria-labelledby="since-h" style="border: 1px solid var(--border-strong); border-radius: 10px; padding: {"12px" if phone else "14px 16px"}; display: flex; flex-direction: column; gap: 8px; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">
                <div style="display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 4px 8px">
                  <h2 id="since-h" style="margin: 0; font-size: 14px; font-weight: 600">Since you last looked</h2>
                  <span style="font-size: 12px; color: var(--muted-fg)">10 Nov, when you returned it</span>
                </div>
                <div style="padding: 8px 10px; border-radius: 8px; background: var(--subtle); font-size: 13px; color: var(--text-1)"><span style="color: var(--muted-fg)">You asked:</span> “Please attach a collateral valuation not older than six months.”</div>
                <div style="display: flex; flex-direction: column">
                {item('repeat', 'Resubmitted by Tanvir Ahmed · 14 Nov, 16:05', '“Added the revised collateral valuation as requested.”', None)}
                {item('file', 'Document: version 2 → version 3', '2 changes', 'Compare')}
                {item('clip', 'Attachment added', 'Panel valuation report, October 2026.pdf', 'Preview')}
                {item('note', '3 new notes', 'Mahmud Karim and Sadia Islam recommended it', 'Read', go_notes)}
                </div>
              </section>'''

def next_checkbox(phone):
    return f'''<label style="display: flex; align-items: flex-start; gap: 10px; {"min-height: 44px;" if phone else ""} font-size: 14px; cursor: pointer">
          <input type="checkbox" checked="{{{{nextOn}}}}" onChange="{{{{toggleNext}}}}" style="margin: 2px 0 0; width: {18 if phone else 16}px; height: {18 if phone else 16}px; accent-color: var(--primary); flex-shrink: 0">
          <span style="display: flex; flex-direction: column; gap: 2px"><span style="font-weight: 500">Open my next task after this</span><span style="font-size: 12px; color: var(--muted-fg)">{{{{nextHint}}}}</span></span>
        </label>'''

def toast(phone):
    pos = 'position: absolute; left: 12px; right: 12px; bottom: 76px' if phone else 'position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%); max-width: 640px'
    return f'''
  <sc-if value="{{{{hasToast}}}}" hint-placeholder-val="{{{{false}}}}">
    <div role="status" style="{pos}; z-index: 40; display: flex; {"flex-direction: column; align-items: stretch;" if phone else "align-items: center;"} gap: 8px 14px; padding: 10px 12px 10px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg); box-shadow: 0 10px 30px rgba(0,0,0,0.15); font-size: 14px">
      <span style="flex: 1">{{{{toast}}}}</span>
      <span style="display: flex; gap: 8px">
        <sc-if value="{{{{toastNext}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" class="ghost" onClick="{{{{dismissToast}}}}" style="height: {"40px" if phone else "30px"}; padding: 0 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">Open next task</button></sc-if>
        <a href="{"Phone" if phone else "Main"}.dc.html" style="display: inline-flex; align-items: center; height: {"40px" if phone else "30px"}; padding: 0 10px; border-radius: 6px; color: var(--fg); font-size: 13px; font-weight: 500">Back to Inbox</a>
      </span>
    </div>
  </sc-if>'''

UX_JS = """
    // Next task and 'since you last looked' (UX additions).
    const queueLast = this.props.queue === 'last';
    const nextOn = st.nextOn !== undefined ? st.nextOn : true;
    const pastTense = { approve: 'Approved', ret: 'Returned', reject: 'Rejected' };
    const ux = {
      nextOn: nextOn,
      toggleNext: () => this.setState({ nextOn: !nextOn }),
      nextHint: queueLast ? 'This is your last task in My tasks.' : 'Next: Credit Risk unit budget, FY2027 (2 of 7 in My tasks)',
      confirmDecision: () => {
        const verb = pastTense[dialog] || 'Done';
        const msg = verb + ' CR/2026/0142. ' + (queueLast ? 'That was your last task: My tasks is clear.' : (nextOn ? 'Opening your next task: Credit Risk unit budget, FY2027.' : ''));
        this.setState({ dialog: '', toast: msg.trim(), toastNext: !nextOn && !queueLast });
      },
      hasToast: !!st.toast, toast: st.toast || '', toastNext: !!st.toastNext,
      dismissToast: () => this.setState({ toast: '' }),
      prevLabel: 'No previous task: this is the first',
      nextLabel: 'Next task: Credit Risk unit budget, FY2027'
    };
"""
NEW_NOTES = "    rounds[0].notes = rounds[0].notes.map((x) => Object.assign({}, x, { isNew: true }));\n    rounds.slice(1).forEach((r) => { r.notes = r.notes.map((x) => Object.assign({}, x, { isNew: false })); });\n"

def wrap_return(s):
    i0 = s.index('data-dc-script')
    r = s.rindex('\n    return {\n', i0)
    s = s[:r] + '\n    return Object.assign(ux, {\n' + s[r + len('\n    return {\n'):]
    e = s.rindex('    };\n  }\n}\n</script>')
    return s[:e] + '    });\n  }\n}\n</script>' + s[e + len('    };\n  }\n}\n</script>'):]

# ---------------- Memo (desktop)
p = proj / 'Memo.dc.html'; s = p.read_text()
if 'since-h' not in s:
    back = re.search(r'        <a href="Main\.dc\.html" style="align-self: flex-start;[^>]*>\n.*?\n          Inbox\n        </a>\n', s, re.S)
    assert back
    nav = f'''        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
{back.group(0).replace('align-self: flex-start; ', '')}          <nav aria-label="Your tasks" style="display: flex; align-items: center; gap: 2px; font-size: 13px; color: var(--muted-fg)">
            <a aria-disabled="true" aria-label="{{{{prevLabel}}}}" title="Previous task (K)" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 6px; color: var(--border-strong); pointer-events: none">{svg(I['left'])}</a>
            <span style="padding: 0 4px; font-variant-numeric: tabular-nums">1 of 7 in My tasks</span>
            <a href="#" class="ghost" aria-label="{{{{nextLabel}}}}" title="Next task (J)" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 6px; color: var(--fg)">{svg(I['right'])}</a>
          </nav>
        </div>
'''
    s = s[:back.start()] + nav + s[back.end():]
    s = rep(s, '''          <sc-if value="{{isOverview}}" hint-placeholder-val="{{true}}">
            <div style="display: flex; flex-direction: column; gap: 12px">
''', '''          <sc-if value="{{isOverview}}" hint-placeholder-val="{{true}}">
            <div style="display: flex; flex-direction: column; gap: 12px">
              ''' + since_card(False) + '\n')
    # note sheet: New chip
    m = re.search(r'(\n *)(<span style="display: inline-flex; align-items: center; height: 18px; padding: 0 6px; border-radius: 6px; background: var\(--muted\); color: var\(--fg\); font-size: 11px; font-weight: 500">\{\{n\.action\}\}</span>)', s)
    s = s[:m.end()] + m.group(1) + f'<sc-if value="{{{{n.isNew}}}}" hint-placeholder-val="{{{{false}}}}">{NEWCHIP}</sc-if>' + s[m.end():]
    # dialog: next-task option and confirm
    s = rep(s, '''          <span style="font-size: 12px; color: var(--muted-fg)">The remark goes on the note sheet with your name and position.</span>
        </div>
        <div style="display: flex; justify-content: flex-end; gap: 8px">''', '''          <span style="font-size: 12px; color: var(--muted-fg)">The remark goes on the note sheet with your name and position.</span>
        </div>
        ''' + next_checkbox(False) + '''
        <div style="display: flex; justify-content: flex-end; gap: 8px">''')
    s = rep(s, '<button type="button" class="{{dlg.btnClass}}" onClick="{{closeDialog}}" style="{{dlg.btnStyle}}">{{dlg.confirm}}</button>', '<button type="button" class="{{dlg.btnClass}}" onClick="{{confirmDecision}}" style="{{dlg.btnStyle}}">{{dlg.confirm}}</button>')
    s = rep(s, '  </sc-if>\n</div>\n</x-dc>', '  </sc-if>' + toast(False) + '\n</div>\n</x-dc>')
    s = rep(s, '    const attachments = [', NEW_NOTES + '    const attachments = [')
    s = rep(s, '    const closeDialog = () => this.setState({ dialog: \'\' });\n', '    const closeDialog = () => this.setState({ dialog: \'\' });\n' + UX_JS)
    s = wrap_return(s)
    s = rep(s, '"$preview":{"width":1440', '"queue":{"editor":"enum","options":["more","last"],"default":"more"},"$preview":{"width":1440')
    p.write_text(s); print('Memo')

# ---------------- Memo (phone)
p = proj / 'MemoPhone.dc.html'; s = p.read_text()
if 'since-h' not in s:
    s = rep(s, '''      <sc-if value="{{isOverview}}" hint-placeholder-val="{{true}}">
        <div style="display: flex; flex-direction: column; gap: 12px">
''', '''      <sc-if value="{{isOverview}}" hint-placeholder-val="{{true}}">
        <div style="display: flex; flex-direction: column; gap: 12px">
          ''' + since_card(True) + '\n')
    m = re.search(r'(\n *)(<span style="display: inline-flex; align-items: center; height: 18px; padding: 0 6px; border-radius: 6px; background: var\(--muted\); font-size: 11px; font-weight: 500">\{\{n\.action\}\}</span>)', s)
    s = s[:m.end()] + m.group(1) + f'<sc-if value="{{{{n.isNew}}}}" hint-placeholder-val="{{{{false}}}}">{NEWCHIP}</sc-if>' + s[m.end():]
    i = s.index('id="pdlg-remark"'); j = s.index('</div>', i) + len('</div>')
    s = s[:j] + '\n      ' + next_checkbox(True) + s[j:]
    s = rep(s, '<button type="button" onClick="{{closeDialog}}" style="{{dlg.btnStyle}}">{{dlg.confirm}}</button>', '<button type="button" onClick="{{confirmDecision}}" style="{{dlg.btnStyle}}">{{dlg.confirm}}</button>')
    s = rep(s, '  </sc-if>\n</div>\n</x-dc>', '  </sc-if>' + toast(True) + '\n</div>\n</x-dc>')
    s = rep(s, '    const attachments = [', NEW_NOTES + '    const attachments = [')
    s = rep(s, '    const closeDialog = () => this.setState({ dialog: \'\' });\n', '    const closeDialog = () => this.setState({ dialog: \'\' });\n' + UX_JS)
    s = wrap_return(s)
    s = rep(s, '"$preview":{"width":390', '"queue":{"editor":"enum","options":["more","last"],"default":"more"},"$preview":{"width":390')
    p.write_text(s); print('MemoPhone')

# ---------------- Unread dots on the Inbox boards
UNREAD_TASKS = {"id: 't2'", "id: 't3'", "id: 't7'"}
UNREAD_WATCH = {"id: 'w1'", "id: 'w3'"}
def mark_data(s, ids):
    out = []
    for line in s.split('\n'):
        if any(k in line for k in ids) and 'unread:' not in line:
            line = line.replace('{ id:', '{ unread: true, id:', 1)
        out.append(line)
    return '\n'.join(out)
for name in ['Main', 'InboxWatching', 'InboxTeam']:
    p = proj / f'{name}.dc.html'; s = p.read_text()
    if 'subjectWeight' in s: continue
    a = '<a href="Memo.dc.html" class="subject" style="font-weight: 500; color: var(--fg); text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{r.subject}}</a>'
    s = rep(s, a, f'<span style="display: flex; align-items: center; gap: 6px; min-width: 0"><sc-if value="{{{{r.unread}}}}" hint-placeholder-val="{{{{false}}}}">{DOT}</sc-if>' + a.replace('font-weight: 500;', 'font-weight: {{r.subjectWeight}};') + '</span>', 2)
    s = rep(s, "      rowClass: d.restricted ? 'trow-locked' : 'trow'\n    }), 'team tasks');", "      rowClass: d.restricted ? 'trow-locked' : 'trow', unread: false, subjectWeight: '500'\n    }), 'team tasks');")
    a = '<a href="{{r.href}}" class="subject" style="font-weight: 500; color: var(--fg); text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{r.subject}}</a>'
    s = rep(s, a, f'<span style="display: flex; align-items: center; gap: 6px; min-width: 0"><sc-if value="{{{{r.unread}}}}" hint-placeholder-val="{{{{false}}}}">{DOT}</sc-if>' + a.replace('font-weight: 500;', 'font-weight: {{r.subjectWeight}};') + '</span>')
    s = mark_data(s, UNREAD_TASKS | UNREAD_WATCH)
    s = rep(s, "        hasNote: !!d.note,\n", "        hasNote: !!d.note,\n        unread: !!d.unread, subjectWeight: d.unread ? '600' : '500',\n")
    s = rep(s, "    const w = listOf(watching, (d) => ({ chipStyle: chipBase + tones[d.status], reason: reasons[d.reason] }), 'watched memos');",
               "    const w = listOf(watching, (d) => ({ chipStyle: chipBase + tones[d.status], reason: reasons[d.reason], unread: !!d.unread, subjectWeight: d.unread ? '600' : '500' }), 'watched memos');")
    p.write_text(s); print('dots', name)
for name in ['Phone', 'InboxWatchingPhone', 'InboxTeamPhone']:
    p = proj / f'{name}.dc.html'; s = p.read_text()
    if 'subjectWeight' in s: continue
    a = '<span style="flex: 1; min-width: 0; font-weight: 500; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden">{{r.subject}}</span>'
    n = s.count(a); assert n == 2, (name, n)
    s = s.replace(a, f'<sc-if value="{{{{r.unread}}}}" hint-placeholder-val="{{{{false}}}}"><span style="display: flex; align-items: center; height: 20px">{DOT}</span></sc-if>' + a.replace('font-weight: 500;', 'font-weight: {{r.subjectWeight}};'))
    s = mark_data(s, UNREAD_TASKS | UNREAD_WATCH)
    s = rep(s, "        hasNote: !!d.note,\n", "        hasNote: !!d.note,\n        unread: !!d.unread, subjectWeight: d.unread ? '600' : '500',\n")
    s = rep(s, "    const w = listOf(watching, (d) => ({ chipStyle: chipBase + tones[d.status], reason: reasons[d.reason] }), 'watched memos');",
               "    const w = listOf(watching, (d) => ({ chipStyle: chipBase + tones[d.status], reason: reasons[d.reason], unread: !!d.unread, subjectWeight: d.unread ? '600' : '500' }), 'watched memos');")
    p.write_text(s); print('dots', name)
