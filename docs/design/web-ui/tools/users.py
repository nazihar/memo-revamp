"""Admin · Users and User detail (desktop and phone).

Copy and rules come from web/src/features/admin/UsersPage.tsx and
web/src/features/access/UserDetailPage.tsx (+ access-messages.ts).
The list boards follow the other list pages (Inbox, Open tasks): segmented
tabs for the main filter, a 240px filter field, the table and footer of
Open tasks, a dashed empty state. Rebuilds Users, UsersPhone, UserDetail
and UserDetailPhone from the Open tasks and Position shells; safe to rerun.
"""
import json, re
from pathlib import Path
proj = Path('canvas/project')
def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:100], s.count(a)); return s.replace(a, b)
def svg(p, s=16, extra=''): return f'<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"{extra}>{p}</svg>'
I = {'userplus': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><line x1="19" x2="19" y1="8" y2="14"></line><line x1="22" x2="16" y1="11" y2="11"></line>',
     'search': '<circle cx="11" cy="11" r="8"></circle><path d="m21 21-4.3-4.3"></path>', 'dots': '<circle cx="12" cy="12" r="1"></circle><circle cx="19" cy="12" r="1"></circle><circle cx="5" cy="12" r="1"></circle>',
     'chev': '<path d="m6 9 6 6 6-6"></path>', 'x': '<path d="M18 6 6 18"></path><path d="m6 6 12 12"></path>', 'back': '<path d="m15 18-6-6 6-6"></path>', 'right': '<path d="m9 18 6-6-6-6"></path>',
     'alert': '<circle cx="12" cy="12" r="10"></circle><line x1="12" x2="12" y1="8" y2="12"></line><line x1="12" x2="12.01" y1="16" y2="16"></line>',
     'plus': '<path d="M5 12h14"></path><path d="M12 5v14"></path>'}
SR = 'position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)'
TH = 'padding: 0 12px; text-align: left; font-weight: 500; color: var(--muted-fg); font-size: 13px; white-space: nowrap'
OUTLINE = 'display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer; box-shadow: 0 1px 2px rgba(0,0,0,0.05)'
AVATAR = '<span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: {s}px; height: {s}px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: {f}px; font-weight: 600; flex-shrink: 0">{{{{{k}}}}}</span>'
ADMIN = '<sc-if value="{{%s.admin}}" hint-placeholder-val="{{false}}"><span style="display: inline-flex; align-items: center; height: 20px; padding: 0 6px; border-radius: 6px; background: var(--muted); color: var(--fg); font-size: 11px; font-weight: 500">Administrator</span></sc-if>'

# ---------------------------------------------------------------- dialogs (shared)
def frame(inner, labelled, role, submit, phone):
    if phone:
        return f'''<button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{closeDlg}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <form role="{role}" aria-modal="true" aria-labelledby="{labelled}" novalidate onSubmit="{{{{{submit}}}}}" onKeyDown="{{{{dlgKey}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; display: flex; flex-direction: column; gap: 14px; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>
{inner}
    </form>'''
    return f'''<div style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box">
      <button type="button" tabIndex="-1" aria-label="Close dialog" onClick="{{{{closeDlg}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; border: 0; padding: 0; background: rgba(0,0,0,0.5); cursor: default"></button>
      <form role="{role}" aria-modal="true" aria-labelledby="{labelled}" novalidate onSubmit="{{{{{submit}}}}}" onKeyDown="{{{{dlgKey}}}}" style="position: relative; width: 100%; max-width: 460px; display: flex; flex-direction: column; gap: 16px; padding: 24px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--card); box-shadow: 0 16px 48px rgba(0,0,0,0.2)">
{inner}
      </form>
    </div>'''

def heading(id_, title, lead):
    return f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <h2 id="{id_}" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{title}</h2>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">{lead}</p>
        </div>'''

def alert(k):
    return f'''        <sc-if value="{{{{{k}.hasAlert}}}}" hint-placeholder-val="{{{{false}}}}">
          <div role="alert" style="display: flex; gap: 10px; padding: 10px 12px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px"><span style="flex-shrink: 0; margin-top: 1px">{svg(I["alert"])}</span><span style="display: flex; flex-direction: column; gap: 2px"><strong style="font-weight: 600">Not done</strong><span>{{{{{k}.alert}}}}</span></span></div>
        </sc-if>'''

def buttons(ok_label, ok_style, phone, cancel='Cancel'):
    h = '44px' if phone else '36px'; fs = '15px' if phone else '14px'
    c = f'<button type="button" class="ghost" onClick="{{{{closeDlg}}}}" style="height: {h}; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: {fs}; font-weight: 500; cursor: pointer">{cancel}</button>'
    ok = f'<button type="submit" style="{ok_style}">{ok_label}</button>'
    return (f'        <div style="display: flex; flex-direction: column; gap: 8px">{ok}{c}</div>' if phone
            else f'        <div style="display: flex; justify-content: flex-end; gap: 8px">{c}{ok}</div>')

def primary(phone):
    return f'height: {"44px" if phone else "36px"}; padding: 0 14px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: {"15px" if phone else "14px"}; font-weight: 500; cursor: pointer'

def confirm_dialog(phone):
    detail = '''        <sc-if value="{{cf.hasDetail}}" hint-placeholder-val="{{false}}"><div style="display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--subtle); font-size: 14px"><span style="font-weight: 500">{{cf.detail}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{cf.detailSub}}</span></div></sc-if>'''
    inner = '\n'.join([heading('cf-title', '{{cf.title}}', '{{cf.explain}}'), detail, alert('cf'), buttons('{{cf.confirm}}', '{{cf.btnStyle}}', phone, '{{cf.cancel}}')])
    return frame(inner, 'cf-title', 'alertdialog', 'cf.submit', phone)

def toast(phone):
    pos = 'position: absolute; left: 16px; right: 16px; bottom: 16px' if phone else 'position: fixed; right: 24px; bottom: 24px'
    return f'''  <sc-if value="{{{{hasToast}}}}" hint-placeholder-val="{{{{false}}}}">
    <div role="status" style="{pos}; z-index: 40; display: flex; align-items: center; gap: 10px; padding: 10px 10px 10px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 8px 24px rgba(0,0,0,0.12); font-size: 14px">
      <span style="flex: 1">{{{{toast}}}}</span>
      <button type="button" class="ghost" aria-label="Dismiss" onClick="{{{{hideToast}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--muted-fg); cursor: pointer">{svg(I["x"], 14)}</button>
    </div>
  </sc-if>
'''

def text_field(pfx, k, label, typ, phone):
    h = '44px' if phone else '36px'; fs = '16px' if phone else '14px'
    return f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <label for="{pfx}-{k}" style="font-size: 14px; font-weight: 500">{label} <span aria-hidden="true" style="color: var(--danger-fg)">*</span></label>
          <input id="{pfx}-{k}" type="{typ}" autocomplete="off" value="{{{{{pfx}.{k}}}}}" onChange="{{{{{pfx}.on_{k}}}}}" aria-invalid="{{{{{pfx}.{k}Invalid}}}}" aria-describedby="{pfx}-{k}-msg" style="width: 100%; height: {h}; padding: 0 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); border: 1px solid {{{{{pfx}.{k}Border}}}}">
          <sc-if value="{{{{{pfx}.{k}HasErr}}}}" hint-placeholder-val="{{{{false}}}}"><span id="{pfx}-{k}-msg" style="font-size: 12px; color: var(--danger-fg)">{{{{{pfx}.{k}Err}}}}</span></sc-if>
        </div>'''

def select_field(pfx, k, label, req, hint, phone, disabled=False):
    h = '44px' if phone else '36px'; fs = '16px' if phone else '14px'
    dis = f' disabled="{{{{{pfx}.{k}Disabled}}}}"' if disabled else ''
    star = ' <span aria-hidden="true" style="color: var(--danger-fg)">*</span>' if req else ''
    err = f'\n          <sc-if value="{{{{{pfx}.{k}HasErr}}}}" hint-placeholder-val="{{{{false}}}}"><span id="{pfx}-{k}-msg" style="font-size: 12px; color: var(--danger-fg)">{{{{{pfx}.{k}Err}}}}</span></sc-if>' if req else ''
    hint_html = f'\n          <span id="{pfx}-{k}-hint" style="font-size: 12px; color: var(--muted-fg)">{hint}</span>' if hint else ''
    return f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <label for="{pfx}-{k}" style="font-size: 14px; font-weight: 500">{label}{star}</label>
          <span style="position: relative; display: flex; align-items: center">
            <select id="{pfx}-{k}"{dis} onChange="{{{{{pfx}.on_{k}}}}}" aria-invalid="{{{{{pfx}.{k}Invalid}}}}" aria-describedby="{pfx}-{k}-msg {pfx}-{k}-hint" style="width: 100%; height: {h}; padding: 0 30px 0 10px; box-sizing: border-box; appearance: none; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); border: 1px solid {{{{{pfx}.{k}Border}}}}; {{{{{pfx}.{k}Look}}}}">
              <sc-for list="{{{{{pfx}.{k}Options}}}}" as="o" hint-placeholder-count="4"><option value="{{{{o.value}}}}" selected="{{{{o.sel}}}}">{{{{o.label}}}}</option></sc-for>
            </select>
            {svg(I["chev"], 14, ' style="position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none"')}
          </span>{err}{hint_html}
        </div>'''

# ---------------------------------------------------------------- shared logic
COMMON_JS = r"""
    const LABEL = { ACTIVE: 'Active', INVITED: 'Invited', LOCKED: 'Locked', DEACTIVATED: 'Deactivated' };
    const chip = 'display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; font-size: 12px; font-weight: 500; white-space: nowrap; ';
    const TONE = { ACTIVE: 'border: 1px solid var(--ok-border); background: var(--ok-bg); color: var(--ok-fg)', INVITED: 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)',
                   LOCKED: 'border: 1px solid var(--danger-border); background: var(--danger-bg); color: var(--danger-fg)', DEACTIVATED: 'border: 1px solid var(--border); background: var(--muted); color: var(--text-2)' };
    const ACT = {
      deactivate: { label: 'Deactivate', title: (n) => 'Deactivate ' + n + '?', explain: 'They are signed out at once and cannot sign in until reactivated. Their work stays.', danger: true },
      reactivate: { label: 'Reactivate', title: (n) => 'Reactivate ' + n + '?', explain: 'They can sign in again.' },
      unlock: { label: 'Unlock', title: (n) => 'Unlock ' + n + '?', explain: 'They can try to sign in again right away.' },
      mfaReset: { label: 'Reset two-step sign-in', title: (n) => 'Reset two-step sign-in for ' + n + '?', explain: 'They are signed out and set up an authenticator app again at the next sign-in. They get an email.' },
      revokeSessions: { label: 'Sign out everywhere', title: (n) => 'Sign ' + n + ' out everywhere?', explain: 'Every device they use is signed out.' }
    };
    // The app's rules: who may get which action.
    const actionsFor = (u) => [].concat(
      u.status === 'LOCKED' ? ['unlock'] : [],
      u.status === 'DEACTIVATED' ? ['reactivate'] : [],
      u.mfa && !u.self ? ['mfaReset'] : [],
      u.status !== 'DEACTIVATED' && !u.self ? ['revokeSessions'] : [],
      u.status !== 'DEACTIVATED' && !u.self ? ['deactivate'] : []);
    const initials = (n) => n.split(' ').map((p) => p[0]).join('');
    const H = PHONE ? '44px' : '36px', BFS = PHONE ? '15px' : '14px';
    const okStyle = (danger) => 'height: ' + H + '; padding: 0 14px; border: 0; border-radius: 8px; font-size: ' + BFS + '; font-weight: 500; cursor: pointer; ' + (danger ? 'background: var(--destructive); color: var(--on-destructive)' : 'background: var(--primary); color: var(--primary-fg)');
    const closeDlg = () => this.setState({ dlg: '', err: 'none' });
    const toastVals = { hasToast: !!st.toast, toast: st.toast || '', hideToast: () => this.setState({ toast: '' }), closeDlg: closeDlg, dlgKey: (ev) => { if (ev && ev.key === 'Escape') closeDlg(); } };
"""

# ---------------------------------------------------------------- list
def list_toolbar(phone):
    if phone:
        return f'''    <div style="padding: 0 16px 8px">
      <label style="position: relative; display: flex; align-items: center">
        <span style="{SR}">Search by name or email</span>
        {svg(I["search"], 16, ' style="position: absolute; left: 12px; color: var(--muted-fg); pointer-events: none"')}
        <input type="search" placeholder="Search by name or email" value="{{{{q}}}}" onChange="{{{{onQ}}}}" style="width: 100%; height: 44px; padding: 0 12px 0 36px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: 16px; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">
      </label>
    </div>
    <div class="chips" role="tablist" aria-label="Status" style="flex-shrink: 0; display: flex; gap: 6px; padding: 4px 16px 12px; overflow-x: auto; scrollbar-width: none">
      <sc-for list="{{{{tabs}}}}" as="f" hint-placeholder-count="5">
        <button type="button" role="tab" aria-selected="{{{{f.aria}}}}" onClick="{{{{f.pick}}}}" style="{{{{f.style}}}}">{{{{f.label}}}}</button>
      </sc-for>
    </div>'''
    return f'''      <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px">
        <div role="tablist" aria-label="Status" style="display: inline-flex; align-items: center; gap: 2px; height: 36px; padding: 3px; box-sizing: border-box; border-radius: 10px; background: var(--muted); max-width: 100%; overflow-x: auto">
          <sc-for list="{{{{tabs}}}}" as="f" hint-placeholder-count="5">
            <button type="button" role="tab" class="tab" aria-selected="{{{{f.aria}}}}" onClick="{{{{f.pick}}}}" style="{{{{f.style}}}}">{{{{f.label}}}}</button>
          </sc-for>
        </div>
        <label style="position: relative; display: flex; align-items: center">
          <span style="{SR}">Search by name or email</span>
          {svg(I["search"], 14, ' style="color: var(--muted-fg); position: absolute; left: 10px"')}
          <input type="search" placeholder="Search by name or email" value="{{{{q}}}}" onChange="{{{{onQ}}}}" style="width: 240px; height: 32px; padding: 0 10px 0 30px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; font-family: inherit; font-size: 14px; color: var(--fg); background: var(--bg); box-shadow: 0 1px 2px rgba(0,0,0,0.05); outline: none">
        </label>
      </div>'''

EMPTY = '''<sc-if value="{{noUsers}}" hint-placeholder-val="{{false}}">
        <div style="display: flex; flex-direction: column; align-items: center; gap: 8px; padding: %s; border: 1px dashed var(--border); border-radius: 10px; text-align: center">
          <span style="font-weight: 500; font-size: 16px">No users match.</span>
          <span style="font-size: 14px; color: var(--muted-fg)">Try another name, email or status.</span>
        </div>
      </sc-if>'''

MENU = '''<sc-if value="{{u.menuOpen}}" hint-placeholder-val="{{false}}">
                      <button type="button" tabIndex="-1" aria-label="Close menu" onClick="{{closeMenu}}" style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 6; border: 0; padding: 0; background: transparent; cursor: default"></button>
                      <div role="menu" aria-label="{{u.menuLabel}}" onKeyDown="{{menuKey}}" style="position: absolute; z-index: 7; top: 34px; right: 8px; width: 230px; padding: 4px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 8px 24px rgba(0,0,0,0.10); text-align: left">
                        <a href="UserDetail.dc.html" role="menuitem" class="ghost" style="display: flex; align-items: center; height: 32px; padding: 0 8px; border-radius: 6px; color: var(--fg); font-size: 14px; text-decoration: none">Open profile and access</a>
                        <sc-if value="{{u.hasActions}}" hint-placeholder-val="{{true}}"><div aria-hidden="true" style="height: 1px; margin: 4px -4px; background: var(--border)"></div></sc-if>
                        <sc-for list="{{u.actions}}" as="a" hint-placeholder-count="3">
                          <button type="button" role="menuitem" class="ghost" onClick="{{a.pick}}" style="{{a.style}}">{{a.label}}</button>
                        </sc-for>
                        <sc-if value="{{u.isSelf}}" hint-placeholder-val="{{false}}"><span style="display: block; padding: 6px 8px; font-size: 12px; color: var(--muted-fg)">You cannot deactivate or sign out your own account here.</span></sc-if>
                      </div>
                    </sc-if>'''

LIST_DESK = f'''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      <div style="display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 12px">
        <div style="display: flex; flex-direction: column; gap: 2px">
          <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">Users</h1>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">Everyone who can sign in to your organisation.</p>
        </div>
        <button type="button" onClick="{{{{openInvite}}}}" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 13px; font-weight: 500; cursor: pointer">{svg(I["userplus"], 14)}Invite user</button>
      </div>

{list_toolbar(False)}

      <sc-if value="{{{{hasUsers}}}}" hint-placeholder-val="{{{{true}}}}">
        <div style="display: flex; flex-direction: column; gap: 8px">
          <div style="border: 1px solid var(--border); border-radius: 10px; overflow-x: auto; background: var(--card)">
            <table style="width: 100%; min-width: 820px; border-collapse: collapse; font-size: 14px">
              <caption style="{SR}">Users of the organisation</caption>
              <thead>
                <tr style="border-bottom: 1px solid var(--border)">
                  <th scope="col" style="height: 40px; {TH.replace('0 12px', '0 16px', 1)}">User</th>
                  <th scope="col" style="{TH}; width: 140px">Status</th>
                  <th scope="col" style="{TH}; width: 160px">Two-step sign-in</th>
                  <th scope="col" style="{TH}; width: 170px">Last sign-in</th>
                  <th scope="col" style="width: 44px"><span style="{SR}">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                <sc-for list="{{{{users}}}}" as="u" hint-placeholder-count="10">
                  <tr class="lrow" style="border-bottom: 1px solid var(--hover)">
                    <td style="padding: 8px 16px; vertical-align: middle; max-width: 0; width: 100%">
                      <span style="display: flex; align-items: center; gap: 10px; min-width: 0">{AVATAR.format(s=28, f=11, k='u.initials')}<span style="display: flex; flex-direction: column; gap: 2px; min-width: 0"><span style="display: flex; align-items: center; gap: 6px; min-width: 0"><a href="UserDetail.dc.html" style="font-weight: 500; color: var(--fg); text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{u.name}}}}</a>{ADMIN % 'u'}</span><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{u.email}}}}</span></span></span>
                    </td>
                    <td style="padding: 0 12px; vertical-align: middle"><span style="{{{{u.chipStyle}}}}">{{{{u.statusLabel}}}}</span></td>
                    <td style="padding: 0 12px; vertical-align: middle; white-space: nowrap; color: {{{{u.mfaColor}}}}">{{{{u.mfa}}}}</td>
                    <td style="padding: 0 12px; vertical-align: middle; color: var(--muted-fg); white-space: nowrap; font-variant-numeric: tabular-nums">{{{{u.last}}}}</td>
                    <td style="position: relative; padding: 0 8px 0 0; vertical-align: middle; text-align: right">
                      <button type="button" class="ghost" aria-label="{{{{u.menuLabel}}}}" aria-haspopup="menu" aria-expanded="{{{{u.menuAria}}}}" onClick="{{{{u.toggleMenu}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; color: var(--text-3)">{svg(I["dots"])}</button>
                    {MENU}
                    </td>
                  </tr>
                </sc-for>
              </tbody>
            </table>
          </div>
          <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px">
            <span style="font-size: 13px; color: var(--muted-fg)">{{{{shownText}}}}</span>
            <button type="button" class="ghost" style="{OUTLINE}">Show more</button>
          </div>
        </div>
      </sc-if>
      {EMPTY % '56px 24px'}
    </main>
  </div>
'''

LIST_PHONE = f'''  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
    <div style="display: flex; align-items: flex-start; gap: 8px; padding: 12px 16px 8px">
      <div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px">
        <h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Users</h1>
        <span style="font-size: 13px; color: var(--muted-fg)">Everyone who can sign in to your organisation.</span>
      </div>
      <button type="button" onClick="{{{{openInvite}}}}" style="flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 40px; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 14px; font-weight: 500">{svg(I["userplus"])}Invite</button>
    </div>
{list_toolbar(True)}
    <sc-if value="{{{{hasUsers}}}}" hint-placeholder-val="{{{{true}}}}">
      <ul aria-label="Users of the organisation" style="list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--hover)">
        <sc-for list="{{{{users}}}}" as="u" hint-placeholder-count="10">
          <li style="display: flex; align-items: center; gap: 10px; padding: 10px 4px 10px 16px; border-bottom: 1px solid var(--hover)">
            {AVATAR.format(s=32, f=12, k='u.initials')}
            <a href="UserDetailPhone.dc.html" style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; text-decoration: none; color: var(--fg)">
              <span style="display: flex; align-items: center; gap: 6px; min-width: 0"><span style="font-size: 15px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{u.name}}}}</span>{ADMIN % 'u'}</span>
              <span style="font-size: 12px; color: var(--muted-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{u.email}}}}</span>
              <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-fg)"><span style="{{{{u.chipStyle}}}}">{{{{u.statusLabel}}}}</span>{{{{u.phoneMeta}}}}</span>
            </a>
            <button type="button" class="ghost" aria-label="{{{{u.menuLabel}}}}" aria-haspopup="dialog" onClick="{{{{u.toggleMenu}}}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--text-3)">{svg(I["dots"], 18)}</button>
          </li>
        </sc-for>
      </ul>
      <div style="display: flex; justify-content: center; padding: 16px">
        <button type="button" class="ghost" style="height: 40px; padding: 0 16px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 14px; font-weight: 500">Show more</button>
      </div>
    </sc-if>
    <div style="padding: 0 16px">{EMPTY % '40px 16px'}</div>
  </main>

  <sc-if value="{{{{sheetOpen}}}}" hint-placeholder-val="{{{{false}}}}">
    <button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{closeMenu}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 20; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <div role="dialog" aria-modal="true" aria-labelledby="us-sheet" onKeyDown="{{{{menuKey}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 21; display: flex; flex-direction: column; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; margin-bottom: 8px; border-radius: 2px; background: var(--chip)"></span>
      <h2 id="us-sheet" style="margin: 0 4px 6px; font-size: 16px; font-weight: 600">{{{{sheet.name}}}}</h2>
      <a href="UserDetailPhone.dc.html" class="ghost" style="display: flex; align-items: center; min-height: 52px; padding: 0 4px; border-top: 1px solid var(--hover); color: var(--fg); font-size: 15px; text-decoration: none">Open profile and access</a>
      <sc-for list="{{{{sheet.actions}}}}" as="a" hint-placeholder-count="3">
        <button type="button" class="ghost" onClick="{{{{a.pick}}}}" style="{{{{a.sheetStyle}}}}">{{{{a.label}}}}</button>
      </sc-for>
      <sc-if value="{{{{sheet.isSelf}}}}" hint-placeholder-val="{{{{false}}}}"><span style="padding: 8px 4px; font-size: 13px; color: var(--muted-fg)">You cannot deactivate or sign out your own account here.</span></sc-if>
      <button type="button" onClick="{{{{closeMenu}}}}" style="margin-top: 8px; height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Close</button>
    </div>
  </sc-if>
'''

def invite_dialog(phone):
    chk = 18 if phone else 16
    inner = '\n'.join([heading('inv-title', 'Invite a user', 'They get an email with a link to set their password. The link works for 7 days.'),
        text_field('inv', 'email', 'Work email', 'email', phone), text_field('inv', 'name', 'Full name', 'text', phone),
        f'''        <label style="display: flex; align-items: flex-start; gap: 10px; {"min-height: 44px; " if phone else ""}font-size: 14px; cursor: pointer">
          <input type="checkbox" checked="{{{{inv.admin}}}}" onChange="{{{{inv.toggleAdmin}}}}" style="margin: 2px 0 0; width: {chk}px; height: {chk}px; accent-color: var(--primary)">
          <span style="display: flex; flex-direction: column; gap: 2px"><span style="font-weight: 500">Administrator of the organisation</span><span style="font-size: 12px; color: var(--muted-fg)">Can manage users, memo types, workflows and settings.</span></span>
        </label>''', buttons('Send invitation', primary(phone), phone)])
    return frame(inner, 'inv-title', 'dialog', 'inv.submit', phone)

def list_dialogs(phone):
    return f'''
  <sc-if value="{{{{isInvite}}}}" hint-placeholder-val="{{{{false}}}}">
    {invite_dialog(phone)}
  </sc-if>
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm_dialog(phone)}
  </sc-if>
''' + toast(phone)

LIST_JS = r"""
    const all = [
      { id: 'u1', name: 'Arif Rahman', email: 'a.rahman@[domain]', status: 'ACTIVE', admin: true, mfa: true, last: 'Today, 09:02', self: true },
      { id: 'u2', name: 'Farhana Rahman', email: 'f.rahman@[domain]', status: 'ACTIVE', admin: false, mfa: true, last: 'Today, 08:31' },
      { id: 'u3', name: 'Imran Hossain', email: 'i.hossain@[domain]', status: 'ACTIVE', admin: false, mfa: true, last: 'Today, 08:12' },
      { id: 'u4', name: 'Kamal Hasan', email: 'k.hasan@[domain]', status: 'LOCKED', admin: false, mfa: true, last: '15 Nov, 17:48' },
      { id: 'u5', name: 'Lutfa Begum', email: 'l.begum@[domain]', status: 'INVITED', admin: false, mfa: false, last: '' },
      { id: 'u6', name: 'Nasir Uddin', email: 'n.uddin@[domain]', status: 'DEACTIVATED', admin: false, mfa: true, last: '2 Nov, 10:05' },
      { id: 'u7', name: 'Nasrin Akter', email: 'n.akter@[domain]', status: 'ACTIVE', admin: false, mfa: false, last: '16 Nov, 15:20' },
      { id: 'u8', name: 'Rashed Khan', email: 'r.khan@[domain]', status: 'ACTIVE', admin: false, mfa: true, last: 'Yesterday, 18:02' },
      { id: 'u9', name: 'Shirin Akter', email: 's.akter@[domain]', status: 'ACTIVE', admin: true, mfa: true, last: 'Yesterday, 11:40' },
      { id: 'u10', name: 'Tanvir Ahmed', email: 't.ahmed@[domain]', status: 'ACTIVE', admin: false, mfa: true, last: 'Yesterday, 17:40' }
    ];
    const q = (st.q || '').toLowerCase(), status = st.status || '';
    const shown = all.filter((u) => (!q || (u.name + ' ' + u.email).toLowerCase().indexOf(q) >= 0) && (!status || u.status === status));
    const dlgProp = this.props.dialog || 'none';
    const dlg = st.dlg !== undefined ? st.dlg : (dlgProp === 'none' ? '' : dlgProp);
    const target = all.find((u) => u.id === (st.target || (dlgProp === 'unlock' ? 'u4' : (dlgProp === 'reactivate' ? 'u6' : 'u9')))) || all[1];
    const err = st.err !== undefined ? st.err : (this.props.error || 'none');
    const openAct = (u, k) => this.setState({ dlg: k, target: u.id, menu: '', err: 'none' });
    const itemStyle = 'display: flex; align-items: center; width: 100%; height: 32px; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; font-size: 14px; text-align: left; cursor: pointer; ';
    const sheetBase = 'display: flex; align-items: center; min-height: 52px; padding: 0 4px; border: 0; border-top: 1px solid var(--hover); background: transparent; font-size: 15px; text-align: left; ';
    const actItems = (u) => actionsFor(u).map((k) => ({ label: ACT[k].label, pick: () => openAct(u, k),
      style: itemStyle + (ACT[k].danger ? 'color: var(--danger-fg)' : 'color: var(--fg)'), sheetStyle: sheetBase + (ACT[k].danger ? 'color: var(--danger-fg)' : 'color: var(--fg)') }));
    const users = shown.map((u) => {
      const acts = actItems(u), open = st.menu === u.id;
      return {
        name: u.name + (u.self ? ' (you)' : ''), email: u.email, admin: u.admin, initials: initials(u.name),
        statusLabel: LABEL[u.status], chipStyle: chip + TONE[u.status],
        mfa: u.mfa ? 'On' : 'Off', mfaColor: u.mfa ? 'var(--text-1)' : 'var(--warn-fg)',
        last: u.last || 'Never',
        phoneMeta: (u.mfa ? '' : 'Two-step sign-in off · ') + (u.last ? 'Signed in ' + u.last.replace(/^(Today|Yesterday)/, (w) => w.toLowerCase()) : 'Never signed in'),
        actions: acts, hasActions: acts.length > 0, isSelf: !!u.self,
        menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + u.name,
        toggleMenu: () => this.setState({ menu: open ? '' : u.id })
      };
    });
    const TABS = [['', 'All'], ['ACTIVE', 'Active'], ['INVITED', 'Invited'], ['LOCKED', 'Locked'], ['DEACTIVATED', 'Deactivated']];
    const tabBase = 'display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; border-radius: 7px; font-size: 13px; font-weight: 500; cursor: pointer; white-space: nowrap; ';
    const pill = 'flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 14px; border-radius: 18px; font-size: 13px; font-weight: 500; white-space: nowrap; ';
    const tabs = TABS.map(([id, label]) => { const on = id === status; return { label: label, aria: on ? 'true' : 'false', pick: () => this.setState({ status: id }),
      style: PHONE ? pill + (on ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: var(--text-2)')
                   : tabBase + (on ? 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06)' : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)') }; });
    const sheetUser = PHONE ? all.find((u) => u.id === st.menu) : null;
    const closeMenu = () => this.setState({ menu: '' });
    // Invite: form validation in the app, never the browser.
    const inv = st.inv || {};
    const tried = !!inv.tried || err === 'required' || err === 'taken';
    const email = inv.email !== undefined ? inv.email : (err === 'taken' ? 'f.rahman@[domain]' : ''), name = inv.name !== undefined ? inv.name : (err === 'taken' ? 'Farhana Rahman' : '');
    const taken = err === 'taken' || all.some((u) => u.email === email.trim());
    const emailErr = tried ? (!email.trim() ? 'Required.' : (taken ? 'An account with this email already exists.' : '')) : '';
    const nameErr = tried && !name.trim() ? 'Required.' : '';
    const setInv = (k) => (ev) => { const n = Object.assign({}, inv); n[k] = ev && ev.target ? ev.target.value : ''; this.setState({ inv: n, err: 'none' }); };
    const ALERTS = { lastAdmin: 'The organisation needs at least one active administrator.', wrongState: 'Not possible in the account’s current status.', changed: 'Someone changed this account meanwhile. The list was reloaded.', failed: 'That did not work. Try again.', self: 'You cannot do this to your own account.' };
    const a = ACT[dlg] || ACT.deactivate, alertText = ALERTS[err] || '';
    const vals = Object.assign(toastVals, {
      users: users, hasUsers: users.length > 0, noUsers: users.length === 0, tabs: tabs,
      shownText: users.length + ' shown',
      q: st.q || '', onQ: (ev) => this.setState({ q: ev && ev.target ? ev.target.value : '' }),
      closeMenu: closeMenu, menuKey: (ev) => { if (ev && ev.key === 'Escape') closeMenu(); },
      sheetOpen: !!sheetUser,
      sheet: sheetUser ? { name: sheetUser.name + (sheetUser.self ? ' (you)' : ''), isSelf: !!sheetUser.self, actions: actItems(sheetUser) } : { name: '', isSelf: false, actions: [] },
      openInvite: () => this.setState({ dlg: 'invite', inv: {}, err: 'none' }),
      isInvite: dlg === 'invite', isConfirm: !!dlg && dlg !== 'invite',
      inv: {
        email: email, name: name, admin: !!inv.admin,
        toggleAdmin: () => this.setState({ inv: Object.assign({}, inv, { admin: !inv.admin }) }),
        on_email: setInv('email'), on_name: setInv('name'),
        emailHasErr: !!emailErr, emailErr: emailErr, emailInvalid: emailErr ? 'true' : 'false', emailBorder: emailErr ? 'var(--destructive)' : 'var(--border)',
        nameHasErr: !!nameErr, nameErr: nameErr, nameInvalid: nameErr ? 'true' : 'false', nameBorder: nameErr ? 'var(--destructive)' : 'var(--border)',
        submit: (ev) => {
          if (ev && ev.preventDefault) ev.preventDefault();
          if (!email.trim() || !name.trim() || taken) { this.setState({ inv: Object.assign({}, inv, { tried: true }) }); return; }
          this.setState({ dlg: '', toast: 'Invitation sent to ' + email.trim() + '.' });
        }
      },
      cf: {
        title: a.title(target.name), explain: a.explain, confirm: a.label, cancel: 'Cancel', btnStyle: okStyle(a.danger),
        hasDetail: false, detail: '', detailSub: '', hasAlert: !!alertText, alert: alertText,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (err !== 'none') { this.setState({ err: err }); return; } this.setState({ dlg: '', toast: 'Done.' }); }
      }
    });
"""
LIST_PROPS = '"dialog":{"editor":"enum","options":["none","invite","deactivate","reactivate","unlock","mfaReset","revokeSessions"],"default":"none"},"error":{"editor":"enum","options":["none","required","taken","lastAdmin","wrongState","changed","failed"],"default":"none"},'

# ---------------------------------------------------------------- detail
def detail_dialogs(phone):
    grant = frame('\n'.join([heading('gr-title', 'Give a role', 'What they may do and where.'),
        select_field('gr', 'role', 'Role', True, '', phone),
        select_field('gr', 'where', 'Where', False, '{{gr.whereHint}}', phone, disabled=True),
        alert('gr'), buttons('Give a role', primary(phone), phone)]), 'gr-title', 'dialog', 'gr.submit', phone)
    radio = f'''        <fieldset style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 0">
          <legend style="{SR}">Clearance</legend>
          <div style="border: 1px solid var(--border); border-radius: 10px; overflow: hidden">
            <sc-for list="{{{{cl.levels}}}}" as="l" hint-placeholder-count="4">
              <label style="{{{{l.style}}}}">
                <input type="radio" name="clearance" value="{{{{l.id}}}}" checked="{{{{l.checked}}}}" onChange="{{{{l.pick}}}}" style="margin: 2px 0 0; width: 16px; height: 16px; accent-color: var(--primary); flex-shrink: 0">
                <span style="display: flex; flex-direction: column; gap: 2px"><span style="font-weight: 500">{{{{l.name}}}}<sc-if value="{{{{l.current}}}}" hint-placeholder-val="{{{{false}}}}"><span style="margin-left: 6px; font-size: 12px; font-weight: 400; color: var(--muted-fg)">Current</span></sc-if></span><span style="font-size: 12px; color: var(--muted-fg)">{{{{l.desc}}}}</span></span>
              </label>
            </sc-for>
          </div>
        </fieldset>'''
    clear = frame('\n'.join([heading('cl-title', 'Change clearance', 'The most sensitive classification this person may open.'), radio, alert('cl'),
        buttons('Save clearance', primary(phone), phone)]), 'cl-title', 'dialog', 'cl.submit', phone)
    return f'''
  <sc-if value="{{{{isGrant}}}}" hint-placeholder-val="{{{{false}}}}">
    {grant}
  </sc-if>
  <sc-if value="{{{{isClearance}}}}" hint-placeholder-val="{{{{false}}}}">
    {clear}
  </sc-if>
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm_dialog(phone)}
  </sc-if>
''' + toast(phone)

CHIPS = f'<span style="{{{{u.chipStyle}}}}">{{{{u.statusLabel}}}}</span>{ADMIN % "u"}'

def dl_desk():
    cell = lambda i, dt, dd: f'''        <div style="display: flex; flex-direction: column; gap: 6px; padding: 12px 16px{"; border-left: 1px solid var(--border)" if i else ""}">
          <dt style="font-size: 12px; font-weight: 500; color: var(--muted-fg)">{dt}</dt>
          <dd style="margin: 0; display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 28px">{dd}</dd>
        </div>'''
    return f'''      <dl style="margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); border: 1px solid var(--border); border-radius: 10px; overflow: hidden; background: var(--card)">
{cell(0, 'Two-step sign-in', '<span style="display: flex; flex-direction: column"><span style="font-weight: 500; color: {{u.mfaColor}}">{{u.mfa}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{u.mfaSub}}</span></span>')}
{cell(1, 'Last sign-in', '<span style="font-weight: 500; font-variant-numeric: tabular-nums">{{u.last}}</span>')}
{cell(2, 'Clearance', '<span style="font-weight: 500">{{u.clearance}}</span><button type="button" class="ghost" onClick="{{openClearance}}" style="flex-shrink: 0; height: 28px; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer; text-decoration: underline; text-underline-offset: 3px">Change</button>')}
      </dl>'''

def table(id_, caption, heads, rows_key, cells, empty_key, empty_text, minw, alias):
    ths = ''.join(f'\n                <th scope="col" style="{"height: 40px; " + TH.replace("0 12px", "0 16px", 1) if i == 0 else TH}{"; width: " + w if w else ""}">{h}</th>' if h else f'\n                <th scope="col" style="width: {w}"><span style="{SR}">Actions</span></th>' for i, (h, w) in enumerate(heads))
    return f'''        <sc-if value="{{{{has_{rows_key}}}}}" hint-placeholder-val="{{{{true}}}}">
          <div style="border: 1px solid var(--border); border-radius: 10px; overflow-x: auto; background: var(--card)">
            <table style="width: 100%; min-width: {minw}px; border-collapse: collapse; font-size: 14px">
              <caption style="{SR}">{caption}</caption>
              <thead>
                <tr style="border-bottom: 1px solid var(--border)">{ths}
                </tr>
              </thead>
              <tbody>
                <sc-for list="{{{{{rows_key}}}}}" as="{alias}" hint-placeholder-count="2">
                  <tr class="lrow" style="border-bottom: 1px solid var(--hover)">
{cells}
                  </tr>
                </sc-for>
              </tbody>
            </table>
          </div>
        </sc-if>
        <sc-if value="{{{{{empty_key}}}}}" hint-placeholder-val="{{{{false}}}}">
          <div style="padding: 20px 16px; border: 1px dashed var(--border); border-radius: 10px; text-align: center; font-size: 14px; color: var(--muted-fg)">{empty_text}</div>
        </sc-if>'''

POS_CELLS = '''                    <td style="padding: 8px 16px; vertical-align: middle"><span style="display: flex; flex-direction: column; gap: 2px"><a href="Position.dc.html" style="font-weight: 500; color: var(--fg); text-decoration: none">{{pr.title}}</a><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{pr.unit}}</span></span></td>
                    <td style="padding: 0 12px; vertical-align: middle; color: var(--text-1); white-space: nowrap; font-variant-numeric: tabular-nums">{{pr.from}}</td>
                    <td style="padding: 0 12px; vertical-align: middle; color: var(--text-1); white-space: nowrap; font-variant-numeric: tabular-nums">{{pr.until}}</td>'''
ROLE_CELLS = '''                    <td style="padding: 8px 16px; vertical-align: middle"><span style="display: flex; flex-direction: column; gap: 2px"><span style="font-weight: 500">{{gt.role}}</span><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{gt.kind}}</span></span></td>
                    <td style="padding: 0 12px; vertical-align: middle; color: var(--text-1)">{{gt.where}}</td>
                    <td style="padding: 0 12px; vertical-align: middle; color: var(--muted-fg); white-space: nowrap; font-variant-numeric: tabular-nums">{{gt.given}}</td>
                    <td style="padding: 0 8px 0 12px; vertical-align: middle; text-align: right"><button type="button" class="ghost" onClick="{{gt.revoke}}" aria-label="{{gt.revokeLabel}}" style="height: 30px; padding: 0 10px; border: 0; border-radius: 6px; background: transparent; color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">Revoke</button></td>'''

DETAIL_DESK = f'''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      <a href="Users.dc.html" style="align-self: flex-start; display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--muted-fg); text-decoration: none">{svg(I["back"], 14)}Back to Users</a>
      <div style="display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 12px; margin-top: -8px">
        <div style="display: flex; align-items: center; gap: 12px; min-width: 0">
          {AVATAR.format(s=40, f=14, k='u.initials')}
          <div style="display: flex; flex-direction: column; gap: 2px; min-width: 0">
            <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px">
              <h1 id="user-title" style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">{{{{u.name}}}}</h1>
              {CHIPS}
            </div>
            <span style="font-size: 13px; color: var(--muted-fg)">{{{{u.email}}}} · Positions, roles and clearance of this person.</span>
          </div>
        </div>
        <div style="display: flex; flex-wrap: wrap; gap: 8px">
          <sc-for list="{{{{acts}}}}" as="a" hint-placeholder-count="3">
            <button type="button" class="ghost" onClick="{{{{a.pick}}}}" style="{{{{a.style}}}}">{{{{a.label}}}}</button>
          </sc-for>
        </div>
      </div>

{dl_desk()}

      <section aria-labelledby="ud-positions" style="display: flex; flex-direction: column; gap: 8px">
        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 32px">
          <h2 id="ud-positions" style="margin: 0; font-size: 14px; font-weight: 600">Positions</h2>
          <a href="Org.dc.html" class="ghost" style="{OUTLINE}; text-decoration: none">Open in Organisation</a>
        </div>
{table('pos', 'Positions held, newest first', [('Position', ''), ('From', '160px'), ('Until', '160px')], 'positions', POS_CELLS, 'noPositions', 'No positions.', 640, 'pr')}
      </section>

      <section aria-labelledby="ud-roles" style="display: flex; flex-direction: column; gap: 8px">
        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 32px">
          <h2 id="ud-roles" style="margin: 0; font-size: 14px; font-weight: 600">Roles</h2>
          <button type="button" class="ghost" onClick="{{{{openGrant}}}}" style="{OUTLINE}">{svg(I["plus"], 14)}Give a role</button>
        </div>
{table('roles', 'Roles given to this person', [('Role', ''), ('Where', '220px'), ('Given on', '140px'), ('', '100px')], 'grants', ROLE_CELLS, 'noGrants', 'Only the basic user role, which everyone has.', 720, 'gt')}
        <span style="padding: 0 4px; font-size: 13px; color: var(--muted-fg)">Everyone also holds the User role. Changes here ask you to confirm it is you.</span>
      </section>
    </main>
  </div>
'''

DETAIL_PHONE = f'''  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 16px 16px 24px">
    <div style="display: flex; align-items: center; gap: 12px">
      {AVATAR.format(s=44, f=15, k='u.initials')}
      <div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px">
        <h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{{{{u.name}}}}</h1>
        <span style="font-size: 13px; color: var(--muted-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{u.email}}}}</span>
        <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px">{CHIPS}</span>
      </div>
    </div>

    <dl style="margin: 0; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; background: var(--card)">
      <div style="display: flex; align-items: center; gap: 10px; min-height: 52px; padding: 8px 12px">
        <dt style="width: 104px; flex-shrink: 0; font-size: 13px; color: var(--muted-fg)">Two-step sign-in</dt>
        <dd style="margin: 0; flex: 1; display: flex; flex-direction: column"><span style="font-weight: 500; color: {{{{u.mfaColor}}}}">{{{{u.mfa}}}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{{{u.mfaSub}}}}</span></dd>
      </div>
      <div style="display: flex; align-items: center; gap: 10px; min-height: 52px; padding: 8px 12px; border-top: 1px solid var(--hover)">
        <dt style="width: 104px; flex-shrink: 0; font-size: 13px; color: var(--muted-fg)">Last sign-in</dt>
        <dd style="margin: 0; flex: 1; font-weight: 500">{{{{u.last}}}}</dd>
      </div>
      <div style="display: flex; align-items: center; gap: 10px; min-height: 52px; padding: 8px 4px 8px 12px; border-top: 1px solid var(--hover)">
        <dt style="width: 104px; flex-shrink: 0; font-size: 13px; color: var(--muted-fg)">Clearance</dt>
        <dd style="margin: 0; flex: 1; font-weight: 500">{{{{u.clearance}}}}</dd>
        <button type="button" class="ghost" onClick="{{{{openClearance}}}}" aria-label="Change clearance" style="flex-shrink: 0; height: 44px; padding: 0 10px; border: 0; border-radius: 8px; background: transparent; color: var(--fg); font-size: 14px; font-weight: 500">Change</button>
      </div>
    </dl>

    <section aria-labelledby="udp-positions" style="display: flex; flex-direction: column; gap: 8px">
      <h2 id="udp-positions" style="margin: 0; font-size: 14px; font-weight: 600">Positions</h2>
      <sc-if value="{{{{has_positions}}}}" hint-placeholder-val="{{{{true}}}}">
        <ul style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; background: var(--card)">
          <sc-for list="{{{{positions}}}}" as="pr" hint-placeholder-count="2">
            <li style="{{{{pr.rowStyle}}}}"><a href="PositionPhone.dc.html" style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; color: var(--fg); text-decoration: none"><span style="font-weight: 500">{{{{pr.title}}}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{{{pr.unit}}}} · {{{{pr.span}}}}</span></a>{svg(I["right"], 16, ' style="flex-shrink: 0; color: var(--muted-fg)"')}</li>
          </sc-for>
        </ul>
      </sc-if>
      <sc-if value="{{{{noPositions}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 16px; border: 1px dashed var(--border); border-radius: 10px; text-align: center; font-size: 14px; color: var(--muted-fg)">No positions.</div></sc-if>
    </section>

    <section aria-labelledby="udp-roles" style="display: flex; flex-direction: column; gap: 8px">
      <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
        <h2 id="udp-roles" style="margin: 0; font-size: 14px; font-weight: 600">Roles</h2>
        <button type="button" class="ghost" onClick="{{{{openGrant}}}}" style="display: inline-flex; align-items: center; gap: 6px; height: 40px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 14px; font-weight: 500">{svg(I["plus"])}Give a role</button>
      </div>
      <sc-if value="{{{{has_grants}}}}" hint-placeholder-val="{{{{true}}}}">
        <ul style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; background: var(--card)">
          <sc-for list="{{{{grants}}}}" as="gt" hint-placeholder-count="2">
            <li style="{{{{gt.rowStyle}}}}"><span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px"><span style="font-weight: 500">{{{{gt.role}}}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{{{gt.where}}}} · Given on {{{{gt.given}}}}</span></span><button type="button" class="ghost" onClick="{{{{gt.revoke}}}}" aria-label="{{{{gt.revokeLabel}}}}" style="flex-shrink: 0; height: 44px; padding: 0 10px; border: 0; border-radius: 8px; background: transparent; color: var(--fg); font-size: 14px; font-weight: 500">Revoke</button></li>
          </sc-for>
        </ul>
      </sc-if>
      <sc-if value="{{{{noGrants}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 16px; border: 1px dashed var(--border); border-radius: 10px; text-align: center; font-size: 14px; color: var(--muted-fg)">Only the basic user role, which everyone has.</div></sc-if>
    </section>
  </main>

  <sc-if value="{{{{sheetOpen}}}}" hint-placeholder-val="{{{{false}}}}">
    <button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{closeMore}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 20; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <div role="dialog" aria-modal="true" aria-labelledby="udp-sheet" onKeyDown="{{{{moreKey}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 21; display: flex; flex-direction: column; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; margin-bottom: 8px; border-radius: 2px; background: var(--chip)"></span>
      <h2 id="udp-sheet" style="margin: 0 4px 6px; font-size: 16px; font-weight: 600">{{{{u.name}}}}</h2>
      <sc-for list="{{{{acts}}}}" as="a" hint-placeholder-count="3">
        <button type="button" class="ghost" onClick="{{{{a.pick}}}}" style="{{{{a.sheetStyle}}}}">{{{{a.label}}}}</button>
      </sc-for>
      <button type="button" onClick="{{{{closeMore}}}}" style="margin-top: 8px; height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Close</button>
    </div>
  </sc-if>
'''

DETAIL_JS = r"""
    const PEOPLE = {
      active: { name: 'Farhana Rahman', email: 'f.rahman@[domain]', status: 'ACTIVE', admin: false, mfa: true, last: 'Today, 08:31', clearance: 'confidential',
        positions: [['Head of Credit', 'Head Office › Credit Risk', '1 Mar 2023', 'Ongoing'], ['Head of Credit Analysis (cover)', 'Head Office › Credit Risk › Credit Analysis', '10 Nov 2026', '30 Nov 2026']],
        grants: [['Auditor', 'Built-in', '', '12 Jan 2026'], ['Dashboard viewer', 'Made by your organisation', 'cr', '3 Mar 2026']] },
      locked: { name: 'Kamal Hasan', email: 'k.hasan@[domain]', status: 'LOCKED', admin: false, mfa: true, last: '15 Nov, 17:48', clearance: 'internal',
        positions: [['Relationship Manager', 'Head Office › Corporate Banking', '5 Jun 2024', 'Ongoing']], grants: [] },
      invited: { name: 'Lutfa Begum', email: 'l.begum@[domain]', status: 'INVITED', admin: false, mfa: false, last: '', clearance: 'internal', positions: [], grants: [] },
      deactivated: { name: 'Nasir Uddin', email: 'n.uddin@[domain]', status: 'DEACTIVATED', admin: false, mfa: true, last: '2 Nov, 10:05', clearance: 'restricted', positions: [], grants: [] },
      admin: { name: 'Shirin Akter', email: 's.akter@[domain]', status: 'ACTIVE', admin: true, mfa: true, last: 'Yesterday, 11:40', clearance: 'strict',
        positions: [['Head of Internal Audit', 'Head Office › Internal Audit', '1 Jul 2021', 'Ongoing']], grants: [['Organisation administrator', 'Built-in', '', '1 Jul 2021']] }
    };
    const account = this.props.account || 'active';
    const p = PEOPLE[account] || PEOPLE.active;
    const LEVELS = [
      { id: 'internal', name: 'Internal', desc: 'Everyday memos inside the organisation.' },
      { id: 'restricted', name: 'Restricted', desc: 'Memos for the units and people named on them.' },
      { id: 'confidential', name: 'Confidential', desc: 'Credit, staff and legal matters.' },
      { id: 'strict', name: 'Strictly confidential', desc: 'Board and regulator matters.' }
    ];
    const UNITS = [['', 'Whole organisation'], ['ho', 'Head Office'], ['cr', 'Credit Risk'], ['ca', 'Credit Analysis'], ['cb', 'Corporate Banking'], ['ia', 'Internal Audit']];
    const unitName = (id) => (UNITS.find((u) => u[0] === id) || UNITS[0])[1];
    const ROLES = [['', '', false], ['tenant_admin', 'Organisation administrator', false], ['org_admin', 'Org directory administrator', false], ['auditor', 'Auditor', false], ['dash', 'Dashboard viewer', true], ['team', 'Team lead view', true]];
    const dlgProp = this.props.dialog || 'none';
    const dlg = st.dlg !== undefined ? st.dlg : (dlgProp === 'none' ? '' : dlgProp);
    const err = st.err !== undefined ? st.err : (this.props.error || 'none');
    const u = { status: p.status, mfa: p.mfa, self: false };
    const sheetBase = 'display: flex; align-items: center; min-height: 52px; padding: 0 4px; border: 0; border-top: 1px solid var(--hover); background: transparent; font-size: 15px; text-align: left; ';
    const btnBase = 'display: inline-flex; align-items: center; height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 13px; font-weight: 500; cursor: pointer; box-shadow: 0 1px 2px rgba(0,0,0,0.05); ';
    const acts = actionsFor(u).map((k) => ({ label: ACT[k].label, pick: () => this.setState({ dlg: k, err: 'none', more: false }),
      style: btnBase + (ACT[k].danger ? 'color: var(--danger-fg)' : 'color: var(--fg)'), sheetStyle: sheetBase + (ACT[k].danger ? 'color: var(--danger-fg)' : 'color: var(--fg)') }));
    const rowP = (i) => 'display: flex; align-items: center; gap: 8px; min-height: 56px; padding: 8px 4px 8px 12px; box-sizing: border-box' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '');
    const positions = p.positions.map((r, i) => ({ title: r[0], unit: r[1], from: r[2], until: r[3], span: r[3] === 'Ongoing' ? 'Since ' + r[2] : r[2] + ' – ' + r[3], rowStyle: rowP(i) }));
    const grants = p.grants.map((g, i) => ({ role: g[0], kind: g[1], where: unitName(g[2]), given: g[3], rowStyle: rowP(i),
      revokeLabel: 'Revoke ' + g[0], revoke: () => this.setState({ dlg: 'revoke', target: i, err: 'none' }) }));
    const ALERTS = { duplicate: 'The person already holds this role there.', notScopable: 'This role cannot be limited to a unit.', lastAdmin: 'The organisation needs at least one active administrator.',
      wrongState: 'Not possible in the account’s current status.', changed: 'Someone changed this meanwhile. It was reloaded.', failed: 'That did not work. Try again.' };
    const alertFor = (allowed) => (allowed.indexOf(err) >= 0 ? ALERTS[err] : '');
    // Give a role
    const gr = st.gr || {};
    const roleId = gr.role !== undefined ? gr.role : (err === 'required' ? '' : (dlgProp === 'grant' ? 'dash' : '')), where = gr.where !== undefined ? gr.where : (roleId === 'dash' ? 'cr' : '');
    const role = ROLES.find((r) => r[0] === roleId) || ROLES[0];
    const scopable = role[2];
    const roleErr = (gr.tried || err === 'required') && !roleId ? 'Required.' : '';
    const grAlert = alertFor(['duplicate', 'notScopable', 'changed', 'failed']);
    const pickGr = (k) => (ev) => { const n = Object.assign({}, gr); n[k] = ev && ev.target ? ev.target.value : ''; this.setState({ gr: n, err: 'none' }); };
    // Clearance
    const lvl = st.lvl || p.clearance;
    const clAlert = alertFor(['changed', 'failed']);
    // Confirm (account actions and revoke)
    const isRevoke = dlg === 'revoke';
    const g = p.grants[st.target || 0] || ['Auditor', 'Built-in', '', '12 Jan 2026'];
    const a = ACT[dlg] || ACT.deactivate;
    const cfAlert = isRevoke ? alertFor(['lastAdmin', 'changed', 'failed']) : alertFor(['lastAdmin', 'wrongState', 'changed', 'failed']);
    const done = (msg) => this.setState({ dlg: '', toast: msg, gr: {} });
    const vals = Object.assign(toastVals, {
      u: { name: p.name, email: p.email, admin: p.admin, initials: initials(p.name), statusLabel: LABEL[p.status], chipStyle: chip + TONE[p.status],
           mfa: p.mfa ? 'On' : 'Off', mfaColor: p.mfa ? 'var(--fg)' : 'var(--warn-fg)', mfaSub: p.mfa ? 'Authenticator app' : (p.status === 'INVITED' ? 'Set up at the first sign-in' : 'Not set up'),
           last: p.last || 'Never', clearance: (LEVELS.find((l) => l.id === lvl) || LEVELS[0]).name },
      acts: acts, sheetOpen: !!st.more, openMore: () => this.setState({ more: true }), closeMore: () => this.setState({ more: false }), moreKey: (ev) => { if (ev && ev.key === 'Escape') this.setState({ more: false }); },
      positions: positions, has_positions: positions.length > 0, noPositions: positions.length === 0,
      grants: grants, has_grants: grants.length > 0, noGrants: grants.length === 0,
      openGrant: () => this.setState({ dlg: 'grant', gr: {}, err: 'none' }), openClearance: () => this.setState({ dlg: 'clearance', err: 'none' }),
      isGrant: dlg === 'grant', isClearance: dlg === 'clearance', isConfirm: !!dlg && dlg !== 'grant' && dlg !== 'clearance',
      gr: {
        roleOptions: ROLES.map((r) => ({ value: r[0], label: r[0] ? r[1] : 'Choose a role', sel: r[0] === roleId })),
        on_role: pickGr('role'), roleHasErr: !!roleErr, roleErr: roleErr, roleInvalid: roleErr ? 'true' : 'false', roleBorder: roleErr ? 'var(--destructive)' : 'var(--border)', roleLook: roleId ? '' : 'color: var(--muted-fg)',
        whereOptions: UNITS.map((x) => ({ value: x[0], label: x[1], sel: x[0] === (scopable ? where : '') })),
        on_where: pickGr('where'), whereDisabled: !scopable, whereInvalid: 'false', whereBorder: 'var(--border)', whereLook: scopable ? '' : 'opacity: 0.6; cursor: not-allowed',
        whereHint: roleId && !scopable ? 'This role always applies to the whole organisation.' : 'Limit to one unit and the units below it, where the role allows.',
        hasAlert: !!grAlert, alert: grAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!roleId) { this.setState({ gr: Object.assign({}, gr, { tried: true }) }); return; } if (grAlert) { this.setState({ err: err }); return; } done('Saved.'); }
      },
      cl: {
        levels: LEVELS.map((l, i) => ({ id: l.id, name: l.name, desc: l.desc, checked: l.id === lvl, current: l.id === p.clearance, pick: () => this.setState({ lvl: l.id, err: 'none' }),
          style: 'display: flex; align-items: flex-start; gap: 10px; padding: ' + (PHONE ? '12px' : '10px 12px') + '; font-size: 14px; cursor: pointer' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') + (l.id === lvl ? '; background: var(--subtle)' : '') })),
        hasAlert: !!clAlert, alert: clAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (clAlert) { this.setState({ err: err }); return; } done('Saved.'); }
      },
      cf: isRevoke ? {
        title: 'Revoke this role?', explain: 'The person loses what this role allowed at once.', confirm: 'Revoke', cancel: 'Keep', btnStyle: okStyle(true),
        hasDetail: true, detail: g[0], detailSub: unitName(g[2]) + ' · Given on ' + g[3], hasAlert: !!cfAlert, alert: cfAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (cfAlert) { this.setState({ err: err }); return; } done('Saved.'); }
      } : {
        title: a.title(p.name), explain: a.explain, confirm: a.label, cancel: 'Cancel', btnStyle: okStyle(a.danger),
        hasDetail: false, detail: '', detailSub: '', hasAlert: !!cfAlert, alert: cfAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (cfAlert) { this.setState({ err: err }); return; } done('Done.'); }
      }
    });
"""
DETAIL_PROPS = '"account":{"editor":"enum","options":["active","locked","invited","deactivated","admin"],"default":"active"},"dialog":{"editor":"enum","options":["none","grant","revoke","clearance","unlock","reactivate","mfaReset","revokeSessions","deactivate"],"default":"none"},"error":{"editor":"enum","options":["none","required","duplicate","notScopable","lastAdmin","wrongState","changed","failed"],"default":"none"},'

# ---------------------------------------------------------------- assemble
DESK_SHELL_JS = """    return Object.assign(vals, {
      themeClass: st.theme || this.props.theme || 'system',
      shortcut: isMac ? '⌘K' : 'Ctrl K',
      sidebarClass: 'sidebar' + (navOpen ? ' open' : '') + (collapsed && !navOpen ? ' collapsed' : ''),
      scrimClass: 'scrim' + (navOpen ? ' open' : ''),
      navAria: (navOpen || !collapsed) ? 'true' : 'false',
      toggleNav: () => {
        const narrow = typeof window !== 'undefined' && window.innerWidth <= 860;
        if (narrow) this.setState({ navOpen: !navOpen });
        else this.setState({ collapsed: !collapsed });
      },
      closeNav: closeNav,
      navKey: (ev) => { if (ev && ev.key === 'Escape') closeNav(); }
    });
"""
DESK_HEAD_JS = """    const st = this.state || {};
    const PHONE = false;
    const navOpen = !!st.navOpen, collapsed = !!st.collapsed;
    const closeNav = () => this.setState({ navOpen: false });
    const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '');
"""
def script(props, w, h, body):
    return f"""<script type="text/x-dc" data-dc-script data-props='{{"theme":{{"editor":"enum","options":["system","light","dark"],"default":"system"}},{props}"$preview":{{"width":{w},"height":{h}}}}}'>
class Component extends DCLogic {{
  renderVals() {{
{body}  }}
}}
</script>
</body>
</html>
"""

t = (proj / 'Tasks.dc.html').read_text()
pre = t[:t.index('    <!-- Content -->\n') + len('    <!-- Content -->\n')]
pre = re.sub(r'<a href="#" class="nav-item" title="Open tasks" aria-current="page" style="([^"]*?); background: var\(--active\); font-weight: 500">', r'<a href="Tasks.dc.html" class="nav-item" title="Open tasks" style="\1">', pre)
pre = pre.replace('<a href="Users.dc.html" class="nav-item" title="Users" style=', '<a href="#" class="nav-item" title="Users" style=')
pre = rep(pre, '<a href="#" class="nav-item" title="Users" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg)">',
          '<a href="Users.dc.html" class="nav-item" title="Users" aria-current="page" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg); background: var(--active); font-weight: 500">')
assert pre.count('aria-current="page"') == 1
for name, title, body, dialogs, js, props, h in [('Users', 'Users', LIST_DESK, list_dialogs(False), LIST_JS, LIST_PROPS, 1000),
                                                 ('UserDetail', 'User detail', DETAIL_DESK, detail_dialogs(False), DETAIL_JS, DETAIL_PROPS, 1000)]:
    p = rep(pre, '<title>Open tasks</title>', f'<title>{title}</title>')
    (proj / f'{name}.dc.html').write_text(p + body + dialogs + '</div>\n</x-dc>\n' + script(props, 1440, h, DESK_HEAD_JS + COMMON_JS + js + DESK_SHELL_JS))

# Phone list: Open tasks phone shell (top bar + drawer, Users active)
tp = (proj / 'TasksPhone.dc.html').read_text()
head = rep(tp[:tp.index('  <main')], '<title>Open tasks on a phone</title>', '<title>Users on a phone</title>')
drawer = tp[tp.index('  <!-- Navigation drawer -->'):tp.index('<script type="text/x-dc"')]
drawer = re.sub(r'<a href="#" class="nav-item" aria-current="page" style="([^"]*?); background: var\(--active\); font-weight: 500">(\s*<svg[^>]*><path d="m3 17 2 2 4-4">)', r'<a href="TasksPhone.dc.html" class="nav-item" style="\1">\2', drawer)
m = re.search(r'<a href="(?:#|UsersPhone\.dc\.html)" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">(\s*<svg[^>]*><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2">.*?\n\s*Users\n)', drawer, re.S)
assert m, 'users drawer item'
drawer = drawer[:m.start()] + f'<a href="UsersPhone.dc.html" class="nav-item" style="{m.group(1)}; background: var(--active); font-weight: 500" aria-current="page">' + m.group(2) + drawer[m.end():]
assert drawer.count('aria-current="page"') == 1
PHONE_LIST_JS = """    const st = this.state || {};
    const PHONE = true;
    const navOpen = !!st.navOpen;
    const closeNav = () => this.setState({ navOpen: false });
""" + COMMON_JS + LIST_JS + """    return Object.assign(vals, {
      themeClass: st.theme || this.props.theme || 'system',
      navOpen: navOpen, navAria: navOpen ? 'true' : 'false',
      openNav: () => this.setState({ navOpen: true }), closeNav: closeNav,
      navKey: (ev) => { if (ev && ev.key === 'Escape') closeNav(); }
    });
"""
(proj / 'UsersPhone.dc.html').write_text(head + LIST_PHONE + list_dialogs(True) + drawer + script(LIST_PROPS, 390, 844, PHONE_LIST_JS))

# Phone detail: Position phone shell (back button top bar)
pp = (proj / 'PositionPhone.dc.html').read_text()
phead = pp[:pp.index('  <header')]
phead = re.sub(r'<title>[^<]*</title>', '<title>User detail on a phone</title>', phead)
topbar = f'''  <header style="flex-shrink: 0; height: 56px; display: flex; align-items: center; gap: 4px; padding: 0 8px; border-bottom: 1px solid var(--border); box-sizing: border-box">
    <a href="UsersPhone.dc.html" class="ghost" aria-label="Back to Users" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border-radius: 8px; color: var(--fg)">{svg(I["back"], 20)}</a>
    <span style="flex: 1; min-width: 0; font-size: 15px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">Users</span>
    <button type="button" class="ghost" aria-haspopup="dialog" onClick="{{{{openMore}}}}" aria-label="Account actions" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--fg); cursor: pointer">{svg(I["dots"], 20)}</button>
  </header>

'''
PHONE_DETAIL_JS = """    const st = this.state || {};
    const PHONE = true;
""" + COMMON_JS + DETAIL_JS + """    return Object.assign(vals, { themeClass: st.theme || this.props.theme || 'system' });
"""
(proj / 'UserDetailPhone.dc.html').write_text(phead + topbar + DETAIL_PHONE + detail_dialogs(True) + '</div>\n</x-dc>\n' + script(DETAIL_PROPS, 390, 844, PHONE_DETAIL_JS))

# Every other sidebar links Users here (idempotent)
for p in sorted(proj.glob('*.dc.html')):
    if p.name.startswith('User'): continue
    s = p.read_text()
    s2 = s.replace('<a href="#" class="nav-item" title="Users" style=', '<a href="Users.dc.html" class="nav-item" title="Users" style=')
    s2 = re.sub(r'<a href="#" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">(\s*<svg[^>]*><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2">)', r'<a href="UsersPhone.dc.html" class="nav-item" style="\1">\2', s2)
    if s2 != s: p.write_text(s2)

# Canvas: Users row, then User detail row, on the Admin page (idempotent)
c = proj / 'canvas.json'; d = json.loads(c.read_text())
B = d['boards']; N = d['notes']
B['Users.dc.html'] = {'x': 0, 'y': 1620, 'w': 1440, 'h': 1000, 'title': 'Users — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'}
B['UsersPhone.dc.html'] = {'x': 1520, 'y': 1620, 'w': 390, 'h': 844, 'title': 'Users — Phone', 'is_interactive': True, 'page': 'admin'}
B['UserDetail.dc.html'] = {'x': 0, 'y': 2980, 'w': 1440, 'h': 1000, 'title': 'User detail — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'}
B['UserDetailPhone.dc.html'] = {'x': 1520, 'y': 2980, 'w': 390, 'h': 844, 'title': 'User detail — Phone', 'is_interactive': True, 'page': 'admin'}
N['usersTitle'] = {'page': 'admin', 'x': 0, 'y': 1360, 'text': 'Users', 'kind': 'title1', 'maxW': 1910}
N['userDetailTitle'] = {'page': 'admin', 'x': 0, 'y': 2720, 'text': 'User detail (profile and access)', 'kind': 'title1', 'maxW': 1910}
N['userDetailNote'] = {'page': 'admin', 'x': 2030, 'y': 2980, 'w': 620, 'fill': 'blue', 'size': 'm', 'text':
  'User detail\n\n• One page for a person: account status and the account actions from the Users list, then positions, roles and clearance (today the app shows only the last three).\n'
  '• Account actions sit in the header and follow the same rules as the list menu; on phones they are in the ⋯ sheet.\n'
  '• Clearance opens a dialog with the levels from least to most sensitive, instead of a select on the page.\n'
  '• Give a role: “Where” is offered only for roles that can be limited to a unit; otherwise it says the role applies to the whole organisation.\n'
  '• Every change asks for step-up (Confirm it is you) first, as the app does. Refusals (already holds it, last administrator, changed meanwhile) show inside the dialog.\n'
  '• Needs nothing new from the API: GET /admin/users/{id}, its assignments and access, plus the existing lifecycle endpoints.'}
for f in ('Users.dc.html', 'UsersPhone.dc.html', 'UserDetail.dc.html', 'UserDetailPhone.dc.html'):
    if f not in d['order']: d['order'].append(f)
N['adminTodo']['text'] = re.sub(r'Admin pages still to design \(\d+\)', 'Admin pages still to design (20)', N['adminTodo']['text']).replace('People and access\n• Users\n', 'People and access\n').replace('• User detail\n', '')
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
