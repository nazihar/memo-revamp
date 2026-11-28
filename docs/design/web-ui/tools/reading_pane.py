"""Inbox with a reading pane (wide screens): the Inbox board's shell, with list and memo side by side."""
import json
from pathlib import Path
proj = Path('canvas/project')
def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:100], s.count(a)); return s.replace(a, b)
def svg(p, s=16): return f'<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{p}</svg>'
SR = 'position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)'
main = (proj / 'Main.dc.html').read_text()
memo = (proj / 'Memo.dc.html').read_text()
a = memo.index('<section aria-labelledby="since-h"'); since = memo[a:memo.index('</section>', a) + len('</section>')].replace('onClick="{{goNotes}}"', '')
TABS = main[main.index('<div role="tablist" aria-label="Inbox"'):]
TABS = TABS[:TABS.index('</sc-for>') + len('</sc-for>')] + '\n        </div>'
DOT = f'<span aria-hidden="true" style="width: 8px; height: 8px; border-radius: 50%; background: var(--info-fg); flex-shrink: 0; margin-top: 6px"></span><span style="{SR}">Unread: </span>'
BTN = 'height: 36px; padding: 0 14px; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; '
body = f'''    <!-- Content: list and reading pane -->
    <main style="flex: 1; display: flex; flex-direction: column; gap: 12px; min-width: 0; padding: 16px 24px 24px">
      <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px">
        <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 16px">
          <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">Inbox</h1>
          {TABS}
        </div>
        <a href="NewMemo.dc.html" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 14px; font-weight: 500; text-decoration: none">{svg('<path d="M5 12h14"></path><path d="M12 5v14"></path>')}New memo</a>
      </div>

      <div style="flex: 1; display: flex; min-height: 720px; border: 1px solid var(--border); border-radius: 10px; overflow: hidden">
        <!-- List -->
        <div style="flex: 0 0 380px; display: flex; flex-direction: column; border-right: 1px solid var(--border); min-width: 0">
          <ul aria-label="My tasks" style="flex: 1; list-style: none; margin: 0; padding: 0; overflow-y: auto">
            <sc-for list="{{{{pane.rows}}}}" as="pr" hint-placeholder-count="7">
              <li>
                <button type="button" onClick="{{{{pr.pick}}}}" aria-current="{{{{pr.current}}}}" style="{{{{pr.style}}}}">
                  <sc-if value="{{{{pr.unread}}}}" hint-placeholder-val="{{{{false}}}}">{DOT}</sc-if>
                  <sc-if value="{{{{pr.read}}}}" hint-placeholder-val="{{{{true}}}}"><span aria-hidden="true" style="width: 8px; flex-shrink: 0"></span></sc-if>
                  <span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; text-align: left">
                    <span style="display: flex; align-items: flex-start; gap: 8px"><span style="{{{{pr.subjectStyle}}}}">{{{{pr.subject}}}}</span><span style="{{{{pr.dueStyle}}}}">{{{{pr.dueShort}}}}</span></span>
                    <span style="font-size: 12px; line-height: 16px; color: var(--muted-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis"><span style="font-family: 'Geist Mono', ui-monospace, monospace">{{{{pr.ref}}}}</span> · {{{{pr.action}}}} · from {{{{pr.from}}}}</span>
                  </span>
                </button>
              </li>
            </sc-for>
          </ul>
          <div style="padding: 8px 12px; border-top: 1px solid var(--border); font-size: 12px; color: var(--muted-fg)">{{{{pane.left}}}} · <kbd style="font-family: 'Geist Mono', ui-monospace, monospace; font-size: 11px; padding: 0 4px; border: 1px solid var(--border); border-radius: 4px">J</kbd> <kbd style="font-family: 'Geist Mono', ui-monospace, monospace; font-size: 11px; padding: 0 4px; border: 1px solid var(--border); border-radius: 4px">K</kbd> to move</div>
        </div>

        <!-- Reading pane -->
        <section aria-labelledby="rp-title" style="flex: 1; min-width: 0; display: flex; flex-direction: column">
          <sc-if value="{{{{pane.has}}}}" hint-placeholder-val="{{{{true}}}}">
          <div style="display: flex; flex-direction: column; gap: 6px; padding: 14px 20px 12px; border-bottom: 1px solid var(--border)">
            <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
              <span style="display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--muted-fg)"><span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg); font-weight: 500">In review</span>{{{{pane.position}}}}</span>
              <a href="{{{{pane.href}}}}" style="display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 500; color: var(--fg)">{svg('<path d="M15 3h6v6"></path><path d="M10 14 21 3"></path><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path>', 14)}Open the full page</a>
            </div>
            <h2 id="rp-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{{{{pane.sel.subject}}}}</h2>
            <span style="font-size: 13px; color: var(--muted-fg)"><span style="font-family: 'Geist Mono', ui-monospace, monospace; color: var(--text-2)">{{{{pane.sel.ref}}}}</span> · {{{{pane.sel.type}}}} · Round {{{{pane.sel.round}}}} · from {{{{pane.sel.from}}}}</span>
          </div>
          <div style="flex: 1; overflow-y: auto; padding: 16px 20px; display: flex; flex-direction: column; gap: 12px">
            <sc-if value="{{{{pane.since}}}}" hint-placeholder-val="{{{{true}}}}">
              {since}
            </sc-if>
            <sc-if value="{{{{pane.firstTime}}}}" hint-placeholder-val="{{{{false}}}}">
              <div style="padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--subtle); font-size: 13px; color: var(--text-1)">First time you open it. {{{{pane.sel.step}}}}: you {{{{pane.verb}}}}.</div>
            </sc-if>
            <section aria-label="Document" style="display: flex; align-items: center; gap: 12px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px">
              <span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 36px; height: 36px; border-radius: 8px; background: var(--info-bg); color: var(--info-fg)">{svg('<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"></path><path d="M14 2v4a2 2 0 0 0 2 2h4"></path>', 18)}</span>
              <span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="font-size: 14px; font-weight: 500">{{{{pane.docName}}}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{{{pane.docMeta}}}}</span></span>
              <a href="Document.dc.html" class="ghost" style="display: inline-flex; align-items: center; height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; color: var(--fg); font-size: 13px; font-weight: 500; text-decoration: none">Open</a>
            </section>
            <section aria-label="Latest note" style="display: flex; flex-direction: column; gap: 4px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px">
              <span style="font-size: 12px; font-weight: 500; color: var(--muted-fg)">Latest note</span>
              <span style="font-size: 13px"><span style="font-weight: 500">{{{{pane.note.who}}}}</span> <span style="color: var(--muted-fg)">· {{{{pane.note.meta}}}}</span></span>
              <p style="margin: 0; font-size: 14px; color: var(--text-1)">{{{{pane.note.text}}}}</p>
            </section>
          </div>
          <div role="toolbar" aria-label="Your decision" style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 12px 20px; border-top: 1px solid var(--border); background: var(--subtle)">
            <button type="button" onClick="{{{{pane.decide}}}}" style="{BTN}border: 0; background: var(--primary); color: var(--primary-fg)">{{{{pane.sel.action}}}}</button>
            <button type="button" class="ghost" style="{BTN}border: 1px solid var(--border); background: var(--bg); color: var(--fg)">Return</button>
            <button type="button" class="ghost" style="{BTN}border: 1px solid var(--border); background: var(--bg); color: var(--danger-fg)">Reject</button>
            <button type="button" class="ghost" aria-label="More actions" style="display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); cursor: pointer">{svg('<circle cx="12" cy="12" r="1"></circle><circle cx="19" cy="12" r="1"></circle><circle cx="5" cy="12" r="1"></circle>')}</button>
            <span style="flex: 1"></span>
            <span style="font-size: 12px; color: var(--muted-fg)">Each decision opens its dialog; afterwards the next task opens here.</span>
          </div>
          </sc-if>
          <sc-if value="{{{{pane.empty}}}}" hint-placeholder-val="{{{{false}}}}">
            <div role="status" style="flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 40px; text-align: center"><span style="font-size: 16px; font-weight: 500">My tasks is clear.</span><span style="font-size: 14px; color: var(--muted-fg)">Nothing is waiting for you.</span></div>
          </sc-if>
        </section>
      </div>

      <sc-if value="{{{{pane.hasToast}}}}" hint-placeholder-val="{{{{false}}}}">
        <div role="status" style="position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%); z-index: 40; display: flex; align-items: center; gap: 12px; padding: 10px 10px 10px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg); box-shadow: 0 10px 30px rgba(0,0,0,0.15); font-size: 14px">{{{{pane.toast}}}}<button type="button" class="ghost" aria-label="Dismiss" onClick="{{{{pane.dismiss}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--muted-fg); cursor: pointer">{svg('<path d="M18 6 6 18"></path><path d="m6 6 12 12"></path>', 14)}</button></div>
      </sc-if>
    </main>'''
JS = r"""
    // Reading pane: the selected task opens beside the list; deciding moves on to the next task.
    const paneDone = st.paneDone || {};
    const pRows = data.filter((d) => !d.restricted && !paneDone[d.id]);
    const paneSel = pRows.find((d) => d.id === st.paneSel) || pRows[0];
    const notes = {
      t1: { who: 'Sadia Islam', meta: 'Credit Risk Analyst · Recommended · 16 Nov, 13:58', text: 'Updated valuation from the panel valuer is attached. Security cover is now 1.4×. Recommend renewal at the proposed limit.' }
    };
    const verbs = { Approve: 'approve or return it', Recommend: 'recommend it or send it back', 'Give opinion': 'give your opinion', Answer: 'answer the question' };
    const paneVals = { pane: {
      rows: pRows.map((d) => {
        const on = paneSel && d.id === paneSel.id;
        const due = d.dueKind === 'overdue' ? 'background: var(--destructive); color: var(--on-destructive)' : (d.dueKind === 'soon' ? 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)' : 'border: 1px solid var(--border); color: var(--text-2)');
        return {
          subject: d.subject, ref: d.ref, action: d.action, from: d.from, unread: !!d.unread, read: !d.unread,
          dueShort: d.dueKind === 'none' ? '' : (d.due || ''),
          dueStyle: d.dueKind === 'none' ? 'display: none' : 'flex-shrink: 0; display: inline-flex; align-items: center; height: 20px; padding: 0 6px; border-radius: 6px; font-size: 11px; font-weight: 500; white-space: nowrap; ' + due,
          subjectStyle: 'flex: 1; min-width: 0; font-size: 14px; line-height: 20px; font-weight: ' + (d.unread ? '600' : '500') + '; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden',
          current: on ? 'true' : 'false',
          style: 'display: flex; gap: 10px; width: 100%; padding: 12px 14px; border: 0; border-bottom: 1px solid var(--hover); color: var(--fg); cursor: pointer; text-align: left; ' + (on ? 'background: var(--active); box-shadow: inset 3px 0 0 var(--primary)' : 'background: transparent'),
          pick: () => this.setState({ paneSel: d.id })
        };
      }),
      has: !!paneSel, empty: !paneSel,
      sel: paneSel || { subject: '', ref: '', type: '', round: '', from: '', step: '', action: '' },
      position: paneSel ? (pRows.indexOf(paneSel) + 1) + ' of ' + pRows.length + ' in My tasks' : '',
      left: pRows.length + (pRows.length === 1 ? ' task' : ' tasks'),
      href: paneSel && paneSel.id === 't1' ? 'Memo.dc.html' : '#',
      since: !!paneSel && paneSel.id === 't1', firstTime: !!paneSel && paneSel.id !== 't1',
      verb: paneSel ? (verbs[paneSel.action] || 'act on it') : '',
      docName: paneSel && paneSel.id === 't1' ? 'Credit memo — Meghna Agro.docx' : (paneSel ? paneSel.subject + '.docx' : ''),
      docMeta: paneSel && paneSel.id === 't1' ? 'Version 3 · 248 KB · uploaded 14 Nov, 16:02' : 'Version 1',
      note: (paneSel && notes[paneSel.id]) || { who: paneSel ? paneSel.from : '', meta: 'Submitted', text: 'Submitted for your decision.' },
      decide: () => {
        if (!paneSel) return;
        const nd = Object.assign({}, paneDone); nd[paneSel.id] = true;
        const rest = pRows.filter((x) => x.id !== paneSel.id);
        this.setState({ paneDone: nd, paneSel: rest.length ? rest[0].id : null, paneToast: (paneSel.action === 'Approve' ? 'Approved ' : 'Done: ') + paneSel.ref + (rest.length ? '. Showing your next task.' : '. That was your last task.') });
      },
      hasToast: !!st.paneToast, toast: st.paneToast || '', dismiss: () => this.setState({ paneToast: '' })
    } };
    Object.assign(listVals, paneVals);
"""
s = main
a = s.index('    <!-- Content -->\n'); b = s.index('    </main>\n', a) + len('    </main>\n')
s = s[:a] + body + '\n' + s[b:]
s = s.replace('<title>Inbox</title>', '<title>Inbox with reading pane</title>')
s = rep(s, '    return Object.assign(listVals, {', JS + '    return Object.assign(listVals, {')
s = s.replace('"$preview":{"width":1440,"height":900}', '"$preview":{"width":1440,"height":1000}')
(proj / 'InboxReading.dc.html').write_text(s)

c = proj / 'canvas.json'; d = json.loads(c.read_text())
d['boards']['InboxReading.dc.html'] = {"x": 0, "y": 0, "w": 1440, "h": 1000, "title": "Inbox with reading pane — Desktop, 1400px and wider", "expand": "fill", "is_interactive": True}
d['notes']['InboxReadingTitle'] = {"x": 0, "y": 0, "text": "Inbox · reading pane (wide screens)", "kind": "title1", "maxW": 1910}
d['notes']['InboxReadingPhone'] = {"x": 1520, "y": 0, "w": 390, "fill": "blue", "size": "m", "text": "No phone version: on phones and screens narrower than 1400px the Inbox stays a list, and a memo opens as its own page."}
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
