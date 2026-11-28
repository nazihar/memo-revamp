"""Give the approved and rejected memo pages the same read-only tabs as the in-review page, and record the rule."""
from pathlib import Path

proj = Path('canvas/project')
memo = (proj / 'Memo.dc.html').read_text()

# --- Shared tab panels, taken from the in-review page --------------------------------------------------------------
start = memo.index('          <!-- Note sheet -->\n')
end = memo.index('        </div>\n\n        <!-- Right: task, approvers, people -->')
panels = memo[start:end]

# The note sheet is read-only on a finished memo: no "Write a note".
hdr_start = panels.index('              <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px 10px 16px')
hdr_end = panels.index('              </div>\n', hdr_start) + len('              </div>\n')
panels = panels[:hdr_start] + (
    '              <div style="padding: 10px 16px; border-bottom: 1px solid var(--border); font-size: 13px; color: var(--muted-fg)">'
    'Newest first. Read-only: nothing can be added to a finished memo.</div>\n'
) + panels[hdr_end:]

# Linked memos: an empty state and the "Link a memo" action.
links_end = panels.index('              </ul>\n            </section>', panels.index('<!-- Linked memos -->')) + len('              </ul>\n')
panels = panels[:links_end] + (
    '              <sc-if value="{{noLinks}}" hint-placeholder-val="{{false}}">\n'
    '                <p style="margin: 0; padding: 16px; font-size: 13px; color: var(--muted-fg)">{{linksEmpty}}</p>\n'
    '              </sc-if>\n'
    '              <div style="padding: 10px 16px; border-top: 1px solid var(--hover)">\n'
    '                <button type="button" class="ghost" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">\n'
    '                  <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path></svg>\n'
    '                  Link a memo\n'
    '                </button>\n'
    '              </div>\n'
) + panels[links_end:]

TAB_BAR = '''          <div role="tablist" aria-label="Memo sections" style="align-self: flex-start; display: inline-flex; align-items: center; gap: 2px; height: 36px; padding: 3px; box-sizing: border-box; border-radius: 10px; background: var(--muted); max-width: 100%; overflow-x: auto">
            <sc-for list="{{tabs}}" as="t" hint-placeholder-count="5">
              <button type="button" role="tab" class="tab" aria-selected="{{t.ariaSel}}" onClick="{{t.pick}}" style="{{t.style}}">
                {{t.label}}
                <sc-if value="{{t.hasCount}}" hint-placeholder-val="{{false}}">
                  <span style="display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; padding: 0 5px; box-sizing: border-box; border-radius: 9px; background: var(--chip); color: var(--text-2); font-size: 11px; font-weight: 500">{{t.count}}</span>
                </sc-if>
              </button>
            </sc-for>
          </div>
'''

TABS_JS = '''    const tabBase = 'display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; border-radius: 7px; font-size: 13px; font-weight: 500; cursor: pointer; white-space: nowrap; ';
    const tabs = [
      { id: 'overview', label: 'Overview' },
      { id: 'notes', label: 'Note sheet', count: __NOTES__ },
      { id: 'attachments', label: 'Attachments', count: __FILES__ },
      { id: 'links', label: 'Linked memos', count: __LINKS__ },
      { id: 'history', label: 'History' }
    ].map((t) => ({
      label: t.label, count: t.count, hasCount: !!t.count,
      ariaSel: t.id === tab ? 'true' : 'false',
      style: tabBase + (t.id === tab
        ? 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06)'
        : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)'),
      pick: () => this.setState({ tab: t.id })
    }));
    const ini = (n) => n.split(' ').map((p) => p[0]).join('').slice(0, 2);
    const note = (author, position, action, time, text) => ({ author, position, action, time, text, initials: ini(author) });
'''

PANEL_VALS = '''      isOverview: tab === 'overview',
      isNotes: tab === 'notes',
      isAttachments: tab === 'attachments',
      isLinks: tab === 'links',
      isHistory: tab === 'history',
      tabs: tabs,
      rounds: rounds,
      attachments: attachments,
      links: links,
      noLinks: links.length === 0,
      history: history,
'''

# --- Approved -------------------------------------------------------------------------------------------------------
ap = (proj / 'Approved.dc.html').read_text()
# Tab bar: keep the existing one (now with five tabs) and swap the placeholders for the real panels.
ph_start = ap.index('          <sc-if value="{{isNotes}}" hint-placeholder-val="{{false}}">\n            <section aria-label="Note sheet" style="border: 1px solid var(--border); border-radius: 10px; padding: 32px')
ph_end = ap.index('        </div>\n\n        <!-- Right -->')
ap = ap[:ph_start] + panels + ap[ph_end:]
# The follow-up card moves into the Linked memos tab; copies join People and access.
fu_start = ap.index('          <section aria-labelledby="a-next-h"')
fu_end = ap.index('          <section aria-labelledby="a-people-h"')
ap = ap[:fu_start] + ap[fu_end:]
ap = ap.replace(
    '<dt style="color: var(--muted-fg)">Can read</dt><dd style="margin: 0">11 people · 2 copies</dd>',
    '<dt style="color: var(--muted-fg)">Copies</dt><dd style="margin: 0">Credit Administration, Branch Gulshan</dd>\n'
    '              <dt style="color: var(--muted-fg)">Can read</dt><dd style="margin: 0">11 people</dd>')
# Script: new tab list and data.
js_start = ap.index("    const tabBase = ")
js_end = ap.index("    const stamps = [")
ap_data = TABS_JS.replace('__NOTES__', '8').replace('__FILES__', '3').replace('__LINKS__', '1') + '''    const rounds = [
      { title: 'Round 2', hint: 'Final round', notes: [
        note('Abdul Kader', 'Managing Director', 'Approved', '18 Nov, 11:05', 'Approved at the proposed limit. Renewal subject to the updated valuation being kept on file, and to a review of security cover at six months.'),
        note('Imran Hossain', 'Chief Risk Officer (acting for Shirin Akter)', 'Approved', '17 Nov, 15:20', 'Risk appetite allows this exposure. Agree with the six-month cover review.'),
        note('Farhana Rahman', 'Head of Credit', 'Approved', '17 Nov, 09:41', 'Valuation concern from round 1 is resolved.'),
        note('Sadia Islam', 'Credit Risk Analyst', 'Recommended', '16 Nov, 13:58', 'Updated valuation from the panel valuer is attached. Security cover is now 1.4×. Recommend renewal at the proposed limit.'),
        note('Mahmud Karim', 'Head of Corporate Banking', 'Recommended', '15 Nov, 10:22', 'Relationship remains satisfactory. Repayments have been on time for 36 months.')
      ]},
      { title: 'Round 1', hint: 'Earlier round: its approvals no longer count', notes: [
        note('Farhana Rahman', 'Head of Credit', 'Returned', '10 Nov, 11:40', 'Please attach a collateral valuation not older than six months.'),
        note('Mahmud Karim', 'Head of Corporate Banking', 'Recommended', '6 Nov, 09:15', 'Supported. Client is a long-standing relationship of the unit.'),
        note('Tanvir Ahmed', 'Relationship Manager', 'Submitted', '5 Nov, 17:30', 'Renewal of the existing revolving line at the same limit; pricing unchanged.')
      ]}
    ];
    const attachments = [
      { title: 'Panel valuation report, October 2026.pdf', meta: 'PDF · 1.8 MB · version 1 · in the final record', restricted: false },
      { title: 'Audited financial statements FY2025.pdf', meta: 'PDF · 4.2 MB · version 1 · in the final record', restricted: false },
      { title: 'Group exposure summary.xlsx', meta: 'Excel · 96 KB · version 2 · kept as an original', restricted: true }
    ].map((f) => Object.assign({}, f, { downloadLabel: 'Download ' + f.title }));
    const links = [
      { kind: 'Follows up', subject: 'Credit line for Meghna Agro, 2025', ref: 'CR/2025/0388', status: 'Approved' }
    ];
    const history = [
      { time: '18 Nov 11:07', who: 'System', what: 'made the final record, version 1', round: 2 },
      { time: '18 Nov 11:05', who: 'Abdul Kader', what: 'approved; the memo is approved', round: 2 },
      { time: '17 Nov 15:20', who: 'Imran Hossain', what: 'approved, acting for Shirin Akter', round: 2 },
      { time: '17 Nov 09:41', who: 'Farhana Rahman', what: 'approved', round: 2 },
      { time: '16 Nov 13:58', who: 'Sadia Islam', what: 'recommended', round: 2 },
      { time: '15 Nov 10:22', who: 'Mahmud Karim', what: 'recommended', round: 2 },
      { time: '14 Nov 16:05', who: 'Tanvir Ahmed', what: 'resubmitted with document version 3', round: 2 },
      { time: '10 Nov 11:40', who: 'Farhana Rahman', what: 'returned the memo to the initiator', round: 1 },
      { time: '5 Nov 17:30', who: 'Tanvir Ahmed', what: 'submitted; numbered CR/2026/0142', round: 1 }
    ];
'''
ap = ap[:js_start] + ap_data + ap[js_end:]
for old in ("      tabs: tabs,\n", "      isOverview: tab === 'overview',\n", "      isNotes: tab === 'notes',\n",
            "      isAttachments: tab === 'attachments',\n", "      isHistory: tab === 'history',\n"):
    assert old in ap, old
    ap = ap.replace(old, '', 1)
ap = ap.replace("      stamps: stamps,\n", "      stamps: stamps,\n" + PANEL_VALS + "      linksEmpty: '',\n", 1)
(proj / 'Approved.dc.html').write_text(ap)

# --- Rejected -------------------------------------------------------------------------------------------------------
rj = (proj / 'Rejected.dc.html').read_text()
ov_start = rj.index('          <!-- Reason -->\n')
more_start = rj.index('          <!-- More -->\n')
left_end = rj.index('        </div>\n\n        <!-- Right -->')
overview = rj[ov_start:more_start].rstrip() + '\n'
rj = rj[:ov_start] + TAB_BAR + '\n          <sc-if value="{{isOverview}}" hint-placeholder-val="{{true}}">\n            <div style="display: flex; flex-direction: column; gap: 12px">\n' + overview + '            </div>\n          </sc-if>\n\n' + panels + rj[left_end:]
rj_data = "    const tab = st.tab || 'overview';\n" + TABS_JS.replace('__NOTES__', '4').replace('__FILES__', '2').replace('__LINKS__', '0') + '''    const rounds = [
      { title: 'Round 1', hint: 'The only round', notes: [
        note('Rafiq Chowdhury', 'Chief Financial Officer', 'Rejected', '17 Nov, 14:32', 'Not approved this year. The Q4 capital budget is fully committed, and the two proposed branches do not meet the 24-month payback in the expansion policy. Bring it back in the FY2027 budget cycle with updated footfall data for both sites.'),
        note('Mahmud Karim', 'Head of Retail Banking', 'Recommended', '12 Nov, 10:15', 'Supported. The Rajshahi footfall estimate looks optimistic; Finance should check it.'),
        note('Sadia Islam', 'Budget analyst', 'Remark', '11 Nov, 16:40', 'Added fit-out quotes from two vendors.'),
        note('Sadia Islam', 'Budget analyst', 'Submitted', '11 Nov, 09:05', 'Budget for two new branches in Sylhet and Rajshahi to meet FY2026 deposit targets.')
      ]}
    ];
    const attachments = [
      { title: 'Site footfall study.pdf', meta: 'PDF · 2.4 MB · version 1', restricted: false },
      { title: 'Fit-out quotes.xlsx', meta: 'Excel · 64 KB · version 1', restricted: false }
    ].map((f) => Object.assign({}, f, { downloadLabel: 'Download ' + f.title }));
    const links = [];
    const history = [
      { time: '17 Nov 14:32', who: 'Rafiq Chowdhury', what: 'rejected; the memo is closed to further action', round: 1 },
      { time: '12 Nov 10:15', who: 'Mahmud Karim', what: 'recommended', round: 1 },
      { time: '11 Nov 16:40', who: 'Sadia Islam', what: 'added a remark', round: 1 },
      { time: '11 Nov 09:05', who: 'Sadia Islam', what: 'submitted; numbered FIN/2026/0311', round: 1 },
      { time: '10 Nov 15:30', who: 'Sadia Islam', what: 'created the draft', round: 1 }
    ];
'''
anchor = "    const defs = ["
rj = rj.replace(anchor, rj_data + anchor, 1)
rj = rj.replace("      route: route,\n      more: more,\n", "      route: route,\n" + PANEL_VALS + "      linksEmpty: 'No linked memos yet. A follow-up memo appears here once you start one.',\n", 1)
(proj / 'Rejected.dc.html').write_text(rj)

# --- Rule -----------------------------------------------------------------------------------------------------------
ru = (proj / 'Rules.dc.html').read_text()
rule = '''    <div style="padding: 16px; border: 1px solid var(--border); border-radius: 10px; display: flex; flex-direction: column; gap: 10px">
      <span style="font-weight: 600">Memo pages</span>
      <ul style="margin: 0; padding-left: 18px; display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 6px 32px; font-size: 13px; color: var(--text-1)">
        <li>Every memo page you read (in review, returned, approved, rejected, closed) has the same parts in the same order: header, outcome bar once it has ended, tabs, and a right column.</li>
        <li>Tabs, always these and always in this order: <strong>Overview, Note sheet, Attachments, Linked memos, History</strong>. Counts only on tabs that have items; an empty tab says what will appear there.</li>
        <li>Overview starts with what the reader came for: the decision to make, the final record, or the reason it was rejected. Then the document and the details.</li>
        <li>The right column holds the task or the approvals, then people and access. Nothing else.</li>
        <li>Only the draft uses stacked sections instead of tabs, because the document, approvers and details must be visible together while preparing it.</li>
        <li>On phones every memo page is one column: header, the main card, then rows that open the note sheet, attachments, linked memos and history.</li>
      </ul>
    </div>
'''
anchor = '  </section>\n\n  <!-- Components -->'
assert anchor in ru
ru = ru.replace(anchor, rule + anchor, 1)
(proj / 'Rules.dc.html').write_text(ru)
print('done')
