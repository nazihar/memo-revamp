"""Admin · Users (desktop and phone), from web/src/features/admin/UsersPage.tsx: copy, statuses, row actions and their rules."""
import json, re
from pathlib import Path
proj = Path('canvas/project')
def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:100], s.count(a)); return s.replace(a, b)
def svg(p, s=16, extra=''): return f'<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"{extra}>{p}</svg>'
I = {'userplus': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><line x1="19" x2="19" y1="8" y2="14"></line><line x1="22" x2="16" y1="11" y2="11"></line>',
     'search': '<circle cx="11" cy="11" r="8"></circle><path d="m21 21-4.3-4.3"></path>', 'dots': '<circle cx="12" cy="12" r="1"></circle><circle cx="19" cy="12" r="1"></circle><circle cx="5" cy="12" r="1"></circle>',
     'chev': '<path d="m6 9 6 6 6-6"></path>', 'x': '<path d="M18 6 6 18"></path><path d="m6 6 12 12"></path>', 'alert': '<circle cx="12" cy="12" r="10"></circle><line x1="12" x2="12" y1="8" y2="12"></line><line x1="12" x2="12.01" y1="16" y2="16"></line>'}
SR = 'position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)'

def fields(phone):
    h = '44px' if phone else '36px'; fs = '16px' if phone else '14px'
    inp = lambda k: f'width: 100%; height: {h}; padding: 0 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); border: 1px solid {{{{inv.{k}Border}}}}'
    f = lambda k, label, typ, auto: f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <label for="inv-{k}" style="font-size: 14px; font-weight: 500">{label} <span aria-hidden="true" style="color: var(--danger-fg)">*</span></label>
          <input id="inv-{k}" type="{typ}" autocomplete="{auto}" value="{{{{inv.{k}}}}}" onChange="{{{{inv.on_{k}}}}}" aria-invalid="{{{{inv.{k}Invalid}}}}" aria-describedby="inv-{k}-msg" style="{inp(k)}">
          <sc-if value="{{{{inv.{k}HasErr}}}}" hint-placeholder-val="{{{{false}}}}"><span id="inv-{k}-msg" style="font-size: 12px; color: var(--danger-fg)">{{{{inv.{k}Err}}}}</span></sc-if>
        </div>'''
    return (f('email', 'Work email', 'email', 'off') + '\n' + f('name', 'Full name', 'text', 'off') + f'''
        <label style="display: flex; align-items: flex-start; gap: 10px; {"min-height: 44px;" if phone else ""} font-size: 14px; cursor: pointer">
          <input type="checkbox" checked="{{{{inv.admin}}}}" onChange="{{{{inv.toggleAdmin}}}}" style="margin: 2px 0 0; width: {18 if phone else 16}px; height: {18 if phone else 16}px; accent-color: var(--primary)">
          <span style="display: flex; flex-direction: column; gap: 2px"><span style="font-weight: 500">Administrator of the organisation</span><span style="font-size: 12px; color: var(--muted-fg)">Can manage users, memo types, workflows and settings.</span></span>
        </label>''')

def alert_block():
    return f'''<sc-if value="{{{{cf.hasAlert}}}}" hint-placeholder-val="{{{{false}}}}">
          <div role="alert" style="display: flex; gap: 10px; padding: 10px 12px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px"><span style="flex-shrink: 0; margin-top: 1px">{svg(I["alert"])}</span><span style="display: flex; flex-direction: column; gap: 2px"><strong style="font-weight: 600">Not done</strong><span>{{{{cf.alert}}}}</span></span></div>
        </sc-if>'''

def dialogs(phone):
    h = '44px' if phone else '36px'; bfs = '15px' if phone else '14px'
    cancel = lambda click: f'<button type="button" class="ghost" onClick="{{{{{click}}}}}" style="height: {h}; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: {bfs}; font-weight: 500; cursor: pointer">Cancel</button>'
    def wrap(inner, labelled, role, submit, key, close):
        if phone:
            return f'''<button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{{close}}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <form role="{role}" aria-modal="true" aria-labelledby="{labelled}" novalidate onSubmit="{{{{{submit}}}}}" onKeyDown="{{{{{key}}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; display: flex; flex-direction: column; gap: 14px; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>
{inner}
    </form>'''
        return f'''<div style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box">
      <button type="button" tabIndex="-1" aria-label="Close dialog" onClick="{{{{{close}}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; border: 0; padding: 0; background: rgba(0,0,0,0.5); cursor: default"></button>
      <form role="{role}" aria-modal="true" aria-labelledby="{labelled}" novalidate onSubmit="{{{{{submit}}}}}" onKeyDown="{{{{{key}}}}}" style="position: relative; width: 100%; max-width: 460px; display: flex; flex-direction: column; gap: 16px; padding: 24px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--card); box-shadow: 0 16px 48px rgba(0,0,0,0.2)">
{inner}
      </form>
    </div>'''
    def btns(primary_label, primary_style, close):
        ok = f'<button type="submit" style="{primary_style}">{primary_label}</button>'
        return (f'<div style="display: flex; flex-direction: column; gap: 8px">{ok}{cancel(close)}</div>' if phone
                else f'<div style="display: flex; justify-content: flex-end; gap: 8px">{cancel(close)}{ok}</div>')
    invite = wrap(f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <h2 id="inv-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Invite a user</h2>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">They get an email with a link to set their password. The link works for 7 days.</p>
        </div>
{fields(phone)}
        {btns('Send invitation', f'height: {h}; padding: 0 14px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: {bfs}; font-weight: 500; cursor: pointer', 'closeDlg')}''',
                  'inv-title', 'dialog', 'inv.submit', 'dlgKey', 'closeDlg')
    confirm = wrap(f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <h2 id="cf-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{{{{cf.title}}}}</h2>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">{{{{cf.explain}}}}</p>
        </div>
        {alert_block()}
        {btns('{{cf.confirm}}', '{{cf.btnStyle}}', 'closeDlg')}''', 'cf-title', 'alertdialog', 'cf.submit', 'dlgKey', 'closeDlg')
    toast_pos = 'position: absolute; left: 16px; right: 16px; bottom: 16px' if phone else 'position: fixed; right: 24px; bottom: 24px'
    return f'''
  <sc-if value="{{{{isInvite}}}}" hint-placeholder-val="{{{{false}}}}">
    {invite}
  </sc-if>
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm}
  </sc-if>
  <sc-if value="{{{{hasToast}}}}" hint-placeholder-val="{{{{false}}}}">
    <div role="status" style="{toast_pos}; z-index: 40; display: flex; align-items: center; gap: 10px; padding: 10px 10px 10px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 8px 24px rgba(0,0,0,0.12); font-size: 14px">
      <span style="flex: 1">{{{{toast}}}}</span>
      <button type="button" class="ghost" aria-label="Dismiss" onClick="{{{{hideToast}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--muted-fg); cursor: pointer">{svg(I["x"], 14)}</button>
    </div>
  </sc-if>
'''

def toolbar(phone):
    h = '44px' if phone else '32px'; fs = '16px' if phone else '14px'
    opts = ''.join(f'<option value="{v}">{t}</option>' for v, t in [('', 'All statuses'), ('ACTIVE', 'Active'), ('INVITED', 'Invited'), ('LOCKED', 'Locked'), ('DEACTIVATED', 'Deactivated')])
    return f'''<form role="search" novalidate onSubmit="{{{{noop}}}}" style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px">
        <label style="position: relative; display: flex; align-items: center; flex: 1 1 {"100%" if phone else "280px"}; max-width: {"none" if phone else "360px"}">
          <span style="{SR}">Search by name or email</span>
          {svg(I["search"], 14, ' style="position: absolute; left: 10px; color: var(--muted-fg)"')}
          <input type="search" placeholder="Search by name or email" value="{{{{q}}}}" onChange="{{{{onQ}}}}" style="width: 100%; height: {h}; padding: 0 10px 0 30px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">
        </label>
        <label style="position: relative; display: flex; align-items: center; {"flex: 1 1 100%" if phone else ""}">
          <span style="{SR}">Status</span>
          <select onChange="{{{{onStatus}}}}" style="width: {"100%" if phone else "170px"}; height: {h}; padding: 0 28px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {"16px" if phone else "13px"}; font-weight: 500; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">{opts}</select>
          {svg(I["chev"], 14, ' style="position: absolute; right: 8px; color: var(--muted-fg); pointer-events: none"')}
        </label>
      </form>'''

def avatar(): return '<span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: 11px; font-weight: 600; flex-shrink: 0">{{u.initials}}</span>'
ADMIN = '<sc-if value="{{u.admin}}" hint-placeholder-val="{{false}}"><span style="display: inline-flex; align-items: center; height: 20px; padding: 0 6px; border-radius: 6px; background: var(--muted); color: var(--fg); font-size: 11px; font-weight: 500">Administrator</span></sc-if>'
MENU = '''<sc-if value="{{u.menuOpen}}" hint-placeholder-val="{{false}}">
                  <button type="button" tabIndex="-1" aria-label="Close menu" onClick="{{closeMenu}}" style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 6; border: 0; padding: 0; background: transparent; cursor: default"></button>
                  <div role="menu" aria-label="{{u.menuLabel}}" onKeyDown="{{menuKey}}" style="position: absolute; z-index: 7; top: 34px; right: 0; width: 230px; padding: 4px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 8px 24px rgba(0,0,0,0.10); text-align: left">
                    <a href="#" role="menuitem" class="ghost" style="display: flex; align-items: center; height: 32px; padding: 0 8px; border-radius: 6px; color: var(--fg); font-size: 14px; text-decoration: none">Open profile and access</a>
                    <sc-if value="{{u.hasActions}}" hint-placeholder-val="{{true}}"><div aria-hidden="true" style="height: 1px; margin: 4px -4px; background: var(--border)"></div></sc-if>
                    <sc-for list="{{u.actions}}" as="a" hint-placeholder-count="3">
                      <button type="button" role="menuitem" class="ghost" onClick="{{a.pick}}" style="{{a.style}}">{{a.label}}</button>
                    </sc-for>
                    <sc-if value="{{u.isSelf}}" hint-placeholder-val="{{false}}"><span style="display: block; padding: 6px 8px; font-size: 12px; color: var(--muted-fg)">You cannot deactivate or sign out your own account here.</span></sc-if>
                  </div>
                </sc-if>'''

DESK_BODY = f'''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      <div style="display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 12px">
        <div style="display: flex; flex-direction: column; gap: 2px">
          <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">Users</h1>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">Everyone who can sign in to your organisation.</p>
        </div>
        <button type="button" onClick="{{{{openInvite}}}}" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 14px; font-weight: 500; cursor: pointer">{svg(I["userplus"])}Invite user</button>
      </div>
      {toolbar(False)}
      <div style="display: flex; flex-direction: column; gap: 8px">
        <div style="border: 1px solid var(--border); border-radius: 10px; overflow-x: auto; background: var(--card)">
          <table style="width: 100%; min-width: 820px; border-collapse: collapse; font-size: 14px">
            <caption style="{SR}">Users of the organisation</caption>
            <thead>
              <tr style="border-bottom: 1px solid var(--border); text-align: left; color: var(--muted-fg); font-size: 13px">
                <th scope="col" style="height: 40px; padding: 0 16px; font-weight: 500">User</th>
                <th scope="col" style="padding: 0 12px; font-weight: 500; width: 140px">Status</th>
                <th scope="col" style="padding: 0 12px; font-weight: 500; width: 160px">Two-step sign-in</th>
                <th scope="col" style="padding: 0 12px; font-weight: 500; width: 170px">Last sign-in</th>
                <th scope="col" style="width: 52px"><span style="{SR}">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <sc-for list="{{{{users}}}}" as="u" hint-placeholder-count="10">
                <tr class="lrow" style="border-top: 1px solid var(--hover)">
                  <td style="padding: 8px 16px">
                    <span style="display: flex; align-items: center; gap: 10px">{avatar()}<span style="display: flex; flex-direction: column; min-width: 0"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><a href="#" style="font-weight: 500; color: var(--fg); text-decoration: none">{{{{u.name}}}}</a>{ADMIN}</span><span style="font-size: 12px; color: var(--muted-fg)">{{{{u.email}}}}</span></span></span>
                  </td>
                  <td style="padding: 0 12px"><span style="{{{{u.chipStyle}}}}">{{{{u.statusLabel}}}}</span></td>
                  <td style="padding: 0 12px; color: {{{{u.mfaColor}}}}">{{{{u.mfa}}}}</td>
                  <td style="padding: 0 12px; color: var(--muted-fg); white-space: nowrap">{{{{u.last}}}}</td>
                  <td style="position: relative; padding: 0 12px 0 0; text-align: right">
                    <button type="button" class="ghost" aria-label="{{{{u.menuLabel}}}}" aria-haspopup="menu" aria-expanded="{{{{u.menuAria}}}}" onClick="{{{{u.toggleMenu}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3); cursor: pointer">{svg(I["dots"])}</button>
                    {MENU}
                  </td>
                </tr>
              </sc-for>
            </tbody>
          </table>
          <sc-if value="{{{{noUsers}}}}" hint-placeholder-val="{{{{false}}}}"><p style="margin: 0; padding: 24px 16px; text-align: center; font-size: 14px; color: var(--muted-fg)">No users match.</p></sc-if>
        </div>
        <div style="display: flex; justify-content: center">
          <button type="button" class="ghost" style="height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">Show more</button>
        </div>
      </div>
    </main>
  </div>
'''

PHONE_BODY = f'''  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; padding: 14px 16px 24px">
    <div style="display: flex; align-items: flex-start; gap: 8px">
      <div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px">
        <h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Users</h1>
        <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">Everyone who can sign in to your organisation.</p>
      </div>
      <button type="button" onClick="{{{{openInvite}}}}" style="flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 40px; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 14px; font-weight: 500">{svg(I["userplus"])}Invite</button>
    </div>
    {toolbar(True)}
    <ul aria-label="Users of the organisation" style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; background: var(--card)">
      <sc-for list="{{{{users}}}}" as="u" hint-placeholder-count="10">
        <li style="{{{{u.rowStyle}}}}">
          {avatar()}
          <a href="#" style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; text-decoration: none; color: var(--fg)">
            <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-weight: 500">{{{{u.name}}}}</span>{ADMIN}</span>
            <span style="font-size: 12px; color: var(--muted-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{u.email}}}}</span>
            <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-fg)"><span style="{{{{u.chipStyle}}}}">{{{{u.statusLabel}}}}</span>{{{{u.phoneMeta}}}}</span>
          </a>
          <button type="button" class="ghost" aria-label="{{{{u.menuLabel}}}}" aria-haspopup="dialog" onClick="{{{{u.toggleMenu}}}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--text-3)">{svg(I["dots"], 18)}</button>
        </li>
      </sc-for>
    </ul>
    <sc-if value="{{{{noUsers}}}}" hint-placeholder-val="{{{{false}}}}"><p style="margin: 0; padding: 16px; text-align: center; font-size: 14px; color: var(--muted-fg)">No users match.</p></sc-if>
    <button type="button" class="ghost" style="align-self: center; height: 40px; padding: 0 16px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 14px; font-weight: 500">Show more</button>
  </main>

  <sc-if value="{{{{sheetOpen}}}}" hint-placeholder-val="{{{{false}}}}">
    <button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{closeMenu}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 20; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <div role="dialog" aria-modal="true" aria-labelledby="us-sheet" onKeyDown="{{{{menuKey}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 21; display: flex; flex-direction: column; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; margin-bottom: 8px; border-radius: 2px; background: var(--chip)"></span>
      <h2 id="us-sheet" style="margin: 0 4px 6px; font-size: 16px; font-weight: 600">{{{{sheet.name}}}}</h2>
      <a href="#" class="ghost" style="display: flex; align-items: center; min-height: 52px; padding: 0 4px; border-top: 1px solid var(--hover); color: var(--fg); font-size: 15px; text-decoration: none">Open profile and access</a>
      <sc-for list="{{{{sheet.actions}}}}" as="a" hint-placeholder-count="3">
        <button type="button" class="ghost" onClick="{{{{a.pick}}}}" style="{{{{a.sheetStyle}}}}">{{{{a.label}}}}</button>
      </sc-for>
      <sc-if value="{{{{sheet.isSelf}}}}" hint-placeholder-val="{{{{false}}}}"><span style="padding: 8px 4px; font-size: 13px; color: var(--muted-fg)">You cannot deactivate or sign out your own account here.</span></sc-if>
      <button type="button" onClick="{{{{closeMenu}}}}" style="margin-top: 8px; height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Close</button>
    </div>
  </sc-if>
'''

LOGIC = r"""
    const PHONE = /*PHONE*/false;
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
      u.status === 'DEACTIVATED' ? ['reactivate'] : (u.self ? [] : ['deactivate']),
      u.status === 'LOCKED' ? ['unlock'] : [],
      u.mfa && !u.self ? ['mfaReset'] : [],
      u.status !== 'DEACTIVATED' && !u.self ? ['revokeSessions'] : []);
    const q = (st.q || '').toLowerCase(), status = st.status || '';
    const shown = all.filter((u) => (!q || (u.name + ' ' + u.email).toLowerCase().indexOf(q) >= 0) && (!status || u.status === status));
    const dlgProp = this.props.dialog || 'none';
    const dlg = st.dlg !== undefined ? st.dlg : (dlgProp === 'none' ? '' : dlgProp);
    const target = all.find((u) => u.id === (st.target || (dlgProp === 'unlock' ? 'u4' : (dlgProp === 'reactivate' ? 'u6' : 'u9')))) || all[1];
    const err = st.err !== undefined ? st.err : (this.props.error || 'none');
    const openAct = (u, k) => this.setState({ dlg: k, target: u.id, menu: '', err: 'none' });
    const itemStyle = 'display: flex; align-items: center; width: 100%; height: 32px; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; font-size: 14px; text-align: left; cursor: pointer; ';
    const sheetBase = 'display: flex; align-items: center; min-height: 52px; padding: 0 4px; border: 0; border-top: 1px solid var(--hover); background: transparent; font-size: 15px; text-align: left; ';
    const users = shown.map((u, i) => {
      const acts = actionsFor(u).map((k) => ({ label: ACT[k].label, pick: () => openAct(u, k),
        style: itemStyle + (ACT[k].danger ? 'color: var(--danger-fg)' : 'color: var(--fg)'), sheetStyle: sheetBase + (ACT[k].danger ? 'color: var(--danger-fg)' : 'color: var(--fg)') }));
      const open = st.menu === u.id;
      return {
        name: u.name + (u.self ? ' (you)' : ''), email: u.email, admin: u.admin, initials: u.name.split(' ').map((p) => p[0]).join(''),
        statusLabel: LABEL[u.status], chipStyle: chip + TONE[u.status],
        mfa: u.mfa ? 'On' : 'Off', mfaColor: u.mfa ? 'var(--text-1)' : 'var(--warn-fg)',
        last: u.last || 'Never',
        phoneMeta: (u.mfa ? '' : 'Two-step sign-in off · ') + (u.last ? 'Signed in ' + u.last : 'Never signed in'),
        actions: acts, hasActions: acts.length > 0, isSelf: !!u.self,
        menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + u.name,
        toggleMenu: () => this.setState({ menu: open ? '' : u.id }),
        rowStyle: 'display: flex; align-items: center; gap: 10px; padding: 10px 4px 10px 12px' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '')
      };
    });
    const sheetUser = PHONE ? all.find((u) => u.id === st.menu) : null;
    const closeMenu = () => this.setState({ menu: '' });
    const closeDlg = () => this.setState({ dlg: '', err: 'none' });
    // Invite: form validation in the app, never the browser.
    const inv = st.inv || {};
    const tried = !!inv.tried || err === 'required' || err === 'taken';
    const email = inv.email !== undefined ? inv.email : (err === 'taken' ? 'f.rahman@[domain]' : ''), name = inv.name !== undefined ? inv.name : (err === 'taken' ? 'Farhana Rahman' : '');
    const emailErr = tried ? (!email.trim() ? 'Required.' : (err === 'taken' || all.some((u) => u.email === email.trim()) ? 'An account with this email already exists.' : '')) : '';
    const nameErr = tried && !name.trim() ? 'Required.' : '';
    const setInv = (k) => (ev) => { const n = Object.assign({}, inv); n[k] = ev && ev.target ? ev.target.value : ''; this.setState({ inv: n, err: 'none' }); };
    const h = PHONE ? '44px' : '36px', bfs = PHONE ? '15px' : '14px';
    const ALERTS = { lastAdmin: 'The organisation needs at least one active administrator.', changed: 'Someone changed this account meanwhile. The list was reloaded.', failed: 'That did not work. Try again.', self: 'You cannot do this to your own account.' };
    const a = ACT[dlg] || ACT.deactivate;
    const alertText = ALERTS[err] || '';
    const vals = {
      users: users, noUsers: users.length === 0,
      q: st.q || '', onQ: (ev) => this.setState({ q: ev && ev.target ? ev.target.value : '' }),
      onStatus: (ev) => this.setState({ status: ev && ev.target ? ev.target.value : '' }),
      noop: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); },
      closeMenu: closeMenu, menuKey: (ev) => { if (ev && ev.key === 'Escape') closeMenu(); },
      sheetOpen: !!sheetUser,
      sheet: sheetUser ? { name: sheetUser.name + (sheetUser.self ? ' (you)' : ''), isSelf: !!sheetUser.self, actions: actionsFor(sheetUser).map((k) => ({ label: ACT[k].label, pick: () => openAct(sheetUser, k), sheetStyle: sheetBase + (ACT[k].danger ? 'color: var(--danger-fg)' : 'color: var(--fg)') })) } : { name: '', isSelf: false, actions: [] },
      openInvite: () => this.setState({ dlg: 'invite', inv: {}, err: 'none' }),
      isInvite: dlg === 'invite', isConfirm: !!dlg && dlg !== 'invite',
      closeDlg: closeDlg, dlgKey: (ev) => { if (ev && ev.key === 'Escape') closeDlg(); },
      inv: {
        email: email, name: name, admin: !!inv.admin,
        toggleAdmin: () => this.setState({ inv: Object.assign({}, inv, { admin: !inv.admin }) }),
        on_email: setInv('email'), on_name: setInv('name'),
        emailHasErr: !!emailErr, emailErr: emailErr, emailInvalid: emailErr ? 'true' : 'false', emailBorder: emailErr ? 'var(--destructive)' : 'var(--border)',
        nameHasErr: !!nameErr, nameErr: nameErr, nameInvalid: nameErr ? 'true' : 'false', nameBorder: nameErr ? 'var(--destructive)' : 'var(--border)',
        submit: (ev) => {
          if (ev && ev.preventDefault) ev.preventDefault();
          if (!email.trim() || !name.trim() || all.some((u) => u.email === email.trim()) || err === 'taken') { this.setState({ inv: Object.assign({}, inv, { tried: true }) }); return; }
          this.setState({ dlg: '', toast: 'Invitation sent to ' + email.trim() + '.' });
        }
      },
      cf: {
        title: a.title(target.name), explain: a.explain, confirm: a.label,
        btnStyle: 'height: ' + h + '; padding: 0 14px; border: 0; border-radius: 8px; font-size: ' + bfs + '; font-weight: 500; cursor: pointer; ' + (a.danger ? 'background: var(--destructive); color: var(--on-destructive)' : 'background: var(--primary); color: var(--primary-fg)'),
        hasAlert: !!alertText, alert: alertText,
        submit: (ev) => {
          if (ev && ev.preventDefault) ev.preventDefault();
          if (err !== 'none') { this.setState({ err: err }); return; }
          this.setState({ dlg: '', toast: 'Done.' });
        }
      },
      hasToast: !!st.toast, toast: st.toast || '', hideToast: () => this.setState({ toast: '' })
    };
"""
PROPS = '"dialog":{"editor":"enum","options":["none","invite","deactivate","reactivate","unlock","mfaReset","revokeSessions"],"default":"none"},"error":{"editor":"enum","options":["none","required","taken","lastAdmin","changed","failed"],"default":"none"},'

def wrap_return(s, ret_pat):
    i0 = s.index('data-dc-script')
    r = s.rindex(ret_pat, i0)
    s = s[:r] + LOGIC_SLOT + '\n    return Object.assign(vals, {\n' + s[r + len(ret_pat):]
    e = s.rindex('    };\n  }\n}\n</script>')
    return s[:e] + '    });\n  }\n}\n</script>' + s[e + len('    };\n  }\n}\n</script>'):]

# ---------- desktop from the Open tasks shell
t = (proj / 'Tasks.dc.html').read_text()
pre = t[:t.index('    <!-- Content -->\n') + len('    <!-- Content -->\n')]
pre = rep(pre, '<title>Open tasks</title>', '<title>Users</title>')
pre = re.sub(r'<a href="#" class="nav-item" title="Open tasks" aria-current="page" style="([^"]*?); background: var\(--active\); font-weight: 500">', r'<a href="Tasks.dc.html" class="nav-item" title="Open tasks" style="\1">', pre)
pre = rep(pre, '<a href="#" class="nav-item" title="Users" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg)">',
          '<a href="Users.dc.html" class="nav-item" title="Users" aria-current="page" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg); background: var(--active); font-weight: 500">')
assert pre.count('aria-current="page"') == 1
script_t = t[t.index('<script type="text/x-dc"'):]
shell = """    const st = this.state || {};
    const navOpen = !!st.navOpen, collapsed = !!st.collapsed;
    const closeNav = () => this.setState({ navOpen: false });
    const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '');
""" + LOGIC + """    return Object.assign(vals, {
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
desk = pre + DESK_BODY + dialogs(False) + '</div>\n</x-dc>\n' + f"""<script type="text/x-dc" data-dc-script data-props='{{"theme":{{"editor":"enum","options":["system","light","dark"],"default":"system"}},{PROPS}"$preview":{{"width":1440,"height":1000}}}}'>
class Component extends DCLogic {{
  renderVals() {{
{shell}  }}
}}
</script>
</body>
</html>
"""
(proj / 'Users.dc.html').write_text(desk)

# ---------- phone from the Open tasks phone shell
tp = (proj / 'TasksPhone.dc.html').read_text()
head = tp[:tp.index('  <main')]
head = rep(head, '<title>Open tasks on a phone</title>', '<title>Users on a phone</title>')
drawer = tp[tp.index('  <!-- Navigation drawer -->'):tp.index('<script type="text/x-dc"')]
drawer = re.sub(r'<a href="#" class="nav-item" aria-current="page" style="([^"]*?); background: var\(--active\); font-weight: 500">(\s*<svg[^>]*><path d="m3 17 2 2 4-4">)', r'<a href="TasksPhone.dc.html" class="nav-item" style="\1">\2', drawer)
m = re.search(r'<a href="#" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">(\s*<svg[^>]*><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2">.*?\n\s*Users\n)', drawer, re.S)
assert m, 'users drawer item'
drawer = drawer[:m.start()] + f'<a href="UsersPhone.dc.html" class="nav-item" style="{m.group(1)}; background: var(--active); font-weight: 500" aria-current="page">' + m.group(2) + drawer[m.end():]
assert drawer.count('aria-current="page"') == 1, drawer.count('aria-current="page"')
pshell = """    const st = this.state || {};
    const navOpen = !!st.navOpen;
    const closeNav = () => this.setState({ navOpen: false });
""" + LOGIC.replace('/*PHONE*/false', 'true') + """    return Object.assign(vals, {
      themeClass: st.theme || this.props.theme || 'system',
      navOpen: navOpen, navAria: navOpen ? 'true' : 'false',
      openNav: () => this.setState({ navOpen: true }), closeNav: closeNav,
      navKey: (ev) => { if (ev && ev.key === 'Escape') closeNav(); }
    });
"""
phone = head + PHONE_BODY + dialogs(True) + drawer + f"""<script type="text/x-dc" data-dc-script data-props='{{"theme":{{"editor":"enum","options":["system","light","dark"],"default":"system"}},{PROPS}"$preview":{{"width":390,"height":844}}}}'>
class Component extends DCLogic {{
  renderVals() {{
{pshell}  }}
}}
</script>
</body>
</html>
"""
(proj / 'UsersPhone.dc.html').write_text(phone)

# Every other admin sidebar links Users here
for p in sorted(proj.glob('*.dc.html')):
    if p.name in ('Users.dc.html', 'UsersPhone.dc.html'): continue
    s = p.read_text()
    s2 = s.replace('<a href="#" class="nav-item" title="Users" style=', '<a href="Users.dc.html" class="nav-item" title="Users" style=')
    s2 = re.sub(r'<a href="#" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">(\s*<svg[^>]*><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2">)', r'<a href="UsersPhone.dc.html" class="nav-item" style="\1">\2', s2)
    if s2 != s: p.write_text(s2)

c = proj / 'canvas.json'; d = json.loads(c.read_text())
d['boards']['Users.dc.html'] = {'x': 0, 'y': 1620, 'w': 1440, 'h': 1000, 'title': 'Users — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'}
d['boards']['UsersPhone.dc.html'] = {'x': 1520, 'y': 1620, 'w': 390, 'h': 844, 'title': 'Users — Phone', 'is_interactive': True, 'page': 'admin'}
d['notes']['usersTitle'] = {'page': 'admin', 'x': 0, 'y': 1360, 'text': 'Users', 'kind': 'title1', 'maxW': 1910}
for f in ('Users.dc.html', 'UsersPhone.dc.html'):
    if f not in d['order']: d['order'].append(f)
t = d['notes']['adminTodo']['text']
d['notes']['adminTodo']['text'] = t.replace('Admin pages still to design (22)', 'Admin pages still to design (21)').replace('People and access\n• Users\n', 'People and access\n')
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
