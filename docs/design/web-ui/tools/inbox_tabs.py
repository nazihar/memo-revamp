"""Inbox Watching and Team tabs (desktop and phone), from the app's WatchingList and TeamList."""
import json, re
from pathlib import Path
proj = Path('canvas/project')
MAIN = Path('backup-inbox/Main.dc.html').read_text()
PHONE = Path('backup-inbox/Phone.dc.html').read_text()
def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:100], s.count(a)); return s.replace(a, b)

CHECK = '<svg aria-hidden="true" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"></path></svg>'
DASH = '<svg aria-hidden="true" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"></path></svg>'
DOTS = '<svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="1"></circle><circle cx="19" cy="12" r="1"></circle><circle cx="5" cy="12" r="1"></circle></svg>'
LOCK = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>'
EYE = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"></path><circle cx="12" cy="12" r="3"></circle></svg>'
EYEOFF = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49"></path><path d="M14.084 14.158a3 3 0 0 1-4.242-4.242"></path><path d="M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143"></path><path d="m2 2 20 20"></path></svg>'
SWAP = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m16 3 4 4-4 4"></path><path d="M20 7H4"></path><path d="m8 21-4-4 4-4"></path><path d="M4 17h16"></path></svg>'
SEARCH = lambda left, size: f'<svg aria-hidden="true" width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--muted-fg); position: absolute; left: {left}px; pointer-events: none"><circle cx="11" cy="11" r="8"></circle><path d="m21 21-4.3-4.3"></path></svg>'
CHEV = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="position: absolute; right: 8px; color: var(--muted-fg); pointer-events: none"><path d="m6 9 6 6 6-6"></path></svg>'
SR = 'position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)'
TH = 'padding: 0 12px; text-align: left; font-weight: 500; color: var(--muted-fg); font-size: 13px'

def headbox(prefix):
    return f'''<button type="button" role="checkbox" aria-checked="{{{{{prefix}.headAria}}}}" aria-label="{{{{{prefix}.headLabel}}}}" onClick="{{{{{prefix}.toggleAll}}}}" style="{{{{{prefix}.headBox}}}}">
                      <sc-if value="{{{{{prefix}.all}}}}" hint-placeholder-val="{{{{false}}}}">{CHECK}</sc-if>
                      <sc-if value="{{{{{prefix}.some}}}}" hint-placeholder-val="{{{{false}}}}">{DASH}</sc-if>
                    </button>'''

def rowbox():
    return f'''<button type="button" class="row-ctl" role="checkbox" aria-checked="{{{{r.ariaChecked}}}}" aria-label="{{{{r.selectLabel}}}}" onClick="{{{{r.toggle}}}}" style="{{{{r.boxStyle}}}}">
                        <sc-if value="{{{{r.checked}}}}" hint-placeholder-val="{{{{false}}}}">{CHECK}</sc-if>
                      </button>'''

def footer(prefix, noun):
    return f'''          <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px">
            <span style="font-size: 13px; color: var(--muted-fg)">{{{{{prefix}.selCount}}}} of {{{{{prefix}.total}}}} selected</span>
            <button type="button" class="ghost" style="display: inline-flex; align-items: center; height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 13px; font-weight: 500; color: var(--fg); cursor: pointer; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">Show more</button>
          </div>'''

def bulkbar(prefix, buttons):
    btn = 'display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; border: 0; border-radius: 8px; background: transparent; color: var(--primary-fg); font-size: 13px; font-weight: 500; cursor: pointer'
    bs = ''.join(f'\n          <button type="button" class="bulk-btn"{(" onClick=" + chr(34) + "{{" + click + "}}" + chr(34)) if click else ""} style="{btn}">{icon}{label}</button>' for label, icon, click in buttons)
    return f'''      <sc-if value="{{{{{prefix}.hasSel}}}}" hint-placeholder-val="{{{{false}}}}">
        <div role="toolbar" aria-label="Bulk actions" style="position: sticky; bottom: 16px; z-index: 3; align-self: center; margin-top: auto; display: flex; align-items: center; gap: 4px; padding: 6px 6px 6px 14px; border-radius: 12px; background: var(--primary); color: var(--primary-fg); box-shadow: 0 10px 30px rgba(0,0,0,0.18)">
          <span style="font-size: 13px; font-weight: 500; padding-right: 8px; font-variant-numeric: tabular-nums">{{{{{prefix}.selCount}}}} selected</span>
          <span aria-hidden="true" style="width: 1px; height: 18px; background: var(--primary-divider); margin-right: 4px"></span>{bs}
          <button type="button" class="bulk-btn" aria-label="Clear selection" onClick="{{{{clearSel}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 0; border-radius: 8px; background: transparent; color: var(--primary-muted); cursor: pointer"><svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg></button>
        </div>
      </sc-if>
'''

def compact_select(label, opts, width):
    o = ''.join(f'<option>{x}</option>' for x in opts)
    return f'''<label style="position: relative; display: flex; align-items: center">
              <span style="{SR}">{label}</span>
              <span aria-hidden="true" style="position: absolute; left: 10px; font-size: 13px; color: var(--muted-fg); pointer-events: none">{label}:</span>
              <select style="width: {width}px; height: 32px; padding: 0 28px 0 {len(label) * 7 + 18}px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: 13px; font-weight: 500; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); cursor: pointer">{o}</select>
              {CHEV}
            </label>'''

DESK_TOOLBAR = f'''
        <sc-if value="{{{{isWatching}}}}" hint-placeholder-val="{{{{false}}}}">
          <span style="font-size: 13px; color: var(--muted-fg)">Newest first · news from these memos reaches you in Notifications</span>
        </sc-if>
        <sc-if value="{{{{isTeam}}}}" hint-placeholder-val="{{{{false}}}}">
          <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px">
            {compact_select('Unit', ['All the units I oversee', 'Credit Risk', 'Credit Analysis', 'Credit Administration'], 260)}
            {compact_select('Status', ['Pending', 'Overdue'], 150)}
            <label style="position: relative; display: flex; align-items: center">
              <span style="{SR}">Person</span>
              {SEARCH(10, 14)}
              <input type="text" role="combobox" aria-expanded="false" placeholder="Person" style="width: 180px; height: 32px; padding: 0 10px 0 30px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; font-family: inherit; font-size: 14px; color: var(--fg); background: var(--bg); outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">
            </label>
          </div>
        </sc-if>'''

DESK_WATCHING = f'''
      <!-- Watching -->
      <sc-if value="{{{{isWatching}}}}" hint-placeholder-val="{{{{false}}}}">
        <div style="display: flex; flex-direction: column; gap: 8px">
          <div style="border: 1px solid var(--border); border-radius: 10px; overflow-x: auto">
            <table style="width: 100%; min-width: 820px; border-collapse: collapse; font-size: 14px">
              <thead>
                <tr style="border-bottom: 1px solid var(--border)">
                  <th scope="col" style="width: 40px; height: 40px; padding: 0 0 0 12px; text-align: left">
                    {headbox('wl')}
                  </th>
                  <th scope="col" style="{TH}">Memo</th>
                  <th scope="col" style="{TH}; width: 130px">Status</th>
                  <th scope="col" style="{TH}; width: 190px">Why you watch it</th>
                  <th scope="col" style="{TH}; width: 150px">Watching since</th>
                  <th scope="col" style="width: 44px"><span style="{SR}">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                <sc-for list="{{{{wl.rows}}}}" as="r" hint-placeholder-count="7">
                  <tr class="trow" style="{{{{r.rowStyle}}}}">
                    <td style="padding: 0 0 0 12px; vertical-align: middle">
                      {rowbox()}
                    </td>
                    <td style="padding: 8px 12px; vertical-align: middle; max-width: 0; width: 100%">
                      <div style="display: flex; flex-direction: column; gap: 2px; min-width: 0">
                        <a href="{{{{r.href}}}}" class="subject" style="font-weight: 500; color: var(--fg); text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{r.subject}}}}</a>
                        <span style="font-size: 12px; line-height: 16px; color: var(--muted-fg); font-family: 'Geist Mono', ui-monospace, monospace">{{{{r.ref}}}}</span>
                      </div>
                    </td>
                    <td style="padding: 0 12px; vertical-align: middle; white-space: nowrap"><span style="{{{{r.chipStyle}}}}">{{{{r.status}}}}</span></td>
                    <td style="padding: 0 12px; vertical-align: middle; color: var(--text-2); white-space: nowrap">{{{{r.reason}}}}</td>
                    <td style="padding: 0 12px; vertical-align: middle; color: var(--muted-fg); white-space: nowrap; font-variant-numeric: tabular-nums">{{{{r.since}}}}</td>
                    <td style="padding: 0 8px 0 0; vertical-align: middle; text-align: right">
                      <button type="button" class="ghost row-ctl" aria-label="{{{{r.menuLabel}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; color: var(--text-3)">{DOTS}</button>
                    </td>
                  </tr>
                </sc-for>
              </tbody>
            </table>
          </div>
{footer('wl', 'memos')}
        </div>
      </sc-if>
'''

DESK_TEAM = f'''
      <!-- Team -->
      <sc-if value="{{{{isTeam}}}}" hint-placeholder-val="{{{{false}}}}">
        <div style="display: flex; flex-direction: column; gap: 8px">
          <span style="font-size: 13px; color: var(--muted-fg)">{{{{tm.count}}}} · overdue first, then by due time</span>
          <div style="border: 1px solid var(--border); border-radius: 10px; overflow-x: auto">
            <table style="width: 100%; min-width: 940px; border-collapse: collapse; font-size: 14px">
              <thead>
                <tr style="border-bottom: 1px solid var(--border)">
                  <th scope="col" style="width: 40px; height: 40px; padding: 0 0 0 12px; text-align: left">
                    {headbox('tm')}
                  </th>
                  <th scope="col" style="{TH}">Memo</th>
                  <th scope="col" style="{TH}; width: 240px">Held by</th>
                  <th scope="col" style="{TH}; width: 140px">Due</th>
                  <th scope="col" style="{TH}; width: 170px">Waiting</th>
                  <th scope="col" style="width: 44px"><span style="{SR}">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                <sc-for list="{{{{tm.rows}}}}" as="r" hint-placeholder-count="8">
                  <tr class="{{{{r.rowClass}}}}" style="{{{{r.rowStyle}}}}">
                    <td style="padding: 0 0 0 12px; vertical-align: middle">
                      {rowbox()}
                    </td>
                    <td style="padding: 8px 12px; vertical-align: middle; max-width: 0; width: 100%">
                      <sc-if value="{{{{r.restricted}}}}" hint-placeholder-val="{{{{false}}}}">
                        <div style="display: flex; flex-direction: column; gap: 2px">
                          <span style="display: flex; align-items: center; gap: 6px; font-weight: 500; color: var(--text-3)">{LOCK}Restricted memo</span>
                          <span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{{{r.unit}}}} · you are not cleared to read it; you can still reassign the task</span>
                        </div>
                      </sc-if>
                      <sc-if value="{{{{r.open}}}}" hint-placeholder-val="{{{{true}}}}">
                        <div style="display: flex; flex-direction: column; gap: 2px; min-width: 0">
                          <a href="Memo.dc.html" class="subject" style="font-weight: 500; color: var(--fg); text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{r.subject}}}}</a>
                          <span style="font-size: 12px; line-height: 16px; color: var(--muted-fg); white-space: nowrap"><span style="font-family: 'Geist Mono', ui-monospace, monospace">{{{{r.ref}}}}</span> · {{{{r.unit}}}}</span>
                        </div>
                      </sc-if>
                    </td>
                    <td style="padding: 8px 12px; vertical-align: middle; white-space: nowrap">
                      <span style="display: flex; align-items: center; gap: 8px">
                        <span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: 10px; font-weight: 600; flex-shrink: 0">{{{{r.initials}}}}</span>
                        <span style="display: flex; flex-direction: column; min-width: 0"><span>{{{{r.holder}}}}</span><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{{{r.step}}}}</span></span>
                      </span>
                    </td>
                    <td style="padding: 0 12px; vertical-align: middle; white-space: nowrap">
                      <sc-if value="{{{{r.hasDue}}}}" hint-placeholder-val="{{{{true}}}}"><span style="{{{{r.dueStyle}}}}">{{{{r.due}}}}</span></sc-if>
                      <sc-if value="{{{{r.noDue}}}}" hint-placeholder-val="{{{{false}}}}"><span style="color: var(--muted-fg)">No due time</span></sc-if>
                    </td>
                    <td style="padding: 0 12px; vertical-align: middle; color: var(--text-2); white-space: nowrap">{{{{r.waiting}}}}</td>
                    <td style="padding: 0 8px 0 0; vertical-align: middle; text-align: right">
                      <button type="button" class="ghost row-ctl" aria-label="{{{{r.menuLabel}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; color: var(--text-3)">{DOTS}</button>
                    </td>
                  </tr>
                </sc-for>
              </tbody>
            </table>
          </div>
{footer('tm', 'tasks')}
        </div>
      </sc-if>
'''

def reassign(phone):
    h = '44px' if phone else '36px'; fs = '16px' if phone else '14px'; bfs = '15px' if phone else '14px'
    inp = f'width: 100%; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); border: 1px solid '
    fields = f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <label for="ra-person" style="font-size: 14px; font-weight: 500">New person <span aria-hidden="true" style="color: var(--danger-fg)">*</span></label>
          <span style="position: relative; display: flex; align-items: center">{SEARCH(10, 16)}<input id="ra-person" type="text" role="combobox" aria-expanded="false" aria-invalid="{{{{ra.personInvalid}}}}" aria-describedby="ra-person-msg" placeholder="Search people" value="{{{{ra.person}}}}" onChange="{{{{ra.onPerson}}}}" style="{inp}{{{{ra.personBorder}}}}; height: {h}; padding: 0 10px 0 32px"></span>
          <sc-if value="{{{{ra.personErr}}}}" hint-placeholder-val="{{{{false}}}}"><span id="ra-person-msg" style="font-size: 12px; color: var(--danger-fg)">Choose the new person.</span></sc-if>
        </div>
        <div style="display: flex; flex-direction: column; gap: 6px">
          <label for="ra-reason" style="font-size: 14px; font-weight: 500">Reason <span aria-hidden="true" style="color: var(--danger-fg)">*</span></label>
          <textarea id="ra-reason" rows="3" aria-invalid="{{{{ra.reasonInvalid}}}}" aria-describedby="ra-reason-msg ra-reason-hint" value="{{{{ra.reason}}}}" onChange="{{{{ra.onReason}}}}" style="{inp}{{{{ra.reasonBorder}}}}; padding: 8px 10px; line-height: 20px; resize: vertical"></textarea>
          <sc-if value="{{{{ra.reasonErr}}}}" hint-placeholder-val="{{{{false}}}}"><span id="ra-reason-msg" style="font-size: 12px; color: var(--danger-fg)">A reason is required.</span></sc-if>
          <span id="ra-reason-hint" style="font-size: 12px; color: var(--muted-fg)">Both people are told, and the reason goes into the memo’s history.</span>
        </div>'''
    cancel = f'<button type="button" class="ghost" onClick="{{{{ra.close}}}}" style="height: {h}; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: {bfs}; font-weight: 500; color: var(--fg); cursor: pointer">Cancel</button>'
    ok = f'<button type="submit" style="height: {h}; padding: 0 14px; border: 0; border-radius: 8px; background: var(--primary); font-size: {bfs}; font-weight: 500; color: var(--primary-fg); cursor: pointer">Reassign</button>'
    head = f'''        <div style="display: flex; flex-direction: column; gap: 6px{'' if phone else '; padding-right: 24px'}">
          <h2 id="ra-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Reassign the selected tasks</h2>
          <p id="ra-desc" style="margin: 0; font-size: 14px; color: var(--muted-fg)">{{{{ra.lead}}}}</p>
        </div>'''
    if phone:
        return f'''  <sc-if value="{{{{ra.open}}}}" hint-placeholder-val="{{{{false}}}}">
    <button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{ra.close}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <form role="dialog" aria-modal="true" aria-labelledby="ra-title" aria-describedby="ra-desc" novalidate onSubmit="{{{{ra.submit}}}}" onKeyDown="{{{{ra.key}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; display: flex; flex-direction: column; gap: 14px; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--bg); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>
{head}
{fields}
      <div style="display: flex; flex-direction: column; gap: 8px">{ok}{cancel}</div>
    </form>
  </sc-if>
'''
    return f'''  <sc-if value="{{{{ra.open}}}}" hint-placeholder-val="{{{{false}}}}">
    <div style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box">
      <button type="button" tabIndex="-1" aria-label="Close dialog" onClick="{{{{ra.close}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; border: 0; padding: 0; background: rgba(0,0,0,0.5); cursor: default"></button>
      <form role="dialog" aria-modal="true" aria-labelledby="ra-title" aria-describedby="ra-desc" novalidate onSubmit="{{{{ra.submit}}}}" onKeyDown="{{{{ra.key}}}}" style="position: relative; width: 100%; max-width: 460px; display: flex; flex-direction: column; gap: 16px; padding: 24px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--bg); box-shadow: 0 16px 48px rgba(0,0,0,0.2)">
        <button type="button" class="ghost" aria-label="Close" onClick="{{{{ra.close}}}}" style="position: absolute; top: 12px; right: 12px; display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; color: var(--muted-fg)"><svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg></button>
{head}
{fields}
        <div style="display: flex; justify-content: flex-end; gap: 8px">{cancel}{ok}</div>
      </form>
    </div>
  </sc-if>
'''

# ---------- shared data and logic (desktop and phone)
LOGIC = r"""
    // Watching (listWatching) and Team (listTeamTasks): one selection map, counted per list.
    const PHONE = /*PHONE*/false;
    const chipBase = 'display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; font-size: 12px; font-weight: 500; white-space: nowrap; ';
    const tones = {
      'In review': 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)',
      'Returned': 'border: 1px solid var(--border-strong); background: var(--bg); color: var(--text-1)',
      'Approved': 'border: 1px solid var(--ok-border); background: var(--ok-bg); color: var(--ok-fg)',
      'Rejected': 'border: 1px solid var(--danger-border); background: var(--danger-bg); color: var(--danger-fg)',
      'Closed': 'border: 1px solid var(--border); background: var(--muted); color: var(--text-2)'
    };
    const reasons = { MANUAL: 'You watch it', INITIATED: 'You started it', ACTED: 'You acted on it', MENTIONED: 'You were mentioned' };
    const watching = [
      { id: 'w1', subject: 'Agro sector exposure limits', ref: 'RSK/2026/0012', status: 'In review', reason: 'MANUAL', since: '17 Nov, 16:20', sinceShort: '17 Nov', href: '#' },
      { id: 'w2', subject: 'Credit line renewal for Meghna Agro', ref: 'CR/2026/0142', status: 'In review', reason: 'ACTED', since: '14 Nov, 10:02', sinceShort: '14 Nov', href: 'Memo.dc.html' },
      { id: 'w3', subject: 'Vendor onboarding: core banking support services', ref: 'PRC/2026/0087', status: 'In review', reason: 'MENTIONED', since: '13 Nov, 08:42', sinceShort: '13 Nov', href: '#' },
      { id: 'w4', subject: 'Branch network review, Sylhet region', ref: 'RB/2026/0061', status: 'Returned', reason: 'INITIATED', since: '10 Nov, 15:30', sinceShort: '10 Nov', href: 'Returned.dc.html' },
      { id: 'w5', subject: 'Treasury limits for FY2027', ref: 'TRY/2026/0019', status: 'Approved', reason: 'ACTED', since: '6 Nov, 11:15', sinceShort: '6 Nov', href: 'Approved.dc.html' },
      { id: 'w6', subject: 'Q4 branch expansion budget', ref: 'FIN/2026/0311', status: 'Rejected', reason: 'MANUAL', since: '11 Nov, 09:40', sinceShort: '11 Nov', href: 'Rejected.dc.html' },
      { id: 'w7', subject: 'Cyber insurance renewal 2026', ref: 'ADM/2026/0044', status: 'Closed', reason: 'INITIATED', since: '28 Oct, 14:05', sinceShort: '28 Oct', href: 'Closed.dc.html' }
    ];
    const team = [
      { id: 'k1', subject: 'Credit line renewal for Meghna Agro', ref: 'CR/2026/0142', unit: 'Credit Risk', holder: 'Farhana Rahman', step: 'Head of Credit review', due: 'Overdue 1 day', dueKind: 'overdue', waiting: 'Waiting 2 working days' },
      { id: 'k2', subject: 'Covenant waiver: Padma Textiles', ref: 'CR/2026/0151', unit: 'Credit Analysis', holder: 'Sadia Islam', step: 'Credit risk analysis', due: 'Overdue 3 days', dueKind: 'overdue', waiting: 'Waiting 6 working days' },
      { id: 'k3', restricted: true, unit: 'Credit Administration', holder: 'Kamal Hasan', step: 'Security documentation', due: 'Overdue 1 day', dueKind: 'overdue', waiting: 'Waiting 4 working days' },
      { id: 'k4', subject: 'Limit increase for Rupali Foods', ref: 'CR/2026/0158', unit: 'Credit Analysis', holder: 'Imran Hossain', step: 'Credit risk analysis', due: 'Due today, 17:00', dueKind: 'soon', waiting: 'Waiting 1 working day' },
      { id: 'k5', subject: 'Collateral release, Jamuna Steel', ref: 'CR/2026/0146', unit: 'Credit Administration', holder: 'Nusrat Jahan', step: 'Credit Risk Manager', due: 'Due 20 Nov', dueKind: 'later', waiting: 'Waiting since today' },
      { id: 'k6', subject: 'Annual review: Meghna Agro group', ref: 'CR/2026/0139', unit: 'Credit Risk', holder: 'Nusrat Jahan', step: 'Credit Risk Manager', due: 'Due 24 Nov', dueKind: 'later', waiting: 'Waiting 3 working days' },
      { id: 'k7', subject: 'Write-off proposal, legacy SME loans', ref: 'CR/2026/0122', unit: 'Credit Risk', holder: 'Farhana Rahman', step: 'Head of Credit review', due: '', dueKind: 'none', waiting: 'Waiting 9 working days' }
    ];
    const tsel = st.sel || {};
    const box = PHONE ? 'display: inline-flex; align-items: center; justify-content: center; width: 18px; height: 18px; box-sizing: border-box; border-radius: 4px; '
      : 'display: inline-flex; align-items: center; justify-content: center; width: 16px; height: 16px; padding: 0; border-radius: 4px; cursor: pointer; vertical-align: middle; box-shadow: 0 1px 2px rgba(0,0,0,0.05); ';
    const bOn = box + 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)';
    const bOff = box + 'border: 1px solid var(--border-strong); background: var(--bg); color: var(--primary-fg)';
    const listOf = (items, extra, noun) => {
      const ids = items.map((i) => i.id);
      const n = ids.filter((id) => tsel[id]).length;
      const all = n === ids.length && n > 0, some = n > 0 && !all;
      return {
        rows: items.map((d, i) => {
          const c = !!tsel[d.id];
          const name = d.restricted ? 'restricted memo' : d.subject;
          return Object.assign({}, d, extra(d), {
            checked: c, ariaChecked: c ? 'true' : 'false', boxStyle: c ? bOn : bOff,
            selectLabel: 'Select ' + name, menuLabel: 'More actions for ' + name,
            rowStyle: (PHONE ? 'display: flex; gap: 4px; padding: 12px 16px' + (selecting ? ' 12px 8px' : '') + '; ' : '') + 'border-bottom: 1px solid var(--hover)' + (c ? '; background: var(--muted)' : ''),
            toggle: (e) => { if (e && e.stopPropagation) e.stopPropagation(); const nx = Object.assign({}, tsel); nx[d.id] = !c; this.setState({ sel: nx }); }
          });
        }),
        total: ids.length, selCount: n, hasSel: n > 0, all: all, some: some,
        headAria: all ? 'true' : (some ? 'mixed' : 'false'), headBox: (all || some) ? bOn : bOff, headLabel: 'Select all ' + noun,
        toggleAll: () => { const nx = Object.assign({}, tsel); ids.forEach((id) => { nx[id] = !all; }); this.setState({ sel: nx }); }
      };
    };
    const w = listOf(watching, (d) => ({ chipStyle: chipBase + tones[d.status], reason: reasons[d.reason] }), 'watched memos');
    const t = listOf(team, (d) => ({
      open: !d.restricted, restricted: !!d.restricted,
      initials: d.holder.split(' ').map((p) => p[0]).join(''),
      hasDue: d.dueKind !== 'none', noDue: d.dueKind === 'none',
      dueStyle: chipBase + (d.dueKind === 'overdue' ? 'background: var(--destructive); color: var(--on-destructive)' : (d.dueKind === 'soon' ? 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)' : 'border: 1px solid var(--border); color: var(--text-2)')),
      rowClass: d.restricted ? 'trow-locked' : 'trow'
    }), 'team tasks');
    const overdueN = team.filter((x) => x.dueKind === 'overdue').length;
    t.count = team.length + ' tasks · ' + overdueN + ' overdue';
    // Reassign: form validation (no browser checks), step-up happens after Reassign.
    const ra = st.ra || {};
    const raTried = !!ra.tried;
    const raPerson = ra.person || '', raReason = ra.reason || '';
    const pErr = raTried && raPerson.trim().length < 2, rErr = raTried && !raReason.trim();
    const raClose = () => this.setState({ ra: null });
    const raOpen = this.props.reassign === true && st.ra === undefined ? true : !!(st.ra);
    const raN = t.selCount || 2;
    const raVals = {
      open: raOpen,
      lead: (raN === 1 ? 'The task goes' : 'The ' + raN + ' tasks go') + ' to the person you choose. You confirm it is you before it is done.',
      person: raPerson, reason: raReason,
      personErr: pErr, reasonErr: rErr,
      personInvalid: pErr ? 'true' : 'false', reasonInvalid: rErr ? 'true' : 'false',
      personBorder: pErr ? 'var(--destructive)' : 'var(--border)', reasonBorder: rErr ? 'var(--destructive)' : 'var(--border)',
      onPerson: (ev) => this.setState({ ra: Object.assign({}, ra, { person: ev && ev.target ? ev.target.value : '' }) }),
      onReason: (ev) => this.setState({ ra: Object.assign({}, ra, { reason: ev && ev.target ? ev.target.value : '' }) }),
      close: raClose,
      key: (ev) => { if (ev && ev.key === 'Escape') raClose(); },
      submit: (ev) => {
        if (ev && ev.preventDefault) ev.preventDefault();
        if (raPerson.trim().length < 2 || !raReason.trim()) { this.setState({ ra: Object.assign({}, ra, { tried: true }) }); return; }
        this.setState({ ra: null, sel: {} });
      }
    };
    const listVals = {
      isWatching: tab === 'watching', isTeam: tab === 'team',
      wl: w, tm: t, ra: raVals,
      openReassign: () => this.setState({ ra: {} })
    };
"""

def build_desktop(default_tab):
    s = MAIN
    s = rep(s, "    const tab = st.tab || 'tasks';", "    const tab = st.tab || this.props.tab || '" + 'tasks' + "';")
    # toolbar beside the tabs
    a = '''      <!-- Tasks table -->'''
    i = s.index(a)
    j = s.rindex('      </div>\n', 0, i)
    s = s[:j] + DESK_TOOLBAR + '\n' + s[j:]
    # lists after the tasks table
    s = rep(s, '      <!-- Empty state for other tabs -->', DESK_WATCHING + DESK_TEAM + '\n      <!-- Empty state for other tabs -->')
    # bulk bars after the tasks bulk bar
    s = rep(s, '    </main>\n  </div>\n</div>\n</x-dc>', bulkbar('wl', [('Stop watching', EYEOFF, None)]) + bulkbar('tm', [('Reassign…', SWAP, 'openReassign'), ('Watch', EYE, None)]) + '    </main>\n  </div>\n' + reassign(False) + '</div>\n</x-dc>')
    # logic
    s = rep(s, "    const empty = {", "    const selecting = false;\n" + LOGIC + "    const empty = {")
    s = rep(s, "      watching: ['You are not following any memo.', 'Watch a memo from its page or from My tasks to see its progress here.', false],",
               "      watching: ['You watch no memos.', 'Open a memo and choose Watch to get its news here.', false],")
    s = rep(s, "      team: ['Nothing is waiting in your team.', 'Tasks held by people in the units you oversee appear here.', false]",
               "      team: ['No task matches.', 'Change the unit, status or person to see more.', false]")
    s = rep(s, "      notTasks: tab !== 'tasks',", "      notTasks: tab !== 'tasks' && tab !== 'watching' && tab !== 'team',")
    s = rep(s, "    return {\n      themeClass:", "    return Object.assign(listVals, {\n      themeClass:")
    i = s.rindex('    };\n  }\n}\n</script>'); s = s[:i] + '    });\n  }\n}\n</script>' + s[i + len('    };\n  }\n}\n</script>'):]
    s = rep(s, '"menuOpen":{"editor":"boolean","default":false},', '"menuOpen":{"editor":"boolean","default":false},"tab":{"editor":"enum","options":["tasks","drafts","sent","closed","watching","team"],"default":"' + default_tab + '"},"reassign":{"editor":"boolean","default":false},')
    return s

# ---------- phone
def pselect(label, opts):
    o = ''.join(f'<option>{x}</option>' for x in opts)
    return f'''<label style="position: relative; display: flex; align-items: center; flex: 1; min-width: 0">
            <span style="{SR}">{label}</span>
            <select style="width: 100%; height: 40px; padding: 0 28px 0 12px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: 14px; font-weight: 500; outline: none">{o}</select>
            {CHEV}
          </label>'''

SELECT_BTN = '<button type="button" class="ghost" aria-pressed="{{selectAria}}" onClick="{{toggleSelecting}}" style="display: inline-flex; align-items: center; height: 36px; padding: 0 12px; border: 0; border-radius: 8px; background: transparent; font-size: 13px; font-weight: 500; color: var(--fg)">{{selectLabel}}</button>'
PBOX = '''<sc-if value="{{selecting}}" hint-placeholder-val="{{false}}">
                <button type="button" role="checkbox" aria-checked="{{r.ariaChecked}}" aria-label="{{r.selectLabel}}" onClick="{{r.toggle}}" style="display: inline-flex; align-items: flex-start; justify-content: center; width: 32px; min-height: 44px; padding-top: 2px; border: 0; background: transparent; flex-shrink: 0">
                  <span style="{{r.boxStyle}}"><sc-if value="{{r.checked}}" hint-placeholder-val="{{false}}">''' + CHECK + '''</sc-if></span>
                </button>
              </sc-if>'''
SHOW_MORE = '''        <div style="display: flex; justify-content: center; padding: 16px 16px 24px">
          <button type="button" class="ghost" style="height: 40px; padding: 0 16px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 14px; font-weight: 500; color: var(--fg)">Show more</button>
        </div>'''

PHONE_LISTS = f'''
    <!-- Watching -->
    <sc-if value="{{{{isWatching}}}}" hint-placeholder-val="{{{{false}}}}">
      <div style="display: flex; flex-direction: column">
        <div style="display: flex; align-items: center; gap: 8px; padding: 0 16px 8px">
          <span style="flex: 1; font-size: 13px; color: var(--muted-fg)">Newest first</span>
          {SELECT_BTN}
        </div>
        <ul style="list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--border)">
          <sc-for list="{{{{wl.rows}}}}" as="r" hint-placeholder-count="7">
            <li style="{{{{r.rowStyle}}}}">
              {PBOX}
              <a href="{{{{r.href}}}}" style="display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 0; text-decoration: none; color: var(--fg)">
                <span style="display: flex; align-items: flex-start; gap: 8px">
                  <span style="flex: 1; min-width: 0; font-weight: 500; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden">{{{{r.subject}}}}</span>
                  <span style="flex-shrink: 0; {{{{r.chipStyle}}}}">{{{{r.status}}}}</span>
                </span>
                <span style="font-size: 12px; line-height: 16px; color: var(--muted-fg); font-family: 'Geist Mono', ui-monospace, monospace">{{{{r.ref}}}}</span>
                <span style="font-size: 13px; line-height: 18px; color: var(--text-2)">{{{{r.reason}}}} <span style="color: var(--muted-fg)">· since {{{{r.sinceShort}}}}</span></span>
              </a>
            </li>
          </sc-for>
        </ul>
{SHOW_MORE}
      </div>
    </sc-if>

    <!-- Team -->
    <sc-if value="{{{{isTeam}}}}" hint-placeholder-val="{{{{false}}}}">
      <div style="display: flex; flex-direction: column">
        <div style="display: flex; flex-direction: column; gap: 8px; padding: 0 16px 8px">
          <div style="display: flex; gap: 8px">
          {pselect('Unit', ['All my units', 'Credit Risk', 'Credit Analysis', 'Credit Administration'])}
          {pselect('Status', ['Pending', 'Overdue'])}
          </div>
          <label style="position: relative; display: flex; align-items: center">
            <span style="{SR}">Person</span>
            {SEARCH(12, 16)}
            <input type="text" role="combobox" aria-expanded="false" placeholder="Person" style="width: 100%; height: 40px; padding: 0 12px 0 36px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; font-family: inherit; font-size: 16px; color: var(--fg); background: var(--bg); outline: none">
          </label>
          <div style="display: flex; align-items: center; gap: 8px">
            <span style="flex: 1; font-size: 13px; color: var(--muted-fg)">{{{{tm.count}}}}</span>
            {SELECT_BTN}
          </div>
        </div>
        <ul style="list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--border)">
          <sc-for list="{{{{tm.rows}}}}" as="r" hint-placeholder-count="7">
            <li style="{{{{r.rowStyle}}}}">
              {PBOX}
              <div style="display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 0">
                <span style="display: flex; align-items: flex-start; gap: 8px">
                  <sc-if value="{{{{r.open}}}}" hint-placeholder-val="{{{{true}}}}"><a href="MemoPhone.dc.html" style="flex: 1; min-width: 0; font-weight: 500; color: var(--fg); text-decoration: none; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden">{{{{r.subject}}}}</a></sc-if>
                  <sc-if value="{{{{r.restricted}}}}" hint-placeholder-val="{{{{false}}}}"><span style="flex: 1; display: flex; align-items: center; gap: 6px; font-weight: 500; color: var(--text-3)">{LOCK}Restricted memo</span></sc-if>
                  <sc-if value="{{{{r.hasDue}}}}" hint-placeholder-val="{{{{true}}}}"><span style="flex-shrink: 0; {{{{r.dueStyle}}}}">{{{{r.due}}}}</span></sc-if>
                </span>
                <span style="font-size: 12px; line-height: 16px; color: var(--muted-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis"><sc-if value="{{{{r.open}}}}" hint-placeholder-val="{{{{true}}}}"><span style="font-family: 'Geist Mono', ui-monospace, monospace">{{{{r.ref}}}}</span> · </sc-if>{{{{r.unit}}}}</span>
                <span style="font-size: 13px; line-height: 18px; color: var(--text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{r.holder}}}} <span style="color: var(--muted-fg)">· {{{{r.step}}}} · {{{{r.waiting}}}}</span></span>
              </div>
            </li>
          </sc-for>
        </ul>
{SHOW_MORE}
      </div>
    </sc-if>
'''

PHONE_BULK = '''  <sc-if value="{{selectingWatching}}" hint-placeholder-val="{{false}}">
    <div role="toolbar" aria-label="Bulk actions" style="flex-shrink: 0; display: flex; align-items: center; gap: 4px; padding: 8px 8px 8px 16px; border-top: 1px solid var(--primary-hover); background: var(--primary); color: var(--primary-fg)">
      <span style="flex: 1; font-size: 13px; font-weight: 500; font-variant-numeric: tabular-nums">{{wl.selCount}} selected</span>
      <button type="button" style="{{wBulkStyle}}" disabled="{{wNoSel}}">Stop watching</button>
    </div>
  </sc-if>
  <sc-if value="{{selectingTeam}}" hint-placeholder-val="{{false}}">
    <div role="toolbar" aria-label="Bulk actions" style="flex-shrink: 0; display: flex; align-items: center; gap: 4px; padding: 8px 8px 8px 16px; border-top: 1px solid var(--primary-hover); background: var(--primary); color: var(--primary-fg)">
      <span style="flex: 1; font-size: 13px; font-weight: 500; font-variant-numeric: tabular-nums">{{tm.selCount}} selected</span>
      <button type="button" style="{{tBulkStyle}}" disabled="{{tNoSel}}" onClick="{{openReassign}}">Reassign…</button>
      <button type="button" style="{{tBulkStyle}}" disabled="{{tNoSel}}">Watch</button>
    </div>
  </sc-if>
'''

def build_phone(default_tab):
    s = PHONE
    s = rep(s, "    const tab = st.tab || 'tasks';", "    const tab = st.tab || this.props.tab || 'tasks';")
    s = rep(s, "    const selecting = !!st.selecting && tab === 'tasks';", "    const selecting = !!st.selecting && (tab === 'tasks' || tab === 'watching' || tab === 'team');")
    s = rep(s, '    <sc-if value="{{notTasks}}" hint-placeholder-val="{{false}}">', PHONE_LISTS + '\n    <sc-if value="{{notTasks}}" hint-placeholder-val="{{false}}">')
    s = rep(s, '  <sc-if value="{{selecting}}" hint-placeholder-val="{{false}}">\n    <div role="toolbar" aria-label="Bulk actions"', '  <sc-if value="{{selectingTasks}}" hint-placeholder-val="{{false}}">\n    <div role="toolbar" aria-label="Bulk actions"')
    s = rep(s, '  <!-- Navigation drawer -->', PHONE_BULK + '\n' + reassign(True) + '\n  <!-- Navigation drawer -->')
    s = rep(s, "    const empty = {", LOGIC.replace('/*PHONE*/false', 'true') + "    const empty = {")
    s = rep(s, "      watching: ['You are not following any memo.', 'Watch a memo from its page or from My tasks to see its progress here.'],",
               "      watching: ['You watch no memos.', 'Open a memo and choose Watch to get its news here.'],")
    s = rep(s, "      team: ['Nothing is waiting in your team.', 'Tasks held by people in the units you oversee appear here.']",
               "      team: ['No task matches.', 'Change the unit, status or person to see more.']")
    s = rep(s, "      notTasks: tab !== 'tasks',", """      notTasks: tab !== 'tasks' && tab !== 'watching' && tab !== 'team',
      selectingTasks: selecting && tab === 'tasks', selectingWatching: selecting && tab === 'watching', selectingTeam: selecting && tab === 'team',
      wNoSel: w.selCount === 0, tNoSel: t.selCount === 0,
      wBulkStyle: 'height: 36px; padding: 0 12px; border: 0; border-radius: 8px; background: transparent; font-size: 13px; font-weight: 500; ' + (w.selCount === 0 ? 'color: var(--muted-fg)' : 'color: var(--primary-fg)'),
      tBulkStyle: 'height: 36px; padding: 0 12px; border: 0; border-radius: 8px; background: transparent; font-size: 13px; font-weight: 500; ' + (t.selCount === 0 ? 'color: var(--muted-fg)' : 'color: var(--primary-fg)'),""")
    s = rep(s, "    return {\n      themeClass:", "    return Object.assign(listVals, {\n      themeClass:")
    i = s.rindex('    };\n  }\n}\n</script>'); s = s[:i] + '    });\n  }\n}\n</script>' + s[i + len('    };\n  }\n}\n</script>'):]
    s = rep(s, '"$preview":{"width":390', '"tab":{"editor":"enum","options":["tasks","drafts","sent","closed","watching","team"],"default":"' + default_tab + '"},"reassign":{"editor":"boolean","default":false},"$preview":{"width":390')
    return s

(proj / 'Main.dc.html').write_text(build_desktop('tasks'))
(proj / 'Phone.dc.html').write_text(build_phone('tasks'))
for tab, name, title in (('watching', 'InboxWatching', 'Inbox, Watching tab'), ('team', 'InboxTeam', 'Inbox, Team tab')):
    (proj / f'{name}.dc.html').write_text(build_desktop(tab).replace('<title>Inbox</title>', f'<title>{title}</title>'))
    (proj / f'{name}Phone.dc.html').write_text(build_phone(tab).replace(re.search(r'<title>[^<]*</title>', PHONE).group(0), f'<title>{title} on a phone</title>'))

c = proj / 'canvas.json'; d = json.loads(c.read_text())
y0 = 42380
for i, (name, title) in enumerate((('InboxWatching', 'Inbox · Watching'), ('InboxTeam', 'Inbox · Team (unit heads and people given the team view)'))):
    y = y0 + i * 1420
    d['boards'][f'{name}.dc.html'] = {"x": 0, "y": y, "w": 1440, "h": 900, "title": f'{title.split(" (")[0]} — Desktop', "expand": "fill", "is_interactive": True}
    d['boards'][f'{name}Phone.dc.html'] = {"x": 1520, "y": y, "w": 390, "h": 844, "title": f'{title.split(" (")[0]} — Phone', "is_interactive": True}
    d['notes'][f'{name}Title'] = {"x": 0, "y": y - 260, "text": title, "kind": "title1", "maxW": 1910}
    for f in (f'{name}.dc.html', f'{name}Phone.dc.html'):
        if f not in d['order']: d['order'].insert(d['order'].index('Rules.dc.html'), f)
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
