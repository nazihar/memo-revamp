"""Admin · Roles and Classification (desktop and phone).

Copy and rules from web/src/features/access/RolesPage.tsx, ClassificationsPage.tsx and access-messages.ts;
permission flags from backend common/authz/Permissions.kt; refusals from authz/internal/AccessAdmin.kt.
Builds Roles, RolesPhone, Classifications, ClassificationsPhone from the Open tasks shells; safe to rerun.
"""
from kit import *

CHIPS_LEGEND = ('<span style="display: inline-flex; align-items: center; height: 18px; padding: 0 6px; border-radius: 5px; background: var(--muted); color: var(--text-2); font-size: 11px; font-weight: 500">Admin</span>',
                '<span style="display: inline-flex; align-items: center; height: 18px; padding: 0 6px; border-radius: 5px; border: 1px solid var(--border); color: var(--text-2); font-size: 11px; font-weight: 500">Per unit</span>')
BADGE = '<span style="display: inline-flex; align-items: center; height: 20px; padding: 0 6px; border-radius: 6px; background: var(--muted); color: var(--text-2); font-size: 11px; font-weight: 500">Built-in</span>'

# ================================================================ Roles
ROLE_HEAD = '''        <div style="display: flex; flex-direction: column; gap: 6px">
          <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px"><h2 id="rd-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{{rd.title}}</h2><sc-if value="{{rd.readonly}}" hint-placeholder-val="{{false}}">''' + BADGE + '''</sc-if></div>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">{{rd.lead}}</p>
        </div>'''
def role_body(phone):
    perm_row = T('''                <label style="{{p.rowStyle}}">
                  <sc-if value="{{rd.editable}}" hint-placeholder-val="{{true}}"><input type="checkbox" checked="{{p.checked}}" onChange="{{p.toggle}}" style="margin: 2px 0 0; width: [[c]]px; height: [[c]]px; flex-shrink: 0; accent-color: var(--primary)"></sc-if>
                  <sc-if value="{{rd.readonly}}" hint-placeholder-val="{{false}}"><span style="flex-shrink: 0; margin-top: 2px; color: var(--ok-fg)">[[check]]</span></sc-if>
                  <span style="flex: 1; min-width: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px"><span>{{p.label}}</span><sc-if value="{{p.privileged}}" hint-placeholder-val="{{true}}">[[admin]]</sc-if><sc-if value="{{p.scopable}}" hint-placeholder-val="{{false}}">[[unit]]</sc-if></span>
                </label>''', c='18' if phone else '16', check=svg(I['check'], 16), admin=CHIPS_LEGEND[0], unit=CHIPS_LEGEND[1])
    return '\n'.join([
        '        <sc-if value="{{rd.editable}}" hint-placeholder-val="{{true}}">',
        text_field('rd', 'name', 'Name', 'text', phone),
        opt_field('rd', 'description', 'Description (optional)', phone, required=False),
        '        </sc-if>',
        '''        <sc-if value="{{rd.hasHolders}}" hint-placeholder-val="{{false}}"><div style="display: flex; gap: 8px; padding: 10px 12px; border: 1px solid var(--info-fg); border-color: color-mix(in srgb, var(--info-fg) 30%, transparent); border-radius: 8px; background: var(--info-bg); color: var(--fg); font-size: 13px">{{rd.holders}}</div></sc-if>''',
        T('''        <fieldset aria-describedby="rd-perm-msg" style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 10px">
          <legend style="display: contents"><span style="display: flex; align-items: baseline; justify-content: space-between; gap: 8px; width: 100%; font-size: 14px; font-weight: 500"><span>Permissions<sc-if value="{{rd.editable}}" hint-placeholder-val="{{true}}"> <span aria-hidden="true" style="color: var(--danger-fg)">*</span></sc-if></span><span style="margin-left: auto; font-size: 12px; font-weight: 400; color: var(--muted-fg)">{{rd.count}}</span></span></legend>
          <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; font-size: 12px; color: var(--muted-fg)"><span style="display: inline-flex; align-items: center; gap: 6px">[[admin]] Administrator permission: always needs two-step sign-in.</span><span style="display: inline-flex; align-items: center; gap: 6px">[[unit]] Can be limited to a unit.</span></span>
          <sc-if value="{{rd.permHasErr}}" hint-placeholder-val="{{false}}"><span id="rd-perm-msg" style="font-size: 12px; color: var(--danger-fg)">{{rd.permErr}}</span></sc-if>
          <sc-for list="{{rd.groups}}" as="g" hint-placeholder-count="5">
            <div style="display: flex; flex-direction: column; gap: 6px">
              <span style="font-size: 12px; font-weight: 600; color: var(--text-3)">{{g.label}}</span>
              <div style="border: 1px solid var(--border); border-radius: 10px; overflow: hidden">
                <sc-for list="{{g.perms}}" as="p" hint-placeholder-count="4">
[[row]]
                </sc-for>
              </div>
            </div>
          </sc-for>
        </fieldset>''', admin=CHIPS_LEGEND[0], unit=CHIPS_LEGEND[1], row=perm_row),
        alert('rd')])
def role_foot(phone):
    h = '44px' if phone else '36px'; fs = '15px' if phone else '14px'
    close = f'<button type="button" class="ghost" onClick="{{{{closeDlg}}}}" style="height: {h}; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: {fs}; font-weight: 500; cursor: pointer">Close</button>'
    return ('        <sc-if value="{{rd.editable}}" hint-placeholder-val="{{true}}">\n' + buttons('Save', primary(phone), phone) + '\n        </sc-if>\n'
            '        <sc-if value="{{rd.readonly}}" hint-placeholder-val="{{false}}"><div style="display: flex; flex-direction: column; align-items: ' + ('stretch' if phone else 'flex-end') + '">' + close + '</div></sc-if>')

def role_dialogs(phone):
    return f'''
  <sc-if value="{{{{isRole}}}}" hint-placeholder-val="{{{{false}}}}">
    {frame_scroll(ROLE_HEAD, role_body(phone), role_foot(phone), 'rd-title', 'dialog', 'rd.submit', phone)}
  </sc-if>
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm_dialog(phone)}
  </sc-if>
''' + toast(phone)

ROLES_DESK = T('''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      [[nav]]
[[head]]
      <div style="display: flex; flex-direction: column; gap: 8px">
        <div style="border: 1px solid var(--border); border-radius: 10px; overflow-x: auto; background: var(--card)">
          <table style="width: 100%; min-width: 860px; border-collapse: collapse; font-size: 14px">
            <caption style="[[sr]]">Roles of the organisation</caption>
            <thead>
              <tr style="border-bottom: 1px solid var(--border)">
                <th scope="col" style="height: 40px; [[th0]]; width: 300px">Role</th>
                <th scope="col" style="[[th]]">Permissions</th>
                <th scope="col" style="[[th]]; width: 110px">Grants</th>
                <th scope="col" style="width: 44px"><span style="[[sr]]">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <sc-for list="{{roles}}" as="r" hint-placeholder-count="8">
                <tr class="lrow" style="border-bottom: 1px solid var(--hover)">
                  <td style="padding: 10px 16px; vertical-align: top">
                    <span style="display: flex; flex-direction: column; gap: 2px">
                      <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><button type="button" onClick="{{r.open}}" style="padding: 0; border: 0; background: transparent; color: var(--fg); font-family: inherit; font-size: 14px; font-weight: 500; text-align: left; cursor: pointer">{{r.name}}</button><sc-if value="{{r.builtIn}}" hint-placeholder-val="{{false}}">[[badge]]</sc-if></span>
                      <sc-if value="{{r.hasDesc}}" hint-placeholder-val="{{true}}"><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{r.desc}}</span></sc-if>
                    </span>
                  </td>
                  <td style="padding: 10px 12px; vertical-align: top; color: var(--text-2)">
                    <span style="display: flex; flex-direction: column; gap: 2px"><span>{{r.summary}}</span><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{r.count}}</span></span>
                  </td>
                  <td style="padding: 10px 12px; vertical-align: top; white-space: nowrap; font-variant-numeric: tabular-nums; color: {{r.grantsColor}}">{{r.grants}}</td>
                  <td style="position: relative; padding: 6px 8px 0 0; vertical-align: top; text-align: right">
                    [[menuBtn]]
                    [[menu]]
                  </td>
                </tr>
              </sc-for>
            </tbody>
          </table>
        </div>
        <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px">[[foot]]</div>
      </div>
    </main>
  </div>
''', nav=section_nav('Roles', False), head=page_head('Roles', 'Roles are sets of permissions. Built-in roles are fixed; add your own for other combinations.', 'New role', 'openNew', False),
     sr=SR, th0=TH.replace('0 12px', '0 16px', 1), th=TH, badge=BADGE, menuBtn=MENU_BTN, menu=MENU, foot=FOOT_NOTE)

ROLES_PHONE = T('''  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
    [[nav]]
[[head]]
    <ul aria-label="Roles of the organisation" style="list-style: none; margin: 4px 0 0; padding: 0; border-top: 1px solid var(--hover)">
      <sc-for list="{{roles}}" as="r" hint-placeholder-count="8">
        <li style="display: flex; align-items: center; gap: 4px; padding: 10px 4px 10px 16px; border-bottom: 1px solid var(--hover)">
          <button type="button" onClick="{{r.open}}" style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; padding: 0; border: 0; background: transparent; color: var(--fg); font-family: inherit; text-align: left">
            <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-size: 15px; font-weight: 500">{{r.name}}</span><sc-if value="{{r.builtIn}}" hint-placeholder-val="{{false}}">[[badge]]</sc-if></span>
            <span style="font-size: 13px; color: var(--text-2); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden">{{r.summary}}</span>
            <span style="font-size: 12px; color: var(--muted-fg)">{{r.phoneMeta}}</span>
          </button>
          <sc-if value="{{r.custom}}" hint-placeholder-val="{{true}}"><button type="button" class="ghost" aria-label="{{r.menuLabel}}" aria-haspopup="dialog" onClick="{{r.toggleMenu}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--text-3)">[[dots]]</button></sc-if>
          <sc-if value="{{r.builtIn}}" hint-placeholder-val="{{false}}"><span aria-hidden="true" style="flex-shrink: 0; display: inline-flex; width: 44px; justify-content: center; color: var(--muted-fg)">[[chev]]</span></sc-if>
        </li>
      </sc-for>
    </ul>
    <p style="margin: 0; padding: 12px 16px 24px; font-size: 13px; color: var(--muted-fg)">{{footNote}}</p>
  </main>

[[sheet]]''', nav=section_nav('Roles', True), head=page_head('Roles', 'Built-in roles are fixed; add your own for other combinations.', 'New role', 'openNew', True),
     badge=BADGE, dots=DOTS18, chev=svg(I['right']), sheet=SHEET)

ROLES_JS = r"""
    const P = (key, label, group, privileged, scopable) => ({ key: key, label: label, group: group, privileged: privileged, scopable: scopable });
    const PERMS = [
      P('directory.read', 'See the org directory', 'Organisation', false, false),
      P('org.manage', 'Manage units, positions and assignments', 'Organisation', true, true),
      P('org.legal_entities.manage', 'Manage legal entities', 'Organisation', true, false),
      P('identity.users.manage', 'Manage users', 'People and access', true, false),
      P('authz.roles.manage', 'Manage roles and grants', 'People and access', true, false),
      P('authz.classifications.manage', 'Manage classification and clearances', 'People and access', true, false),
      P('memo.create', 'Write and submit memos', 'Memos', false, false),
      P('memo.types.manage', 'Manage memo types', 'Memos', true, false),
      P('memo.types.approve', 'Review and publish memo types', 'Memos', true, false),
      P('memo.read.unit', 'Read the memos of a unit', 'Memos', true, true),
      P('memo.share.manage', 'Revoke any share of a memo', 'Memos', true, false),
      P('memo.copy.add', 'Copy approved memos to more people', 'Memos', true, false),
      P('memo.distribution.manage', 'Manage distribution lists', 'Memos', true, false),
      P('memo.rendition.regenerate', 'Make a new version of a final record', 'Memos', true, false),
      P('memo.import', 'Import memos from a legacy system', 'Memos', true, false),
      P('memo.dashboard.read', 'See the approvals dashboard of a unit', 'Memos', false, true),
      P('workflow.tasks.reassign', 'Reassign approval tasks', 'Workflows', true, false),
      P('inbox.team.read', 'See and reassign the open tasks of a team', 'Workflows', true, true),
      P('workflow.definitions.manage', 'Write workflow definitions and submit them for review', 'Workflows', true, false),
      P('workflow.definitions.approve', 'Review and publish workflow definitions', 'Workflows', true, false),
      P('workflow.instances.cancel', 'Cancel running workflows', 'Workflows', true, false),
      P('workflow.queues.manage', 'Manage workflow queues', 'Workflows', true, false),
      P('workflow.doa.manage', 'Draft the delegation of authority and submit it for review', 'Workflows', true, false),
      P('workflow.doa.approve', 'Review and publish the delegation of authority', 'Workflows', true, false),
      P('workflow.calendar.manage', 'Set the business calendar for deadlines', 'Workflows', true, false),
      P('audit.read', 'Read the audit history', 'Records and settings', true, false),
      P('notification.settings.manage', 'Manage email delivery', 'Records and settings', true, false),
      P('tenant.settings.manage', 'Manage organisation settings', 'Records and settings', true, false)
    ];
    const GROUPS = ['Organisation', 'People and access', 'Memos', 'Workflows', 'Records and settings'];
    const ON_PURPOSE = ['memo.read.unit', 'memo.share.manage', 'memo.copy.add', 'memo.rendition.regenerate', 'inbox.team.read', 'memo.dashboard.read', 'memo.import'];
    const R = (id, name, builtIn, desc, perms, grants) => ({ id: id, name: name, builtIn: builtIn, desc: desc, perms: perms, grants: grants });
    const ROLES = [
      R('admin', 'Organisation administrator', true, '', PERMS.map((p) => p.key).filter((k) => ON_PURPOSE.indexOf(k) < 0), 2),
      R('orgadmin', 'Org directory administrator', true, '', ['directory.read', 'org.manage'], 1),
      R('auditor', 'Auditor', true, '', ['directory.read', 'audit.read'], 3),
      R('user', 'User', true, '', ['directory.read', 'memo.create'], -1),
      R('dash', 'Dashboard viewer', false, 'Unit heads who follow approval times.', ['memo.dashboard.read'], 6),
      R('team', 'Team lead view', false, 'Sees and reassigns the open tasks of a team.', ['inbox.team.read', 'memo.dashboard.read'], 4),
      R('records', 'Records office', false, 'Copies approved memos and keeps the distribution lists.', ['memo.copy.add', 'memo.distribution.manage', 'memo.rendition.regenerate'], 3),
      R('types', 'Memo type editors', false, 'Draft memo types and send them for review.', ['memo.types.manage'], 0)
    ];
    const label = (k) => (PERMS.find((p) => p.key === k) || { label: k }).label;
    const summary = (r) => r.id === 'admin' ? 'Everything, except the permissions given on purpose (reading a unit’s memos, dashboards, imports and similar)'
      : r.perms.slice(0, 2).map(label).join(', ') + (r.perms.length > 2 ? ' and ' + (r.perms.length - 2) + ' more' : '');
    const grantsText = (r) => r.grants < 0 ? 'Everyone' : String(r.grants);
    const target = ROLES.find((r) => r.id === (st.target || ({ edit: 'dash', view: 'auditor', remove: err === 'inUse' ? 'dash' : 'types' })[dlgProp] || 'dash')) || ROLES[4];
    const openRole = (r) => this.setState({ dlg: r.builtIn ? 'view' : 'edit', target: r.id, err: 'none', form: null, menu: '' });
    const itemsFor = (r) => r.builtIn ? [item('View permissions', () => openRole(r))]
      : [item('Edit', () => openRole(r)), item('Remove', () => this.setState({ dlg: 'remove', target: r.id, err: 'none', menu: '' }), true)];
    const roles = ROLES.map((r) => { const open = st.menu === r.id; return {
      name: r.name, builtIn: r.builtIn, custom: !r.builtIn, desc: r.desc, hasDesc: !!r.desc, summary: summary(r),
      count: r.perms.length === 1 ? '1 permission' : r.perms.length + ' permissions', grants: grantsText(r), grantsColor: r.grants === 0 ? 'var(--muted-fg)' : 'var(--fg)',
      phoneMeta: (r.perms.length === 1 ? '1 permission' : r.perms.length + ' permissions') + ' · ' + (r.grants < 0 ? 'everyone' : r.grants === 1 ? '1 grant' : r.grants + ' grants'),
      open: () => openRole(r), items: itemsFor(r), menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + r.name,
      toggleMenu: () => this.setState({ menu: open ? '' : r.id }) }; });
    const sheetRole = PHONE ? ROLES.find((r) => r.id === st.menu) : null;
    // Role dialog: new, edit, or view (built-in)
    const isNew = dlg === 'new', isView = dlg === 'view';
    const startPerms = isNew ? (err === 'required' ? [] : ['memo.read.unit', 'memo.dashboard.read']) : target.perms;
    const chosen = form.perms || startPerms;
    const name = form.name !== undefined ? form.name : (isNew ? (err === 'required' ? '' : (err === 'duplicateName' ? 'Dashboard viewer' : 'Credit portfolio readers')) : target.name);
    const description = form.description !== undefined ? form.description : (isNew ? '' : target.desc);
    const tried = !!form.tried || err === 'required';
    const nameErr = tried && !name.trim() ? 'Required.' : (err === 'duplicateName' ? 'Another one has this name.' : '');
    const permErr = tried && chosen.length === 0 ? 'Choose at least one permission.' : '';
    const RD_ALERTS = { inUse: 'It is still in use. People who hold this role for a unit need a permission that can be limited to a unit.', changed: 'Someone changed this meanwhile. It was reloaded.', failed: 'That did not work. Try again.' };
    const rdAlert = RD_ALERTS[err] || '';
    const toggle = (k) => () => { const has = chosen.indexOf(k) >= 0; this.setState({ form: Object.assign({}, form, { name: name, description: description, perms: has ? chosen.filter((x) => x !== k) : chosen.concat([k]) }), err: 'none' }); };
    const shown = isView ? PERMS.filter((p) => target.perms.indexOf(p.key) >= 0) : PERMS;
    const rowStyle = (i) => 'display: flex; align-items: flex-start; gap: 10px; padding: ' + (PHONE ? '12px' : '8px 12px') + '; font-size: 14px; line-height: 20px; cursor: ' + (isView ? 'default' : 'pointer') + (i > 0 ? '; border-top: 1px solid var(--hover)' : '');
    const groups = GROUPS.map((g) => ({ label: g, perms: shown.filter((p) => p.group === g).map((p, i) => ({ label: p.label, privileged: p.privileged, scopable: p.scopable, checked: chosen.indexOf(p.key) >= 0, toggle: toggle(p.key), rowStyle: rowStyle(i) })) })).filter((g) => g.perms.length > 0);
    const vals = Object.assign(base, {
      roles: roles, footNote: 'Built-in roles cannot change. Changes here ask you to confirm it is you.',
      openNew: () => this.setState({ dlg: 'new', form: {}, err: 'none' }),
      sheetOpen: !!sheetRole, sheet: sheetRole ? { name: sheetRole.name, items: itemsFor(sheetRole) } : { name: '', items: [] },
      isRole: dlg === 'new' || dlg === 'edit' || dlg === 'view', isConfirm: dlg === 'remove',
      rd: Object.assign({
        title: isNew ? 'New role' : (isView ? target.name : 'Edit role'),
        lead: isView ? 'Built-in roles cannot change. To give a different combination, add your own role.' : 'A set of permissions you can give to people, for the whole organisation or one unit.',
        readonly: isView, editable: !isView,
        hasHolders: dlg === 'edit' && target.grants > 0, holders: 'Changes apply at once to everyone who holds this role (' + target.grants + ' grants).',
        count: isView ? (target.perms.length + ' permissions') : (chosen.length + ' of ' + PERMS.length + ' chosen'),
        permHasErr: !!permErr, permErr: permErr, groups: groups, hasAlert: !!rdAlert, alert: rdAlert,
        on_name: setForm('name'), on_description: setForm('description'), description: description,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (isView) { closeDlg(); return; }
          if (!name.trim() || !chosen.length || err === 'duplicateName') { this.setState({ form: Object.assign({}, form, { name: name, description: description, perms: chosen, tried: true }) }); return; }
          if (rdAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Saved.' }); }
      }, fieldVals('rd', 'name', name, nameErr)),
      cf: (() => { const a = ({ inUse: 'It is still in use. Revoke it from everyone who holds it first.', changed: 'Someone changed this meanwhile. It was reloaded.', failed: 'That did not work. Try again.' })[err] || '';
        return { title: 'Remove this role?', explain: 'Only roles nobody holds can be removed.', confirm: 'Remove', cancel: 'Keep', btnStyle: okStyle(true),
          hasDetail: true, detail: target.name, detailSub: target.grants > 0 ? 'Held: ' + target.grants + ' grants' : 'Nobody holds it', hasAlert: !!a, alert: a,
          submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (a) { this.setState({ err: err }); return; } this.setState({ dlg: '', toast: 'Saved.' }); } }; })()
    });
"""
ROLES_PROPS = '"dialog":{"editor":"enum","options":["none","new","edit","view","remove"],"default":"none"},"error":{"editor":"enum","options":["none","required","duplicateName","inUse","changed","failed"],"default":"none"},'

# ================================================================ Classification
def level_dialogs(phone):
    lvl = frame('\n'.join([heading('lv-title', '{{lv.title}}', '{{lv.lead}}'),
        text_field('lv', 'name', 'Name', 'text', phone),
        opt_field('lv', 'description', 'Description (optional)', phone, required=False, hint='Shown to people choosing a classification for a memo.'),
        alert('lv'), buttons('Save', primary(phone), phone)]), 'lv-title', 'dialog', 'lv.submit', phone)
    return f'''
  <sc-if value="{{{{isLevel}}}}" hint-placeholder-val="{{{{false}}}}">
    {lvl}
  </sc-if>
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm_dialog(phone)}
  </sc-if>
''' + toast(phone)

TAG = '<sc-if value="{{r.hasTag}}" hint-placeholder-val="{{false}}"><span style="display: inline-flex; align-items: center; height: 20px; padding: 0 6px; border-radius: 6px; border: 1px solid var(--border); color: var(--text-2); font-size: 11px; font-weight: 500; white-space: nowrap">{{r.tag}}</span></sc-if>'
ORDER = '<span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 6px; background: var(--muted); color: var(--text-2); font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums">{{r.order}}</span>'
def move_btn(k, icon, phone):
    s = 44 if phone else 28
    return f'<button type="button" class="ghost" aria-label="{{{{r.{k}Label}}}}" disabled="{{{{r.{k}Disabled}}}}" onClick="{{{{r.{k}}}}}" style="display: inline-flex; align-items: center; justify-content: center; width: {s}px; height: {s}px; border: 0; border-radius: 6px; background: transparent; color: {{{{r.{k}Color}}}}; cursor: pointer">{svg(I[icon])}</button>'

LEVELS_DESK = T('''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      [[nav]]
[[head]]
      <div style="display: flex; flex-direction: column; gap: 8px">
        <div style="border: 1px solid var(--border); border-radius: 10px; overflow-x: auto; background: var(--card)">
          <table style="width: 100%; min-width: 720px; border-collapse: collapse; font-size: 14px">
            <caption style="[[sr]]">Classification levels, least sensitive first</caption>
            <thead>
              <tr style="border-bottom: 1px solid var(--border)">
                <th scope="col" style="height: 40px; [[th0]]; width: 72px">Order</th>
                <th scope="col" style="[[th]]">Level</th>
                <th scope="col" style="[[th]]; width: 104px; text-align: right"><span style="[[sr]]">Move</span></th>
                <th scope="col" style="width: 44px"><span style="[[sr]]">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <sc-for list="{{levels}}" as="r" hint-placeholder-count="4">
                <tr class="lrow" style="{{r.trStyle}}">
                  <td style="padding: 10px 16px; vertical-align: middle">[[order]]</td>
                  <td style="padding: 10px 12px; vertical-align: middle">
                    <span style="display: flex; flex-direction: column; gap: 2px"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px"><span style="font-weight: 500">{{r.name}}</span>[[tag]]</span><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{r.desc}}</span></span>
                  </td>
                  <td style="padding: 0 4px 0 12px; vertical-align: middle; text-align: right; white-space: nowrap">[[up]][[down]]</td>
                  <td style="position: relative; padding: 0 8px 0 0; vertical-align: middle; text-align: right">
                    [[menuBtn]]
                    [[menu]]
                  </td>
                </tr>
              </sc-for>
            </tbody>
          </table>
        </div>
        <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px">[[foot]]</div>
      </div>
    </main>
  </div>
''', nav=section_nav('Classification', False), head=page_head('Classification', 'Levels from least to most sensitive. People open documents up to their clearance.', 'Add level', 'openNew', False),
     sr=SR, th0=TH.replace('0 12px', '0 16px', 1), th=TH, order=ORDER, tag=TAG, up=move_btn('up', 'up', False), down=move_btn('down', 'down', False), menuBtn=MENU_BTN, menu=MENU, foot=FOOT_NOTE)

LEVELS_PHONE = T('''  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
    [[nav]]
[[head]]
    <ol aria-label="Classification levels, least sensitive first" style="list-style: none; margin: 4px 0 0; padding: 0; border-top: 1px solid var(--hover)">
      <sc-for list="{{levels}}" as="r" hint-placeholder-count="4">
        <li style="{{r.liStyle}}">
          [[order]]
          <span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px">
            <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-size: 15px; font-weight: 500">{{r.name}}</span>[[tag]]</span>
            <span style="font-size: 13px; color: var(--muted-fg)">{{r.desc}}</span>
          </span>
          <button type="button" class="ghost" aria-label="{{r.menuLabel}}" aria-haspopup="dialog" onClick="{{r.toggleMenu}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--text-3)">[[dots]]</button>
        </li>
      </sc-for>
    </ol>
    <p style="margin: 0; padding: 12px 16px 24px; font-size: 13px; color: var(--muted-fg)">{{footNote}}</p>
  </main>

[[sheet]]''', nav=section_nav('Classification', True), head=page_head('Classification', 'Least sensitive first. People open documents up to their clearance.', 'Add', 'openNew', True),
     order=ORDER.replace('flex;', 'flex; flex-shrink: 0;', 1), tag=TAG, dots=DOTS18, sheet=SHEET)

LEVELS_JS = r"""
    const LV = {
      internal: { name: 'Internal', desc: 'Everyday memos inside the organisation.' },
      restricted: { name: 'Restricted', desc: 'Memos for the units and people named on them.' },
      confidential: { name: 'Confidential', desc: 'Credit, staff and legal matters.' },
      strict: { name: 'Strictly confidential', desc: 'Board and regulator matters.' }
    };
    const order = st.order || ['internal', 'restricted', 'confidential', 'strict'];
    const n = order.length;
    const move = (i, by) => { const o = order.slice(); const x = o.splice(i, 1)[0]; o.splice(i + by, 0, x); this.setState({ order: o, menu: '', moved: x, toast: 'Saved.' }); };
    const tid = st.target || (dlgProp === 'remove' ? (err === 'lastLevel' ? 'internal' : 'restricted') : 'restricted');
    const target = LV[tid] || LV.restricted;
    const itemsFor = (id, i) => [].concat(i > 0 ? [item('Make less sensitive', () => move(i, -1))] : [], i < n - 1 ? [item('Make more sensitive', () => move(i, 1))] : [],
      [item('Rename', () => this.setState({ dlg: 'rename', target: id, err: 'none', form: null, menu: '' })), item('Remove', () => this.setState({ dlg: 'remove', target: id, err: 'none', menu: '' }), true)]);
    const levels = order.map((id, i) => { const l = LV[id], open = st.menu === id; return {
      order: String(i + 1), name: l.name, desc: l.desc, hasTag: i === 0 || i === n - 1, tag: i === 0 ? 'Least sensitive' : 'Most sensitive',
      up: () => move(i, -1), upDisabled: i === 0, upLabel: 'Make ' + l.name + ' less sensitive', upColor: i === 0 ? 'var(--chip)' : 'var(--text-3)',
      down: () => move(i, 1), downDisabled: i === n - 1, downLabel: 'Make ' + l.name + ' more sensitive', downColor: i === n - 1 ? 'var(--chip)' : 'var(--text-3)',
      trStyle: 'border-bottom: 1px solid var(--hover)' + (st.moved === id ? '; background: var(--subtle)' : ''),
      liStyle: 'display: flex; align-items: center; gap: 12px; padding: 10px 4px 10px 16px; border-bottom: 1px solid var(--hover)' + (st.moved === id ? '; background: var(--subtle)' : ''),
      items: itemsFor(id, i).filter((x) => PHONE || (x.label !== 'Make less sensitive' && x.label !== 'Make more sensitive')),
      menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + l.name, toggleMenu: () => this.setState({ menu: open ? '' : id }) }; });
    const sheetIdx = PHONE ? order.indexOf(st.menu) : -1;
    const isAdd = dlg === 'add';
    const name = form.name !== undefined ? form.name : (isAdd ? (err === 'required' ? '' : (err === 'duplicateName' ? 'Confidential' : 'Board only')) : target.name);
    const description = form.description !== undefined ? form.description : (isAdd ? '' : target.desc);
    const tried = !!form.tried || err === 'required';
    const nameErr = tried && !name.trim() ? 'Required.' : (err === 'duplicateName' ? 'Another one has this name.' : '');
    const lvAlert = ({ tooMany: 'An organisation has at most 20 levels.', changed: 'Someone changed this meanwhile. It was reloaded.', failed: 'That did not work. Try again.' })[err] || '';
    const cfAlert = ({ inUse: 'It is still in use. Some people are cleared to this level: change their clearance first.', lastLevel: 'The organisation needs at least one level.', changed: 'Someone changed this meanwhile. It was reloaded.', failed: 'That did not work. Try again.' })[err] || '';
    const vals = Object.assign(base, {
      levels: levels, footNote: 'Moving a level changes at once what people cleared to it can open. Changes here ask you to confirm it is you.',
      openNew: () => this.setState({ dlg: 'add', form: {}, err: 'none' }),
      sheetOpen: sheetIdx >= 0, sheet: sheetIdx >= 0 ? { name: LV[order[sheetIdx]].name, items: itemsFor(order[sheetIdx], sheetIdx) } : { name: '', items: [] },
      isLevel: dlg === 'add' || dlg === 'rename', isConfirm: dlg === 'remove',
      lv: Object.assign({
        title: isAdd ? 'Add level' : 'Rename level',
        lead: isAdd ? 'It goes in as the most sensitive level. Move it down the list afterwards if it belongs lower.' : 'People keep their clearance; only the name and description change.',
        description: description, on_name: setForm('name'), on_description: setForm('description'), hasAlert: !!lvAlert, alert: lvAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault();
          if (!name.trim() || err === 'duplicateName') { this.setState({ form: Object.assign({}, form, { name: name, description: description, tried: true }) }); return; }
          if (lvAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Saved.' }); }
      }, fieldVals('lv', 'name', name, nameErr)),
      cf: { title: 'Remove this level?', explain: 'Only levels nobody is cleared to can be removed.', confirm: 'Remove', cancel: 'Keep', btnStyle: okStyle(true),
        hasDetail: true, detail: target.name, detailSub: target.desc, hasAlert: !!cfAlert, alert: cfAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (cfAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', toast: 'Saved.' }); } }
    });
"""
LEVELS_PROPS = '"dialog":{"editor":"enum","options":["none","add","rename","remove"],"default":"none"},"error":{"editor":"enum","options":["none","required","duplicateName","tooMany","inUse","lastLevel","changed","failed"],"default":"none"},'

# ================================================================ assemble
for name, title, desk, dialogs, js, props in [('Roles', 'Roles', ROLES_DESK, role_dialogs, ROLES_JS, ROLES_PROPS),
                                              ('Classifications', 'Classification', LEVELS_DESK, level_dialogs, LEVELS_JS, LEVELS_PROPS)]:
    pre = desk_pre(title)
    (proj / f'{name}.dc.html').write_text(pre + desk + dialogs(False) + '</div>\n</x-dc>\n' + script(props, 1440, 1000, DESK_HEAD_JS + BASE_JS + js + DESK_SHELL_JS))
    head, drawer = phone_parts(f'{title} on a phone')
    body = ROLES_PHONE if name == 'Roles' else LEVELS_PHONE
    (proj / f'{name}Phone.dc.html').write_text(head + body + dialogs(True) + drawer + script(props, 390, 844, PHONE_HEAD_JS + BASE_JS + js + PHONE_SHELL_JS))

canvas_add({
    'Roles.dc.html': {'x': 0, 'y': 4340, 'w': 1440, 'h': 1000, 'title': 'Roles — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'},
    'RolesPhone.dc.html': {'x': 1520, 'y': 4340, 'w': 390, 'h': 844, 'title': 'Roles — Phone', 'is_interactive': True, 'page': 'admin'},
    'Classifications.dc.html': {'x': 0, 'y': 5700, 'w': 1440, 'h': 1000, 'title': 'Classification — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'},
    'ClassificationsPhone.dc.html': {'x': 1520, 'y': 5700, 'w': 390, 'h': 844, 'title': 'Classification — Phone', 'is_interactive': True, 'page': 'admin'},
}, {
    'rolesTitle': {'page': 'admin', 'x': 0, 'y': 4080, 'text': 'Roles', 'kind': 'title1', 'maxW': 1910},
    'classificationsTitle': {'page': 'admin', 'x': 0, 'y': 5440, 'text': 'Classification', 'kind': 'title1', 'maxW': 1910},
    'accessNote': {'page': 'admin', 'x': 2030, 'y': 4340, 'w': 620, 'fill': 'blue', 'size': 'm', 'text':
      'People and access: Users, Roles, Classification\n\n'
      '• The three pages share a row of links under the top bar; the sidebar’s Users entry opens the section until the full Administration menu exists.\n'
      '• Roles: open a role to see its permissions (built-in roles are read-only). New and Edit list every permission in five groups, with the Admin (needs two-step sign-in) and Per unit tags. Remove only works for roles nobody holds.\n'
      '• Editing a role that people hold says that changes apply to them at once.\n'
      '• Classification: up and down move a level one place; the arrows point where the row goes (the app’s arrows point the other way today). A new level goes in as the most sensitive, as the API does.\n'
      '• Refusals show inside the dialog: name taken, still in use, last level, at most 20 levels, changed meanwhile. Every change asks for step-up first.\n'
      '• API: tenant.settings.manage has no label in the web catalogue yet (shown here as “Manage organisation settings”). Nothing else new.'},
}, done=('Roles', 'Classifications'))
print('ok')
