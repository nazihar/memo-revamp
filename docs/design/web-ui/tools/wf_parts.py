"""Shared pieces for tools/workflows.py: section links, read-only look, remark dialog, version bar, tabs."""
from kit import *

I.update({'undo': '<path d="M9 14 4 9l5-5"></path><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"></path>', 'redo': '<path d="m15 14 5-5-5-5"></path><path d="M20 9H9.5a5.5 5.5 0 0 0 0 11H13"></path>',
          'play': '<polygon points="6 3 20 12 6 21 6 3"></polygon>', 'cal': '<rect width="18" height="18" x="3" y="4" rx="2"></rect><path d="M16 2v4"></path><path d="M8 2v4"></path><path d="M3 10h18"></path>',
          'left': '<path d="m15 18-6-6 6-6"></path>', 'arrow': '<path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path>', 'checkc': '<circle cx="12" cy="12" r="10"></circle><path d="m9 12 2 2 4-4"></path>'})
WF = [('Workflows', 'Workflows'), ('Delegation of authority', 'Doa'), ('Queues', 'Queues'), ('Substitutions', 'Substitutions'), ('Business calendar', 'BusinessCalendar')]
def nav(active, phone): return section_nav(active, phone, WF, 'Workflows')
RO_CSS = '.ed-main>*{flex-shrink:0}\ninput:disabled,select:disabled,textarea:disabled{background:var(--subtle)!important;color:var(--text-2)!important;box-shadow:none!important;cursor:not-allowed}\n'
def with_ro(head): return head.replace('</style>', RO_CSS + '</style>', 1)
def SMALL(t, tone='neutral'): return chip(t, tone).replace('height: 22px', 'height: 20px').replace('font-size: 12px', 'font-size: 11px')
def H(phone): return '44px' if phone else '36px'
def FS(phone): return '16px' if phone else '14px'
def obtn(phone, extra=''): return f'height: {H(phone)}; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: {"15px" if phone else "14px"}; font-weight: 500; cursor: pointer{extra}'
def icon_btn(label, icon, phone, click='', disabled=''):
    s = 44 if phone else 30
    c = f' onClick="{click}"' if click else ''
    d = f' disabled="{disabled}"' if disabled else ''
    return f'<button type="button" class="ghost" aria-label="{label}" title="{label}"{c}{d} style="display: inline-flex; align-items: center; justify-content: center; width: {s}px; height: {s}px; border: 0; border-radius: 6px; background: transparent; color: var(--text-3); cursor: pointer">{svg(I[icon], 16)}</button>'

def remark_dialog(phone):
    """Approve / send back / retire, with the remark (required only to send back)."""
    ta = (f'        <div style="display: flex; flex-direction: column; gap: 6px"><label for="rk-remark" style="font-size: 14px; font-weight: 500">{{{{rk.label}}}}</label>'
          f'<textarea id="rk-remark" rows="3" aria-invalid="{{{{rk.remarkInvalid}}}}" aria-describedby="rk-remark-msg" onChange="{{{{rk.on_remark}}}}" style="width: 100%; padding: 8px 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}; resize: vertical; outline: none; border: 1px solid {{{{rk.remarkBorder}}}}">{{{{rk.remark}}}}</textarea>'
          '<sc-if value="{{rk.remarkHasErr}}" hint-placeholder-val="{{false}}"><span id="rk-remark-msg" style="font-size: 12px; color: var(--danger-fg)">{{rk.remarkErr}}</span></sc-if></div>')
    body = '\n'.join([heading('rk-title', '{{rk.title}}', '{{rk.lead}}'), ta, alert('rk'), buttons('{{rk.confirm}}', '{{rk.btnStyle}}', phone)])
    return frame(body, 'rk-title', 'dialog', 'rk.submit', phone)

REMARK_JS = r"""
    const RK = { approve: { title: 'Publish this version?', lead: APPROVE_LEAD, confirm: 'Approve and publish', label: 'Remark (optional)', danger: false },
                 sendBack: { title: 'Send this version back?', lead: 'The author gets it back as a draft with your remark.', confirm: 'Send back', label: 'Remark', danger: false, needs: true },
                 retire: { title: 'Retire this version?', lead: RETIRE_LEAD, confirm: 'Retire', label: 'Remark (optional)', danger: true } };
    const rkd = RK[dlg] || RK.approve;
    const rkRemark = form.remark !== undefined ? form.remark : '';
    const rkErr = rkd.needs && (form.tried || err === 'required') && !rkRemark.trim() ? REMARK_REQUIRED : '';
    const rkAlert = (RK_ALERTS[dlg] || {})[err] || '';
    vals.rk = Object.assign({ title: rkd.title, lead: rkd.lead, confirm: rkd.confirm, label: rkd.label, btnStyle: okStyle(rkd.danger), on_remark: setForm('remark'), hasAlert: !!rkAlert, alert: rkAlert,
      submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (rkd.needs && !rkRemark.trim()) { this.setState({ form: Object.assign({}, form, { tried: true }) }); return; }
        if (rkAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, state: dlg === 'approve' ? 'published' : (dlg === 'sendBack' ? 'draft' : 'published'), toast: 'Done.' }); } },
      fieldVals('rk', 'remark', rkRemark, rkErr));
"""

TABS_JS = r"""
    const tabBase = 'display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; border-radius: 7px; font-size: 13px; font-weight: 500; cursor: pointer; white-space: nowrap; ';
    const pill = 'flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 14px; border-radius: 18px; font-size: 13px; font-weight: 500; white-space: nowrap; ';
    const mkTabs = (list, cur, key) => list.map(([id, l, count]) => { const on = id === cur; const o = {}; o[key] = id; return { label: l, aria: on ? 'true' : 'false', pick: () => this.setState(o), hasCount: !!count, count: String(count || ''),
      countStyle: 'display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; padding: 0 5px; box-sizing: border-box; border-radius: 9px; font-size: 11px; ' + (on && PHONE ? 'background: var(--primary-divider); color: var(--primary-fg)' : 'background: var(--chip); color: var(--text-2)'),
      style: PHONE ? pill + (on ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: var(--text-2)')
                   : tabBase + (on ? 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06)' : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)') }; });
"""

def tabs_markup(phone, key='tabs', label='Show'):
    inner = (f'<sc-for list="{{{{{key}}}}}" as="t" hint-placeholder-count="4"><button type="button" role="tab"{"" if phone else " class=\"tab\""} aria-selected="{{{{t.aria}}}}" onClick="{{{{t.pick}}}}" style="{{{{t.style}}}}">{{{{t.label}}}}'
             '<sc-if value="{{t.hasCount}}" hint-placeholder-val="{{false}}"><span style="{{t.countStyle}}">{{t.count}}</span></sc-if></button></sc-for>')
    if phone:
        return f'<div class="chips" role="tablist" aria-label="{label}" style="flex-shrink: 0; display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none">{inner}</div>'
    return f'<div role="tablist" aria-label="{label}" style="display: inline-flex; align-items: center; gap: 2px; height: 36px; padding: 3px; box-sizing: border-box; border-radius: 10px; background: var(--muted)">{inner}</div>'

def review_bar(phone, draft_buttons, pub_buttons):
    """Bottom action bar for versioned things: draft / in review / published."""
    pos = ('flex-shrink: 0; padding: 12px 16px 16px; border-top: 1px solid var(--border); background: var(--card)' if phone else
           'position: sticky; bottom: 0; z-index: 3; padding: 12px 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 -4px 16px rgba(0,0,0,0.06)')
    row = 'display: flex; gap: 8px' + ('' if phone else '; justify-content: flex-end; align-items: center')
    note = lambda k: '' if phone else f'<span style="flex: 1; font-size: 13px; color: var(--muted-fg)">{{{{{k}}}}}</span>'
    g1, g2 = ('; flex: 1', '; flex: 2') if phone else ('', '')
    review = f'<button type="button" class="ghost" onClick="{{{{openSendBack}}}}" style="{obtn(phone, g1)}">Send back</button><button type="button" onClick="{{{{openApprove}}}}" style="{primary(phone)}{g2}">Approve and publish</button>'
    return (f'<sc-if value="{{{{showBar}}}}" hint-placeholder-val="{{{{true}}}}"><div style="{pos}; display: flex; flex-direction: column; gap: 10px">\n        {alert("bar")}\n'
            f'        <sc-if value="{{{{isDraft}}}}" hint-placeholder-val="{{{{false}}}}"><div style="{row}">{note("draftNote")}{draft_buttons}</div></sc-if>\n'
            f'        <sc-if value="{{{{isReview}}}}" hint-placeholder-val="{{{{true}}}}"><div style="{row}">{note("reviewNote")}{review}</div></sc-if>\n'
            f'        <sc-if value="{{{{isPublished}}}}" hint-placeholder-val="{{{{false}}}}"><div style="{row}">{note("publishedNote")}{pub_buttons}</div></sc-if>\n'
            '      </div></sc-if>')

def list_body(phone, rows_key, cols, cells, phone_row, caption, alias='r'):
    """A standard admin list: desktop table on a card, or a full-width phone list."""
    if phone:
        return (f'    <ul aria-label="{caption}" style="list-style: none; margin: 4px 0 0; padding: 0; border-top: 1px solid var(--hover)"><sc-for list="{{{{{rows_key}}}}}" as="{alias}" hint-placeholder-count="5">'
                f'<li style="display: flex; align-items: center; gap: 4px; padding: 10px 4px 10px 16px; border-bottom: 1px solid var(--hover)">{phone_row}</li></sc-for></ul>')
    ths = ''.join((f'<th scope="col" style="height: 40px; {TH.replace("0 12px", "0 16px", 1)}{"; width: " + w if w else ""}">{h}</th>' if i == 0 else
                   (f'<th scope="col" style="{TH}{"; width: " + w if w else ""}">{h}</th>' if h else f'<th scope="col" style="width: {w}"><span style="{SR}">Actions</span></th>')) for i, (h, w) in enumerate(cols))
    return (f'<div style="{CARD}; overflow-x: auto"><table style="width: 100%; min-width: 860px; border-collapse: collapse; font-size: 14px"><caption style="{SR}">{caption}</caption>'
            f'<thead><tr style="border-bottom: 1px solid var(--border)">{ths}</tr></thead><tbody><sc-for list="{{{{{rows_key}}}}}" as="{alias}" hint-placeholder-count="5"><tr class="lrow" style="border-bottom: 1px solid var(--hover)">{cells}</tr></sc-for></tbody></table></div>')

PHONE_DOTS = '<button type="button" class="ghost" aria-label="{{r.menuLabel}}" aria-haspopup="dialog" onClick="{{r.toggleMenu}}" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--text-3)">' + DOTS18 + '</button>'
MENU_CELL = f'<td style="position: relative; padding: 0 8px 0 0; vertical-align: middle; text-align: right">{MENU_BTN}{MENU}</td>'

def page(nav_html, head_html, body_html, phone, extra_top=''):
    if phone:
        return f'''  <main class="ed-main" style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
    {nav_html}
{head_html}
{extra_top}{body_html}
  </main>
'''
    return f'''    <main style="flex: 1; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      {nav_html}
{head_html}
{extra_top}{body_html}
    </main>
  </div>
'''

def title_only(title, lead, phone):
    if phone:
        return f'    <div style="display: flex; flex-direction: column; gap: 2px; padding: 12px 16px 8px"><h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{title}</h1><span style="font-size: 13px; color: var(--muted-fg)">{lead}</span></div>'
    return f'      <div style="display: flex; flex-direction: column; gap: 2px; max-width: 820px"><h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">{title}</h1><p style="margin: 0; font-size: 14px; color: var(--muted-fg)">{lead}</p></div>'

def foot(phone, extra=''):
    if phone:
        return '    <p style="margin: 0; padding: 12px 16px 24px; font-size: 13px; color: var(--muted-fg)">{{footNote}}</p>'
    return f'<div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px">{FOOT_NOTE}{extra}</div>'

def build(name, title, desk, phone, dialogs, js, props, h, back=None, js_head=''):
    """Write the desktop and phone boards. back=(href, label) gives the phone a back-button top bar instead of the menu."""
    js = js_head + js
    pre = with_ro(desk_pre(title, 'Workflows', 'Workflows.dc.html'))
    (proj / f'{name}.dc.html').write_text(pre + desk + dialogs(False) + '</div>\n</x-dc>\n' + script(props, 1440, h, DESK_HEAD_JS + BASE_JS + js + DESK_SHELL_JS))
    if back:
        pp = (proj / 'PositionPhone.dc.html').read_text()
        head = with_ro(re.sub(r'<title>[^<]*</title>', f'<title>{title} on a phone</title>', pp[:pp.index('  <header')])).replace('.ghost:active', '.chips::-webkit-scrollbar{display:none}\n.ghost:active', 1)
        topbar = (f'  <header style="flex-shrink: 0; height: 56px; display: flex; align-items: center; gap: 4px; padding: 0 8px; border-bottom: 1px solid var(--border); box-sizing: border-box">\n'
                  f'    <a href="{back[0]}" class="ghost" aria-label="Back to {back[1].lower()}" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border-radius: 8px; color: var(--fg)">{svg(I["back"], 20)}</a>\n'
                  f'    <span style="flex: 1; min-width: 0; font-size: 15px; font-weight: 500">{back[1]}</span>\n  </header>\n')
        pjs = "    const st = this.state || {};\n    const PHONE = true;\n" + BASE_JS + js + "    return Object.assign(vals, { themeClass: st.theme || this.props.theme || 'system' });\n"
        (proj / f'{name}Phone.dc.html').write_text(head + topbar + phone + dialogs(True) + '</div>\n</x-dc>\n' + script(props, 390, 844, pjs))
    else:
        head, drawer = phone_parts(f'{title} on a phone', 'Workflows', 'WorkflowsPhone.dc.html')
        (proj / f'{name}Phone.dc.html').write_text(with_ro(head) + phone + dialogs(True) + drawer + script(props, 390, 844, PHONE_HEAD_JS + BASE_JS + js + PHONE_SHELL_JS))

JS_HEAD = '    ' + TONE_JS + TABS_JS
