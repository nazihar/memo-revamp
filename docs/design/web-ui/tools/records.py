"""Admin · Overview (the grouped Administration menu), Audit, Import (batches) and one Import batch (desktop and phone).

Copy from web/src/features/admin (AdminHomePage, AuditPage, admin-messages.ts), app/navigation.ts (the admin menu and its
labels) and web/src/features/imports (messages.ts, format.ts, steps.ts). Builds AdminOverview, Audit, Imports and
ImportBatch with their phone boards; safe to rerun.
"""
from wf_parts import with_ro, SMALL, H, FS, obtn, icon_btn, JS_HEAD, tabs_markup, PHONE_DOTS, MENU_CELL
from kit import *

I.update({'users': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M22 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path>',
          'file': '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"></path><path d="M14 2v4a2 2 0 0 0 2 2h4"></path>',
          'flow': '<rect width="8" height="8" x="3" y="3" rx="2"></rect><path d="M7 11v4a2 2 0 0 0 2 2h4"></path><rect width="8" height="8" x="13" y="13" rx="2"></rect>',
          'gear': '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"></path><circle cx="12" cy="12" r="3"></circle>',
          'archive': '<rect width="20" height="5" x="2" y="3" rx="1"></rect><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"></path><path d="M10 12h4"></path>',
          'download': '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" x2="12" y1="15" y2="3"></line>',
          'folder': '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"></path>'})
REC = [('Audit', 'Audit'), ('Import', 'Imports')]
def nav(active, phone): return section_nav(active, phone, REC, 'Records')

def head(title, lead, phone, btns=''):
    if phone:
        return (f'    <div style="display: flex; align-items: flex-start; gap: 8px; padding: 12px 16px 8px"><div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px"><h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{title}</h1>'
                f'<span style="font-size: 13px; color: var(--muted-fg)">{lead}</span></div>{btns}</div>')
    return (f'      <div style="display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 12px"><div style="display: flex; flex-direction: column; gap: 2px; max-width: 820px"><h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">{title}</h1>'
            f'<p style="margin: 0; font-size: 14px; color: var(--muted-fg)">{lead}</p></div><div style="display: flex; flex-wrap: wrap; gap: 8px">{btns}</div></div>')
PBTN = lambda l, c, icon='plus', phone=False: f'<button type="button" onClick="{{{{{c}}}}}" style="display: inline-flex; align-items: center; gap: 6px; height: {"40px" if phone else "32px"}; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: {"14px" if phone else "13px"}; font-weight: 500; cursor: pointer; flex-shrink: 0">{svg(I[icon], 14)}{l}</button>'
OBTN = lambda l, c, icon=None, phone=False: f'<button type="button" class="ghost" onClick="{{{{{c}}}}}" style="flex-shrink: 0; {OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">{svg(I[icon], 14) if icon else ""}{l}</button>'
def wrap_desk(body): return f'    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">\n{body}\n    </main>\n  </div>\n'
def wrap_phone(body): return f'  <main class="ed-main" style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">\n{body}\n  </main>\n'

# ================================================================ Overview (the grouped Administration menu)
GROUPS = [
    ('People and access', 'users', [('Users', 'Users', 'Invite people, reset sign-in, end sessions.', '{{w.users}}'), ('Roles', 'Roles', 'Who may do what, for the whole organisation or one part.', ''), ('Classification', 'Classifications', 'Classification levels and clearances.', '')]),
    ('Memos', 'file', [('Memo types', 'MemoTypes', 'Forms, numbering and approval of each kind of memo.', '{{w.types}}'), ('Memo numbering', 'MemoNumbering', 'Every number given, by series and period.', ''), ('Distribution lists', 'DistributionLists', 'Groups to copy memos to.', ''), ('Import', 'Imports', 'Bring memos from an earlier system.', '{{w.imports}}')]),
    ('Workflows', 'flow', [('Workflows', 'Workflows', 'How memos move from step to step.', '{{w.workflows}}'), ('Delegation of authority', 'Doa', 'Who must approve which memos.', ''), ('Queues', 'Queues', 'Steps that go to a team; one member claims.', ''), ('Substitutions', 'Substitutions', 'Who acts for people who are away.', ''), ('Business calendar', 'BusinessCalendar', 'Working hours and holidays for deadlines.', ''), ('Open tasks', 'Tasks', 'Give a task to someone else when its approver cannot act.', '{{w.tasks}}')]),
    ('Organisation-wide settings', 'gear', [('Security', 'OrgSecurity', 'Two-step sign-in, session length, passwords and lockout.', ''), ('Email', 'OrgEmail', 'Your mail server and the delivery log.', '{{w.email}}'), ('Branding', 'OrgBranding', 'Logo and colours.', ''), ('Language and terms', 'OrgLanguage', 'Default language, time zone and your own words.', '')]),
    ('Records', 'archive', [('Audit', 'Audit', 'Who changed what, and a verifiable export.', '')]),
]
def overview(phone):
    def item(label, board, desc, badge):
        b = f'<sc-if value="{{{{w.has_{board}}}}}" hint-placeholder-val="{{{{false}}}}"><span style="{CHIP}{TONES["warn"]}; height: 20px; font-size: 11px">{badge}</span></sc-if>' if badge else ''
        return (f'<li><a href="{board}{"Phone" if phone else ""}.dc.html" class="ghost" style="display: flex; align-items: center; gap: 10px; min-height: {"56px" if phone else "52px"}; padding: 8px 10px 8px 12px; border-radius: 8px; color: var(--fg); text-decoration: none">'
                f'<span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-weight: 500">{label}</span>{b}</span><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{desc}</span></span>'
                f'{svg(I["right"], 16, " style=\"flex-shrink: 0; color: var(--muted-fg)\"")}</a></li>')
    groups = ''.join(f'<section aria-labelledby="ov-{i}" style="{CARD}; display: flex; flex-direction: column; padding: 8px">'
                     f'<h2 id="ov-{i}" style="display: flex; align-items: center; gap: 8px; margin: 0; padding: 8px 10px 6px; font-size: 13px; font-weight: 600; color: var(--text-2)"><span style="display: inline-flex; color: var(--muted-fg)">{svg(I[icon], 16)}</span>{g}</h2>'
                     f'<ul style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column">{"".join(item(*it) for it in items)}</ul></section>'
                     for i, (g, icon, items) in enumerate(GROUPS))
    waiting = (f'<section aria-labelledby="ov-wait" style="border: 1px solid var(--warn-border); border-radius: 10px; background: var(--warn-bg); padding: 12px 16px; display: flex; flex-direction: column; gap: 8px">'
               '<h2 id="ov-wait" style="margin: 0; font-size: 14px; font-weight: 600; color: var(--warn-fg)">Waiting for you</h2>'
               '<ul style="margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px"><sc-for list="{{waiting}}" as="wt" hint-placeholder-count="4">'
               f'<li><a href="{{{{wt.href}}}}" style="display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--fg); text-decoration: none"><span style="width: 6px; height: 6px; border-radius: 50%; background: var(--warn-fg); flex-shrink: 0"></span><span style="flex: 1">{{{{wt.text}}}}</span>{svg(I["right"], 14, " style=\"color: var(--muted-fg)\"")}</a></li></sc-for></ul></section>')
    cols = 'display: flex; flex-direction: column; gap: 12px' if phone else 'display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; align-items: start'
    if phone:
        return wrap_phone(head('Administration', 'Settings for your whole organisation. You see the parts you may change.', True) + f'\n    <div style="display: flex; flex-direction: column; gap: 12px; padding: 4px 16px 24px">{waiting}<div style="{cols}">{groups}</div></div>')
    # three columns, packed by hand so the long Workflows group has a column of its own
    sec = groups.split('</section>')[:-1]
    sec = [s + '</section>' for s in sec]
    colhtml = (f'<div style="display: flex; flex-direction: column; gap: 16px; min-width: 0">{sec[0]}{sec[1]}</div>'
               f'<div style="display: flex; flex-direction: column; gap: 16px; min-width: 0">{sec[2]}</div>'
               f'<div style="display: flex; flex-direction: column; gap: 16px; min-width: 0">{sec[3]}{sec[4]}</div>')
    return wrap_desk(head('Administration', 'Settings for your whole organisation. You see the parts you may change.', False) + f'\n      {waiting}\n      <div style="{cols}">{colhtml}</div>'
                     + '\n      <p style="margin: 0; font-size: 13px; color: var(--muted-fg)">You see only the parts your roles let you change.</p>')
OV_JS = r"""
    const full = (this.props.viewer || 'admin') === 'admin';
    const P = PHONE ? 'Phone' : '';
    const W = [['3 tasks are waiting for reassignment.', 'Tasks'], ['Credit proposal version 5 is waiting for your review.', 'MemoTypeEditor'], ['Credit approval version 7 is waiting for your review.', 'WorkflowBuilder'], ['1 email could not be delivered.', 'OrgEmail'], ['Kamal Hasan is locked out.', 'Users']];
    const vals = Object.assign(base, {
      waiting: (full ? W : W.slice(0, 1)).map((w) => ({ text: w[0], href: w[1] + P + '.dc.html' })),
      w: { has_Users: full, has_MemoTypes: full, has_Imports: full, has_Workflows: full, has_Tasks: true, has_OrgEmail: full }
    });
"""
OV_PROPS = '"viewer":{"editor":"enum","options":["admin","tasksOnly"],"default":"admin"},'
def ov_badges(s):
    return (s.replace('{{w.users}}', '1 locked').replace('{{w.types}}', '1 to review').replace('{{w.imports}}', 'Importing').replace('{{w.workflows}}', '1 to review')
             .replace('{{w.tasks}}', '3 waiting').replace('{{w.email}}', '1 failed'))

# ================================================================ Audit
AUDIT_JS = r"""
    const E = (time, who, action, object, change, detail) => ({ time: time, who: who, action: action, object: object, change: change, detail: detail });
    const ROWS = [
      E('Today, 09:41', 'Shirin Akter', 'memo.type.version.submitted', 'memo_type CR · version 5', 'Submitted for review', [['state', 'DRAFT', 'IN_REVIEW']]),
      E('Today, 09:12', 'Arif Rahman', 'authz.grant.created', 'grant · Farhana Rahman', 'Dashboard viewer, Credit Risk', [['role', '—', 'Dashboard viewer'], ['scope', '—', 'Credit Risk']]),
      E('Today, 08:40', 'System', 'mail.delivery.failed', 'mail · l.begum@[domain]', 'Invitation, attempt 5', [['status', 'PENDING', 'FAILED']]),
      E('Today, 08:02', 'Not signed in', 'identity.signin.failed', 'user · k.hasan@[domain]', 'Wrong password (20th in a row)', []),
      E('Today, 08:02', 'System', 'identity.user.locked', 'user · Kamal Hasan', 'Locked for 30 minutes', [['status', 'ACTIVE', 'LOCKED']]),
      E('Yesterday, 17:20', 'Arif Rahman', 'tenant.settings.updated', 'settings · security', 'Shortest password 12 → 14', [['security.password.min.length', '12', '14']]),
      E('Yesterday, 16:05', 'Shirin Akter', 'authz.classification.reordered', 'classification', 'New order of 4 levels', [['order', 'Internal, Confidential, Restricted, Strictly confidential', 'Internal, Restricted, Confidential, Strictly confidential']]),
      E('Yesterday, 11:40', 'Platform operator', 'tenant.plan.updated', 'tenant', 'Storage limit raised', [['storage', '200 GB', '500 GB']])
    ];
    const open = st.open !== undefined ? st.open : (this.props.details === false ? -1 : 0);
    const rows = ROWS.map((r, i) => ({ time: r.time, who: r.who, action: r.action, object: r.object, change: r.change, isOpen: open === i, hasDetail: r.detail.length > 0,
      toggle: () => this.setState({ open: open === i ? -1 : i }), toggleLabel: open === i ? 'Hide' : 'Details', toggleAria: open === i ? 'true' : 'false',
      whoStyle: ['System', 'Platform operator', 'Not signed in'].indexOf(r.who) >= 0 ? 'color: var(--muted-fg); font-style: italic' : 'color: var(--fg)',
      detail: r.detail.map((d) => ({ field: d[0], before: d[1], after: d[2] })) }));
    const exAlert = err === 'tooMany' ? 'Too many exports this hour. Try again later.' : (err === 'failed' ? 'That did not work. Try again.' : '');
    const vals = Object.assign(base, {
      rows: rows, isExport: dlg === 'export', openExport: () => this.setState({ dlg: 'export', err: this.props.error || 'none' }),
      filtersText: 'From 1 Nov 2026 · Action starts with “authz.”', hasFilters: !!this.props.filtered,
      ex: { hasAlert: !!exAlert, alert: exAlert, submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (exAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', toast: 'Export downloaded.' }); } }
    });
"""
AUDIT_PROPS = '"details":{"editor":"boolean","default":true},"filtered":{"editor":"boolean","default":false},"dialog":{"editor":"enum","options":["none","export"],"default":"none"},"error":{"editor":"enum","options":["none","tooMany","failed"],"default":"none"},'
def audit(phone):
    fld = lambda id_, l, v, w, ph='': (f'<div style="display: flex; flex-direction: column; gap: 6px; {"" if phone else "width: " + w}"><label for="au-{id_}" style="font-size: 13px; font-weight: 500">{l}</label>'
                                       f'<input id="au-{id_}" type="text" value="{v}" placeholder="{ph}" style="width: 100%; height: {"44px" if phone else "32px"}; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)"></div>')
    filters = (f'<form role="search" novalidate style="display: {"grid; grid-template-columns: 1fr 1fr" if phone else "flex; flex-wrap: wrap; align-items: flex-end"}; gap: 10px">'
               + fld('from', 'From', '1 Nov 2026', '140px') + fld('to', 'To', '', '140px', 'Today') + fld('action', 'Action starts with', '', '200px', 'for example authz.') + fld('type', 'Object type', '', '170px', 'for example user')
               + f'<button type="button" class="ghost" style="height: {"44px" if phone else "32px"}; padding: 0 10px; border: 0; border-radius: 8px; background: transparent; color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer; {"grid-column: 1 / -1" if phone else ""}">Clear filters</button></form>')
    detail = (f'<div style="width: 100%; box-sizing: border-box; display: grid; grid-template-columns: {"1fr" if phone else "minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1fr)"}; gap: 4px 12px; padding: 10px 12px; border-radius: 8px; background: var(--subtle); font-size: 13px">'
              + ('' if phone else '<span style="font-size: 12px; font-weight: 500; color: var(--muted-fg)">Field</span><span style="font-size: 12px; font-weight: 500; color: var(--muted-fg)">Before</span><span style="font-size: 12px; font-weight: 500; color: var(--muted-fg)">After</span>')
              + f'<sc-for list="{{{{r.detail}}}}" as="d" hint-placeholder-count="1"><span style="{MONO}; font-size: 12px">{{{{d.field}}}}</span><span style="color: var(--danger-fg)">{"Before: " if phone else ""}{{{{d.before}}}}</span><span style="color: var(--ok-fg)">{"After: " if phone else ""}{{{{d.after}}}}</span></sc-for></div>')
    tog = f'<sc-if value="{{{{r.hasDetail}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" onClick="{{{{r.toggle}}}}" aria-expanded="{{{{r.toggleAria}}}}" style="height: {"40px" if phone else "28px"}; padding: 0 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">{{{{r.toggleLabel}}}}</button></sc-if>'
    if phone:
        lst = ('<ul aria-label="Audit entries, newest first" style="list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--hover)"><sc-for list="{{rows}}" as="r" hint-placeholder-count="8"><li style="display: flex; flex-direction: column; gap: 6px; padding: 10px 16px; border-bottom: 1px solid var(--hover)">'
               f'<span style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><span style="{MONO}; font-size: 12px; font-weight: 500; overflow: hidden; text-overflow: ellipsis">{{{{r.action}}}}</span>{tog}</span>'
               '<span style="font-size: 14px">{{r.change}}</span><span style="font-size: 12px; color: var(--muted-fg)"><span style="{{r.whoStyle}}">{{r.who}}</span> · {{r.time}} · {{r.object}}</span>'
               f'<sc-if value="{{{{r.isOpen}}}}" hint-placeholder-val="{{{{false}}}}">{detail}</sc-if></li></sc-for></ul>')
        btn = f'<button type="button" class="ghost" onClick="{{{{openExport}}}}" style="flex-shrink: 0; {OUTLINE.replace("height: 32px", "height: 40px")}">{svg(I["download"], 14)}Export</button>'
        return wrap_phone(f'    {nav("Audit", True)}\n' + head('Audit', 'Every change, newest first. The record cannot be altered without it showing.', True, btn)
                          + f'\n    <div style="padding: 0 16px 12px">{filters}</div>\n    {lst}\n    <div style="display: flex; justify-content: center; padding: 16px"><button type="button" class="ghost" style="{obtn(True)}">Show more</button></div>')
    th = TH
    table = (f'<div style="{CARD}; overflow-x: auto"><table style="width: 100%; min-width: 980px; border-collapse: collapse; font-size: 14px"><caption style="{SR}">Audit entries, newest first</caption>'
             f'<thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 40px; {th.replace("0 12px", "0 16px", 1)}; width: 140px">Time</th><th scope="col" style="{th}; width: 160px">Who</th><th scope="col" style="{th}; width: 250px">Action</th><th scope="col" style="{th}; width: 210px">Object</th><th scope="col" style="{th}">Change</th><th scope="col" style="width: 90px"><span style="{SR}">Details</span></th></tr></thead>'
             '<tbody><sc-for list="{{rows}}" as="r" hint-placeholder-count="8"><tr class="lrow" style="border-bottom: 1px solid var(--hover)">'
             '<td style="padding: 10px 16px; vertical-align: top; color: var(--muted-fg); white-space: nowrap; font-variant-numeric: tabular-nums">{{r.time}}</td><td style="padding: 10px 12px; vertical-align: top"><span style="{{r.whoStyle}}">{{r.who}}</span></td>'
             f'<td style="padding: 10px 12px; vertical-align: top; {MONO}; font-size: 12px">{{{{r.action}}}}</td><td style="padding: 10px 12px; vertical-align: top; color: var(--text-2)">{{{{r.object}}}}</td>'
             f'<td style="padding: 10px 12px; vertical-align: top"><span style="display: flex; flex-direction: column; gap: 8px"><span>{{{{r.change}}}}</span><sc-if value="{{{{r.isOpen}}}}" hint-placeholder-val="{{{{false}}}}">{detail}</sc-if></span></td>'
             f'<td style="padding: 8px 12px 0 0; vertical-align: top; text-align: right">{tog}</td></tr></sc-for></tbody></table></div>')
    btn = f'<button type="button" class="ghost" onClick="{{{{openExport}}}}" style="{OUTLINE}">{svg(I["download"], 14)}Export</button>'
    return wrap_desk(f'      {nav("Audit", False)}\n' + head('Audit', 'Every change in your organisation, newest first. The record cannot be altered without it showing.', False, btn)
                     + f'\n      {filters}\n      <div style="display: flex; flex-direction: column; gap: 8px">{table}<div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px"><span style="font-size: 13px; color: var(--muted-fg)">Times are in Asia/Dhaka. Older entries load as you ask for them.</span><button type="button" class="ghost" style="{OUTLINE}">Show more</button></div></div>')
def audit_dialogs(phone):
    body = '\n'.join([heading('ex-title', 'Export audit entries', 'A ZIP file with the entries, a manifest and the steps to verify it. Without filters it proves nothing is missing.'),
                      '        <sc-if value="{{hasFilters}}" hint-placeholder-val="{{false}}"><div style="padding: 10px 12px; border: 1px solid var(--warn-border); border-radius: 8px; background: var(--warn-bg); font-size: 13px"><strong style="font-weight: 600">Filtered:</strong> {{filtersText}}. Only the matching entries are exported, so the export cannot prove that nothing is missing.</div></sc-if>',
                      alert('ex'), buttons('Export', primary(phone), phone)])
    return f'''
  <sc-if value="{{{{isExport}}}}" hint-placeholder-val="{{{{false}}}}">
    {frame(body, 'ex-title', 'dialog', 'ex.submit', phone)}
  </sc-if>
''' + toast(phone)

# ================================================================ Imports (batches)
IMP_JS = r"""
    const B = (id, name, source, state, tone, updated) => ({ id: id, name: name, source: source, state: state, tone: tone, updated: updated });
    const ALL = [B('b1', 'Credit memos 2019–2023', 'LEGACY-DMS', 'Importing', 'warn', 'Today, 09:30'), B('b2', 'Board papers 2021', 'BOARD-ARCH', 'Dry run done', 'ok', 'Yesterday, 16:12'),
      B('b3', 'HR letters', 'HR-OLD', 'Open', 'neutral', '14 Nov, 11:05'), B('b4', 'Pilot sample', 'PILOT', 'Completed', 'ok', '2 Nov, 15:40'), B('b5', 'Test upload', 'TEST', 'Abandoned', 'neutral', '28 Oct, 10:15')];
    const batches = ALL.map((b) => ({ name: b.name, source: b.source, state: b.state, stateStyle: TONE[b.tone], updated: b.updated }));
    const f = (k, d) => form[k] !== undefined ? form[k] : (err === 'required' ? '' : d);
    const name = f('name', 'Procurement files 2020'), code = f('code', err === 'codeTaken' ? 'LEGACY-DMS' : 'PROC-2020');
    const tried = !!form.tried || err === 'required';
    const vals = Object.assign(base, {
      batches: batches, openNew: () => this.setState({ dlg: 'new', form: {}, err: 'none' }), isNew: dlg === 'new', template: () => this.setState({ toast: 'Template downloaded.' }),
      nb: Object.assign({ on_name: setForm('name'), on_code: setForm('code'), on_prefix: setForm('prefix'), prefix: '', hasAlert: err === 'failed', alert: 'That did not work. Try again.',
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!name.trim() || !code.trim() || err === 'codeTaken') { this.setState({ form: Object.assign({}, form, { name: name, code: code, tried: true }) }); return; }
          if (err === 'failed') { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Batch created. Opening it…' }); } },
        fieldVals('nb', 'name', name, tried && !name.trim() ? 'Required.' : ''), fieldVals('nb', 'code', code, tried && !code.trim() ? 'Required.' : (err === 'codeTaken' ? 'Another batch uses this source code. Re-runs of one source keep the same code; use it there.' : '')))
    });
"""
IMP_PROPS = '"dialog":{"editor":"enum","options":["none","new"],"default":"none"},"error":{"editor":"enum","options":["none","required","codeTaken","failed"],"default":"none"},'
IMP_LEAD = 'Bring memos from an earlier system: their originals and a spreadsheet of their facts. Nothing is imported until you have checked a dry run.'
def imports(phone):
    if phone:
        btns = PBTN('New', 'openNew', 'plus', True)
        lst = ('<ul aria-label="Import batches, newest first" style="list-style: none; margin: 4px 0 0; padding: 0; border-top: 1px solid var(--hover)"><sc-for list="{{batches}}" as="r" hint-placeholder-count="5"><li><a href="ImportBatchPhone.dc.html" style="display: flex; align-items: center; gap: 8px; padding: 12px 8px 12px 16px; border-bottom: 1px solid var(--hover); color: var(--fg); text-decoration: none">'
               f'<span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px"><span style="font-size: 15px; font-weight: 500">{{{{r.name}}}}</span><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-fg)"><span style="{{{{r.stateStyle}}}}">{{{{r.state}}}}</span><span style="{MONO}">{{{{r.source}}}}</span> · {{{{r.updated}}}}</span></span>{svg(I["right"], 16, " style=\"color: var(--muted-fg)\"")}</a></li></sc-for></ul>')
        return wrap_phone(f'    {nav("Import", True)}\n' + head('Import legacy memos', 'Memos from an earlier system, checked in a dry run first.', True, btns) + f'\n    {lst}\n    <p style="margin: 0; padding: 12px 16px 24px; font-size: 13px; color: var(--muted-fg)">Sending a folder of originals needs a computer. On a phone you can follow a batch and read its report.</p>')
    th = TH
    table = (f'<div style="{CARD}; overflow-x: auto"><table style="width: 100%; min-width: 820px; border-collapse: collapse; font-size: 14px"><caption style="{SR}">Import batches, newest first</caption>'
             f'<thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 40px; {th.replace("0 12px", "0 16px", 1)}">Name</th><th scope="col" style="{th}; width: 200px">Source</th><th scope="col" style="{th}; width: 160px">State</th><th scope="col" style="{th}; width: 170px">Updated</th></tr></thead>'
             '<tbody><sc-for list="{{batches}}" as="r" hint-placeholder-count="5"><tr class="lrow" style="border-bottom: 1px solid var(--hover)"><td style="height: 48px; padding: 0 16px"><a href="ImportBatch.dc.html" style="font-weight: 500; color: var(--fg); text-decoration: none">{{r.name}}</a></td>'
             f'<td style="padding: 0 12px; {MONO}; font-size: 13px; color: var(--text-2)">{{{{r.source}}}}</td><td style="padding: 0 12px"><span style="{{{{r.stateStyle}}}}">{{{{r.state}}}}</span></td><td style="padding: 0 12px; color: var(--muted-fg); white-space: nowrap">{{{{r.updated}}}}</td></tr></sc-for></tbody></table></div>')
    btns = OBTN('Download template', 'template', 'download') + PBTN('New batch', 'openNew')
    return wrap_desk(f'      {nav("Import", False)}\n' + head('Import legacy memos', IMP_LEAD, False, btns) + f'\n      <div style="display: flex; flex-direction: column; gap: 8px">{table}<div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px"><span style="font-size: 13px; color: var(--muted-fg)">Each batch goes through metadata, originals, mappings, a dry run, the import and a report.</span><button type="button" class="ghost" style="{OUTLINE}">Show more</button></div></div>')
def imp_dialogs(phone):
    sel = lambda id_, l, opts: (f'<div style="display: flex; flex-direction: column; gap: 6px"><label for="{id_}" style="font-size: 14px; font-weight: 500">{l}</label><span style="position: relative; display: flex; align-items: center"><select id="{id_}" style="width: 100%; height: {H(phone)}; padding: 0 30px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}">'
                                + ''.join(f'<option{" selected" if i == 0 else ""}>{o}</option>' for i, o in enumerate(opts)) + '</select>' + svg(I['chev'], 14, ' style="position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none"') + '</span></div>')
    code = text_field('nb', 'code', 'Source code', 'text', phone).replace('</div>', '  <span style="font-size: 12px; color: var(--muted-fg)">Capital letters, digits and "-". The same source code keeps re-runs from duplicating memos.</span>\n        </div>', 1).replace('outline: none;', 'outline: none; ' + MONO + ';', 1)
    g = f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 12px">'
    body = '\n'.join([text_field('nb', 'name', 'Name', 'text', phone), code, g + sel('nb-tz', 'Time zone of dates', ['Asia/Dhaka', 'UTC']) + sel('nb-cls', 'Default classification for blank cells', ['None: blank is an error', 'Internal', 'Restricted', 'Confidential']) + '</div>',
                      opt_field('nb', 'prefix', 'Number prefix (optional)', phone, required=False, hint='Added before every legacy number, for example "LEG-", if they clash with ours.'), alert('nb')])
    nb = frame_scroll('<div style="display: flex; flex-direction: column; gap: 6px"><h2 id="nb-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">New import batch</h2><p style="margin: 0; font-size: 14px; color: var(--muted-fg)">You can change these until the import starts.</p></div>',
                      body, buttons('Create', primary(phone), phone), 'nb-title', 'dialog', 'nb.submit', phone, width=560)
    return f'''
  <sc-if value="{{{{isNew}}}}" hint-placeholder-val="{{{{false}}}}">
    {nb}
  </sc-if>
''' + toast(phone)

# ================================================================ Import batch
BATCH_JS = r"""
    const step = st.step || this.props.step || 'import';
    const run = st.run || this.props.run || 'running';
    const STEPS = [['metadata', 'Metadata'], ['originals', 'Originals'], ['mappings', 'Mappings'], ['dryRun', 'Dry run'], ['import', 'Import'], ['report', 'Report']];
    const doneUpTo = run === 'done' ? 6 : 4;
    const steps = STEPS.map(([id, l], i) => { const on = id === step, done = i < doneUpTo; return { label: l, n: String(i + 1), done: done, notDone: !done, aria: on ? 'step' : 'false', pick: () => this.setState({ step: id }),
      srLabel: done ? l + ' (done)' : l,
      style: 'display: inline-flex; align-items: center; gap: 8px; height: ' + (PHONE ? '40px' : '36px') + '; padding: 0 12px 0 6px; border-radius: 18px; font-size: 13px; font-weight: 500; white-space: nowrap; cursor: pointer; flex-shrink: 0; '
        + (on ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: ' + (done ? 'var(--fg)' : 'var(--muted-fg)')),
      dotStyle: 'display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 50%; font-size: 11px; font-weight: 600; '
        + (on ? 'background: var(--primary-divider); color: var(--primary-fg)' : (done ? 'background: var(--ok-bg); color: var(--ok-fg); border: 1px solid var(--ok-border)' : 'background: var(--muted); color: var(--text-3)')) }; });
    const kind = st.kind || 'STATUS';
    const KINDS = [['STATUS', 'Statuses'], ['TYPE', 'Types'], ['UNIT', 'Units'], ['CLASSIFICATION', 'Classification'], ['PERSON', 'People']];
    const mtabs = mkTabs(KINDS.map((k) => [k[0], k[1]]), kind, 'kind');
    const MAPS = { STATUS: [['Approved', 412, 'Approved', true, false], ['Closed', 701, 'Closed', true, false], ['Rejected', 52, 'Rejected', true, false], ['Pending MD', 15, 'Not imported (in flight)', false, true]],
      TYPE: [['CRM', 812, 'Credit proposal', true, false], ['BUD', 233, 'Budget request', true, false], ['GEN', 135, 'Choose…', false, false]],
      UNIT: [['CR-DHK', 640, 'Credit Risk', true, false], ['CORP', 410, 'Corporate Banking', true, false], ['BR-GUL', 130, 'Choose…', false, false]],
      CLASSIFICATION: [['Normal', 900, 'Internal', true, false], ['Restricted', 212, 'Restricted', true, false], ['Secret', 68, 'Strictly confidential', false, true]],
      PERSON: [['m.karim@[domain]', 230, 'Linked: Mahmud Karim', true, false], ['EMP-0412', 118, 'Link to…', false, false], ['A. Hossain', 41, 'Not linked: keep the name as text', true, false]] };
    const maps = MAPS[kind].map((m, i) => ({ legacy: m[0], memos: m[1].toLocaleString('en-US'), ours: m[2], confirmed: m[3], unconfirmed: !m[3], proposed: m[4], confirmLabel: 'Confirm “' + m[0] + '”', oursLabel: 'Ours for “' + m[0] + '”',
      oursStyle: m[2] === 'Choose…' || m[2] === 'Link to…' ? 'color: var(--muted-fg)' : 'color: var(--fg)' }));
    const P = (sheet, row, col, level, problem) => ({ sheet: sheet, row: row, col: col, level: level, problem: problem, levelStyle: TONE[level === 'Error' ? 'danger' : (level === 'Warning' ? 'warn' : 'neutral')] });
    const problems = [P('Memos', '118', 'approved_on', 'Error', 'The date is before the memo was written.'), P('Memos', '233', 'unit', 'Error', 'The unit BR-GUL is not mapped.'), P('Files', '1,402', 'path', 'Warning', 'The original was not received.'), P('Approvals', '77', 'approver', 'Warning', 'A. Hossain is kept as text (no identifier).')];
    const progress = run === 'stopped' ? 640 : (run === 'done' ? 1180 : 640);
    const cfKind = dlg;
    const reason = form.reason !== undefined ? form.reason : '';
    const reasonErr = (form.tried || err === 'required') && !reason.trim() ? 'Required.' : '';
    const CF = {
      start: { title: 'Import 1,180 memos?', explain: '1,180 memos will be imported with their originals (2,904 originals received). 24 rejected rows will not.', confirm: 'Confirm and verify identity', danger: false,
        lines: ['The dry run listed 37 warnings (for example 41 people kept as text).', 'Imported memos are final. They get no approvals, tasks, notifications or final record. Their legacy approvals show as history, not verified by this system.', 'This cannot be undone. Corrections are added later, never by deleting.'] },
      finish: { title: 'Finish without the rest?', explain: 'The rows not imported yet are left out ("not started"); they can go into a later batch. The imported memos are checked and the report is made.', confirm: 'Finish without the rest', danger: true, lines: [] },
      close: { title: 'Close this batch?', explain: 'Staged copies of the originals are deleted. The report and imported memos stay.', confirm: 'Close batch', danger: false, lines: [], needsReason: true },
      abandon: { title: 'Abandon this batch?', explain: 'Nothing has been imported. Staged copies of the files are deleted; the batch stays listed.', confirm: 'Abandon batch', danger: true, lines: [], needsReason: true }
    };
    const cfd = CF[cfKind] || CF.start;
    const vals = Object.assign(base, {
      steps: steps, sMetadata: step === 'metadata', sOriginals: step === 'originals', sMappings: step === 'mappings', sDryRun: step === 'dryRun', sImport: step === 'import', sReport: step === 'report',
      stateLabel: run === 'done' ? 'Completed' : (run === 'stopped' ? 'Stopped' : (run === 'ready' ? 'Dry run done' : 'Importing')), stateStyle: TONE[run === 'done' ? 'ok' : (run === 'ready' ? 'ok' : 'warn')],
      canAbandon: run === 'ready', canClose: run === 'done',
      mtabs: mtabs, maps: maps, isClass: kind === 'CLASSIFICATION', isPeople: kind === 'PERSON', problems: problems,
      ready: run === 'ready', running: run === 'running', stopped: run === 'stopped', done: run === 'done', importingNow: run === 'running' || run === 'stopped',
      progressText: run === 'stopped' ? 'Importing · 640 of 1,180 memos' : 'Importing · 640 of 1,180 memos · about 18 minutes left',
      progressWidth: 'width: ' + Math.round(progress / 1180 * 100) + '%; height: 100%; border-radius: 4px; background: ' + (run === 'stopped' ? 'var(--warn-fg)' : 'var(--fg)'),
      progressCounts: 'Imported 631 · Skipped 4 · Rejected 0 · Failed 5 · Not started 540',
      openStart: () => this.setState({ dlg: 'start', err: 'none' }), openFinish: () => this.setState({ dlg: 'finish' }), openClose: () => this.setState({ dlg: 'close', form: {} }), openAbandon: () => this.setState({ dlg: 'abandon', form: {} }),
      stop: () => this.setState({ run: 'stopped', toast: 'Stopping after the current chunk of rows…' }), resume: () => this.setState({ run: 'running' }),
      runDry: () => this.setState({ toast: 'The dry run is running. Nothing is imported.' }), confirmAll: () => this.setState({ toast: 'Saved.' }),
      isConfirm: !!CF[cfKind],
      cf: Object.assign({ title: cfd.title, explain: cfd.explain, confirm: cfd.confirm, cancel: 'Cancel', btnStyle: okStyle(cfd.danger), lines: cfd.lines.map((t) => ({ t: t })), hasLines: cfd.lines.length > 0, needsReason: !!cfd.needsReason,
        on_reason: setForm('reason'), hasAlert: err === 'conflict', alert: 'Someone else changed this batch. It has been reloaded with their changes.',
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (cfd.needsReason && !reason.trim()) { this.setState({ form: Object.assign({}, form, { tried: true }) }); return; }
          if (err === 'conflict') { this.setState({ err: err }); return; } this.setState({ dlg: '', run: cfKind === 'start' ? 'running' : run, toast: cfKind === 'close' ? 'The batch was closed.' : (cfKind === 'abandon' ? 'The batch was abandoned.' : 'Done.') }); } },
        fieldVals('cf', 'reason', reason, reasonErr))
    });
"""
BATCH_PROPS = '"step":{"editor":"enum","options":["import","metadata","originals","mappings","dryRun","report"],"default":"import"},"run":{"editor":"enum","options":["running","stopped","ready","done"],"default":"running"},"dialog":{"editor":"enum","options":["none","start","finish","close","abandon"],"default":"none"},"error":{"editor":"enum","options":["none","required","conflict"],"default":"none"},'

def batch(phone):
    tbl = lambda cols, body, caption, minw=720: (f'<div style="{CARD}; overflow-x: auto"><table style="width: 100%; min-width: {minw}px; border-collapse: collapse; font-size: 14px"><caption style="{SR}">{caption}</caption><thead><tr style="border-bottom: 1px solid var(--border)">'
                                                 + ''.join(f'<th scope="col" style="height: 38px; {TH.replace("0 12px", "0 16px", 1) if i == 0 else TH}{"; width: " + w if w else ""}">{h}</th>' for i, (h, w) in enumerate(cols)) + f'</tr></thead><tbody>{body}</tbody></table></div>')
    box = lambda title, lead, inner: (f'<section style="{CARD}; padding: 16px; display: flex; flex-direction: column; gap: 14px"><div style="display: flex; flex-direction: column; gap: 4px"><h2 style="margin: 0; font-size: 15px; font-weight: 600">{title}</h2>'
                                      + (f'<span style="font-size: 13px; color: var(--muted-fg)">{lead}</span>' if lead else '') + f'</div>{inner}</section>')
    filerow = lambda name, meta, act='Replace': (f'<li style="display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 10px 12px; border-top: 1px solid var(--hover)"><span style="display: inline-flex; color: var(--text-3)">{svg(I["file"], 16)}</span>'
                                                 f'<span style="flex: 1; min-width: 160px; display: flex; flex-direction: column"><span style="font-weight: 500">{name}</span><span style="font-size: 12px; color: var(--muted-fg)">{meta}</span></span><button type="button" class="ghost" style="{OUTLINE}">{act}</button></li>')
    s = 18 if phone else 16
    metadata = box('Metadata', 'Use our template, or your own file with the same columns.',
                   f'<fieldset style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 8px"><legend style="margin-bottom: 8px; padding: 0; font-size: 14px; font-weight: 500">Metadata as</legend>'
                   f'<label style="display: flex; gap: 10px; font-size: 14px"><input type="radio" name="bm-kind" checked style="width: {s}px; height: {s}px; accent-color: var(--primary)">One workbook (.xlsx) with sheets Memos, Files, Approvals</label>'
                   f'<label style="display: flex; gap: 10px; font-size: 14px"><input type="radio" name="bm-kind" style="width: {s}px; height: {s}px; accent-color: var(--primary)">CSV files, UTF-8, one per table</label></fieldset>'
                   f'<ul style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden">{filerow("credit-memos-2019-2023.xlsx", "Workbook · 4.2 MB · uploaded 12 Nov").replace("border-top: 1px solid var(--hover)", "border-top: 0", 1)}</ul>'
                   '<span style="font-size: 12px; color: var(--muted-fg)">Up to 20 MB.</span>'
                   '<div style="padding: 10px 12px; border: 1px solid var(--warn-border); border-radius: 8px; background: var(--warn-bg); font-size: 13px">Tip: never open a legacy CSV export in Excel before uploading it. Excel removes leading zeros from numbers and changes dates.</div>')
    originals = box('Originals', 'Unpack the archive on your computer, then choose the folder. Each file is sent and checked on its own.',
                    ('<div style="padding: 12px; border: 1px dashed var(--border); border-radius: 8px; font-size: 13px; color: var(--muted-fg); text-align: center">Use a computer to send a folder.</div>' if phone else f'<div style="display: flex; flex-wrap: wrap; gap: 8px">{OBTN("Choose folder", "noop", "folder")}<button type="button" class="ghost" style="{OUTLINE}">Pause</button></div>')
                    + f'<span style="display: flex; align-items: center; gap: 8px; font-size: 13px">{svg(I["folder"], 16)}Folder: credit-memos-originals · 2,907 files · 2.1 GB</span>'
                    + '<div style="display: flex; flex-wrap: wrap; gap: 8px">' + chip('Received 2,904', 'ok') + chip('Waiting 0', 'neutral') + chip('Refused 3', 'danger') + '</div>'
                    + '<span style="font-size: 13px; color: var(--muted-fg)">Every file of the folder was sent. Skipped: 14 system files (Thumbs.db, .DS_Store)</span>'
                    + tbl([('Path', ''), ('Reason', '280px')], ''.join(f'<tr style="border-bottom: 1px solid var(--hover)"><td style="padding: 8px 16px; {MONO}; font-size: 12px">{p}</td><td style="padding: 8px 12px; color: var(--text-2)">{r}</td></tr>' for p, r in
                                                                   [('2019/CRM-0412/scan.tif', 'Too large (over 100 MB)'), ('2021/BUD-0033/~$draft.docx', 'Not a document we can keep'), ('2022/CRM-0981/sheet.xlsm', 'Contains macros')]), 'Refused originals', 480))
    mapping_rows = (f'<sc-for list="{{{{maps}}}}" as="m" hint-placeholder-count="4"><tr style="border-bottom: 1px solid var(--hover)"><td style="padding: 8px 16px; font-weight: 500">{{{{m.legacy}}}}</td><td style="padding: 8px 12px; color: var(--text-2); font-variant-numeric: tabular-nums">{{{{m.memos}}}}</td>'
                    f'<td style="padding: 8px 12px"><span style="position: relative; display: flex; align-items: center"><span aria-label="{{{{m.oursLabel}}}}" style="display: flex; align-items: center; width: 100%; height: {"40px" if phone else "32px"}; padding: 0 30px 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); {{{{m.oursStyle}}}}">{{{{m.ours}}}}</span>{svg(I["chev"], 14, " style=\"position: absolute; right: 10px; color: var(--muted-fg)\"")}</span></td>'
                    '<td style="padding: 8px 12px"><span style="display: flex; align-items: center; gap: 8px"><input type="checkbox" checked="{{m.confirmed}}" aria-label="{{m.confirmLabel}}" style="width: 16px; height: 16px; accent-color: var(--primary)"><sc-if value="{{m.proposed}}" hint-placeholder-val="{{false}}">' + SMALL('Proposed', 'warn') + '</sc-if></span></td></tr></sc-for>')
    mappings = box('Mappings', 'Say what each legacy value means here. Every value must be confirmed; the dry run proposes what it can.',
                   f'<div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px">{tabs_markup(phone, "mtabs", "Mappings")}<button type="button" class="ghost" onClick="{{{{confirmAll}}}}" style="{OUTLINE}">Confirm all proposals</button></div>'
                   '<sc-if value="{{isClass}}" hint-placeholder-val="{{false}}"><span style="font-size: 13px; color: var(--muted-fg)">Order the legacy markings from least to most strict. A stricter marking can never map to a lower level than a less strict one.</span></sc-if>'
                   '<sc-if value="{{isPeople}}" hint-placeholder-val="{{false}}"><span style="font-size: 13px; color: var(--muted-fg)">An email equal to a user’s is proposed; a legacy id is linked through the people picker. People given only by name are kept as text (no identifier) and cannot be linked.</span></sc-if>'
                   + tbl([('Legacy value', '200px'), ('Memos', '90px'), ('Ours', ''), ('Confirmed', '150px')], mapping_rows, 'Mappings', 640)
                   + f'<div style="display: flex; justify-content: flex-end"><button type="button" style="{primary(phone)}">Save mappings</button></div>')
    prob_rows = ('<sc-for list="{{problems}}" as="p" hint-placeholder-count="4"><tr style="border-bottom: 1px solid var(--hover)"><td style="padding: 8px 16px">{{p.sheet}}</td><td style="padding: 8px 12px; font-variant-numeric: tabular-nums">{{p.row}}</td>'
                 f'<td style="padding: 8px 12px; {MONO}; font-size: 12px">{{{{p.col}}}}</td><td style="padding: 8px 12px"><span style="{{{{p.levelStyle}}}}">{{{{p.level}}}}</span></td><td style="padding: 8px 12px">{{{{p.problem}}}}</td></tr></sc-for>')
    dry = box('Dry run', 'Checks every row and original against your mappings. Nothing is imported.',
              f'<div style="display: flex; flex-wrap: wrap; align-items: center; gap: 10px"><button type="button" onClick="{{{{runDry}}}}" style="{primary(phone)}">Run dry run</button><span style="font-size: 13px; color: var(--muted-fg)">Last run 16 Nov, 15:02 · nothing was imported</span></div>'
              + '<div style="display: flex; flex-wrap: wrap; gap: 8px">' + chip('1,180 memos ready', 'ok') + chip('24 rejected', 'danger') + chip('37 warnings', 'warn') + chip('0 already imported', 'neutral') + '</div>'
              + '<span style="font-size: 13px; color: var(--text-2)">2,904 originals received · 12 not used by any row · 3 refused</span>'
              + '<h3 style="margin: 4px 0 0; font-size: 14px; font-weight: 600">Problems</h3>' + tbl([('Sheet', '110px'), ('Row', '80px'), ('Column', '140px'), ('Level', '110px'), ('Problem', '')], prob_rows, 'Problems found, by level and code', 720))
    start_ready = (f'<sc-if value="{{{{ready}}}}" hint-placeholder-val="{{{{false}}}}"><div style="display: flex; flex-direction: column; gap: 10px"><span style="font-size: 14px">1,180 memos are ready. Importing makes them permanent records.</span>'
                   f'<div style="display: flex; flex-wrap: wrap; gap: 8px"><button type="button" onClick="{{{{openStart}}}}" style="{primary(phone)}">Import…</button></div></div></sc-if>')
    g1 = '; flex: 1' if phone else ''
    running = (f'<sc-if value="{{{{importingNow}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; flex-direction: column; gap: 10px"><span style="font-size: 14px; font-weight: 500">{{{{progressText}}}}</span>'
               '<div role="progressbar" aria-valuemin="0" aria-valuemax="1180" aria-valuenow="640" aria-label="Memos imported" style="height: 8px; border-radius: 4px; background: var(--muted); overflow: hidden"><div style="{{progressWidth}}"></div></div>'
               '<span style="font-size: 13px; color: var(--text-2)">{{progressCounts}}</span>'
               '<sc-if value="{{running}}" hint-placeholder-val="{{true}}"><span style="font-size: 13px; color: var(--muted-fg)">You can leave this page; the import goes on. Waiting for previews to catch up is normal and resumes by itself.</span>'
               f'<div style="display: flex; gap: 8px"><button type="button" class="ghost" onClick="{{{{stop}}}}" style="{obtn(phone, g1)}">Stop</button></div></sc-if>'
               '<sc-if value="{{stopped}}" hint-placeholder-val="{{false}}"><div style="padding: 10px 12px; border: 1px solid var(--warn-border); border-radius: 8px; background: var(--warn-bg); font-size: 13px">Stopped. Resume the import (you verify your identity again), or finish without the rest.</div>'
               f'<div style="display: flex; flex-wrap: wrap; gap: 8px"><button type="button" onClick="{{{{resume}}}}" style="{primary(phone)}{g1}">Resume</button><button type="button" class="ghost" onClick="{{{{openFinish}}}}" style="{obtn(phone, g1)}">Finish without the rest</button></div></sc-if></div></sc-if>')
    finished = '<sc-if value="{{done}}" hint-placeholder-val="{{false}}"><span style="font-size: 14px">All 1,180 memos were imported and checked. See the report.</span></sc-if>'
    imp = box('Import', '', start_ready + running + finished)
    badge = lambda t: chip(t, 'ok')
    line = lambda t: f'<li style="padding: 8px 12px; border-top: 1px solid var(--hover); font-size: 13px; font-variant-numeric: tabular-nums">{t}</li>'
    parts = ''.join(f'<li style="display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-top: 1px solid var(--hover)"><span style="flex: 1; font-size: 14px">{p}</span><button type="button" class="ghost" style="{OUTLINE}">{svg(I["download"], 14)}Download</button></li>'
                    for p in ['Summary (PDF)', 'Details (XLSX)', 'Manifest (SHA-256)', 'Workbook used'])
    report = box('Reconciliation report', '', '<sc-if value="{{done}}" hint-placeholder-val="{{true}}">'
                 + f'<div style="display: flex; flex-wrap: wrap; gap: 8px">{badge("Balanced")}{badge("Complete")}{badge("Valid")}</div>'
                 + f'<ul style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden">'
                 + line('Rows read 1,204 = imported 1,175 + rejected 24 + skipped 0 + failed 5 + not started 0').replace('border-top: 1px solid var(--hover)', 'border-top: 0', 1)
                 + line('Originals registered 2,907 = imported 2,880 + held back 12 + not used 12 + refused 3 + never sent 0')
                 + line('Previews (PDF): 2,861 done · 7 not possible · 12 not needed · 0 failed · 0 pending') + line('Fixity: 2,880 originals re-checked · 0 mismatches') + '</ul>'
                 + f'<ul style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden">{parts.replace("border-top: 1px solid var(--hover)", "border-top: 0", 1)}</ul>'
                 + f'<div style="display: flex; justify-content: flex-end"><button type="button" class="ghost" onClick="{{{{openClose}}}}" style="{obtn(phone)}">Close batch</button></div></sc-if>'
                 + '<sc-if value="{{importingNow}}" hint-placeholder-val="{{false}}"><span style="font-size: 13px; color: var(--muted-fg)">The report is made when the previews are done.</span></sc-if>'
                 + '<sc-if value="{{ready}}" hint-placeholder-val="{{false}}"><span style="font-size: 13px; color: var(--muted-fg)">Nothing has been imported yet.</span></sc-if>')
    stepper = ('<ol aria-label="Steps of the import" style="list-style: none; margin: 0; padding: 0; display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none' + ('; margin: 0 -16px; padding: 0 16px' if phone else '') + '"><sc-for list="{{steps}}" as="s" hint-placeholder-count="6"><li style="flex-shrink: 0">'
               f'<button type="button" onClick="{{{{s.pick}}}}" aria-current="{{{{s.aria}}}}" aria-label="{{{{s.srLabel}}}}" style="{{{{s.style}}}}"><span style="{{{{s.dotStyle}}}}"><sc-if value="{{{{s.done}}}}" hint-placeholder-val="{{{{true}}}}">{svg(I["check"], 12)}</sc-if><sc-if value="{{{{s.notDone}}}}" hint-placeholder-val="{{{{false}}}}">{{{{s.n}}}}</sc-if></span>{{{{s.label}}}}</button></li></sc-for></ol>')
    panes = (f'<sc-if value="{{{{sMetadata}}}}" hint-placeholder-val="{{{{false}}}}">{metadata}</sc-if><sc-if value="{{{{sOriginals}}}}" hint-placeholder-val="{{{{false}}}}">{originals}</sc-if>'
             f'<sc-if value="{{{{sMappings}}}}" hint-placeholder-val="{{{{false}}}}">{mappings}</sc-if><sc-if value="{{{{sDryRun}}}}" hint-placeholder-val="{{{{false}}}}">{dry}</sc-if>'
             f'<sc-if value="{{{{sImport}}}}" hint-placeholder-val="{{{{true}}}}">{imp}</sc-if><sc-if value="{{{{sReport}}}}" hint-placeholder-val="{{{{false}}}}">{report}</sc-if>')
    actions = (f'<button type="button" class="ghost" style="{OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">Settings</button>'
               f'<sc-if value="{{{{canAbandon}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" class="ghost" onClick="{{{{openAbandon}}}}" style="{OUTLINE.replace("color: var(--fg)", "color: var(--danger-fg)").replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">Abandon batch</button></sc-if>')
    title = f'<div style="display: flex; flex-wrap: wrap; align-items: center; gap: 10px"><h1 style="margin: 0; font-size: {"18px" if phone else "20px"}; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">Credit memos 2019–2023</h1><span style="{MONO}; font-size: 12px; color: var(--text-2)">LEGACY-DMS</span><span style="{{{{stateStyle}}}}">{{{{stateLabel}}}}</span></div>'
    if phone:
        return f'  <main class="ed-main" style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 16px 16px 24px">\n    {title}\n    <div style="display: flex; flex-wrap: wrap; gap: 8px">{actions}</div>\n    {stepper}\n    {panes}\n  </main>\n'
    return wrap_desk(f'      <a href="Imports.dc.html" style="align-self: flex-start; display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--muted-fg); text-decoration: none">{svg(I["back"], 14)}Back to Import</a>\n'
                     f'      <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: -8px">{title}<div style="display: flex; gap: 8px">{actions}</div></div>\n      {stepper}\n      {panes}')
def batch_dialogs(phone):
    reason = (f'        <sc-if value="{{{{cf.needsReason}}}}" hint-placeholder-val="{{{{false}}}}"><div style="display: flex; flex-direction: column; gap: 6px"><label for="cf-reason" style="font-size: 14px; font-weight: 500">Reason <span aria-hidden="true" style="color: var(--danger-fg)">*</span></label>'
              f'<textarea id="cf-reason" rows="2" onChange="{{{{cf.on_reason}}}}" aria-invalid="{{{{cf.reasonInvalid}}}}" style="width: 100%; padding: 8px 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}; resize: vertical; outline: none; border: 1px solid {{{{cf.reasonBorder}}}}">{{{{cf.reason}}}}</textarea>'
              '<sc-if value="{{cf.reasonHasErr}}" hint-placeholder-val="{{false}}"><span style="font-size: 12px; color: var(--danger-fg)">{{cf.reasonErr}}</span></sc-if></div></sc-if>')
    lines = '        <sc-if value="{{cf.hasLines}}" hint-placeholder-val="{{false}}"><ul style="margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-2)"><sc-for list="{{cf.lines}}" as="ln" hint-placeholder-count="3"><li>{{ln.t}}</li></sc-for></ul></sc-if>'
    body = '\n'.join([heading('cf-title', '{{cf.title}}', '{{cf.explain}}'), lines, reason, alert('cf'), buttons('{{cf.confirm}}', '{{cf.btnStyle}}', phone, '{{cf.cancel}}')])
    return f'''
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {frame(body, 'cf-title', 'alertdialog', 'cf.submit', phone)}
  </sc-if>
''' + toast(phone)

# ---------------------------------------------------------------- empty states
_i_empty = lambda ph: empty_state('No import batches yet.', 'Download the template and fill it in from the earlier system. Then start a batch: nothing is imported until you have checked a dry run.', btn_outline('Download template', 'template', ph) + btn_primary('New batch', 'openNew', ph), ph)
_a_empty = lambda ph: empty_state('Nothing in this period.', 'Try a longer period, or clear the filters.', btn_outline('Clear filters', 'clearFilters', ph), ph)
IMP_JS += empty_js('batches'); IMP_PROPS = '"empty":{"editor":"boolean","default":false},' + IMP_PROPS
AUDIT_JS += empty_js('rows') + "    vals.clearFilters = () => this.setState({ toast: 'Filters cleared.' });\n"; AUDIT_PROPS = '"empty":{"editor":"boolean","default":false},' + AUDIT_PROPS

# ================================================================ assemble
def build(name, title, desk, phone, dialogs, js, props, h, back=None):
    js = JS_HEAD + js + "    vals.noop = (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); };\n"
    pre = with_ro(desk_pre(title, 'Overview', 'AdminOverview.dc.html'))
    (proj / f'{name}.dc.html').write_text(pre + desk + dialogs(False) + '</div>\n</x-dc>\n' + script(props, 1440, h, DESK_HEAD_JS + BASE_JS + js + DESK_SHELL_JS))
    if back:
        pp = (proj / 'PositionPhone.dc.html').read_text()
        hd = with_ro(re.sub(r'<title>[^<]*</title>', f'<title>{title} on a phone</title>', pp[:pp.index('  <header')]))
        topbar = (f'  <header style="flex-shrink: 0; height: 56px; display: flex; align-items: center; gap: 4px; padding: 0 8px; border-bottom: 1px solid var(--border); box-sizing: border-box">\n'
                  f'    <a href="{back[0]}" class="ghost" aria-label="Back to {back[1].lower()}" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border-radius: 8px; color: var(--fg)">{svg(I["back"], 20)}</a>\n'
                  f'    <span style="flex: 1; min-width: 0; font-size: 15px; font-weight: 500">{back[1]}</span>\n  </header>\n')
        pjs = "    const st = this.state || {};\n    const PHONE = true;\n" + BASE_JS + js + "    return Object.assign(vals, { themeClass: st.theme || this.props.theme || 'system' });\n"
        (proj / f'{name}Phone.dc.html').write_text(hd + topbar + phone + dialogs(True) + '</div>\n</x-dc>\n' + script(props, 390, 844, pjs))
    else:
        hd, drawer = phone_parts(f'{title} on a phone', 'Overview', 'AdminOverviewPhone.dc.html')
        (proj / f'{name}Phone.dc.html').write_text(with_ro(hd) + phone + dialogs(True) + drawer + script(props, 390, 844, PHONE_HEAD_JS + BASE_JS + js + PHONE_SHELL_JS))

build('AdminOverview', 'Administration', ov_badges(overview(False)), ov_badges(overview(True)), toast, OV_JS, OV_PROPS, 1000)
build('Audit', 'Audit', with_empty(audit(False), _a_empty(False)), with_empty(audit(True), _a_empty(True), True), audit_dialogs, AUDIT_JS, AUDIT_PROPS, 1000)
build('Imports', 'Import legacy memos', with_empty(imports(False), _i_empty(False)), with_empty(imports(True), _i_empty(True), True), imp_dialogs, IMP_JS, IMP_PROPS, 1000)
build('ImportBatch', 'Credit memos 2019–2023 · Import', batch(False), batch(True), batch_dialogs, BATCH_JS, BATCH_PROPS, 1000, ('ImportsPhone.dc.html', 'Import'))

# Every other sidebar's Administration › Overview links here (idempotent)
for p in sorted(proj.glob('*.dc.html')):
    s = p.read_text()
    s2 = s.replace('<a href="#" class="nav-item" title="Overview" style=', '<a href="AdminOverview.dc.html" class="nav-item" title="Overview" style=')
    s2 = re.sub(r'<a href="#" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">((?:(?!</a>).)*?\n\s*Overview\n)', r'<a href="AdminOverviewPhone.dc.html" class="nav-item" style="\1">\2', s2, flags=re.S)
    if s2 != s: p.write_text(s2)

boards, notes = {}, {}
for n, y, t in [('AdminOverview', -1100, 'Administration overview (the grouped Administration menu)'), ('Audit', 26500, 'Audit'), ('Imports', 27860, 'Import legacy memos'), ('ImportBatch', 29220, 'Import batch (metadata, originals, mappings, dry run, import, report)')]:
    short = t.split(' (')[0]
    boards[n + '.dc.html'] = {'x': 0, 'y': y, 'w': 1440, 'h': 1000, 'title': short + ' — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'}
    boards[n + 'Phone.dc.html'] = {'x': 1520, 'y': y, 'w': 390, 'h': 844, 'title': short + ' — Phone', 'is_interactive': True, 'page': 'admin'}
    notes['rec' + n + 'Title'] = {'page': 'admin', 'x': 0, 'y': y - 260, 'text': t, 'kind': 'title1', 'maxW': 1910}
notes['recordsNote'] = {'page': 'admin', 'x': 2030, 'y': 26500, 'w': 620, 'fill': 'blue', 'size': 'm', 'text':
  'Records: Audit and Import\n\n'
  '• Audit and Import share a row of section links; the sidebar shows them under Overview until the menu changes (see the overview note).\n'
  '• Audit: the filters of the app (From, To, Action starts with, Object type) above the table; Details opens the before and after of one entry. Export asks first and warns that a filtered export cannot prove nothing is missing.\n'
  '• Import: batches with their state; one batch is a six-step page (Metadata, Originals, Mappings, Dry run, Import, Report) with done steps ticked. The “run” tweak shows a batch ready to import, importing, stopped, and completed with its reconciliation report.\n'
  '• Importing asks first and says plainly that imported memos are final and cannot be undone; Abandon and Close need a reason.\n'
  '• Phones: no folder sending (“Use a computer to send a folder.”); everything else can be followed.\n'
  '• API: nothing new.'}
notes['overviewNote'] = {'page': 'admin', 'x': 2030, 'y': -1100, 'w': 620, 'fill': 'blue', 'size': 'm', 'text':
  'Administration overview and the grouped menu\n\n'
  '• The overview is the full Administration menu: all 18 entries of app/navigation.ts in five groups (People and access, Memos, Workflows, Organisation-wide settings, Records), each with one line saying what it is for. People see only the entries their roles allow; the “viewer” tweak shows someone who can only reassign tasks.\n'
  '• Each section page has a row of links to its siblings, so the sidebar can stay short.\n'
  '• Proposal for the sidebar’s Administration group: Overview, People and access, Memos, Workflows, Open tasks, Settings, Records, each opening the first page of its section. The boards still show today’s six entries (Overview, Users, Memo types, Workflows, Open tasks, Security).\n'
  '• “Waiting for you” lists what needs an administrator now: tasks waiting for reassignment, versions to review, failed email, locked accounts. New: it reads existing lists (open tasks, memo types, workflows, mail log, users); no new API, but several calls.'}
canvas_add(boards, notes, done=('Admin overview', 'Audit log', 'Imports', 'Import batch'))
# All admin pages are designed: the to-do note says so
c = proj / 'canvas.json'; d = json.loads(c.read_text())
if 'adminTodo' in d['notes'] and 'still to design (0)' in d['notes']['adminTodo']['text']:
    d['notes']['adminTodo']['text'] = ('Admin pages: all designed\n\nEvery admin route of the app has a desktop and a phone board on this page. '
                                       'The grouped Administration menu is the overview at the top, with a proposal for the sidebar in its note.\n\nThe operator console is on the Operator page.')
    c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
