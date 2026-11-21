"""Admin · Memo types, Memo type editor, Memo numbering, Distribution lists (desktop and phone).

Copy and rules from web/src/features/memo-types (MemoTypesPage, MemoTypePage, SettingsTab, FieldsTab, TemplateBlock,
NumberingTab, NumberingPage, messages.ts) and features/memo/DistributionListsPage.tsx; state labels from
workflows/workflow-messages.ts. Builds MemoTypes, MemoTypeEditor, MemoNumbering, DistributionLists and their phone
boards from the Open tasks shells; safe to rerun.
"""
from kit import *

MEMO = [('Memo types', 'MemoTypes'), ('Numbering', 'MemoNumbering'), ('Distribution lists', 'DistributionLists')]
def nav(active, phone): return section_nav(active, phone, MEMO, 'Memos')

# ================================================================ Memo types list
TYPES_JS = r"""
    const T = (id, name, code, pub, changes, changesTone, active) => ({ id: id, name: name, code: code, pub: pub, changes: changes, changesTone: changesTone, active: active });
    const TYPES = [
      T('cr', 'Credit proposal', 'CR', 4, 'Version 5 in review, submitted by Shirin Akter', 'warn', true),
      T('fin', 'Budget request', 'FIN', 2, '', '', true),
      T('capex', 'Capital expenditure', 'CAPEX', 3, '', '', true),
      T('prc', 'Procurement', 'PRC', 2, 'Version 3: Draft', 'neutral', true),
      T('hr', 'HR request', 'HR', 1, '', '', true),
      T('aud', 'Audit', 'AUD', 1, '', '', true),
      T('pol', 'Policy', 'POL', 2, '', '', true),
      T('brd', 'Board paper', 'BRD', 1, '', '', true),
      T('trv', 'Travel request', 'TRV', 0, 'Version 1: Draft', 'neutral', false),
      T('lv', 'Leave application', 'LV', 1, '', '', false)
    ];
    const filter = st.filter || 'all', q = (st.q || '').toLowerCase();
    const shown = TYPES.filter((t) => (filter === 'all' || (filter === 'on') === t.active) && (!q || (t.name + ' ' + t.code).toLowerCase().indexOf(q) >= 0));
    const target = TYPES.find((t) => t.id === (st.target || 'lv')) || TYPES[9];
    const switchOff = (t) => this.setState({ dlg: 'switchOff', target: t.id, err: 'none', menu: '' });
    const itemsFor = (t) => [item('Open', () => this.setState({ menu: '' })), t.active ? item('Switch off', () => switchOff(t), true) : item('Switch on', () => this.setState({ menu: '', toast: 'Saved.' }))];
    const types = shown.map((t) => { const open = st.menu === t.id; return {
      name: t.name, code: t.code, pub: t.pub ? 'v' + t.pub : 'Not yet', pubColor: t.pub ? 'var(--fg)' : 'var(--muted-fg)',
      hasChanges: !!t.changes, noChanges: !t.changes, changes: t.changes, changesStyle: t.changesTone === 'warn' ? 'color: var(--warn-fg); font-weight: 500' : 'color: var(--text-2)',
      statusLabel: t.active ? 'In use' : 'Switched off', statusStyle: TONE[t.active ? 'ok' : 'neutral'],
      phoneMeta: (t.pub ? 'v' + t.pub : 'Not published yet') + (t.changes ? ' · ' + t.changes : ''),
      items: itemsFor(t), menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + t.name, toggleMenu: () => this.setState({ menu: open ? '' : t.id }) }; });
    const TABS = [['all', 'All'], ['on', 'In use'], ['off', 'Switched off']];
    const tabBase = 'display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; border-radius: 7px; font-size: 13px; font-weight: 500; cursor: pointer; white-space: nowrap; ';
    const pill = 'flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 14px; border-radius: 18px; font-size: 13px; font-weight: 500; white-space: nowrap; ';
    const tabs = TABS.map(([id, l]) => { const on = id === filter; return { label: l, aria: on ? 'true' : 'false', pick: () => this.setState({ filter: id }),
      style: PHONE ? pill + (on ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: var(--text-2)')
                   : tabBase + (on ? 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06)' : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)') }; });
    const sheetT = PHONE ? TYPES.find((t) => t.id === st.menu) : null;
    const name = form.name !== undefined ? form.name : (err === 'required' ? '' : 'Vendor payment');
    const code = form.code !== undefined ? form.code : (err === 'required' ? '' : (err === 'duplicate' ? 'PRC' : 'VP'));
    const tried = !!form.tried || err === 'required';
    const nameErr = tried && !name.trim() ? 'Required.' : '';
    const codeErr = tried && !code.trim() ? 'Required.' : (err === 'duplicate' ? 'Another memo type has this code.' : '');
    const ntAlert = err === 'failed' && dlg === 'new' ? 'That did not work. Try again.' : '';
    const cfAlert = ({ lastActive: 'Keep at least one memo type in use.', failed: 'That did not work. Try again.' })[err] || '';
    const vals = Object.assign(base, {
      types: types, hasTypes: types.length > 0, noTypes: types.length === 0, tabs: tabs, q: st.q || '', onQ: (ev) => this.setState({ q: ev && ev.target ? ev.target.value : '' }),
      footNote: TYPES.length + ' memo types. Every change is reviewed by a second administrator before it applies.',
      openNew: () => this.setState({ dlg: 'new', form: {}, err: 'none' }),
      sheetOpen: !!sheetT, sheet: sheetT ? { name: sheetT.name, items: itemsFor(sheetT) } : { name: '', items: [] },
      isNew: dlg === 'new', isConfirm: dlg === 'switchOff',
      nt: Object.assign({ on_name: setForm('name'), on_code: setForm('code'), hasAlert: !!ntAlert, alert: ntAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!name.trim() || !code.trim() || err === 'duplicate') { this.setState({ form: Object.assign({}, form, { name: name, code: code, tried: true }) }); return; }
          if (ntAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Draft created. Opening it…' }); } },
        fieldVals('nt', 'name', name, nameErr), fieldVals('nt', 'code', code, codeErr)),
      cf: { title: 'Switch off ' + target.name + '?', explain: 'People can no longer choose it for new memos. Memos already written keep it, and you can switch it on again.', confirm: 'Switch off', cancel: 'Keep', btnStyle: okStyle(true),
        hasDetail: false, detail: '', detailSub: '', hasAlert: !!cfAlert, alert: cfAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (cfAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', toast: 'Saved.' }); } }
    });
"""
TYPES_PROPS = '"dialog":{"editor":"enum","options":["none","new","switchOff"],"default":"none"},"error":{"editor":"enum","options":["none","required","duplicate","lastActive","failed"],"default":"none"},'

TYPES_DESK = T('''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      [[nav]]
[[head]]
[[toolbar]]
      <div style="display: flex; flex-direction: column; gap: 8px">
        <div style="[[card]]; overflow-x: auto">
          <table style="width: 100%; min-width: 900px; border-collapse: collapse; font-size: 14px">
            <caption style="[[sr]]">Memo types of the organisation</caption>
            <thead><tr style="border-bottom: 1px solid var(--border)">
              <th scope="col" style="height: 40px; [[th0]]">Name</th>
              <th scope="col" style="[[th]]; width: 100px">Code</th>
              <th scope="col" style="[[th]]; width: 100px">Published</th>
              <th scope="col" style="[[th]]; width: 340px">Changes</th>
              <th scope="col" style="[[th]]; width: 130px">Status</th>
              <th scope="col" style="width: 44px"><span style="[[sr]]">Actions</span></th>
            </tr></thead>
            <tbody>
              <sc-for list="{{types}}" as="r" hint-placeholder-count="10">
                <tr class="lrow" style="border-bottom: 1px solid var(--hover)">
                  <td style="height: 48px; padding: 0 16px; vertical-align: middle"><a href="MemoTypeEditor.dc.html" style="font-weight: 500; color: var(--fg); text-decoration: none">{{r.name}}</a></td>
                  <td style="padding: 0 12px; vertical-align: middle; [[mono]]; font-size: 13px; color: var(--text-2)">{{r.code}}</td>
                  <td style="padding: 0 12px; vertical-align: middle; color: {{r.pubColor}}; font-variant-numeric: tabular-nums">{{r.pub}}</td>
                  <td style="padding: 0 12px; vertical-align: middle"><sc-if value="{{r.hasChanges}}" hint-placeholder-val="{{false}}"><span style="{{r.changesStyle}}">{{r.changes}}</span></sc-if><sc-if value="{{r.noChanges}}" hint-placeholder-val="{{true}}"><span style="color: var(--muted-fg)">None</span></sc-if></td>
                  <td style="padding: 0 12px; vertical-align: middle"><span style="{{r.statusStyle}}">{{r.statusLabel}}</span></td>
                  <td style="position: relative; padding: 0 8px 0 0; vertical-align: middle; text-align: right">[[menuBtn]][[menu]]</td>
                </tr>
              </sc-for>
            </tbody>
          </table>
        </div>
        <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px">[[foot]]</div>
      </div>
    </main>
  </div>
''', nav=nav('Memo types', False), head=page_head('Memo types', 'What people choose when they write a memo: its form, numbering and approval. Every change is reviewed by a second administrator before it applies.', 'New memo type', 'openNew', False),
     toolbar=toolbar(False, 'Filter by name or code…'), card=CARD, sr=SR, th0=TH.replace('0 12px', '0 16px', 1), th=TH, mono=MONO, menuBtn=MENU_BTN, menu=MENU, foot=FOOT_NOTE)

TYPES_PHONE = T('''  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
    [[nav]]
[[head]]
[[toolbar]]
    <ul aria-label="Memo types of the organisation" style="list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--hover)">
      <sc-for list="{{types}}" as="r" hint-placeholder-count="10">
        <li style="display: flex; align-items: center; gap: 4px; padding: 10px 4px 10px 16px; border-bottom: 1px solid var(--hover)">
          <a href="MemoTypeEditorPhone.dc.html" style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; color: var(--fg); text-decoration: none">
            <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-size: 15px; font-weight: 500">{{r.name}}</span><span style="[[mono]]; font-size: 12px; color: var(--text-2)">{{r.code}}</span></span>
            <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-fg)"><span style="{{r.statusStyle}}">{{r.statusLabel}}</span>{{r.phoneMeta}}</span>
          </a>
          <button type="button" class="ghost" aria-label="{{r.menuLabel}}" aria-haspopup="dialog" onClick="{{r.toggleMenu}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--text-3)">[[dots]]</button>
        </li>
      </sc-for>
    </ul>
    <p style="margin: 0; padding: 12px 16px 24px; font-size: 13px; color: var(--muted-fg)">{{footNote}}</p>
  </main>

[[sheet]]''', nav=nav('Memo types', True), head=page_head('Memo types', 'Every change is reviewed by a second administrator before it applies.', 'New', 'openNew', True),
     toolbar=toolbar(True, 'Filter by name or code…'), mono=MONO, dots=DOTS18, sheet=SHEET)

def types_dialogs(phone):
    code_field = text_field('nt', 'code', 'Code', 'text', phone).replace('</div>', '  <span style="font-size: 12px; color: var(--muted-fg)">Up to 10 capital letters, digits or hyphens; used in memo numbers.</span>\n        </div>', 1)
    code_field = code_field.replace('font-size: ' + ('16px' if phone else '14px') + '; outline: none', 'font-size: ' + ('16px' if phone else '14px') + '; outline: none; ' + MONO + '; text-transform: uppercase', 1)
    nt = frame('\n'.join([heading('nt-title', 'New memo type', 'It starts as a draft. You fill in its form, numbering and approval, then a second administrator publishes it.'),
                          text_field('nt', 'name', 'Name', 'text', phone), code_field, alert('nt'), buttons('Create draft', primary(phone), phone)]), 'nt-title', 'dialog', 'nt.submit', phone)
    return f'''
  <sc-if value="{{{{isNew}}}}" hint-placeholder-val="{{{{false}}}}">
    {nt}
  </sc-if>
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm_dialog(phone)}
  </sc-if>
''' + toast(phone)

# ================================================================ Memo type editor
def settings_panel(phone):
    label, field, text, select, check, radios = ctl(phone)
    grid = f'display: grid; grid-template-columns: repeat(auto-fit, minmax({300 if phone else 380}px, 1fr)); gap: 16px; align-items: start'
    basics = card('Basics', field('Name', text('Credit proposal', 'st-name'), req=True, id_='st-name')
                  + field('Code', text('CR', 'st-code', mono=True, w='160px'), 'Up to 10 capital letters, digits or hyphens; used in memo numbers.', True, 'st-code')
                  + field('Default classification', select(['Lowest level', 'Internal', 'Restricted', 'Confidential', 'Strictly confidential'], 3, 'st-cls'), id_='st-cls'))
    approval = card('Approval', field('Workflow', select(['Credit approval (v6)', 'Two-step approval (v3)', 'Board approval (v2)'], 0, 'st-wf'), 'Memos submitted from now on use this version; memos already submitted keep theirs.', id_='st-wf')
                    + radios('Approvers', 'st-pol', ['The author chooses them', 'The workflow’s steps, plus those the author chooses', 'The workflow names every step'], 2)
                    + check('The route must give the delegation of authority its approvals before the memo can be submitted', True)
                    + check('Approvers confirm it is them before approving', True))
    unitchip = lambda n, sub: f'<li style="display: flex; align-items: center; gap: 8px; padding: {"10px 4px 10px 12px" if phone else "6px 4px 6px 12px"}; border-top: 1px solid var(--hover)"><span style="flex: 1; display: flex; flex-direction: column"><span>{n}</span><span style="font-size: 12px; color: var(--muted-fg)">{sub}</span></span><sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" aria-label="Remove {n}" style="height: {"44px" if phone else "28px"}; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; color: var(--fg); font-size: 13px; font-weight: 500">Remove</button></sc-if></li>'
    raise_ = card('Who may raise it', radios('Who may raise it', 'st-scope', ['Everyone who can create memos', 'Only people in these units'], 1).replace('<legend style="margin-bottom: 8px; padding: 0; font-size: 14px; font-weight: 500">Who may raise it</legend>', f'<legend style="{SR}">Who may raise it</legend>')
                  + f'<ul style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden">{unitchip("Credit Risk", "with the units below it").replace("border-top: 1px solid var(--hover)", "border-top: 0", 1)}{unitchip("Corporate Banking", "with the units below it")}{unitchip("Agro and SME Finance", "this unit only")}</ul>'
                  + f'<sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" style="align-self: flex-start; {OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">{svg(I["plus"], 14)}Add a unit</button></sc-if>')
    years = f'<div style="display: flex; gap: 8px">{text("10", w="96px", typ="text")}{select(["Approval", "Closure"], 1, w="180px")}</div>'
    records = card('Records', field('Keep memos for', years, 'Years, counted from approval or closure. Recorded now; records management acts on it later.')
                   + field('Close approved memos automatically after (days)', text('30', w='120px'), 'Leave empty to close them only by hand.')
                   + check('Attachments are required', True)
                   + f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "120px 1fr"}; gap: 12px; padding-left: {0 if phone else 26}px">' + field('At least', text('1', w='96px')) + field('What to attach', text('Credit memo with the borrower’s financial statements')) + '</div>')
    return f'<div style="{grid}">{basics}{approval}{raise_}{records}</div>'

DESK_FIELD_CTL = '''<sc-if value="{{editable}}" hint-placeholder-val="{{true}}"><span style="flex-shrink: 0; display: flex; align-items: center">
                  <button type="button" class="ghost" aria-label="{{f.upLabel}}" disabled="{{f.first}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3)">''' + svg(I['up'], 15) + '''</button>
                  <button type="button" class="ghost" aria-label="{{f.downLabel}}" disabled="{{f.last}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3)">''' + svg(I['down'], 15) + '''</button>
                  <button type="button" class="ghost" aria-label="{{f.editLabel}}" onClick="{{f.edit}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3)">''' + svg(I['pencil'], 14) + '''</button>
                  <button type="button" class="ghost" aria-label="{{f.removeLabel}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3)">''' + svg(I['trash'], 14) + '''</button>
                </span></sc-if>'''
PHONE_FIELD_BTN = '<sc-if value="{{editable}}" hint-placeholder-val="{{true}}"><button type="button" class="ghost" aria-label="{{f.menuLabel}}" aria-haspopup="dialog" onClick="{{f.toggleMenu}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--text-3)">' + DOTS18 + '</button></sc-if>'

def form_panel(phone):
    btn = 44 if phone else 28
    field_row = T('''<li style="display: flex; align-items: center; gap: 10px; padding: [[pad]]; border-top: 1px solid var(--hover)">
                <span aria-hidden="true" style="flex-shrink: 0; width: 20px; text-align: right; font-size: 12px; color: var(--muted-fg); font-variant-numeric: tabular-nums">{{f.n}}</span>
                <span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px">
                  <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-weight: 500">{{f.label}}</span><sc-if value="{{f.isNew}}" hint-placeholder-val="{{false}}">[[newchip]]</sc-if></span>
                  <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-fg)"><span>{{f.type}}</span><sc-for list="{{f.tags}}" as="t" hint-placeholder-count="2"><span style="display: inline-flex; align-items: center; height: 18px; padding: 0 6px; border-radius: 5px; background: var(--muted); color: var(--text-2); font-size: 11px; font-weight: 500">{{t.label}}</span></sc-for></span>
                </span>
[[controls]]
              </li>''', controls=PHONE_FIELD_BTN if phone else DESK_FIELD_CTL, pad='10px 4px 10px 12px' if phone else '8px 8px 8px 12px', newchip=chip('Added in this version', 'ok').replace('height: 22px', 'height: 20px').replace('font-size: 12px', 'font-size: 11px'),
              b=str(btn), up=svg(I['up'], 15), down=svg(I['down'], 15), pencil=svg(I['pencil'], 14), trash=svg(I['trash'], 14))
    fields = f'''<section style="{CARD}; display: flex; flex-direction: column">
            <div style="display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 16px 16px 12px"><span style="display: flex; flex-direction: column; gap: 4px"><h3 style="margin: 0; font-size: 14px; font-weight: 600">Fields</h3><span style="font-size: 13px; color: var(--muted-fg)">What the author fills in, in this order, after the subject.</span></span>
              <sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" onClick="{{{{openField}}}}" style="flex-shrink: 0; {OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">{svg(I["plus"], 14)}Add field</button></sc-if></div>
            <ol style="list-style: none; margin: 0; padding: 0">
              <sc-for list="{{{{fields}}}}" as="f" hint-placeholder-count="8">
              {field_row}
              </sc-for>
            </ol>
          </section>'''
    tag = lambda t: f'<span style="display: inline-flex; align-items: center; height: 22px; padding: 0 6px; border-radius: 5px; border: 1px solid var(--border); {MONO}; font-size: 12px; color: var(--text-2)">{t}</span>'
    template = f'''<section style="{CARD}; padding: 16px; display: flex; flex-direction: column; gap: 12px">
            <h3 style="margin: 0; font-size: 14px; font-weight: 600">Template</h3>
            <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px">
              <span style="display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 6px; background: var(--muted); color: var(--text-2)">{svg(I["file"])}</span>
              <span style="flex: 1; min-width: 160px; display: flex; flex-direction: column"><span style="font-weight: 500">credit-proposal.docx</span><span style="font-size: 12px; color: var(--muted-fg)">48 KB · uploaded with this version</span></span>
              {chip("Previewed", "ok")}
            </div>
            <div style="display: flex; flex-wrap: wrap; gap: 8px">
              <button type="button" class="ghost" style="{OUTLINE}">Preview as PDF</button>
              <sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" style="{OUTLINE}">Replace the template</button><button type="button" class="ghost" style="{OUTLINE.replace("color: var(--fg)", "color: var(--danger-fg)")}">Remove</button></sc-if>
            </div>
            <span style="font-size: 12px; color: var(--muted-fg)">A .docx file, at most 10 MB. Macros and templates (.dotx) are refused.</span>
            <div style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 13px; font-weight: 500">Tags it uses</span><span style="display: flex; flex-wrap: wrap; gap: 6px"><sc-for list="{{{{tagsUsed}}}}" as="tg" hint-placeholder-count="6">{tag("{{tg.label}}")}</sc-for></span></div>
            <div style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 13px; font-weight: 500">Tags you can use</span><span style="display: flex; flex-wrap: wrap; gap: 6px"><sc-for list="{{{{tagsFree}}}}" as="tg" hint-placeholder-count="7">{tag("{{tg.label}}").replace("<span ", "<button type=\"button\" class=\"ghost\" ", 1).replace("</span>", "</button>").replace("{MONO}", "{MONO}; background: var(--bg); cursor: pointer")}</sc-for></span><span style="font-size: 12px; color: var(--muted-fg)">Select a tag to copy it, then paste it into the document in Word.</span></div>
          </section>'''
    h = '44px' if phone else '36px'
    inp = f'height: {h}; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--muted-fg); font-family: inherit; font-size: 14px; display: flex; align-items: center'
    pv = lambda l, ctl_, req=True, hint='': f'<div style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 14px; font-weight: 500">{l}{" <span aria-hidden=\"true\" style=\"color: var(--danger-fg)\">*</span>" if req else ""}</span>{ctl_}' + (f'<span style="font-size: 12px; color: var(--muted-fg)">{hint}</span>' if hint else '') + '</div>'
    box = lambda t='': f'<span style="{inp}">{t}</span>'
    preview = f'''<section aria-label="Preview" style="{CARD}; padding: 16px; display: flex; flex-direction: column; gap: 14px; background: var(--subtle)">
            <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><h3 style="margin: 0; font-size: 14px; font-weight: 600">Preview</h3><span style="font-size: 12px; color: var(--muted-fg)">As the author sees it</span></div>
            {pv("Subject", box())}
            {pv("Borrower", box(), hint="Legal name, as on the trade licence.")}
            {pv("Facility amount", f'<span style="display: flex; gap: 8px"><span style="{inp}; width: 96px">BDT</span><span style="{inp}; flex: 1"></span></span>')}
            {pv("Facility type", box("Choose…"))}
            {pv("Tenor in months", box(), False)}
            {pv("Collateral value", f'<span style="display: flex; gap: 8px"><span style="{inp}; width: 96px">BDT</span><span style="{inp}; flex: 1"></span></span>', False)}
            {pv("Branch", box("Search units"))}
            {pv("Relationship manager", box("Search people"), False)}
            {pv("Purpose", f'<span style="{inp}; height: 72px"></span>', False)}
          </section>'''
    cols = 'display: flex; flex-direction: column; gap: 16px' if phone else 'display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 16px; align-items: start'
    return f'<div style="{cols}"><div style="display: flex; flex-direction: column; gap: 16px; min-width: 0">{fields}{template}</div>{preview}</div>'

def numbering_panel(phone):
    label, field, text, select, check, radios = ctl(phone)
    b = 44 if phone else 28
    part = lambda kind, value, extra='': f'''<li style="display: flex; align-items: center; gap: 10px; padding: {"10px 4px 10px 12px" if phone else "6px 8px 6px 12px"}; border-top: 1px solid var(--hover)">
                <span style="width: 120px; flex-shrink: 0; font-size: 13px; color: var(--muted-fg)">{kind}</span>
                <span style="flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px">{value}</span>
                <sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><span style="flex-shrink: 0; display: flex">{"".join(f'<button type="button" class="ghost" aria-label="{a} {kind}" style="display: inline-flex; align-items: center; justify-content: center; width: {b}px; height: {b}px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3)">{svg(I[i], 14)}</button>' for a, i in [("Move up", "up"), ("Move down", "down"), ("Remove", "trash")])}</span></sc-if>
              </li>'''
    small = lambda v, w='72px', mono=True: text(v, w=w, mono=mono)
    parts = f'''<section style="{CARD}; display: flex; flex-direction: column">
            <div style="padding: 16px 16px 12px; display: flex; flex-direction: column; gap: 4px"><h3 style="margin: 0; font-size: 14px; font-weight: 600">Number parts</h3><span style="font-size: 13px; color: var(--muted-fg)">Numbers never repeat and leave no gaps. Types with the same series share one register.</span></div>
            <ol style="list-style: none; margin: 0; padding: 0">
              {part("Type code", f'<span style="{MONO}; font-weight: 500">CR</span>').replace("border-top: 1px solid var(--hover)", "border-top: 1px solid var(--border)", 1)}
              {part("Text", small("/", "64px"))}
              {part("Year", '<span style="font-weight: 500">2026</span>')}
              {part("Text", small("/", "64px"))}
              {part("Sequence", f'<span style="font-size: 13px; color: var(--muted-fg)">Digits</span>{small("4", "64px", False)}')}
            </ol>
            <sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 12px 16px; border-top: 1px solid var(--border)"><span style="font-size: 13px; color: var(--muted-fg); margin-right: 4px">Add</span>{"".join(f'<button type="button" class="ghost" style="{OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "28px")).replace("padding: 0 12px", "padding: 0 10px")}">{svg(I["plus"], 12)}{x}</button>' for x in ["Text", "Unit code", "Financial year"])}</div></sc-if>
          </section>'''
    rules = card('How it counts', field('Series', text('credit', mono=True, w='200px'), 'Lower-case letters, digits and hyphens. Only Credit proposal numbers in this series.')
                 + field('Start again at 1', select(['Never', 'Every year', 'Every financial year'], 1, w='240px'))
                 + radios('Count', 'nm-count', ['One sequence for the series', 'One sequence per unit'], 0)
                 + radios('Give the number when the memo is', 'nm-moment', ['Submitted', 'Finally approved'], 0))
    sample = f'''<section aria-live="polite" style="border: 1px solid var(--border); border-radius: 10px; background: var(--subtle); padding: 14px 16px; display: flex; flex-direction: column; gap: 8px">
            <h3 style="margin: 0; font-size: 13px; font-weight: 500">Sample</h3>
            <span style="display: flex; flex-wrap: wrap; gap: 8px">{"".join(f'<span style="display: inline-flex; align-items: center; height: 28px; padding: 0 10px; border-radius: 6px; background: var(--card); border: 1px solid var(--border); {MONO}; font-size: 13px">{x}</span>' for x in ["CR/2026/0159", "CR/2026/0160", "CR/2027/0001"])}</span>
            <span style="font-size: 12px; color: var(--muted-fg)">The next numbers, made by the same rules the server uses. The last one shows the yearly restart.</span>
          </section>'''
    cols = 'display: flex; flex-direction: column; gap: 16px' if phone else 'display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 16px; align-items: start'
    return f'<div style="{cols}"><div style="display: flex; flex-direction: column; gap: 16px; min-width: 0">{parts}{sample}</div>{rules}</div>'

def changes_panel(phone):
    if phone:
        return T('''<div style="display: flex; flex-direction: column; gap: 10px"><span style="font-size: 13px; color: var(--muted-fg)">What this version changes compared with the published one.</span>
          <ul style="list-style: none; margin: 0; padding: 0; [[card]]; overflow: hidden">
            <sc-for list="{{changes}}" as="c" hint-placeholder-count="5">
              <li style="{{c.liStyle}}"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="{{c.kindStyle}}">{{c.kind}}</span><span style="font-weight: 500">{{c.what}}</span></span>
                <sc-if value="{{c.hasBefore}}" hint-placeholder-val="{{true}}"><span style="font-size: 13px; color: var(--muted-fg); text-decoration: line-through">{{c.before}}</span></sc-if>
                <sc-if value="{{c.hasAfter}}" hint-placeholder-val="{{true}}"><span style="font-size: 13px">{{c.after}}</span></sc-if></li>
            </sc-for>
          </ul></div>''', card=CARD)
    return T('''<div style="display: flex; flex-direction: column; gap: 10px"><span style="font-size: 13px; color: var(--muted-fg)">What this version changes compared with the published one.</span>
          <div style="[[card]]; overflow-x: auto"><table style="width: 100%; min-width: 760px; border-collapse: collapse; font-size: 14px">
            <caption style="[[sr]]">Changes in this version</caption>
            <thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 40px; [[th0]]; width: 240px">What</th><th scope="col" style="[[th]]; width: 120px">Change</th><th scope="col" style="[[th]]">Before (version 4)</th><th scope="col" style="[[th]]">This version</th></tr></thead>
            <tbody><sc-for list="{{changes}}" as="c" hint-placeholder-count="5"><tr style="border-bottom: 1px solid var(--hover)">
              <td style="padding: 10px 16px; vertical-align: top; font-weight: 500">{{c.what}}</td>
              <td style="padding: 10px 12px; vertical-align: top"><span style="{{c.kindStyle}}">{{c.kind}}</span></td>
              <td style="padding: 10px 12px; vertical-align: top; color: var(--muted-fg)">{{c.beforeText}}</td>
              <td style="padding: 10px 12px; vertical-align: top">{{c.afterText}}</td>
            </tr></sc-for></tbody>
          </table></div></div>''', card=CARD, sr=SR, th0=TH.replace('0 12px', '0 16px', 1), th=TH)

def action_bar(phone):
    h = '44px' if phone else '36px'; fs = '15px' if phone else '14px'
    ob = f'height: {h}; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: {fs}; font-weight: 500; cursor: pointer'
    pb = primary(phone)
    stack = 'flex-direction: column; align-items: stretch' if phone else 'flex-direction: row; align-items: center; justify-content: flex-end'
    pos = 'flex-shrink: 0; padding: 12px 16px 16px; border-top: 1px solid var(--border); background: var(--card)' if phone else f'position: sticky; bottom: 0; z-index: 3; padding: 12px 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 -4px 16px rgba(0,0,0,0.06)'
    return T('''<sc-if value="{{showBar}}" hint-placeholder-val="{{true}}"><div style="[[pos]]; display: flex; flex-direction: column; gap: 10px">
        [[alert]]
        <sc-if value="{{isDraft}}" hint-placeholder-val="{{false}}"><div style="display: flex; [[stack]]; gap: 8px"><span style="flex: 1; font-size: 13px; color: var(--muted-fg)">{{draftNote}}</span><div style="display: flex; gap: 8px"><button type="button" class="ghost" onClick="{{save}}" style="[[ob]][[grow]]">Save</button><button type="button" onClick="{{submitReview}}" style="[[pb]][[grow2]]">Submit for review</button></div></div></sc-if>
        <sc-if value="{{isReview}}" hint-placeholder-val="{{true}}"><form novalidate onSubmit="{{noop}}" style="display: flex; [[stack]]; gap: 10px">
          <div style="flex: 1; display: flex; flex-direction: column; gap: 4px; min-width: 0"><label for="rv-remark" style="[[sr]]">Remark</label><textarea id="rv-remark" rows="1" placeholder="Remark" aria-invalid="{{rv.remarkInvalid}}" aria-describedby="rv-remark-msg rv-remark-hint" style="width: 100%; min-height: [[h]]; padding: 8px 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: [[fs]]; resize: vertical; outline: none; border: 1px solid {{rv.remarkBorder}}">{{rv.remark}}</textarea>
            <sc-if value="{{rv.remarkHasErr}}" hint-placeholder-val="{{false}}"><span id="rv-remark-msg" style="font-size: 12px; color: var(--danger-fg)">{{rv.remarkErr}}</span></sc-if><span id="rv-remark-hint" style="font-size: 12px; color: var(--muted-fg)">Required to send it back; optional when approving.</span></div>
          <div style="display: flex; gap: 8px"><button type="button" class="ghost" onClick="{{rv.sendBack}}" style="[[ob]][[grow]]">Send back</button><button type="button" onClick="{{rv.approve}}" style="[[pb]][[grow2]]">Approve and publish</button></div>
        </form></sc-if>
      </div></sc-if>''', pos=pos, grow='; flex: 1' if phone else '', grow2='; flex: 2' if phone else '', alert=alert('bar').replace('        <sc-if', '<sc-if', 1), stack=stack, ob=ob, pb=pb, sr=SR, h=h, fs='16px' if phone else '14px')

def editor_body(phone):
    tabs = ('<div class="chips" role="tablist" aria-label="Parts of the memo type" style="display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none; margin: 0 -16px; padding: 0 16px"><sc-for list="{{tabs}}" as="t" hint-placeholder-count="4"><button type="button" role="tab" aria-selected="{{t.aria}}" onClick="{{t.pick}}" style="{{t.style}}">{{t.label}}<sc-if value="{{t.hasCount}}" hint-placeholder-val="{{false}}"><span style="{{t.countStyle}}">{{t.count}}</span></sc-if></button></sc-for></div>' if phone else
            '<div role="tablist" aria-label="Parts of the memo type" style="align-self: flex-start; display: inline-flex; align-items: center; gap: 2px; height: 36px; padding: 3px; box-sizing: border-box; border-radius: 10px; background: var(--muted)"><sc-for list="{{tabs}}" as="t" hint-placeholder-count="4"><button type="button" role="tab" class="tab" aria-selected="{{t.aria}}" onClick="{{t.pick}}" style="{{t.style}}">{{t.label}}<sc-if value="{{t.hasCount}}" hint-placeholder-val="{{false}}"><span style="{{t.countStyle}}">{{t.count}}</span></sc-if></button></sc-for></div>')
    label, field, text, select, check, radios = ctl(phone)
    vsel = f'''<span style="position: relative; display: flex; align-items: center; width: {"100%" if phone else "260px"}"><select aria-label="Version" onChange="{{{{noop}}}}" style="width: 100%; height: {"44px" if phone else "32px"}; padding: 0 30px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {"16px" if phone else "13px"}; font-weight: 500; box-shadow: 0 1px 2px rgba(0,0,0,0.05)"><sc-for list="{{{{versions}}}}" as="v" hint-placeholder-count="4"><option selected="{{{{v.sel}}}}">{{{{v.label}}}}</option></sc-for></select>{svg(I["chev"], 14, ' style="position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none"')}</span>'''
    return T('''[[vrow]]
      <sc-if value="{{hasRemark}}" hint-placeholder-val="{{false}}"><div style="display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border: 1px solid var(--warn-border); border-radius: 8px; background: var(--warn-bg); font-size: 13px"><strong style="font-weight: 600; color: var(--warn-fg)">Sent back by Arif Rahman</strong><span>{{remark}}</span></div></sc-if>
      <sc-if value="{{readOnly}}" hint-placeholder-val="{{false}}"><div style="padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--subtle); font-size: 13px; color: var(--text-2)">{{readOnlyText}}</div></sc-if>
      <sc-if value="{{hasProblems}}" hint-placeholder-val="{{false}}"><div role="alert" style="display: flex; flex-direction: column; gap: 4px; padding: 10px 12px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px"><strong style="font-weight: 600">Fix these before saving:</strong><ul style="margin: 0; padding-left: 18px"><sc-for list="{{problems}}" as="p" hint-placeholder-count="2"><li>{{p.text}}</li></sc-for></ul></div></sc-if>
      [[tabs]]
      <sc-if value="{{tabSettings}}" hint-placeholder-val="{{false}}">[[settings]]</sc-if>
      <sc-if value="{{tabForm}}" hint-placeholder-val="{{false}}">[[form]]</sc-if>
      <sc-if value="{{tabNumbering}}" hint-placeholder-val="{{false}}">[[numbering]]</sc-if>
      <sc-if value="{{tabChanges}}" hint-placeholder-val="{{true}}">[[changes]]</sc-if>''',
      vrow=(f'<div style="display: flex; flex-direction: column; gap: 6px">{vsel}<span style="font-size: 13px; color: var(--muted-fg)">{{{{versionNote}}}}</span></div>' if phone else
            f'<div style="display: flex; flex-wrap: wrap; align-items: center; gap: 12px">{vsel}<span style="{{{{versionChip}}}}">{{{{versionState}}}}</span><span style="font-size: 13px; color: var(--muted-fg)">{{{{versionNote}}}}</span></div>'),
      tabs=tabs, settings=settings_panel(phone), form=form_panel(phone), numbering=numbering_panel(phone), changes=changes_panel(phone))

EDITOR_DESK = T('''    <main style="flex: 1; padding: 20px 24px 24px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      <a href="MemoTypes.dc.html" style="align-self: flex-start; display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--muted-fg); text-decoration: none">[[back]]Back to Memo types</a>
      <div style="display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 12px; margin-top: -8px">
        <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 10px"><h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">Credit proposal</h1><span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); [[mono]]; font-size: 12px; color: var(--text-2)">CR</span><span style="{{activeStyle}}">{{activeLabel}}</span></div>
        <div style="display: flex; flex-wrap: wrap; gap: 8px"><sc-if value="{{canNewDraft}}" hint-placeholder-val="{{false}}"><button type="button" onClick="{{newDraft}}" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 13px; font-weight: 500; cursor: pointer">[[plus]]New draft</button></sc-if><button type="button" class="ghost" style="[[outline]]">Switch off</button></div>
      </div>
[[body]]
      [[bar]]
    </main>
  </div>
''', back=svg(I['back'], 14), mono=MONO, plus=svg(I['plus'], 14), outline=OUTLINE, body=editor_body(False), bar=action_bar(False))

EDITOR_PHONE = T('''  <main class="ed-main" style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 16px 16px 24px">
    <div style="display: flex; flex-direction: column; gap: 6px"><h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Credit proposal</h1><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); [[mono]]; font-size: 12px; color: var(--text-2)">CR</span><span style="{{activeStyle}}">{{activeLabel}}</span><span style="{{versionChip}}">{{versionState}}</span></span></div>
[[body]]
  </main>
  [[bar]]
[[sheet]]''', mono=MONO, body=editor_body(True), bar=action_bar(True), sheet=SHEET)

def editor_dialogs(phone):
    label, field, text, select, check, radios = ctl(phone)
    fd_head = heading('fd-title', 'Edit field', 'Field “Facility amount”. Rules use its key, so it cannot change once published.')
    key_field = text_field('fd', 'key', 'Key', 'text', phone).replace('</div>', '  <span style="font-size: 12px; color: var(--muted-fg)">A letter, then letters or digits (for example amount). Rules use it; do not change it once published.</span>\n        </div>', 1).replace('outline: none;', 'outline: none; ' + MONO + ';', 1)
    body = '\n'.join([text_field('fd', 'label', 'Label', 'text', phone), key_field,
        f'        <div style="display: flex; flex-direction: column; gap: 6px"><label for="fd-help" style="font-size: 14px; font-weight: 500">Help text</label><input id="fd-help" type="text" value="In the currency of the facility." style="height: {"44px" if phone else "36px"}; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {"16px" if phone else "14px"}; outline: none"></div>',
        '        ' + field('Type', select(['Text', 'Long text', 'Number', 'Amount', 'Date', 'Unit', 'Person', 'Choice', 'Multiple choice'], 3).replace('disabled="{{ro}}"', '')),
        f'        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px">' + field('Smallest value', text('100000').replace('disabled="{{ro}}"', '')) + field('Largest value', text('').replace('disabled="{{ro}}"', '')) + '</div>',
        '        ' + field('Currencies', text('BDT, USD', mono=True).replace('disabled="{{ro}}"', ''), 'ISO codes separated by commas; the first is the default.'),
        '        <div style="display: flex; flex-direction: column; gap: 8px">' + check('Required', True).replace('disabled="{{ro}}"', '') + check('Use in workflow rules', True, 'Rules and the delegation of authority can test its value.').replace('disabled="{{ro}}"', '') + check('Show in inbox lists', True).replace('disabled="{{ro}}"', '') + '</div>',
        alert('fd')])
    fd = frame_scroll(fd_head, body, buttons('Keep field', primary(phone), phone), 'fd-title', 'dialog', 'fd.submit', phone, width=560)
    return f'''
  <sc-if value="{{{{isField}}}}" hint-placeholder-val="{{{{false}}}}">
    {fd}
  </sc-if>
''' + toast(phone)

EDITOR_JS = r"""
    const state = st.state || this.props.state || 'review';
    const isDraft = state === 'draft', isReview = state === 'review', isPub = state === 'published';
    const tab = st.tab || (this.props.tab && this.props.tab !== 'auto' ? this.props.tab : (isReview ? 'changes' : 'settings'));
    const editable = isDraft;
    const FIELDS = [
      ['Borrower', 'Text', ['Required', 'Inbox lists'], false], ['Facility amount', 'Amount', ['Required', 'Workflow rules', 'Inbox lists'], false],
      ['Facility type', 'Choice', ['Required', 'Workflow rules'], false], ['Tenor in months', 'Number', [], false], ['Collateral value', 'Amount', [], true],
      ['Branch', 'Unit', ['Required', 'Workflow rules'], false], ['Relationship manager', 'Person', [], false], ['Purpose', 'Long text', ['Required'], false]
    ];
    const fields = FIELDS.map((f, i) => ({ n: String(i + 1), label: f[0], type: f[1], tags: f[2].map((t) => ({ label: t })), isNew: f[3] && !isPub,
      first: i === 0, last: i === FIELDS.length - 1, upLabel: 'Move ' + f[0] + ' up', downLabel: 'Move ' + f[0] + ' down', editLabel: 'Edit ' + f[0], removeLabel: 'Remove ' + f[0],
      edit: () => this.setState({ dlg: 'field', err: 'none', form: null, menu: '' }), menuLabel: 'Actions for ' + f[0], toggleMenu: () => this.setState({ menu: 'f' + i }) }));
    const fi = PHONE && st.menu ? parseInt(String(st.menu).slice(1), 10) : -1;
    const fieldSheet = fi >= 0 ? { name: FIELDS[fi][0], items: [].concat(fi > 0 ? [item('Move up', () => this.setState({ menu: '' }))] : [], fi < FIELDS.length - 1 ? [item('Move down', () => this.setState({ menu: '' }))] : [],
      [item('Edit', () => this.setState({ dlg: 'field', err: 'none', form: null, menu: '' })), item('Remove', () => this.setState({ menu: '' }), true)]) } : null;
    const C = (what, kind, before, after) => ({ what: what, kind: kind, before: before, after: after });
    const CHANGES = [
      C('Field “Collateral value”', 'Added', '', 'Amount, BDT or USD, optional'),
      C('Field “Branch code”', 'Removed', 'Text, required', ''),
      C('Approvers', 'Changed', 'The workflow’s steps, plus those the author chooses', 'The workflow names every step'),
      C('Template', 'Changed', 'credit-proposal.docx, 41 KB', 'credit-proposal.docx, 48 KB (adds {{collateral}})'),
      C('Close approved memos automatically after (days)', 'Changed', 'Only by hand', '30 days')
    ];
    const kindTone = { Added: 'ok', Removed: 'danger', Changed: 'neutral' };
    const changes = isPub ? [] : CHANGES.map((c, i) => ({ what: c.what, kind: c.kind, kindStyle: TONE[kindTone[c.kind]], before: c.before, after: c.after, hasBefore: !!c.before, hasAfter: !!c.after,
      beforeText: c.before || '—', afterText: c.after || '—', liStyle: 'display: flex; flex-direction: column; gap: 4px; padding: 12px' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') }));
    const TABS = [['settings', 'Settings'], ['form', 'Form'], ['numbering', 'Numbering'], ['changes', 'Changes']];
    const tabBase = 'display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; border-radius: 7px; font-size: 13px; font-weight: 500; cursor: pointer; white-space: nowrap; ';
    const pill = 'flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 14px; border-radius: 18px; font-size: 13px; font-weight: 500; white-space: nowrap; ';
    const tabs = TABS.map(([id, l]) => { const on = id === tab; return { label: l, aria: on ? 'true' : 'false', pick: () => this.setState({ tab: id }), hasCount: id === 'changes' && changes.length > 0, count: String(changes.length),
      countStyle: 'display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; padding: 0 5px; box-sizing: border-box; border-radius: 9px; font-size: 11px; ' + (on && PHONE ? 'background: var(--primary-divider); color: var(--primary-fg)' : 'background: var(--chip); color: var(--text-2)'),
      style: PHONE ? pill + (on ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: var(--text-2)')
                   : tabBase + (on ? 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06)' : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)') }; });
    const V = isPub ? [['Version 4 · Published', true], ['Version 3 · Retired', false], ['Version 2 · Retired', false], ['Version 1 · Retired', false]]
      : [['Version 5 · ' + (isDraft ? 'Draft' : 'In review'), true], ['Version 4 · Published', false], ['Version 3 · Retired', false], ['Version 2 · Retired', false], ['Version 1 · Retired', false]];
    const rv = st.rv || {};
    const remark = rv.remark !== undefined ? rv.remark : '';
    const remarkErr = (rv.tried || err === 'required') ? 'Required.' : '';
    const BAR = { selfReview: 'Someone other than the submitter has to review it.', conflict: 'Someone else changed this version. It has been reloaded with their changes.', movedOn: 'This version has moved on. Reload and try again.', failed: 'That did not work. Try again.' };
    const barAlert = BAR[err] || '';
    const fdLabel = form.label !== undefined ? form.label : (err === 'required' && dlg === 'field' ? '' : 'Facility amount');
    const fdErr = (form.tried || (err === 'required' && dlg === 'field')) && !fdLabel.trim() ? 'Required.' : '';
    const vals = Object.assign(base, {
      editable: editable, ro: !editable, isDraft: isDraft, isReview: isReview, showBar: !isPub,
      activeLabel: 'In use', activeStyle: TONE.ok, canNewDraft: isPub, newDraft: () => this.setState({ state: 'draft', toast: 'Version 5 started as a draft.' }),
      versions: V.map((v) => ({ label: v[0], sel: v[1] })), versionState: isPub ? 'Published' : (isDraft ? 'Draft' : 'In review'), versionChip: TONE[isPub ? 'ok' : (isDraft ? 'neutral' : 'warn')],
      versionNote: isReview ? 'Submitted by Shirin Akter, 17 Nov. Check the changes, then publish it or send it back.' : (isDraft ? 'Started by Shirin Akter, 14 Nov' : 'Published 2 Sep 2026 by Arif Rahman'),
      hasRemark: isDraft, remark: 'Reviewer’s remark: Keep Branch required, and add the collateral value to the template too.',
      readOnly: !isDraft, readOnlyText: isPub ? 'Only a draft can be changed. Start a new draft to change this type.' : 'In review: nothing can change until it is published or sent back.',
      hasProblems: err === 'invalid', problems: [{ text: 'Code: Up to 10 capital letters, digits or hyphens.' }, { text: 'Number parts: add a Sequence part.' }],
      tabs: tabs, tabSettings: tab === 'settings', tabForm: tab === 'form', tabNumbering: tab === 'numbering', tabChanges: tab === 'changes',
      fields: fields, changes: changes, sheetOpen: !!fieldSheet, sheet: fieldSheet || { name: '', items: [] },
      tagsUsed: ['subject', 'reference', 'borrower', 'amount', 'facility', 'approvals'].map((x) => ({ label: '{{' + x + '}}' })),
      tagsFree: ['author', 'date', 'tenor', 'collateral', 'branch', 'rm', 'purpose'].map((x) => ({ label: '{{' + x + '}}' })), openField: () => this.setState({ dlg: 'field', err: 'none', form: { label: '' } }),
      draftNote: st.saved ? 'Saved. Submit it when it is ready for review.' : 'Unsaved changes. Save before submitting.',
      save: () => this.setState({ saved: true, toast: 'Saved.' }), submitReview: () => { if (barAlert) { this.setState({ err: err }); return; } this.setState({ state: 'review', tab: 'changes', toast: 'Submitted for review.' }); },
      noop: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); },
      bar: { hasAlert: !!barAlert, alert: barAlert },
      rv: Object.assign({ sendBack: () => { if (!remark.trim()) { this.setState({ rv: Object.assign({}, rv, { tried: true }) }); return; } this.setState({ state: 'draft', toast: 'Sent back to Shirin Akter.' }); },
        approve: () => { if (barAlert) { this.setState({ err: err }); return; } this.setState({ state: 'published', tab: 'settings', toast: 'Version 5 published.' }); } }, fieldVals('rv', 'remark', remark, remarkErr)),
      isField: dlg === 'field',
      fd: Object.assign({ on_label: setForm('label'), on_key: setForm('key'), hasAlert: false, alert: '',
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!fdLabel.trim()) { this.setState({ form: Object.assign({}, form, { label: fdLabel, tried: true }) }); return; } this.setState({ dlg: '', form: null, toast: 'Field kept. Save the draft to keep it for good.' }); } },
        fieldVals('fd', 'label', fdLabel, fdErr), fieldVals('fd', 'key', 'amount', ''))
    });
"""
EDITOR_PROPS = '"state":{"editor":"enum","options":["review","draft","published"],"default":"review"},"tab":{"editor":"enum","options":["auto","settings","form","numbering","changes"],"default":"auto"},"dialog":{"editor":"enum","options":["none","field"],"default":"none"},"error":{"editor":"enum","options":["none","required","invalid","selfReview","conflict","failed"],"default":"none"},'

# ================================================================ Numbering
NUM_JS = r"""
    const S = [
      { id: 'credit', types: 'Credit proposal', perUnit: false, counters: [['2026', '', 'CR/2026/0158', false, 'c26'], ['2025', '', 'CR/2025/0611', false, 'c25']] },
      { id: 'fin', types: 'Budget request, Capital expenditure', perUnit: false, counters: [['2026', '', 'FIN/2026/0329', false, 'f26'], ['2025', '', 'FIN/2025/1187', false, 'f25']] },
      { id: 'policy', types: 'Policy', perUnit: true, counters: [['2026', 'IT', 'IT/2026/0054', false, 'pit'], ['2026', 'RSK', 'RSK/2026/0044', false, 'prk']] },
      { id: 'hr', types: 'HR request', perUnit: false, counters: [['2026', '', 'HR/2026/0129', true, 'h26']] }
    ];
    const sel = st.counter || 'c26';
    const gaps = (this.props.register || 'gaps') === 'gaps' && sel === 'c26';
    const btn = (on) => 'height: ' + (PHONE ? '40px' : '28px') + '; padding: 0 10px; border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer; ' + (on ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: var(--fg)');
    const series = S.map((s) => ({ id: s.id, types: 'Used by: ' + s.types, perUnit: s.perUnit, start: () => this.setState({ dlg: 'start', target: s.id, err: 'none', form: null }), startLabel: 'Set a starting number for ' + s.id,
      counters: s.counters.map((c, i) => ({ period: c[0], unit: c[1] || '—', last: c[2], started: c[3], open: sel === c[4], pick: () => this.setState({ counter: c[4] }), btnLabel: sel === c[4] ? 'Showing' : 'Register', btnStyle: btn(sel === c[4]),
        rowStyle: 'border-bottom: 1px solid var(--hover)' + (sel === c[4] ? '; background: var(--subtle)' : ''), liStyle: 'display: flex; align-items: center; gap: 10px; padding: 10px 12px' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') + (sel === c[4] ? '; background: var(--subtle)' : ''),
        meta: (c[1] ? c[1] + ' · ' : '') + 'last ' + c[2] })) }));
    const cur = (S.map((s) => s.counters.map((c) => ({ s: s, c: c }))).reduce((a, b) => a.concat(b), [])).find((x) => x.c[4] === sel);
    const prefix = cur.c[2].replace(/\d+$/, ''), lastN = parseInt(cur.c[2].match(/\d+$/)[0], 10), width = cur.c[2].match(/\d+$/)[0].length;
    const SUBJ = ['Limit increase for Rupali Foods', 'Working capital for Padma Textiles', 'Term loan, Rupsha Cold Storage', 'Overdraft limit, Karnaphuli Traders', 'LC facility, Surma Tea Estates', 'Restructuring, Jamuna Poultry', 'Lease finance, Teesta Logistics', 'Covenant waiver: Padma Textiles'];
    const register = [0, 1, 2, 3, 4, 5, 6, 7].map((k) => { const n = lastN - k; const num = prefix + String(n).padStart(width, '0');
      const isVoid = k === 3, hidden = k === 5, missing = gaps && k === 6;
      return { number: num, memo: missing ? 'No memo' : (hidden ? 'Not visible to you' : SUBJ[k]), memoStyle: missing ? 'color: var(--danger-fg); font-weight: 500' : (hidden ? 'color: var(--muted-fg); font-style: italic' : 'color: var(--fg)'),
        link: !missing && !hidden, plain: missing || hidden, status: missing ? '—' : (isVoid ? 'Withdrawn' : (k < 2 || k === 7 ? 'In review' : (k === 4 ? 'Closed' : 'Approved'))), isVoid: isVoid,
        rowStyle: 'border-bottom: 1px solid var(--hover)' + (missing ? '; background: var(--danger-bg)' : '') }; });
    const startErrs = { required: true };
    const period = form.period !== undefined ? form.period : (err === 'required' ? '' : '2027'), first = form.first !== undefined ? form.first : (err === 'required' ? '' : '1');
    const tried = !!form.tried || err === 'required';
    const stAlert = ({ used: 'This counter has given numbers already; its first number cannot be set.', failed: 'That did not work. Try again.' })[err] || '';
    const tgt = S.find((s) => s.id === (st.target || 'hr')) || S[3];
    const vals = Object.assign(base, {
      series: series, regTitle: 'Register of ' + cur.s.id + ' · ' + cur.c[0] + (cur.c[1] ? ' · ' + cur.c[1] : ''), register: register,
      complete: !gaps, hasGaps: gaps, gapText: '1 number has no memo: ' + prefix + String(lastN - 6).padStart(width, '0'),
      isStart: dlg === 'start',
      sn: Object.assign({ title: 'Set a starting number for ' + tgt.id, perUnit: tgt.perUnit, on_period: setForm('period'), on_first: setForm('first'), hasAlert: !!stAlert, alert: stAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!period.trim() || !first.trim()) { this.setState({ form: Object.assign({}, form, { period: period, first: first, tried: true }) }); return; }
          if (stAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Saved.' }); } },
        fieldVals('sn', 'period', period, tried && !period.trim() ? 'Required.' : ''), fieldVals('sn', 'first', first, tried && !first.trim() ? 'Required.' : ''))
    });
"""
NUM_PROPS = '"register":{"editor":"enum","options":["gaps","complete"],"default":"gaps"},"dialog":{"editor":"enum","options":["none","start"],"default":"none"},"error":{"editor":"enum","options":["none","required","used","failed"],"default":"none"},'

STARTED = chip('Started by an administrator', 'neutral').replace('height: 22px', 'height: 20px').replace('font-size: 12px', 'font-size: 11px')
VOID = chip('Void', 'danger').replace('height: 22px', 'height: 20px').replace('font-size: 12px', 'font-size: 11px')
def register_block(phone):
    return T('''<section aria-labelledby="reg-title" style="[[card]]; display: flex; flex-direction: column[[sticky]]">
          <div style="display: flex; flex-direction: column; gap: 8px; padding: 14px 16px 12px">
            <h2 id="reg-title" style="margin: 0; font-size: 15px; font-weight: 600">{{regTitle}}</h2>
            <sc-if value="{{complete}}" hint-placeholder-val="{{true}}"><span style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--ok-fg)">[[check]]Complete: every number up to the last has its memo.</span></sc-if>
            <sc-if value="{{hasGaps}}" hint-placeholder-val="{{false}}"><div role="status" style="display: flex; gap: 8px; padding: 8px 10px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px">[[alert]]<span>{{gapText}}</span></div></sc-if>
          </div>
          <table style="width: 100%; border-collapse: collapse; font-size: 14px">
            <caption style="[[sr]]">Numbers in order</caption>
            <thead><tr style="border-top: 1px solid var(--border); border-bottom: 1px solid var(--border)"><th scope="col" style="height: 36px; [[th0]]; width: [[w1]]">Number</th><th scope="col" style="[[th]]">Memo</th>[[sth]]</tr></thead>
            <tbody><sc-for list="{{register}}" as="g" hint-placeholder-count="8"><tr style="{{g.rowStyle}}">
              <td style="padding: 8px 16px; vertical-align: top; [[mono]]; font-size: 13px; white-space: nowrap">{{g.number}}</td>
              <td style="padding: 8px 12px; vertical-align: top"><span style="display: flex; flex-direction: column; gap: 2px"><sc-if value="{{g.link}}" hint-placeholder-val="{{true}}"><a href="Memo.dc.html" style="{{g.memoStyle}}; text-decoration: none">{{g.memo}}</a></sc-if><sc-if value="{{g.plain}}" hint-placeholder-val="{{false}}"><span style="{{g.memoStyle}}">{{g.memo}}</span></sc-if>[[pstatus]]</span></td>
              [[std]]
            </tr></sc-for></tbody>
          </table>
          <div style="display: flex; justify-content: center; padding: 12px"><button type="button" class="ghost" style="[[outline]]">Show more</button></div>
        </section>''', card=CARD, sticky='' if phone else '; position: sticky; top: 16px', check=svg(I['check'], 14), alert=svg(I['alert'], 14, ' style="flex-shrink: 0; margin-top: 2px"'), sr=SR,
        th0=TH.replace('0 12px', '0 16px', 1), th=TH, w1='130px' if phone else '150px', mono=MONO, outline=OUTLINE,
        sth='' if phone else f'<th scope="col" style="{TH}; width: 150px">Status</th>',
        std='' if phone else f'<td style="padding: 8px 12px; vertical-align: top; white-space: nowrap"><span style="display: inline-flex; align-items: center; gap: 6px; color: var(--text-2)">{{{{g.status}}}}<sc-if value="{{{{g.isVoid}}}}" hint-placeholder-val="{{{{false}}}}">{VOID}</sc-if></span></td>',
        pstatus=f'<span style="display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-fg)">{{{{g.status}}}}<sc-if value="{{{{g.isVoid}}}}" hint-placeholder-val="{{{{false}}}}">{VOID}</sc-if></span>' if phone else '')

def series_block(phone):
    start_btn = f'<button type="button" class="ghost" onClick="{{{{s.start}}}}" aria-label="{{{{s.startLabel}}}}" style="flex-shrink: 0; {OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">Set a starting number</button>'
    if phone:
        rows = f'''<ul style="list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--border)"><sc-for list="{{{{s.counters}}}}" as="c" hint-placeholder-count="2"><li style="{{{{c.liStyle}}}}">
              <span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px"><span style="font-weight: 500">{{{{c.period}}}}</span><span style="font-size: 12px; color: var(--muted-fg); {MONO}">{{{{c.meta}}}}</span><sc-if value="{{{{c.started}}}}" hint-placeholder-val="{{{{false}}}}">{STARTED}</sc-if></span>
              <button type="button" onClick="{{{{c.pick}}}}" style="{{{{c.btnStyle}}}}">{{{{c.btnLabel}}}}</button></li></sc-for></ul>'''
    else:
        rows = f'''<table style="width: 100%; border-collapse: collapse; font-size: 14px"><caption style="{SR}">Counters of the series</caption>
              <thead><tr style="border-top: 1px solid var(--border); border-bottom: 1px solid var(--border)"><th scope="col" style="height: 36px; {TH.replace('0 12px', '0 16px', 1)}; width: 90px">Period</th><th scope="col" style="{TH}; width: 90px">Unit</th><th scope="col" style="{TH}">Last number</th><th scope="col" style="width: 96px"><span style="{SR}">Register</span></th></tr></thead>
              <tbody><sc-for list="{{{{s.counters}}}}" as="c" hint-placeholder-count="2"><tr style="{{{{c.rowStyle}}}}">
                <td style="height: 44px; padding: 0 16px; vertical-align: middle; font-weight: 500">{{{{c.period}}}}</td>
                <td style="padding: 0 12px; vertical-align: middle; color: var(--text-2)">{{{{c.unit}}}}</td>
                <td style="padding: 0 12px; vertical-align: middle"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px"><span style="{MONO}; font-size: 13px">{{{{c.last}}}}</span><sc-if value="{{{{c.started}}}}" hint-placeholder-val="{{{{false}}}}">{STARTED}</sc-if></span></td>
                <td style="padding: 0 12px 0 0; vertical-align: middle; text-align: right"><button type="button" onClick="{{{{c.pick}}}}" style="{{{{c.btnStyle}}}}">{{{{c.btnLabel}}}}</button></td>
              </tr></sc-for></tbody></table>'''
    return f'''<sc-for list="{{{{series}}}}" as="s" hint-placeholder-count="4"><section style="{CARD}; display: flex; flex-direction: column; overflow: hidden">
            <div style="display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 12px 16px"><span style="min-width: 0; display: flex; flex-direction: column; gap: 2px"><h2 style="margin: 0; font-size: 15px; font-weight: 600; {MONO}">{{{{s.id}}}}</h2><span style="font-size: 13px; color: var(--muted-fg)">{{{{s.types}}}}</span></span>{start_btn}</div>
            {rows}
          </section></sc-for>'''

NUM_DESK = T('''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      [[nav]]
      <div style="display: flex; flex-direction: column; gap: 2px; max-width: 760px"><h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">Memo numbering</h1><p style="margin: 0; font-size: 14px; color: var(--muted-fg)">Every number given, in order, by series and period. Numbers are never reused: those of withdrawn or cancelled memos are void.</p></div>
      <div style="display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; align-items: start">
        <div style="display: flex; flex-direction: column; gap: 12px; min-width: 0">[[series]]</div>
        [[register]]
      </div>
    </main>
  </div>
''', nav=nav('Numbering', False), series=series_block(False), register=register_block(False))

NUM_PHONE = T('''  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
    [[nav]]
    <div style="display: flex; flex-direction: column; gap: 2px; padding: 12px 16px 8px"><h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Memo numbering</h1><span style="font-size: 13px; color: var(--muted-fg)">Every number given, in order. Numbers of withdrawn or cancelled memos are void, never reused.</span></div>
    <div style="display: flex; flex-direction: column; gap: 12px; padding: 4px 16px 24px">[[series]][[register]]</div>
  </main>
''', nav=nav('Numbering', True), series=series_block(True), register=register_block(True))

def num_dialogs(phone):
    unit = f'''        <sc-if value="{{{{sn.perUnit}}}}" hint-placeholder-val="{{{{false}}}}"><div style="display: flex; flex-direction: column; gap: 6px"><label for="sn-unit" style="font-size: 14px; font-weight: 500">Unit</label><input id="sn-unit" type="text" role="combobox" aria-expanded="false" placeholder="Search units" style="height: {"44px" if phone else "36px"}; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {"16px" if phone else "14px"}; outline: none"><span style="font-size: 12px; color: var(--muted-fg)">Only for a series counted per unit.</span></div></sc-if>'''
    period = text_field('sn', 'period', 'Period', 'text', phone).replace('</div>', '  <span style="font-size: 12px; color: var(--muted-fg)">For example 2026 or FY2026-27.</span>\n        </div>', 1)
    sn = frame('\n'.join([heading('sn-title', '{{sn.title}}', 'To continue an existing paper or legacy series: the first number this counter gives. Possible only before it gives any.'),
                          period, unit, text_field('sn', 'first', 'First number', 'text', phone).replace('type="text"', 'type="text" inputmode="numeric"', 1), alert('sn'), buttons('Set', primary(phone), phone)]), 'sn-title', 'dialog', 'sn.submit', phone)
    return f'''
  <sc-if value="{{{{isStart}}}}" hint-placeholder-val="{{{{false}}}}">
    {sn}
  </sc-if>
''' + toast(phone)

# ================================================================ Distribution lists
LISTS_JS = r"""
    const M = (kind, name, sub) => ({ kind: kind, name: name, sub: sub });
    const LISTS = [
      { id: 'cc', name: 'Credit committee', members: [M('pos', 'Head of Credit', 'Farhana Rahman'), M('pos', 'Chief Financial Officer', 'Rafiq Chowdhury'), M('pos', 'Deputy CFO', 'Karim Reza'), M('pos', 'Head of Corporate Banking', 'Mahmud Karim'), M('person', 'Nusrat Jahan', 'Credit Risk Manager')] },
      { id: 'ia', name: 'Internal audit', members: [M('pos', 'Head of Internal Audit', 'Shirin Akter'), M('person', 'Abdul Kader', 'Auditor')] },
      { id: 'bs', name: 'Board secretariat', members: [M('person', 'Rafiq Chowdhury', 'Chief Financial Officer'), M('person', 'Nasrin Akter', 'Company Secretary')] },
      { id: 'br', name: 'Branch managers, Dhaka', members: [M('pos', 'Branch Manager, Gulshan', 'Tanvir Ahmed'), M('pos', 'Branch Manager, Motijheel', 'Rashed Khan'), M('pos', 'Branch Manager, Uttara', 'Vacant')] },
      { id: 'it', name: 'IT security', members: [M('person', 'Imran Hossain', 'IT Security Officer')] }
    ];
    const target = LISTS.find((l) => l.id === (st.target || 'cc')) || LISTS[0];
    const isNew = dlg === 'new';
    const startMembers = isNew ? (err === 'required' ? [] : [M('pos', 'Head of Retail Banking', 'Vacant')]) : target.members;
    const members = form.members || startMembers;
    const name = form.name !== undefined ? form.name : (isNew ? (err === 'required' ? '' : 'Retail heads') : target.name);
    const tried = !!form.tried || err === 'required';
    const kind = form.kind || 'person';
    const ldAlert = ({ conflict: 'Someone changed this list meanwhile. Open it again.', failed: 'That did not work. Try again.' })[err] || '';
    const openList = (l) => this.setState({ dlg: 'edit', target: l.id, err: 'none', form: null, menu: '' });
    const itemsFor = (l) => [item('Edit', () => openList(l)), item('Switch off', () => this.setState({ menu: '', toast: 'The list is switched off. Memos that used it keep it.' }), true)];
    const mrow = (m, i, removable) => ({ name: m.name, sub: m.sub + (m.kind === 'pos' ? ' holds it now' : ''), isPos: m.kind === 'pos', isPerson: m.kind !== 'pos', subStyle: m.sub === 'Vacant' ? 'color: var(--warn-fg)' : 'color: var(--muted-fg)',
      kindLabel: m.kind === 'pos' ? 'Position' : 'Person', remove: () => this.setState({ form: Object.assign({}, form, { name: name, members: members.filter((x, j) => j !== i) }) }), removeLabel: 'Remove ' + m.name,
      liStyle: 'display: flex; align-items: center; gap: 10px; padding: ' + (PHONE ? '8px 4px 8px 12px' : '6px 4px 6px 12px') + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') });
    const lists = LISTS.map((l) => { const open = st.menu === l.id; const pos = l.members.filter((m) => m.kind === 'pos').length, ppl = l.members.length - pos; return {
      name: l.name, open: () => openList(l), items: itemsFor(l), chips: l.members.slice(0, 3).map((m) => ({ name: m.name, isPos: m.kind === 'pos', isPerson: m.kind !== 'pos' })), more: l.members.length > 3 ? '+' + (l.members.length - 3) + ' more' : '', hasMore: l.members.length > 3,
      count: [pos ? pos + (pos === 1 ? ' position' : ' positions') : '', ppl ? ppl + (ppl === 1 ? ' person' : ' people') : ''].filter(Boolean).join(', '),
      menuOpen: open && !PHONE, menuAria: open ? 'true' : 'false', menuLabel: 'Actions for ' + l.name, toggleMenu: () => this.setState({ menu: open ? '' : l.id }) }; });
    const sheetL = PHONE ? LISTS.find((l) => l.id === st.menu) : null;
    const seg = (on) => 'display: inline-flex; align-items: center; height: ' + (PHONE ? '36px' : '28px') + '; padding: 0 10px; border-radius: 7px; font-size: 13px; font-weight: 500; cursor: pointer; ' + (on ? 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06)' : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)');
    const vals = Object.assign(base, {
      lists: lists, footNote: LISTS.length + ' lists. Switched-off lists are not offered in Copy to; memos that used them keep them.',
      openNew: () => this.setState({ dlg: 'new', form: {}, err: 'none' }),
      sheetOpen: !!sheetL, sheet: sheetL ? { name: sheetL.name, items: itemsFor(sheetL) } : { name: '', items: [] },
      isList: dlg === 'new' || dlg === 'edit',
      ld: Object.assign({ title: isNew ? 'Add list' : 'Edit ' + target.name, isEdit: !isNew, on_name: setForm('name'),
        members: members.map((m, i) => mrow(m, i, true)), hasMembers: members.length > 0, noMembers: members.length === 0,
        membersHasErr: tried && members.length === 0, membersErr: 'Add at least one member.', count: members.length + (members.length === 1 ? ' member' : ' members'),
        personStyle: seg(kind === 'person'), positionStyle: seg(kind === 'position'), pickPerson: () => this.setState({ form: Object.assign({}, form, { name: name, members: members, kind: 'person' }) }), pickPosition: () => this.setState({ form: Object.assign({}, form, { name: name, members: members, kind: 'position' }) }),
        personAria: kind === 'person' ? 'true' : 'false', positionAria: kind === 'position' ? 'true' : 'false', memberPlaceholder: kind === 'person' ? 'Search people' : 'Search positions',
        switchOff: () => this.setState({ dlg: '', toast: 'The list is switched off. Memos that used it keep it.' }),
        hasAlert: !!ldAlert, alert: ldAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!name.trim() || !members.length) { this.setState({ form: Object.assign({}, form, { name: name, members: members, tried: true }) }); return; }
          if (ldAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, toast: 'Saved.' }); } },
        fieldVals('ld', 'name', name, tried && !name.trim() ? 'Required.' : ''))
    });
"""
LISTS_PROPS = '"dialog":{"editor":"enum","options":["none","new","edit"],"default":"none"},"error":{"editor":"enum","options":["none","required","conflict","failed"],"default":"none"},'

KIND_ICON = ('<sc-if value="{{%s.isPos}}" hint-placeholder-val="{{true}}"><span aria-hidden="true" style="display: inline-flex; color: var(--text-3)">' + svg(I['brief'], 13) + '</span></sc-if>'
             '<sc-if value="{{%s.isPerson}}" hint-placeholder-val="{{false}}"><span aria-hidden="true" style="display: inline-flex; color: var(--text-3)">' + svg(I['user'], 13) + '</span></sc-if>')
MCHIP = ('<sc-for list="{{r.chips}}" as="mc" hint-placeholder-count="3"><span style="display: inline-flex; align-items: center; gap: 4px; height: 24px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); font-size: 12px; color: var(--text-2); white-space: nowrap">'
         + KIND_ICON % ('mc', 'mc') + '{{mc.name}}</span></sc-for><sc-if value="{{r.hasMore}}" hint-placeholder-val="{{true}}"><span style="font-size: 12px; color: var(--muted-fg); white-space: nowrap">{{r.more}}</span></sc-if>')

LISTS_DESK = T('''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      [[nav]]
[[head]]
      <div style="display: flex; flex-direction: column; gap: 8px">
        <div style="[[card]]; overflow-x: auto">
          <table style="width: 100%; min-width: 860px; border-collapse: collapse; font-size: 14px">
            <caption style="[[sr]]">Distribution lists</caption>
            <thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 40px; [[th0]]; width: 260px">Name</th><th scope="col" style="[[th]]">Members</th><th scope="col" style="width: 44px"><span style="[[sr]]">Actions</span></th></tr></thead>
            <tbody><sc-for list="{{lists}}" as="r" hint-placeholder-count="5"><tr class="lrow" style="border-bottom: 1px solid var(--hover)">
              <td style="padding: 10px 16px; vertical-align: middle"><span style="display: flex; flex-direction: column; gap: 2px"><button type="button" onClick="{{r.open}}" style="padding: 0; border: 0; background: transparent; color: var(--fg); font-family: inherit; font-size: 14px; font-weight: 500; text-align: left; cursor: pointer">{{r.name}}</button><span style="font-size: 12px; color: var(--muted-fg)">{{r.count}}</span></span></td>
              <td style="padding: 10px 12px; vertical-align: middle"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px">[[chips]]</span></td>
              <td style="position: relative; padding: 0 8px 0 0; vertical-align: middle; text-align: right">[[menuBtn]][[menu]]</td>
            </tr></sc-for></tbody>
          </table>
        </div>
        <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px">[[foot]]</div>
      </div>
    </main>
  </div>
''', nav=nav('Distribution lists', False), head=page_head('Distribution lists', 'Named groups of people and positions to copy memos to. A list is expanded when the copy is sent: whoever holds a position then gets it.', 'Add list', 'openNew', False),
     card=CARD, sr=SR, th0=TH.replace('0 12px', '0 16px', 1), th=TH, chips=MCHIP, menuBtn=MENU_BTN, menu=MENU, foot=FOOT_NOTE)

LISTS_PHONE = T('''  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
    [[nav]]
[[head]]
    <ul aria-label="Distribution lists" style="list-style: none; margin: 4px 0 0; padding: 0; border-top: 1px solid var(--hover)">
      <sc-for list="{{lists}}" as="r" hint-placeholder-count="5"><li style="display: flex; align-items: center; gap: 4px; padding: 10px 4px 10px 16px; border-bottom: 1px solid var(--hover)">
        <button type="button" onClick="{{r.open}}" style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; padding: 0; border: 0; background: transparent; color: var(--fg); font-family: inherit; text-align: left">
          <span style="display: flex; flex-direction: column; gap: 2px"><span style="font-size: 15px; font-weight: 500">{{r.name}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{r.count}}</span></span>
          <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px">[[chips]]</span>
        </button>
        <button type="button" class="ghost" aria-label="{{r.menuLabel}}" aria-haspopup="dialog" onClick="{{r.toggleMenu}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--text-3)">[[dots]]</button>
      </li></sc-for>
    </ul>
    <p style="margin: 0; padding: 12px 16px 24px; font-size: 13px; color: var(--muted-fg)">{{footNote}}</p>
  </main>

[[sheet]]''', nav=nav('Distribution lists', True), head=page_head('Distribution lists', 'Groups of people and positions to copy memos to.', 'Add', 'openNew', True), chips=MCHIP, dots=DOTS18, sheet=SHEET)

def lists_dialogs(phone):
    h = '44px' if phone else '36px'; fs = '16px' if phone else '14px'; b = 44 if phone else 28
    members = T('''        <fieldset aria-describedby="ld-members-msg" style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 8px">
          <legend style="display: contents"><span style="display: flex; align-items: baseline; justify-content: space-between; width: 100%; font-size: 14px; font-weight: 500"><span>Members <span aria-hidden="true" style="color: var(--danger-fg)">*</span></span><span style="font-size: 12px; font-weight: 400; color: var(--muted-fg)">{{ld.count}}</span></span></legend>
          <sc-if value="{{ld.hasMembers}}" hint-placeholder-val="{{true}}"><ul style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden"><sc-for list="{{ld.members}}" as="mb" hint-placeholder-count="4"><li style="{{mb.liStyle}}">
            [[icon]]<span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="font-weight: 500">{{mb.name}}</span><span style="font-size: 12px; {{mb.subStyle}}">{{mb.kindLabel}} · {{mb.sub}}</span></span>
            <button type="button" class="ghost" aria-label="{{mb.removeLabel}}" onClick="{{mb.remove}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: [[b]]px; height: [[b]]px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3)">[[x]]</button>
          </li></sc-for></ul></sc-if>
          <sc-if value="{{ld.noMembers}}" hint-placeholder-val="{{false}}"><div style="padding: 12px; border: 1px dashed var(--border); border-radius: 8px; text-align: center; font-size: 13px; color: var(--muted-fg)">No members yet.</div></sc-if>
          <sc-if value="{{ld.membersHasErr}}" hint-placeholder-val="{{false}}"><span id="ld-members-msg" style="font-size: 12px; color: var(--danger-fg)">{{ld.membersErr}}</span></sc-if>
          <div style="display: flex; flex-direction: column; gap: 8px; padding: 10px; border-radius: 8px; background: var(--subtle); border: 1px solid var(--border)">
            <div role="radiogroup" aria-label="Add a" style="align-self: flex-start; display: inline-flex; gap: 2px; padding: 3px; border-radius: 10px; background: var(--muted)"><button type="button" role="radio" aria-checked="{{ld.personAria}}" onClick="{{ld.pickPerson}}" style="{{ld.personStyle}}">Person</button><button type="button" role="radio" aria-checked="{{ld.positionAria}}" onClick="{{ld.pickPosition}}" style="{{ld.positionStyle}}">Position</button></div>
            <div style="display: flex; gap: 8px"><label for="ld-member" style="[[sr]]">Member</label><input id="ld-member" type="text" role="combobox" aria-expanded="false" placeholder="{{ld.memberPlaceholder}}" style="flex: 1; min-width: 0; height: [[h]]; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: [[fs]]; outline: none"><button type="button" class="ghost" style="flex-shrink: 0; height: [[h]]; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 14px; font-weight: 500; cursor: pointer">Add member</button></div>
            <span style="font-size: 12px; color: var(--muted-fg)">A position reaches whoever holds it when a copy is sent.</span>
          </div>
        </fieldset>''', icon=KIND_ICON % ('mb', 'mb'), b=str(b), x=svg(I['x'], 14), sr=SR, h=h, fs=fs)
    foot = T('''<div style="display: flex; [[dir]]; gap: 8px"><sc-if value="{{ld.isEdit}}" hint-placeholder-val="{{true}}"><button type="button" class="ghost" onClick="{{ld.switchOff}}" style="height: [[h]]; padding: 0 12px; border: 0; border-radius: 8px; background: transparent; color: var(--danger-fg); font-size: 14px; font-weight: 500; cursor: pointer[[mr]]">Switch off</button></sc-if>[[btns]]</div>''',
             dir='flex-direction: column-reverse' if phone else 'align-items: center', h=h, mr='' if phone else '; margin-right: auto',
             btns=buttons('Save', primary(phone), phone).strip().replace('justify-content: flex-end; ', ''))
    ld = frame_scroll('<div style="display: flex; flex-direction: column; gap: 6px"><h2 id="ld-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{{ld.title}}</h2><p style="margin: 0; font-size: 14px; color: var(--muted-fg)">People named here get a copy. Positions are expanded when the copy is sent.</p></div>',
                      '\n'.join([text_field('ld', 'name', 'Name', 'text', phone), members, alert('ld')]), foot, 'ld-title', 'dialog', 'ld.submit', phone, width=560)
    return f'''
  <sc-if value="{{{{isList}}}}" hint-placeholder-val="{{{{false}}}}">
    {ld}
  </sc-if>
''' + toast(phone)

# ---------------------------------------------------------------- empty states
_clear = lambda ph: btn_outline('Clear the filter', 'clearFilter', ph)
TYPES_DESK = with_empty(TYPES_DESK, empty_state('No memo type matches.', 'Try another name or code, or another tab.', _clear(False)))
TYPES_PHONE = with_empty(TYPES_PHONE, empty_state('No memo type matches.', 'Try another name or code, or another tab.', _clear(True), True), True)
_lists_empty = lambda ph: empty_state('No distribution lists yet.', 'Make a list for a group you often copy memos to, such as a committee. Add positions rather than people, and the list stays right when people move.', btn_primary('Add list', 'openNew', ph), ph)
LISTS_DESK = with_empty(LISTS_DESK, _lists_empty(False))
LISTS_PHONE = with_empty(LISTS_PHONE, _lists_empty(True), True)
TYPES_JS += empty_js('types', 'noMatch') + "    vals.clearFilter = () => this.setState({ q: '', filter: 'all' });\n"
TYPES_PROPS = '"noMatch":{"editor":"boolean","default":false},' + TYPES_PROPS
LISTS_JS += empty_js('lists')
LISTS_PROPS = '"empty":{"editor":"boolean","default":false},' + LISTS_PROPS

# ================================================================ assemble
RO_CSS = '.ed-main>*{flex-shrink:0}\ninput:disabled,select:disabled,textarea:disabled{background:var(--subtle)!important;color:var(--text-2)!important;box-shadow:none!important;cursor:not-allowed}\n'
def with_ro(head): return head.replace('</style>', RO_CSS + '</style>', 1)
BOARDS = [('MemoTypes', 'Memo types', TYPES_DESK, TYPES_PHONE, types_dialogs, TYPES_JS, TYPES_PROPS, 1000),
          ('MemoTypeEditor', 'Credit proposal · Memo type', EDITOR_DESK, EDITOR_PHONE, editor_dialogs, EDITOR_JS, EDITOR_PROPS, 1200),
          ('MemoNumbering', 'Memo numbering', NUM_DESK, NUM_PHONE, num_dialogs, NUM_JS, NUM_PROPS, 1000),
          ('DistributionLists', 'Distribution lists', LISTS_DESK, LISTS_PHONE, lists_dialogs, LISTS_JS, LISTS_PROPS, 1000)]
for name, title, desk, phone, dialogs, js, props, h in BOARDS:
    js = '    ' + TONE_JS + js
    pre = with_ro(desk_pre(title, 'Memo types', 'MemoTypes.dc.html'))
    (proj / f'{name}.dc.html').write_text(pre + desk + dialogs(False) + '</div>\n</x-dc>\n' + script(props, 1440, h, DESK_HEAD_JS + BASE_JS + js + DESK_SHELL_JS))
    if name == 'MemoTypeEditor':
        # A page inside the section: back button top bar, like Position and User detail
        pp = (proj / 'PositionPhone.dc.html').read_text()
        head = re.sub(r'<title>[^<]*</title>', f'<title>{title} on a phone</title>', pp[:pp.index('  <header')])
        topbar = f'''  <header style="flex-shrink: 0; height: 56px; display: flex; align-items: center; gap: 4px; padding: 0 8px; border-bottom: 1px solid var(--border); box-sizing: border-box">
    <a href="MemoTypesPhone.dc.html" class="ghost" aria-label="Back to memo types" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border-radius: 8px; color: var(--fg)">{svg(I["back"], 20)}</a>
    <span style="flex: 1; min-width: 0; font-size: 15px; font-weight: 500">Memo types</span>
  </header>
'''
        body = with_ro(head).replace('.ghost:active', '.chips::-webkit-scrollbar{display:none}\n.ghost:active', 1) + topbar + phone + dialogs(True) + '</div>\n</x-dc>\n'
        pjs = "    const st = this.state || {};\n    const PHONE = true;\n" + BASE_JS + js + "    return Object.assign(vals, { themeClass: st.theme || this.props.theme || 'system' });\n"
        (proj / f'{name}Phone.dc.html').write_text(body + script(props, 390, 844, pjs))
    else:
        head, drawer = phone_parts(f'{title} on a phone', 'Memo types', 'MemoTypesPhone.dc.html'); head = with_ro(head)
        (proj / f'{name}Phone.dc.html').write_text(head + phone + dialogs(True) + drawer + script(props, 390, 844, PHONE_HEAD_JS + BASE_JS + js + PHONE_SHELL_JS))

# Every other sidebar links Memo types here (idempotent)
for p in sorted(proj.glob('*.dc.html')):
    s = p.read_text()
    s2 = s.replace('<a href="#" class="nav-item" title="Memo types" style=', '<a href="MemoTypes.dc.html" class="nav-item" title="Memo types" style=')
    s2 = re.sub(r'<a href="#" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">((?:(?!</a>).)*?\n\s*Memo types\n)', r'<a href="MemoTypesPhone.dc.html" class="nav-item" style="\1">\2', s2, flags=re.S)
    if s2 != s: p.write_text(s2)

B = lambda n, y, h=1000, t='': {n + '.dc.html': {'x': 0, 'y': y, 'w': 1440, 'h': h, 'title': t + ' — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'},
                                 n + 'Phone.dc.html': {'x': 1520, 'y': y, 'w': 390, 'h': 844, 'title': t + ' — Phone', 'is_interactive': True, 'page': 'admin'}}
boards = {}
for n, y, h, t in [('MemoTypes', 7060, 1000, 'Memo types'), ('MemoTypeEditor', 8420, 1200, 'Memo type editor'), ('MemoNumbering', 9980, 1000, 'Memo numbering'), ('DistributionLists', 11340, 1000, 'Distribution lists')]:
    boards.update(B(n, y, h, t))
canvas_add(boards, {
    'memoTypesTitle': {'page': 'admin', 'x': 0, 'y': 6800, 'text': 'Memo types', 'kind': 'title1', 'maxW': 1910},
    'memoTypeEditorTitle': {'page': 'admin', 'x': 0, 'y': 8160, 'text': 'Memo type editor (settings, form and template, numbering, changes)', 'kind': 'title1', 'maxW': 1910},
    'memoNumberingTitle': {'page': 'admin', 'x': 0, 'y': 9720, 'text': 'Memo numbering', 'kind': 'title1', 'maxW': 1910},
    'distListsTitle': {'page': 'admin', 'x': 0, 'y': 11080, 'text': 'Distribution lists', 'kind': 'title1', 'maxW': 1910},
    'memoAdminNote': {'page': 'admin', 'x': 2030, 'y': 7060, 'w': 620, 'fill': 'blue', 'size': 'm', 'text':
      'Memos: Memo types, Numbering, Distribution lists\n\n'
      '• The three pages share a row of section links; the sidebar’s Memo types entry opens the section.\n'
      '• Memo types list: tabs In use / Switched off and a filter by name or code, worked out in the browser (the list is small). Switching off asks first and says memos already written keep the type.\n'
      '• Editor: one page per type with a version picker. The tweak “state” shows a draft (Save, Submit for review), a version in review (Changes tab first; remark, Send back, Approve and publish) and a published version (read-only, New draft). Only a draft can change; reviewing needs a second administrator, and publishing asks for step-up.\n'
      '• Form tab: fields in order with move, edit, remove, and the template with its tags; the preview shows the form as the author will see it.\n'
      '• Numbering tab: number parts, series, restart, count and when the number is given, with a sample of the next numbers.\n'
      '• Numbering page: series on the left, the chosen counter’s register on the right, with the completeness check and void numbers. “Set a starting number” offers Unit only for series counted per unit, as a unit picker instead of a unit id.\n'
      '• Distribution lists: members as people or positions; a position reaches whoever holds it when a copy is sent. A name and at least one member are checked separately.\n'
      '• API: nothing new. The memo type filter and tabs are client-side.'},
}, done=('Memo types', 'Memo type editor (fields, templates, settings)', 'Memo numbering', 'Distribution lists'))
print('ok')
