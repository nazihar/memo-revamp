"""Long or growing lists become a searchable select (shadcn Combobox: Popover + Command), 2026-11-21.

The rule (Design rules, section 1): shadcn Select for a fixed list of up to about 10 options; shadcn Combobox for a list
of more than about 10, or one that grows with the organisation's data (time zones, memo types, workflows, roles, units,
versions); the directory search (people, positions, units across the organisation) is a Combobox that searches the
server as you type.

This script finds those lists on the boards and replaces each native <select> with a Combobox trigger: a button with
role="combobox" showing the chosen value. Desktop opens a popover with a search field and the options (a tick on the
chosen one, "No … found." when nothing matches); phones open a bottom sheet. Every board with one gets a `combobox` tweak
to show it open. Data-driven lists keep their data and their change handler.

Run after the scripts that build these boards (settings, op_console, records, workflows, memo_admin, users,
dialogs_gallery) and before card_surface. Idempotent: a board already converted is left alone.
"""
import json, re
from pathlib import Path
proj = Path('canvas/project')

# board -> the lists to convert, by their label; and the noun for "Search …" / "No … found."
TARGETS = {
    'OrgLanguage': {'Time zone': 'time zones'}, 'OpNewTenant': {'Time zone': 'time zones'}, 'Imports': {'Time zone of dates': 'time zones'},
    'WorkflowBuilder': {'Memo type': 'memo types'}, 'Doa': {'Memo type': 'memo types', 'Initiator’s unit': 'units', 'Version': 'versions'},
    'MemoTypeEditor': {'Workflow': 'workflows', 'Version': 'versions'}, 'UserDetail': {'Role': 'roles', 'Where': 'units'},
    'Document': {'Version': 'versions'}, 'MemoDialogs': {'Older version': 'versions', 'Newer version': 'versions'},
}
CHEVRONS = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink: 0; color: var(--muted-fg)"><path d="m7 15 5 5 5-5"></path><path d="m7 9 5-5 5 5"></path></svg>'
SEARCH = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink: 0; color: var(--muted-fg)"><circle cx="11" cy="11" r="8"></circle><path d="m21 21-4.3-4.3"></path></svg>'
CHECK = '<svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="{{%s.check}}"><path d="M20 6 9 17l-5-5"></path></svg>'
SR = 'position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0'
# Time zones are the long list: the boards show a realistic slice of the IANA list, so the search earns its place.
ZONES = [('Africa/Cairo', '+2'), ('America/New_York', '−5'), ('Asia/Bangkok', '+7'), ('Asia/Colombo', '+5:30'), ('Asia/Dhaka', '+6'), ('Asia/Dubai', '+4'), ('Asia/Karachi', '+5'),
         ('Asia/Kathmandu', '+5:45'), ('Asia/Kolkata', '+5:30'), ('Asia/Kuala_Lumpur', '+8'), ('Asia/Riyadh', '+3'), ('Asia/Singapore', '+8'), ('Asia/Tokyo', '+9'),
         ('Australia/Sydney', '+11'), ('Europe/Berlin', '+1'), ('Europe/London', ''), ('UTC', '')]
def zones(opts):
    gmt = any('(GMT' in o for o in opts)
    return [(f'{z} (GMT{o})' if gmt else z) for z, o in ZONES]
CSS = 'button[role="combobox"]:disabled{background:var(--subtle)!important;color:var(--text-2)!important;box-shadow:none!important;cursor:not-allowed}\n'

def name_of(s, m):
    aria = re.search(r'aria-label="([^"]*)"', m.group(0)[:m.group(0).index('>') + 1])
    if aria: return aria.group(1)
    lab = re.findall(r'<label[^>]*>([^<]{1,40})', s[max(0, m.start() - 700):m.start()])
    return lab[-1].strip() if lab else ''

def camel(label):
    w = re.sub(r'[^A-Za-z ]', '', label).split()
    return (w[0].lower() + ''.join(x.capitalize() for x in w[1:])) if w else 'list'

def trigger_and_popup(key, tag, label, noun, phone, alias):
    attrs = dict(re.findall(r'\s([\w-]+)="([^"]*)"', tag))
    style = attrs.get('style', '')
    style = re.sub(r'\s*appearance:\s*none;?', '', style).rstrip('; ')
    keep = ''.join(f' {a}="{attrs[a]}"' for a in ('id', 'disabled', 'aria-invalid', 'aria-describedby', 'aria-label') if a in attrs)
    btn = (f'<button type="button"{keep} role="combobox" aria-haspopup="listbox" aria-expanded="{{{{cbx.{key}.aria}}}}" aria-controls="cbx-{key}-list" onClick="{{{{cbx.{key}.toggle}}}}" '
           f'style="{style}; display: flex; align-items: center; justify-content: space-between; gap: 8px; text-align: left; cursor: pointer">'
           f'<span style="min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{{{cbx.{key}.value}}}}</span>{CHEVRONS}</button>')
    item = (f'<sc-for list="{{{{cbx.{key}.items}}}}" as="{alias}" hint-placeholder-count="4"><li role="none"><button type="button" role="option" aria-selected="{{{{{alias}.aria}}}}" onClick="{{{{{alias}.pick}}}}" class="ghost" '
            f'style="display: flex; align-items: center; gap: 8px; width: 100%; min-height: {"48px" if phone else "32px"}; padding: 6px 8px; border: 0; border-radius: 6px; background: transparent; color: var(--fg); font-family: inherit; font-size: {"15px" if phone else "14px"}; text-align: left; cursor: pointer">'
            f'{CHECK % alias}<span>{{{{{alias}.label}}}}</span></button></li></sc-for>')
    lst = f'<ul id="cbx-{key}-list" role="listbox" aria-label="{label}" style="list-style: none; margin: 0; padding: 0; {"flex: 1; min-height: 0" if phone else "max-height: 240px"}; overflow-y: auto">{item}</ul>'
    empty = f'<sc-if value="{{{{cbx.{key}.empty}}}}" hint-placeholder-val="{{{{false}}}}"><span style="padding: 16px 8px; font-size: 13px; color: var(--muted-fg); text-align: center">No {noun} found.</span></sc-if>'
    search_in = (f'<input type="text" placeholder="Search {noun}…" value="{{{{cbx.{key}.q}}}}" onChange="{{{{cbx.{key}.onQ}}}}" aria-controls="cbx-{key}-list" autocomplete="off" '
                 f'style="flex: 1; min-width: 0; height: {"42px" if phone else "34px"}; border: 0; outline: none; background: transparent; color: var(--fg); font-family: inherit; font-size: {"16px" if phone else "14px"}">')
    backdrop = f'<button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{cbx.{key}.close}}}}" style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 40; border: 0; padding: 0; background: {"rgba(0,0,0,0.5)" if phone else "transparent"}; cursor: default"></button>'
    if phone:
        pop = (f'<div role="dialog" aria-modal="true" aria-label="{label}" onKeyDown="{{{{cbx.{key}.key}}}}" style="position: fixed; left: 0; right: 0; bottom: 0; z-index: 41; height: 70%; box-sizing: border-box; display: flex; flex-direction: column; gap: 8px; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">'
               '<span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>'
               f'<span style="margin: 0 4px; font-size: 16px; font-weight: 600">{label}</span>'
               f'<label style="display: flex; align-items: center; gap: 8px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg)">{SEARCH}<span style="{SR}">Search {noun}</span>{search_in}</label>'
               + lst + empty + '</div>')
    else:
        pop = (f'<div onKeyDown="{{{{cbx.{key}.key}}}}" style="position: absolute; z-index: 41; top: calc(100% + 4px); left: 0; min-width: 100%; width: max-content; max-width: 380px; box-sizing: border-box; display: flex; flex-direction: column; padding: 0 4px 4px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 8px 24px rgba(0,0,0,0.12)">'
               f'<label style="display: flex; align-items: center; gap: 8px; margin: 0 -4px 4px; padding: 0 10px; border-bottom: 1px solid var(--border)">{SEARCH}<span style="{SR}">Search {noun}</span>{search_in}</label>'
               + lst + empty + '</div>')
    wide = 'width: 100%' in style
    wrap = 'position: relative; display: flex; flex-direction: column; flex: 1 1 auto; min-width: 0; width: 100%' if wide else 'position: relative; display: inline-flex; flex-direction: column; min-width: 0'
    return f'<span style="{wrap}">{btn}<sc-if value="{{{{cbx.{key}.isOpen}}}}" hint-placeholder-val="{{{{false}}}}">{backdrop}{pop}</sc-if></span>'

SHIM = r"""
  renderVals() {
    const v = this._rv0() || {}; const st = this.state || {};
    const CBX = /*CBX*/;
    const get = (o, p) => p.split('.').reduce((a, k) => (a == null ? a : a[k]), o);
    const open = st.cbxOpen !== undefined ? st.cbxOpen : (this.props.combobox && this.props.combobox !== 'none' ? this.props.combobox : '');
    const q = (st.cbxQ || '').toLowerCase(); const vals = st.cbxVal || {};
    const close = () => this.setState({ cbxOpen: '', cbxQ: '' });
    v.cbx = {};
    Object.keys(CBX).forEach((k) => {
      const c = CBX[k];
      const all = c.path ? (get(v, c.path) || []).map((o) => ({ value: String(o[c.valueKey] != null ? o[c.valueKey] : o[c.labelKey]), label: String(o[c.labelKey]), sel: !!o[c.selKey] }))
                         : c.options.map((o, i) => ({ value: o, label: o, sel: i === c.sel }));
      const placeholder = all.find((o) => o.value === '');
      const opts = all.filter((o) => o.value !== '');
      const chosen = vals[k] !== undefined ? vals[k] : ((opts.find((o) => o.sel) || placeholder || opts[0] || { label: '' }).label);
      const isOpen = open === k;
      const items = opts.filter((o) => !isOpen || !q || o.label.toLowerCase().indexOf(q) >= 0).map((o) => ({
        label: o.label, aria: o.label === chosen ? 'true' : 'false', check: 'flex-shrink: 0; visibility: ' + (o.label === chosen ? 'visible' : 'hidden'),
        pick: () => { const n = Object.assign({}, vals); n[k] = o.label; const h = c.onChange ? get(v, c.onChange) : null; if (typeof h === 'function') h({ target: { value: o.value } }); this.setState({ cbxOpen: '', cbxQ: '', cbxVal: n }); }
      }));
      v.cbx[k] = { value: chosen, isOpen: isOpen, aria: isOpen ? 'true' : 'false', toggle: () => this.setState({ cbxOpen: isOpen ? '' : k, cbxQ: '' }), close: close,
        key: (ev) => { if (ev && ev.key === 'Escape') close(); }, q: st.cbxQ || '', onQ: (ev) => this.setState({ cbxQ: ev && ev.target ? ev.target.value : '' }),
        items: items, empty: items.length === 0 };
    });
    return v;
  }
"""

changed = []
for base, wanted in TARGETS.items():
    for name in (base + '.dc.html', base + 'Phone.dc.html'):
        p = proj / name
        if not p.exists(): continue
        s = p.read_text()
        if '_rv0()' in s: continue
        phone = name.endswith('Phone.dc.html')
        head, tail = s.split('<script type="text/x-dc"', 1)
        cfg, n = {}, 0
        def conv(m):
            global n
            label = name_of(head, m)
            if label not in wanted: return m.group(0)
            key = camel(label); k2 = key; i = 2
            while k2 in cfg: k2 = key + str(i); i += 1
            tag = re.match(r'<select\b[^>]*>', m.group(0)).group(0); inner = m.group(1)
            loop = re.search(r'<sc-for list="\{\{([\w.]+)\}\}" as="(\w+)"', inner)
            if loop:
                a = loop.group(2)
                lab = re.search(r'>\{\{' + a + r'\.(\w+)\}\}<', inner); sel = re.search(r'selected="\{\{' + a + r'\.(\w+)\}\}"', inner); val = re.search(r'value="\{\{' + a + r'\.(\w+)\}\}"', inner)
                on = re.search(r'onChange="\{\{([\w.]+)\}\}"', tag)
                cfg[k2] = {'path': loop.group(1), 'labelKey': lab.group(1), 'selKey': sel.group(1) if sel else 'sel', 'valueKey': val.group(1) if val else lab.group(1), 'onChange': on.group(1) if on and on.group(1) != 'noop' else None}
            else:
                opts = re.findall(r'<option([^>]*)>([^<]*)</option>', inner)
                texts = [o[1] for o in opts]; chosen = texts[next((i for i, o in enumerate(opts) if 'selected' in o[0]), 0)]
                if wanted[label] == 'time zones': texts = zones(texts)
                cfg[k2] = {'options': texts, 'sel': texts.index(chosen) if chosen in texts else 0}
            n += 1
            return trigger_and_popup(k2, tag, label, wanted[label], phone, 'c%d' % n)
        new_head = re.sub(r'<select\b[^>]*>(.*?)</select>(\s*<svg\b[^>]*>.*?</svg>)?', conv, head, flags=re.S)
        if not cfg: continue
        new_head = new_head.replace('</style>', CSS + '</style>', 1)
        script = '<script type="text/x-dc"' + tail
        script = re.sub(r'(data-props=\'\{.*?)("\$preview")', lambda m: m.group(1) + '"combobox":' + json.dumps({'editor': 'enum', 'options': ['none'] + list(cfg), 'default': 'none'}) + ',' + m.group(2), script, count=1, flags=re.S)
        assert re.search(r'renderVals\(\)\s*\{', script)
        script = re.sub(r'renderVals\(\)\s*\{', '_rv0() {', script, count=1)
        script = script.replace('class Component extends DCLogic {', 'class Component extends DCLogic {' + SHIM.replace('/*CBX*/', json.dumps(cfg, ensure_ascii=False)), 1)
        p.write_text(new_head + script); changed.append(f'{name}: {", ".join(cfg)}')
print('\n'.join(changed) or 'nothing to do')
