"""Admin · Workflows: list, builder (one definition), Delegation of authority, Queues, Substitutions, Business calendar.

Copy from web/src/features/workflows (workflow-messages.ts, doa-messages.ts, builder/builder-messages.ts and the
builder panels, QueuesPage, SubstitutionsPage, CalendarPage). Builds Workflows, WorkflowBuilder, Doa, Queues,
Substitutions, BusinessCalendar and their phone boards from the Open tasks shells; safe to rerun.
Pieces live in wf_parts.py (shared) and wf_builder.py (builder and delegation of authority).
"""
from wf_parts import *
from wf_builder import BUILDER_DESK, BUILDER_PHONE, builder_dialogs, BUILDER_JS, BUILDER_PROPS, DOA_DESK, DOA_PHONE, doa_dialogs, DOA_JS, DOA_PROPS

SEARCH = lambda ph: (f'<label style="position: relative; display: flex; align-items: center"><span style="{SR}">{ph}</span>{svg(I["search"], 14, " style=\"color: var(--muted-fg); position: absolute; left: 10px\"")}'
                     f'<input type="search" placeholder="{ph}" value="{{{{q}}}}" onChange="{{{{onQ}}}}" style="width: 240px; height: 32px; padding: 0 10px 0 30px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; font-family: inherit; font-size: 14px; color: var(--fg); background: var(--bg); box-shadow: 0 1px 2px rgba(0,0,0,0.05); outline: none"></label>')

# ================================================================ Workflows (list)
LIST_JS = r"""
    const D = (key, name, desc, pub, open, builtIn, fragment) => ({ key: key, name: name, desc: desc, pub: pub, open: open, builtIn: builtIn, fragment: fragment });
    const DEFS = [
      D('credit-approval', 'Credit approval', 'Credit proposals: line manager, then the committee or the delegation of authority.', 6, 'Version 7 · In review', false, false),
      D('two-step', 'Two-step approval', 'A review, then one approval.', 3, '', false, false),
      D('board', 'Board approval', 'Board papers through the secretariat to the board.', 2, 'Version 3 · Draft', false, false),
      D('procurement', 'Procurement approval', 'Purchases by amount, with the delegation of authority.', 4, '', false, false),
      D('policy-review', 'Policy review', 'Policies: owner, compliance, then the approving committee.', 1, '', false, false),
      D('simple', 'Simple approval', 'The approvers the author chooses, one after the other.', 1, '', true, false),
      D('committee', 'Committee decision', 'Any three members decide.', 2, '', false, true),
      D('legal-opinion', 'Legal opinion', 'Ask the legal team before a decision.', 1, '', false, true),
      D('expense', 'Expense approval', '', 0, 'Version 1 · Draft', false, false)
    ];
    const filter = st.filter || 'all', q = (st.q || '').toLowerCase();
    const shown = DEFS.filter((d) => (filter === 'all' || (filter === 'open' ? !!d.open : (filter === 'sub' ? d.fragment : !d.fragment))) && (!q || (d.name + ' ' + d.key).toLowerCase().indexOf(q) >= 0));
    const tabs = mkTabs([['all', 'All'], ['main', 'Workflows'], ['sub', 'Sub-workflows'], ['open', 'Changes in progress', DEFS.filter((d) => d.open).length]], filter, 'filter');
    const itemsFor = (d) => [item('Open', () => this.setState({ menu: '' })), item('Export', () => this.setState({ menu: '', toast: 'Done.' }))];
    const defs = shown.map((d) => { const open = st.menu === d.key; return {
      name: d.name, key: d.key, desc: d.desc, hasDesc: !!d.desc, builtIn: d.builtIn, fragment: d.fragment,
      pub: d.pub ? 'Version ' + d.pub : 'None', pubColor: d.pub ? 'var(--fg)' : 'var(--muted-fg)', hasOpen: !!d.open, noOpen: !d.open, open: d.open, openStyle: d.open.indexOf('In review') >= 0 ? 'color: var(--warn-fg); font-weight: 500' : 'color: var(--text-2)',
      phoneMeta: (d.pub ? 'Version ' + d.pub : 'Not published') + (d.open ? ' · ' + d.open : ''),
      items: itemsFor(d), menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + d.name, toggleMenu: () => this.setState({ menu: open ? '' : d.key }) }; });
    const sheetD = PHONE ? DEFS.find((d) => d.key === st.menu) : null;
    const key = form.key !== undefined ? form.key : (err === 'required' ? '' : (err === 'keyTaken' ? 'credit-approval' : 'vendor-payment'));
    const name = form.name !== undefined ? form.name : (err === 'required' ? '' : 'Vendor payment approval');
    const tried = !!form.tried || err === 'required';
    const nwAlert = err === 'failed' ? 'That did not work. Try again.' : '';
    const vals = Object.assign(base, {
      defs: defs, tabs: tabs, q: st.q || '', onQ: (ev) => this.setState({ q: ev && ev.target ? ev.target.value : '' }),
      footNote: DEFS.length + ' workflows. Memos keep the version they started on.',
      openNew: () => this.setState({ dlg: 'new', form: {}, err: 'none' }), isNew: dlg === 'new',
      sheetOpen: !!sheetD, sheet: sheetD ? { name: sheetD.name, items: itemsFor(sheetD) } : { name: '', items: [] },
      nw: Object.assign({ on_key: setForm('key'), on_name: setForm('name'), on_description: setForm('description'), description: '', hasAlert: !!nwAlert, alert: nwAlert,
        fragment: !!form.fragment, toggleFragment: () => this.setState({ form: Object.assign({}, form, { key: key, name: name, fragment: !form.fragment }) }),
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!key.trim() || !name.trim() || err === 'keyTaken') { this.setState({ form: Object.assign({}, form, { key: key, name: name, tried: true }) }); return; }
          if (nwAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Draft created. Opening it…' }); } },
        fieldVals('nw', 'key', key, tried && !key.trim() ? 'Required.' : (err === 'keyTaken' ? 'A workflow with this key exists.' : '')), fieldVals('nw', 'name', name, tried && !name.trim() ? 'Required.' : ''))
    });
"""
LIST_PROPS = '"dialog":{"editor":"enum","options":["none","new"],"default":"none"},"error":{"editor":"enum","options":["none","required","keyTaken","failed"],"default":"none"},'
BADGES = '<sc-if value="{{r.builtIn}}" hint-placeholder-val="{{false}}">' + SMALL('Built in') + '</sc-if><sc-if value="{{r.fragment}}" hint-placeholder-val="{{false}}">' + SMALL('Sub-workflow') + '</sc-if>'
LIST_CELLS = ('<td style="padding: 10px 16px; vertical-align: middle"><span style="display: flex; flex-direction: column; gap: 2px"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><a href="WorkflowBuilder.dc.html" style="font-weight: 500; color: var(--fg); text-decoration: none">{{r.name}}</a>' + BADGES + '</span>'
              '<sc-if value="{{r.hasDesc}}" hint-placeholder-val="{{true}}"><span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{r.desc}}</span></sc-if></span></td>'
              f'<td style="padding: 0 12px; vertical-align: middle; {MONO}; font-size: 13px; color: var(--text-2); white-space: nowrap">{{{{r.key}}}}</td>'
              '<td style="padding: 0 12px; vertical-align: middle; color: {{r.pubColor}}; white-space: nowrap">{{r.pub}}</td>'
              '<td style="padding: 0 12px; vertical-align: middle; white-space: nowrap"><sc-if value="{{r.hasOpen}}" hint-placeholder-val="{{false}}"><span style="{{r.openStyle}}">{{r.open}}</span></sc-if><sc-if value="{{r.noOpen}}" hint-placeholder-val="{{true}}"><span style="color: var(--muted-fg)">None</span></sc-if></td>' + MENU_CELL)
LIST_PROW = ('<a href="WorkflowBuilderPhone.dc.html" style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; color: var(--fg); text-decoration: none">'
             '<span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-size: 15px; font-weight: 500">{{r.name}}</span>' + BADGES + '</span>'
             f'<span style="{MONO}; font-size: 12px; color: var(--text-2)">{{{{r.key}}}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{{{r.phoneMeta}}}}</span></a>' + PHONE_DOTS)
WF_LEAD = 'How memos move from step to step. A version is written, reviewed by someone else, then published; memos keep the version they started on.'
LIST_DESK = page(nav('Workflows', False), page_head('Workflows', WF_LEAD, 'New workflow', 'openNew', False),
                 '      <div style="display: flex; flex-direction: column; gap: 8px">' + list_body(False, 'defs', [('Name', ''), ('Key', '170px'), ('Published', '120px'), ('In progress', '200px'), ('', '44px')], LIST_CELLS, '', 'Workflow definitions of the organisation')
                 + foot(False, f'<button type="button" class="ghost" style="{OUTLINE}">Import a file</button>') + '</div>', False,
                 f'      <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px">{tabs_markup(False)}{SEARCH("Filter by name or key")}</div>\n')
LIST_PHONE = page(nav('Workflows', True), page_head('Workflows', 'Written, reviewed by someone else, then published.', 'New', 'openNew', True),
                  list_body(True, 'defs', [], '', LIST_PROW, 'Workflow definitions of the organisation') + '\n' + foot(True), True,
                  f'    <div style="padding: 4px 16px 12px">{tabs_markup(True)}</div>\n') + '\n' + SHEET

def list_dialogs(phone):
    key = text_field('nw', 'key', 'Key', 'text', phone).replace('</div>', '  <span style="font-size: 12px; color: var(--muted-fg)">Lower case letters, digits and dashes, starting with a letter. It never changes.</span>\n        </div>', 1).replace('outline: none;', 'outline: none; ' + MONO + ';', 1)
    chk = 18 if phone else 16
    frag = f'        <label style="display: flex; align-items: flex-start; gap: 10px; font-size: 14px; cursor: pointer"><input type="checkbox" checked="{{{{nw.fragment}}}}" onChange="{{{{nw.toggleFragment}}}}" style="margin: 2px 0 0; width: {chk}px; height: {chk}px; accent-color: var(--primary)"><span>Meant to be used inside other workflows</span></label>'
    body = '\n'.join([heading('nw-title', 'New workflow', 'It starts as a draft. Someone else reviews it before it is published.'), text_field('nw', 'name', 'Name', 'text', phone), key,
                      opt_field('nw', 'description', 'Description (optional)', phone, required=False), frag, alert('nw'), buttons('Create draft', primary(phone), phone)])
    return f'''
  <sc-if value="{{{{isNew}}}}" hint-placeholder-val="{{{{false}}}}">
    {frame(body, 'nw-title', 'dialog', 'nw.submit', phone)}
  </sc-if>
''' + toast(phone)

# ================================================================ Queues
ICON = ('<sc-if value="{{%s.isPos}}" hint-placeholder-val="{{true}}"><span aria-hidden="true" style="display: inline-flex; color: var(--text-3)">' + svg(I['brief'], 13) + '</span></sc-if>'
        '<sc-if value="{{%s.isPerson}}" hint-placeholder-val="{{false}}"><span aria-hidden="true" style="display: inline-flex; color: var(--text-3)">' + svg(I['user'], 13) + '</span></sc-if>')
QCHIPS = ('<sc-for list="{{r.chips}}" as="mc" hint-placeholder-count="3"><span style="display: inline-flex; align-items: center; gap: 4px; height: 24px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); font-size: 12px; color: var(--text-2); white-space: nowrap">'
          + ICON % ('mc', 'mc') + '{{mc.name}}</span></sc-for><sc-if value="{{r.hasMore}}" hint-placeholder-val="{{true}}"><span style="font-size: 12px; color: var(--muted-fg)">{{r.more}}</span></sc-if>')
QUEUES_JS = r"""
    const M = (kind, name, sub) => ({ kind: kind, name: name, sub: sub });
    const QS = [
      { key: 'credit-desk', name: 'Credit desk', members: [M('pos', 'Credit Risk Analyst', 'Sadia Islam, Imran Hossain'), M('pos', 'Credit Risk Manager', 'Nusrat Jahan')] },
      { key: 'legal', name: 'Legal team', members: [M('person', 'Abdul Kader', 'Legal Counsel'), M('person', 'Nasrin Akter', 'Company Secretary')] },
      { key: 'it-service', name: 'IT service desk', members: [M('person', 'Imran Hossain', 'IT Security Officer'), M('pos', 'IT Support Officer', 'Vacant')] },
      { key: 'board-secretariat', name: 'Board secretariat', members: [M('person', 'Nasrin Akter', 'Company Secretary'), M('person', 'Rafiq Chowdhury', 'Chief Financial Officer'), M('pos', 'Board Liaison Officer', 'Shirin Akter'), M('person', 'Karim Reza', 'Deputy CFO')] }
    ];
    const target = QS.find((q) => q.key === (st.target || 'credit-desk')) || QS[0];
    const isNew = dlg === 'new';
    const members = form.members || (isNew ? (err === 'required' ? [] : [M('pos', 'Compliance Officer', 'Vacant')]) : target.members);
    const key = form.key !== undefined ? form.key : (isNew ? (err === 'required' ? '' : (err === 'keyTaken' ? 'legal' : 'compliance')) : target.key);
    const name = form.name !== undefined ? form.name : (isNew ? (err === 'required' ? '' : 'Compliance review') : target.name);
    const tried = !!form.tried || err === 'required';
    const kind = form.kind || 'person';
    const qdAlert = ({ conflict: 'Someone changed this queue meanwhile. Open it again.', failed: 'That did not work. Try again.' })[err] || '';
    const openQ = (q) => this.setState({ dlg: 'edit', target: q.key, err: 'none', form: null, menu: '' });
    const itemsFor = (q) => [item('Edit', () => openQ(q))];
    const queues = QS.map((q) => { const open = st.menu === q.key; return { name: q.name, key: q.key, open: () => openQ(q), items: itemsFor(q),
      chips: q.members.slice(0, 3).map((m) => ({ name: m.name, isPos: m.kind === 'pos', isPerson: m.kind !== 'pos' })), hasMore: q.members.length > 3, more: '+' + (q.members.length - 3) + ' more',
      count: q.members.length + (q.members.length === 1 ? ' member' : ' members'), menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + q.name, toggleMenu: () => this.setState({ menu: open ? '' : q.key }) }; });
    const sheetQ = PHONE ? QS.find((q) => q.key === st.menu) : null;
    const seg = (on) => 'display: inline-flex; align-items: center; height: ' + (PHONE ? '36px' : '28px') + '; padding: 0 10px; border-radius: 7px; font-size: 13px; font-weight: 500; cursor: pointer; ' + (on ? 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06)' : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)');
    const keep = (extra) => this.setState({ form: Object.assign({}, form, { key: key, name: name, members: members }, extra) });
    const vals = Object.assign(base, {
      queues: queues, footNote: QS.length + ' queues. Workflow steps name a queue by its key.',
      openNew: () => this.setState({ dlg: 'new', form: {}, err: 'none' }), isList: dlg === 'new' || dlg === 'edit',
      sheetOpen: !!sheetQ, sheet: sheetQ ? { name: sheetQ.name, items: itemsFor(sheetQ) } : { name: '', items: [] },
      ld: Object.assign({ title: isNew ? 'Add queue' : 'Edit ' + target.name, isNew: isNew, isEdit: !isNew, keyText: target.key, on_name: setForm('name'), on_key: setForm('key'),
        members: members.map((m, i) => ({ name: m.name, sub: m.sub, kindLabel: m.kind === 'pos' ? 'Position' : 'Person', isPos: m.kind === 'pos', isPerson: m.kind !== 'pos', subStyle: m.sub === 'Vacant' ? 'color: var(--warn-fg)' : 'color: var(--muted-fg)', removeLabel: 'Remove ' + m.name,
          remove: () => keep({ members: members.filter((x, j) => j !== i) }), liStyle: 'display: flex; align-items: center; gap: 10px; padding: ' + (PHONE ? '8px 4px 8px 12px' : '6px 4px 6px 12px') + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') })),
        hasMembers: members.length > 0, noMembers: members.length === 0, count: members.length + (members.length === 1 ? ' member' : ' members'),
        personStyle: seg(kind === 'person'), positionStyle: seg(kind === 'position'), personAria: kind === 'person' ? 'true' : 'false', positionAria: kind === 'position' ? 'true' : 'false', memberPlaceholder: kind === 'person' ? 'Search people' : 'Search positions',
        pickPerson: () => keep({ kind: 'person' }), pickPosition: () => keep({ kind: 'position' }), hasAlert: !!qdAlert, alert: qdAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if ((isNew && !key.trim()) || !name.trim() || err === 'keyTaken') { keep({ tried: true }); return; }
          if (qdAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Saved.' }); } },
        fieldVals('ld', 'key', key, tried && !key.trim() ? 'Required.' : (err === 'keyTaken' ? 'A queue with this key exists.' : '')), fieldVals('ld', 'name', name, tried && !name.trim() ? 'Required.' : ''))
    });
"""
QUEUES_PROPS = '"dialog":{"editor":"enum","options":["none","new","edit"],"default":"none"},"error":{"editor":"enum","options":["none","required","keyTaken","conflict","failed"],"default":"none"},'
Q_NAME = ('<span style="display: flex; flex-direction: column; gap: 2px"><button type="button" onClick="{{r.open}}" style="padding: 0; border: 0; background: transparent; color: var(--fg); font-family: inherit; font-size: 14px; font-weight: 500; text-align: left; cursor: pointer">{{r.name}}</button>'
          '<span style="font-size: 12px; color: var(--muted-fg)">{{r.count}}</span></span>')
Q_CELLS = (f'<td style="padding: 10px 16px; vertical-align: middle">{Q_NAME}</td><td style="padding: 0 12px; vertical-align: middle; {MONO}; font-size: 13px; color: var(--text-2)">{{{{r.key}}}}</td>'
           f'<td style="padding: 10px 12px; vertical-align: middle"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px">{QCHIPS}</span></td>' + MENU_CELL)
Q_PROW = ('<button type="button" onClick="{{r.open}}" style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; padding: 0; border: 0; background: transparent; color: var(--fg); font-family: inherit; text-align: left">'
          f'<span style="display: flex; flex-direction: column; gap: 2px"><span style="font-size: 15px; font-weight: 500">{{{{r.name}}}}</span><span style="{MONO}; font-size: 12px; color: var(--text-2)">{{{{r.key}}}} · {{{{r.count}}}}</span></span>'
          f'<span style="display: flex; flex-wrap: wrap; gap: 6px">{QCHIPS}</span></button>' + PHONE_DOTS)
Q_LEAD = 'A workflow step can go to a queue: every member gets the task, one claims it, and the first decision completes it. Members are people, or whoever holds a position.'
QUEUES_DESK = page(nav('Queues', False), page_head('Queues', Q_LEAD, 'Add queue', 'openNew', False),
                   '      <div style="display: flex; flex-direction: column; gap: 8px">' + list_body(False, 'queues', [('Name', '240px'), ('Key', '190px'), ('Members', ''), ('', '44px')], Q_CELLS, '', 'Workflow queues') + foot(False) + '</div>', False)
QUEUES_PHONE = page(nav('Queues', True), page_head('Queues', 'One member claims the task; the first decision completes it.', 'Add', 'openNew', True),
                    list_body(True, 'queues', [], '', Q_PROW, 'Workflow queues') + '\n' + foot(True), True) + '\n' + SHEET

def queue_dialog(phone):
    h = H(phone); fs = FS(phone); b = 44 if phone else 28
    members = T('''        <fieldset style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 8px">
          <legend style="display: contents"><span style="display: flex; align-items: baseline; justify-content: space-between; width: 100%; font-size: 14px; font-weight: 500"><span>Members</span><span style="font-size: 12px; font-weight: 400; color: var(--muted-fg)">{{ld.count}}</span></span></legend>
          <sc-if value="{{ld.hasMembers}}" hint-placeholder-val="{{true}}"><ul style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden"><sc-for list="{{ld.members}}" as="mb" hint-placeholder-count="3"><li style="{{mb.liStyle}}">
            [[icon]]<span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="font-weight: 500">{{mb.name}}</span><span style="font-size: 12px; {{mb.subStyle}}">{{mb.kindLabel}} · {{mb.sub}}</span></span>
            <button type="button" class="ghost" aria-label="{{mb.removeLabel}}" onClick="{{mb.remove}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: [[b]]px; height: [[b]]px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3)">[[x]]</button>
          </li></sc-for></ul></sc-if>
          <sc-if value="{{ld.noMembers}}" hint-placeholder-val="{{false}}"><div style="padding: 12px; border: 1px dashed var(--border); border-radius: 8px; text-align: center; font-size: 13px; color: var(--muted-fg)">No members yet.</div></sc-if>
          <div style="display: flex; flex-direction: column; gap: 8px; padding: 10px; border-radius: 8px; background: var(--subtle); border: 1px solid var(--border)">
            <div role="radiogroup" aria-label="Add a" style="align-self: flex-start; display: inline-flex; gap: 2px; padding: 3px; border-radius: 10px; background: var(--muted)"><button type="button" role="radio" aria-checked="{{ld.personAria}}" onClick="{{ld.pickPerson}}" style="{{ld.personStyle}}">Person</button><button type="button" role="radio" aria-checked="{{ld.positionAria}}" onClick="{{ld.pickPosition}}" style="{{ld.positionStyle}}">Position</button></div>
            <div style="display: flex; gap: 8px"><label for="ld-member" style="[[sr]]">Member</label><input id="ld-member" type="text" role="combobox" aria-expanded="false" placeholder="{{ld.memberPlaceholder}}" style="flex: 1; min-width: 0; height: [[h]]; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: [[fs]]; outline: none"><button type="button" class="ghost" style="flex-shrink: 0; height: [[h]]; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 14px; font-weight: 500; cursor: pointer">Add member</button></div>
          </div>
        </fieldset>''', icon=ICON % ('mb', 'mb'), b=str(b), x=svg(I['x'], 14), sr=SR, h=h, fs=fs)
    key_new = text_field('ld', 'key', 'Key', 'text', phone).replace('</div>', '  <span style="font-size: 12px; color: var(--muted-fg)">Definitions name the queue by its key: lower case letters, digits and dashes. It cannot change later.</span>\n        </div>', 1).replace('outline: none;', 'outline: none; ' + MONO + ';', 1)
    key_edit = f'<div style="display: flex; flex-direction: column; gap: 4px"><span style="font-size: 14px; font-weight: 500">Key</span><span style="{MONO}; font-size: 13px">{{{{ld.keyText}}}}</span><span style="font-size: 12px; color: var(--muted-fg)">It cannot change.</span></div>'
    body = '\n'.join([text_field('ld', 'name', 'Name', 'text', phone), '        <sc-if value="{{ld.isNew}}" hint-placeholder-val="{{true}}">\n' + key_new + '\n        </sc-if>',
                      f'        <sc-if value="{{{{ld.isEdit}}}}" hint-placeholder-val="{{{{false}}}}">{key_edit}</sc-if>', members, alert('ld')])
    ld = frame_scroll('<div style="display: flex; flex-direction: column; gap: 6px"><h2 id="ld-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{{ld.title}}</h2><p style="margin: 0; font-size: 14px; color: var(--muted-fg)">Every member gets the task; one claims it.</p></div>',
                      body, buttons('Save', primary(phone), phone), 'ld-title', 'dialog', 'ld.submit', phone, width=520)
    return f'''
  <sc-if value="{{{{isList}}}}" hint-placeholder-val="{{{{false}}}}">
    {ld}
  </sc-if>
''' + toast(phone)

# ================================================================ Substitutions
SUBS_JS = r"""
    const S = (id, person, sub, dates, reason, status, tasks) => ({ id: id, person: person, sub: sub, dates: dates, reason: reason, status: status, tasks: tasks });
    const ALL = [
      S('s1', 'Farhana Rahman', 'Nusrat Jahan', '17 Nov to 24 Nov 2026', 'Annual leave', 'in', 3),
      S('s2', 'Nasir Uddin', 'Rashed Khan', 'From 2 Nov 2026', 'Left the bank; until the position is filled', 'in', 1),
      S('s3', 'Mahmud Karim', 'Tanvir Ahmed', '1 Dec to 8 Dec 2026', 'Training abroad', 'planned', 0),
      S('s4', 'Rafiq Chowdhury', 'Karim Reza', '3 Nov to 10 Nov 2026', 'Annual leave', 'ended', 0),
      S('s5', 'Shirin Akter', 'Arif Rahman', '12 Oct to 15 Oct 2026', 'Conference', 'ended', 0)
    ];
    const LBL = { in: 'In force', planned: 'Planned', ended: 'Ended' }, TN = { in: 'ok', planned: 'warn', ended: 'neutral' };
    const filter = st.filter || 'all';
    const tabs = mkTabs([['all', 'All'], ['in', 'In force', ALL.filter((s) => s.status === 'in').length], ['planned', 'Planned'], ['ended', 'Ended']], filter, 'filter');
    const shown = ALL.filter((s) => filter === 'all' || s.status === filter);
    const moved = (n) => n === 0 ? 'No open tasks moved.' : (n === 1 ? '1 open task moved.' : n + ' open tasks moved.');
    const itemsFor = (s) => s.status === 'in' ? [item('Move open tasks', () => this.setState({ menu: '', toast: moved(s.tasks) })), item('End today', () => this.setState({ menu: '', toast: 'Done.' }), true)]
      : (s.status === 'planned' ? [item('Cancel it', () => this.setState({ menu: '', toast: 'Done.' }), true)] : []);
    const ini = (n) => n.split(' ').map((p) => p[0]).join('');
    const subs = shown.map((s) => { const open = st.menu === s.id; const items = itemsFor(s); return { person: s.person, sub: s.sub, pi: ini(s.person), si: ini(s.sub), dates: s.dates, reason: s.reason,
      statusLabel: LBL[s.status], statusStyle: TONE[TN[s.status]], isIn: s.status === 'in', move: () => this.setState({ toast: moved(s.tasks) }), moveLabel: 'Move open tasks of ' + s.person + ' to ' + s.sub,
      items: items, hasItems: items.length > 0, menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + s.person, toggleMenu: () => this.setState({ menu: open ? '' : s.id }) }; });
    const sheetS = PHONE ? ALL.find((s) => s.id === st.menu) : null;
    const f = (k, dflt) => form[k] !== undefined ? form[k] : (err === 'required' ? '' : dflt);
    const person = f('person', 'Imran Hossain'), substitute = f('substitute', 'Sadia Islam'), from = f('from', '22 Nov 2026');
    const tried = !!form.tried || err === 'required';
    const sdAlert = ({ overlap: 'This person already has a substitute for some of these dates.', failed: 'That did not work. Try again.' })[err] || '';
    const vals = Object.assign(base, {
      subs: subs, tabs: tabs, footNote: 'New tasks go to the substitute while a substitution is in force. Open tasks stay where they are unless you move them.',
      openNew: () => this.setState({ dlg: 'new', form: {}, err: 'none' }), isNew: dlg === 'new',
      sheetOpen: !!sheetS, sheet: sheetS ? { name: sheetS.person + ' → ' + sheetS.sub, items: itemsFor(sheetS) } : { name: '', items: [] },
      sd: Object.assign({ on_person: setForm('person'), on_substitute: setForm('substitute'), on_from: setForm('from'), on_until: setForm('until'), on_reason: setForm('reason'), until: '', reason: '', hasAlert: !!sdAlert, alert: sdAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!person.trim() || !substitute.trim() || !from.trim()) { this.setState({ form: Object.assign({}, form, { person: person, substitute: substitute, from: from, tried: true }) }); return; }
          if (sdAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Done.' }); } },
        fieldVals('sd', 'person', person, tried && !person.trim() ? 'Required.' : ''), fieldVals('sd', 'substitute', substitute, tried && !substitute.trim() ? 'Required.' : ''), fieldVals('sd', 'from', from, tried && !from.trim() ? 'Required.' : ''))
    });
"""
SUBS_PROPS = '"dialog":{"editor":"enum","options":["none","new"],"default":"none"},"error":{"editor":"enum","options":["none","required","overlap","failed"],"default":"none"},'
AV = lambda k: f'<span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: 10px; font-weight: 600; flex-shrink: 0">{{{{r.{k}}}}}</span>'
ARROW = f'<span aria-hidden="true" style="display: inline-flex; color: var(--muted-fg)">{svg(I["arrow"], 14)}</span><span style="{SR}">substituted by</span>'
S_CELLS = (f'<td style="padding: 10px 16px; vertical-align: middle"><span style="display: flex; align-items: center; gap: 8px; white-space: nowrap">{AV("pi")}<span style="font-weight: 500">{{{{r.person}}}}</span>{ARROW}{AV("si")}<span>{{{{r.sub}}}}</span></span></td>'
           '<td style="padding: 0 12px; vertical-align: middle; white-space: nowrap">{{r.dates}}</td><td style="padding: 0 12px; vertical-align: middle; color: var(--text-2)">{{r.reason}}</td>'
           '<td style="padding: 0 12px; vertical-align: middle"><span style="{{r.statusStyle}}">{{r.statusLabel}}</span></td>'
           '<td style="position: relative; padding: 0 8px 0 12px; vertical-align: middle; text-align: right; white-space: nowrap"><sc-if value="{{r.isIn}}" hint-placeholder-val="{{false}}"><button type="button" class="ghost" onClick="{{r.move}}" aria-label="{{r.moveLabel}}" style="height: 28px; padding: 0 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer; margin-right: 4px">Move open tasks</button></sc-if>'
           f'<sc-if value="{{{{r.hasItems}}}}" hint-placeholder-val="{{{{true}}}}">{MENU_BTN}</sc-if>{MENU}</td>')
S_PROW = (f'<span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 15px"><span style="font-weight: 500">{{{{r.person}}}}</span>{ARROW}<span>{{{{r.sub}}}}</span></span>'
          '<span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-fg)"><span style="{{r.statusStyle}}">{{r.statusLabel}}</span>{{r.dates}}</span><span style="font-size: 12px; color: var(--text-2)">{{r.reason}}</span></span>'
          f'<sc-if value="{{{{r.hasItems}}}}" hint-placeholder-val="{{{{true}}}}">{PHONE_DOTS}</sc-if>')
S_LEAD = 'While a substitution is in force, new tasks for the person go to their substitute, who acts for them. Never on a memo the substitute wrote or has an interest in.'
SUBS_DESK = page(nav('Substitutions', False), page_head('Substitutions', S_LEAD, 'Add substitution', 'openNew', False),
                 '      <div style="display: flex; flex-direction: column; gap: 8px">' + list_body(False, 'subs', [('Person and substitute', '380px'), ('Dates', '190px'), ('Reason', ''), ('Status', '110px'), ('', '170px')], S_CELLS, '', 'Substitutions, newest first') + foot(False) + '</div>', False,
                 f'      <div>{tabs_markup(False)}</div>\n')
SUBS_PHONE = page(nav('Substitutions', True), page_head('Substitutions', 'New tasks go to the substitute while it is in force.', 'Add', 'openNew', True),
                  list_body(True, 'subs', [], '', S_PROW, 'Substitutions, newest first') + '\n' + foot(True), True, f'    <div style="padding: 4px 16px 12px">{tabs_markup(True)}</div>\n') + '\n' + SHEET

def subs_dialogs(phone):
    pick = lambda k, l: text_field('sd', k, l, 'text', phone).replace('type="text"', 'type="text" role="combobox" aria-expanded="false"', 1)
    grid = f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 12px">'
    until = opt_field('sd', 'until', 'Until (not included)', phone, required=False, hint='Leave empty until someone ends it.')
    body = '\n'.join([heading('sd-title', 'Add substitution', 'New tasks for the person go to the substitute while it is in force.'), pick('person', 'Person away'), pick('substitute', 'Substitute'),
                      grid + text_field('sd', 'from', 'From', 'text', phone) + until + '</div>', opt_field('sd', 'reason', 'Reason', phone, required=False), alert('sd'), buttons('Save', primary(phone), phone)])
    return f'''
  <sc-if value="{{{{isNew}}}}" hint-placeholder-val="{{{{false}}}}">
    {frame(body, 'sd-title', 'dialog', 'sd.submit', phone)}
  </sc-if>
''' + toast(phone)

# ================================================================ Business calendar
CAL_JS = r"""
    const DAYS = [['Sunday', [['09:00', '17:00']]], ['Monday', [['09:00', '17:00']]], ['Tuesday', [['09:00', '17:00']]], ['Wednesday', [['09:00', '17:00']]], ['Thursday', [['09:00', '13:00'], ['14:00', '17:00']]], ['Friday', []], ['Saturday', []]];
    const week = DAYS.map((d, i) => ({ day: d[0], notWorked: d[1].length === 0, periods: d[1].map((p) => ({ from: p[0], until: p[1], removeLabel: 'Remove these hours on ' + d[0], fromLabel: d[0] + ', from', untilLabel: d[0] + ', until' })),
      addLabel: 'Add hours on ' + d[0], rowStyle: 'display: flex; align-items: flex-start; gap: ' + (PHONE ? '6px' : '12px') + '; padding: ' + (PHONE ? '10px 4px 10px 12px' : '8px 14px') + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') }));
    const SPECIAL = { 16: 'closed', 25: 'closed', 31: 'hours' };
    const cells = [];
    for (let i = 0; i < 2; i++) cells.push({ label: '', aria: '', style: 'height: ' + (PHONE ? '40px' : '44px') });
    for (let d = 1; d <= 31; d++) { const wd = (d + 1) % 7; const s = SPECIAL[d]; const off = wd === 5 || wd === 6;
      cells.push({ label: String(d), aria: d + ' December' + (s === 'closed' ? ', closed' : (s === 'hours' ? ', other hours' : (off ? ', not a working day' : ''))),
        style: 'display: flex; align-items: center; justify-content: center; height: ' + (PHONE ? '40px' : '44px') + '; border-radius: 8px; font-size: 13px; font-variant-numeric: tabular-nums; '
        + (s === 'closed' ? 'background: var(--danger-bg); color: var(--danger-fg); font-weight: 600; border: 1px solid var(--danger-border)' : (s === 'hours' ? 'background: var(--warn-bg); color: var(--warn-fg); font-weight: 600; border: 1px solid var(--warn-border)' : (off ? 'color: var(--muted-fg)' : 'color: var(--fg)'))) }); }
    const special = [['Wed 16 Dec', 'Closed', 'Victory Day'], ['Fri 25 Dec', 'Closed', 'Christmas Day'], ['Thu 31 Dec', '09:00–13:00', 'Year-end closing']]
      .map((s, i) => ({ day: s[0], hours: s[1], note: s[2], closed: s[1] === 'Closed', open: s[1] !== 'Closed', removeLabel: 'Remove ' + s[0], style: 'display: flex; align-items: center; gap: 10px; padding: 8px 4px 8px 16px' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') }));
    const kind = form.kind || 'closed';
    const first = form.first !== undefined ? form.first : (err === 'required' ? '' : '26 Mar 2027');
    const tried = !!form.tried || err === 'required';
    const weekAlert = dlg !== 'day' && err === 'conflict' ? 'Someone changed the hours meanwhile. They have been reloaded.' : '';
    const keepDay = (extra) => this.setState({ form: Object.assign({}, form, { first: first }, extra) });
    const vals = Object.assign(base, {
      week: week, month: 'December 2026', cells: cells, special: special, timeZone: 'Time zone: Asia/Dhaka',
      dueText: 'Due Sun 22 Nov 2026, 11:30', calculate: () => this.setState({ toast: 'Due Sun 22 Nov 2026, 11:30' }),
      saveWeek: () => { if (weekAlert) { this.setState({ err: err }); return; } this.setState({ toast: 'Saved.' }); }, hasWeekAlert: !!weekAlert, weekAlert: weekAlert,
      openDay: () => this.setState({ dlg: 'day', form: {}, err: 'none' }), isDay: dlg === 'day',
      cd: Object.assign({ on_first: setForm('first'), on_last: setForm('last'), on_note: setForm('note'), last: '', note: 'Independence Day', closed: kind === 'closed', hours: kind === 'hours',
        pickClosed: () => keepDay({ kind: 'closed' }), pickHours: () => keepDay({ kind: 'hours' }), hasAlert: err === 'failed', alert: 'That did not work. Try again.',
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!first.trim()) { keepDay({ tried: true }); return; } if (err === 'failed') { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Saved.' }); } },
        fieldVals('cd', 'first', first, tried && !first.trim() ? 'Required.' : ''))
    });
"""
CAL_PROPS = '"dialog":{"editor":"enum","options":["none","day"],"default":"none"},"error":{"editor":"enum","options":["none","required","conflict","failed"],"default":"none"},'
def cal_main(phone):
    h = '44px' if phone else '32px'
    tin = lambda v, lbl: f'<input type="text" inputmode="numeric" aria-label="{lbl}" value="{v}" style="width: {"62px" if phone else "68px"}; height: {h}; padding: 0 6px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}; font-variant-numeric: tabular-nums; outline: none">'
    pt = '12px' if phone else '6px'
    week = f'''<section aria-labelledby="wk-title" style="{CARD}; display: flex; flex-direction: column">
        <div style="display: flex; flex-direction: column; gap: 4px; padding: 14px 16px 10px"><h2 id="wk-title" style="margin: 0; font-size: 15px; font-weight: 600">Working hours</h2><span style="font-size: 13px; color: var(--muted-fg)">{{{{timeZone}}}}</span></div>
        <sc-if value="{{{{hasWeekAlert}}}}" hint-placeholder-val="{{{{false}}}}"><div role="alert" style="margin: 0 16px 10px; padding: 8px 10px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px">{{{{weekAlert}}}}</div></sc-if>
        <ul style="list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--border)"><sc-for list="{{{{week}}}}" as="d" hint-placeholder-count="7"><li style="{{{{d.rowStyle}}}}">
          <span style="width: {"80px" if phone else "100px"}; flex-shrink: 0; padding-top: {pt}; font-size: 14px; font-weight: 500">{{{{d.day}}}}</span>
          <span style="flex: 1; display: flex; flex-direction: column; gap: 6px">
            <sc-for list="{{{{d.periods}}}}" as="p" hint-placeholder-count="1"><span style="display: flex; align-items: center; gap: 6px">{tin("{{p.from}}", "{{p.fromLabel}}")}<span style="color: var(--muted-fg)">–</span>{tin("{{p.until}}", "{{p.untilLabel}}")}{icon_btn("{{p.removeLabel}}", "x", phone)}</span></sc-for>
            <sc-if value="{{{{d.notWorked}}}}" hint-placeholder-val="{{{{false}}}}"><span style="padding-top: {pt}; font-size: 13px; color: var(--muted-fg)">Not worked</span></sc-if>
          </span>
          {icon_btn("{{d.addLabel}}", "plus", True) if phone else f'<button type="button" class="ghost" aria-label="{{{{d.addLabel}}}}" style="flex-shrink: 0; height: {h}; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">Add hours</button>'}
        </li></sc-for></ul>
        <div style="display: flex; justify-content: flex-end; padding: 12px 16px; border-top: 1px solid var(--border)"><button type="button" onClick="{{{{saveWeek}}}}" style="{primary(phone)}{"; flex: 1" if phone else ""}">Save working hours</button></div>
      </section>'''
    heads = ''.join(f'<span role="columnheader" style="text-align: center; font-size: 11px; font-weight: 500; color: var(--muted-fg); padding-bottom: 4px">{d}</span>' for d in ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'])
    sw = lambda bg, bd: f'<span style="width: 10px; height: 10px; border-radius: 3px; background: var({bg}); border: 1px solid var({bd})"></span>'
    days = f'''<section aria-labelledby="hd-title" style="{CARD}; display: flex; flex-direction: column">
        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 14px 16px 10px"><h2 id="hd-title" style="margin: 0; font-size: 15px; font-weight: 600">Holidays and special days</h2><button type="button" class="ghost" onClick="{{{{openDay}}}}" style="flex-shrink: 0; {OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">{svg(I["plus"], 14)}{"Add" if phone else "Add a day or a range"}</button></div>
        <div style="display: flex; align-items: center; justify-content: space-between; padding: 0 12px 8px">{icon_btn("Previous month", "left", phone)}<span style="font-weight: 600">{{{{month}}}}</span>{icon_btn("Next month", "right", phone)}</div>
        <div role="grid" aria-label="{{{{month}}}}" style="display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; padding: 0 12px 12px">{heads}<sc-for list="{{{{cells}}}}" as="c" hint-placeholder-count="31"><span role="gridcell" aria-label="{{{{c.aria}}}}" style="{{{{c.style}}}}">{{{{c.label}}}}</span></sc-for></div>
        <div style="display: flex; flex-wrap: wrap; gap: 12px; padding: 0 16px 12px; font-size: 12px; color: var(--muted-fg)"><span style="display: inline-flex; align-items: center; gap: 6px">{sw("--danger-bg", "--danger-border")}Closed</span><span style="display: inline-flex; align-items: center; gap: 6px">{sw("--warn-bg", "--warn-border")}Other hours</span><span>Grey dates are not working days</span></div>
        <ul style="list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--border)"><sc-for list="{{{{special}}}}" as="s" hint-placeholder-count="3"><li style="{{{{s.style}}}}"><span style="width: 90px; flex-shrink: 0; font-weight: 500; font-variant-numeric: tabular-nums">{{{{s.day}}}}</span><span style="flex: 1; display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><sc-if value="{{{{s.closed}}}}" hint-placeholder-val="{{{{true}}}}">{SMALL("Closed", "danger")}</sc-if><sc-if value="{{{{s.open}}}}" hint-placeholder-val="{{{{false}}}}">{SMALL("{{s.hours}}", "warn")}</sc-if><span style="font-size: 13px; color: var(--text-2)">{{{{s.note}}}}</span></span>{icon_btn("{{s.removeLabel}}", "trash", phone)}</li></sc-for></ul>
      </section>'''
    due = f'''<section aria-labelledby="du-title" style="{CARD}; padding: 14px 16px; display: flex; flex-direction: column; gap: 12px">
        <div style="display: flex; flex-direction: column; gap: 4px"><h2 id="du-title" style="margin: 0; font-size: 15px; font-weight: 600">When is it due?</h2><span style="font-size: 13px; color: var(--muted-fg)">A deadline such as P2D (two working days) or PT4H (four working hours), counted from now.</span></div>
        <div style="display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px"><div style="display: flex; flex-direction: column; gap: 6px"><label for="du-d" style="font-size: 14px; font-weight: 500">Deadline</label><input id="du-d" type="text" value="P2D" style="width: 120px; height: {H(phone)}; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); {MONO}; font-size: {FS(phone)}; outline: none"></div>
          <label style="display: flex; align-items: center; gap: 8px; height: {H(phone)}; font-size: 14px"><input type="checkbox" checked style="width: 16px; height: 16px; accent-color: var(--primary)">Count working time only</label>
          <button type="button" class="ghost" onClick="{{{{calculate}}}}" style="{obtn(phone)}">Calculate</button></div>
        <div role="status" style="display: flex; align-items: center; gap: 8px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--subtle); font-size: 14px; font-weight: 500">{svg(I["cal"], 16)}{{{{dueText}}}}</div>
        <span style="font-size: 12px; color: var(--muted-fg)">From now, Wed 18 Nov, 11:30: Thursday is worked; Friday and Saturday are not.</span>
      </section>'''
    if phone:
        return f'<div style="display: flex; flex-direction: column; gap: 14px; padding: 4px 16px 24px">{week}{days}{due}</div>'
    return f'<div style="display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; align-items: start"><div style="display: flex; flex-direction: column; gap: 16px; min-width: 0">{week}{due}</div>{days}</div>'

CAL_LEAD = 'Deadlines in working time count only these hours. Changes apply to tasks that start afterwards; tasks already waiting keep their due times.'
CAL_DESK = page(nav('Business calendar', False), title_only('Business calendar', CAL_LEAD, False), '      ' + cal_main(False), False)
CAL_PHONE = page(nav('Business calendar', True), title_only('Business calendar', 'Deadlines in working time count only these hours.', True), cal_main(True), True)

def cal_dialogs(phone):
    s = 18 if phone else 16
    tin = lambda l, v: f'<input type="text" aria-label="{l}" value="{v}" style="width: 72px; height: {H(phone)}; padding: 0 8px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}">'
    radio = lambda k, l, click: f'<label style="display: flex; align-items: center; gap: 10px; font-size: 14px; cursor: pointer{"; min-height: 40px" if phone else ""}"><input type="radio" name="cd-kind" checked="{{{{cd.{k}}}}}" onChange="{{{{cd.{click}}}}}" style="width: {s}px; height: {s}px; accent-color: var(--primary)">{l}</label>'
    kind = (f'        <fieldset style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 8px"><legend style="margin-bottom: 8px; padding: 0; font-size: 14px; font-weight: 500">The day is</legend>'
            + radio('closed', 'Closed (a holiday)', 'pickClosed') + radio('hours', 'Worked, with these hours', 'pickHours')
            + f'<sc-if value="{{{{cd.hours}}}}" hint-placeholder-val="{{{{false}}}}"><span style="display: flex; align-items: center; gap: 6px; padding-left: 26px">{tin("From", "09:00")}<span style="color: var(--muted-fg)">–</span>{tin("Until", "13:00")}</span></sc-if></fieldset>')
    grid = f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 12px">'
    body = '\n'.join([heading('cd-title', 'Add a day or a range', 'A holiday, or a day with other hours. Tasks already waiting keep their due times.'),
                      grid + text_field('cd', 'first', 'First day', 'text', phone) + opt_field('cd', 'last', 'Last day (for a range)', phone, required=False) + '</div>', kind,
                      opt_field('cd', 'note', 'Note', phone, required=False), alert('cd'), buttons('Add', primary(phone), phone)])
    return f'''
  <sc-if value="{{{{isDay}}}}" hint-placeholder-val="{{{{false}}}}">
    {frame(body, 'cd-title', 'dialog', 'cd.submit', phone)}
  </sc-if>
''' + toast(phone)

# ================================================================ assemble
BOARDS = [('Workflows', 'Workflows', LIST_DESK, LIST_PHONE, list_dialogs, LIST_JS, LIST_PROPS, 1000, None),
          ('WorkflowBuilder', 'Credit approval · Workflow', BUILDER_DESK, BUILDER_PHONE, builder_dialogs, BUILDER_JS, BUILDER_PROPS, 1100, ('WorkflowsPhone.dc.html', 'Workflows')),
          ('Doa', 'Delegation of authority', DOA_DESK, DOA_PHONE, doa_dialogs, DOA_JS, DOA_PROPS, 1100, None),
          ('Queues', 'Queues', QUEUES_DESK, QUEUES_PHONE, queue_dialog, QUEUES_JS, QUEUES_PROPS, 1000, None),
          ('Substitutions', 'Substitutions', SUBS_DESK, SUBS_PHONE, subs_dialogs, SUBS_JS, SUBS_PROPS, 1000, None),
          ('BusinessCalendar', 'Business calendar', CAL_DESK, CAL_PHONE, cal_dialogs, CAL_JS, CAL_PROPS, 1000, None)]
for name, title, desk, phone, dialogs, js, props, h, back in BOARDS:
    build(name, title, desk, phone, dialogs, js, props, h, back, JS_HEAD)

# Every other sidebar links Workflows here (idempotent)
for p in sorted(proj.glob('*.dc.html')):
    s = p.read_text()
    s2 = s.replace('<a href="#" class="nav-item" title="Workflows" style=', '<a href="Workflows.dc.html" class="nav-item" title="Workflows" style=')
    s2 = re.sub(r'<a href="#" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">((?:(?!</a>).)*?\n\s*Workflows\n)', r'<a href="WorkflowsPhone.dc.html" class="nav-item" style="\1">\2', s2, flags=re.S)
    if s2 != s: p.write_text(s2)

boards, notes, y = {}, {}, 12700
for n, h, t in [('Workflows', 1000, 'Workflows'), ('WorkflowBuilder', 1100, 'Workflow builder'), ('Doa', 1100, 'Delegation of authority'), ('Queues', 1000, 'Queues'), ('Substitutions', 1000, 'Substitutions'), ('BusinessCalendar', 1000, 'Business calendar')]:
    boards[n + '.dc.html'] = {'x': 0, 'y': y, 'w': 1440, 'h': h, 'title': t + ' — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'}
    boards[n + 'Phone.dc.html'] = {'x': 1520, 'y': y, 'w': 390, 'h': 844, 'title': t + ' — Phone', 'is_interactive': True, 'page': 'admin'}
    notes['wf' + n + 'Title'] = {'page': 'admin', 'x': 0, 'y': y - 260, 'text': {'WorkflowBuilder': 'Workflow builder (diagram, steps, JSON, changes, simulate)'}.get(n, t), 'kind': 'title1', 'maxW': 1910}
    y += h + 360
notes['workflowsNote'] = {'page': 'admin', 'x': 2030, 'y': 12700, 'w': 620, 'fill': 'blue', 'size': 'm', 'text':
  'Workflows: definitions, builder, delegation of authority, queues, substitutions, business calendar\n\n'
  '• The five pages share a row of section links; the sidebar’s Workflows entry opens the section. Open tasks keeps its own sidebar entry.\n'
  '• Builder: the version picker and states work as for memo types (draft, in review, published). Views: Diagram, Steps (the same workflow as a list, for keyboard and screen readers), JSON, and Changes while in review. The side panel shows the selected step, or Simulate. Added, changed, reached and problem steps are marked with a word, never colour alone.\n'
  '• Check runs the validator and shows the result above the view; Submit is refused with “Fix the problems first.” while there are problems.\n'
  '• Phones: no diagram (it needs a wider screen); Steps is the default view, and a step opens as a sheet.\n'
  '• Published version: “Memos on older versions” lists running memos and which can move.\n'
  '• Delegation of authority: versions, then the selected version’s date and rules (first match applies) with approval groups; “Who approves this?” sits beside it. A rule opens for editing with Change.\n'
  '• Queues: like distribution lists, with a key that cannot change once made.\n'
  '• Substitutions: In force / Planned / Ended tabs; Move open tasks and End today. Business calendar: weekly hours, a month view of holidays and special hours, and “When is it due?”.\n'
  '• API: nothing new. The workflow list tabs and filter are worked out in the browser.'}
canvas_add(boards, notes, done=('Workflows', 'Workflow builder (visual editor)', 'Delegation of authority', 'Queues', 'Substitutions', 'Business calendar'))
print('ok', y)
