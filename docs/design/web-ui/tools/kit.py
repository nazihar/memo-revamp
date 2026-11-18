"""Shared pieces for the board scripts (users.py, access.py): icons, dialogs, fields, script wrapper, shells."""
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

# ---------------------------------------------------------------- canvas
def canvas_add(boards, notes, done=()):
    """Add boards and notes to canvas.json only where missing (the canvas re-saves the file in its own format,
    so existing entries are left alone), and take the designed pages off the Admin to-do note."""
    c = proj / 'canvas.json'; d = json.loads(c.read_text())
    for k, v in boards.items():
        d['boards'].setdefault(k, v)
        if k not in d['order']: d['order'].append(k)
    for k, v in notes.items():
        d['notes'].setdefault(k, v)
        if 'noteOrder' in d and k not in d['noteOrder']: d['noteOrder'].append(k)
    todo = d['notes'].get('adminTodo')
    if todo:
        lines = [l for l in todo['text'].split('\n') if l not in {'• ' + x for x in done}]
        # drop group headings left with no pages under them
        lines = [l for i, l in enumerate(lines) if not (0 < i < len(lines) - 1 and l and not l.startswith(('• ', 'Also')) and lines[i - 1] == '' and lines[i + 1] == '')]
        lines = [l for i, l in enumerate(lines) if not (l == '' and i > 0 and lines[i - 1] == '')]
        text = '\n'.join(lines)
        todo['text'] = re.sub(r'Admin pages still to design \(\d+\)', f'Admin pages still to design ({sum(l.startswith("• ") for l in lines)})', text)
    c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))

# ---------------------------------------------------------------- shells
def T(s, **kw):
    """Fill [[name]] slots in plain markup (keeps {{holes}} free of f-string escaping)."""
    for k, v in kw.items(): s = s.replace(f'[[{k}]]', v)
    assert '[[' not in s, s[s.index('[['):s.index('[[') + 40]
    return s

def desk_pre(title, active='Users', href='Users.dc.html'):
    """Desktop shell (sidebar + top bar) from the Open tasks board, with [active] marked in the sidebar."""
    t = (proj / 'Tasks.dc.html').read_text()
    pre = t[:t.index('    <!-- Content -->\n') + len('    <!-- Content -->\n')]
    pre = re.sub(r'<a href="#" class="nav-item" title="Open tasks" aria-current="page" style="([^"]*?); background: var\(--active\); font-weight: 500">', r'<a href="Tasks.dc.html" class="nav-item" title="Open tasks" style="\1">', pre)
    pre = re.sub(rf'<a href="[^"]*" class="nav-item" title="{active}" style="([^"]*)">', lambda m: f'<a href="{href}" class="nav-item" title="{active}" aria-current="page" style="{m.group(1)}; background: var(--active); font-weight: 500">', pre, count=1)
    assert pre.count('aria-current="page"') == 1
    return pre.replace('<title>Open tasks</title>', f'<title>{title}</title>')

def phone_parts(title, active='Users', href='UsersPhone.dc.html'):
    """Phone shell from the Open tasks phone board: (head up to <main>, navigation drawer), [active] marked."""
    tp = (proj / 'TasksPhone.dc.html').read_text()
    head = tp[:tp.index('  <main')].replace('<title>Open tasks on a phone</title>', f'<title>{title}</title>')
    drawer = tp[tp.index('  <!-- Navigation drawer -->'):tp.index('<script type="text/x-dc"')]
    drawer = re.sub(r'<a href="#" class="nav-item" aria-current="page" style="([^"]*?); background: var\(--active\); font-weight: 500">(\s*<svg[^>]*><path d="m3 17 2 2 4-4">)', r'<a href="TasksPhone.dc.html" class="nav-item" style="\1">\2', drawer)
    m = re.search(rf'<a href="[^"]*" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">((?:(?!</a>).)*?\n\s*{active}\n)', drawer, re.S)
    drawer = drawer[:m.start()] + f'<a href="{href}" class="nav-item" style="{m.group(1)}; background: var(--active); font-weight: 500" aria-current="page">' + m.group(2) + drawer[m.end():]
    assert drawer.count('aria-current="page"') == 1
    return head, drawer

PHONE_SHELL_JS = """    return Object.assign(vals, {
      themeClass: st.theme || this.props.theme || 'system',
      navOpen: navOpen, navAria: navOpen ? 'true' : 'false',
      openNav: () => this.setState({ navOpen: true }), closeNav: closeNav,
      navKey: (ev) => { if (ev && ev.key === 'Escape') closeNav(); }
    });
"""
PHONE_HEAD_JS = """    const st = this.state || {};
    const PHONE = true;
    const navOpen = !!st.navOpen;
    const closeNav = () => this.setState({ navOpen: false });
"""

SECTION = [('Users', 'Users'), ('Roles', 'Roles'), ('Classification', 'Classifications')]
def section_nav(active, phone, items=None, label='People and access'):
    """Links between the People and access pages (Users, Roles, Classification): an underlined link row."""
    out = []
    for name, board in (items or SECTION):
        on = name == active
        st = (f'display: inline-flex; align-items: center; height: {"44px" if phone else "36px"}; padding: 0 {"12px" if phone else "10px"}; margin-bottom: -1px; '
              f'border-bottom: 2px solid {"var(--fg)" if on else "transparent"}; color: {"var(--fg)" if on else "var(--muted-fg)"}; font-size: 14px; font-weight: 500; text-decoration: none; white-space: nowrap')
        out.append(f'<a href="{board}{"Phone" if phone else ""}.dc.html"{" aria-current=\"page\"" if on else ""} class="ghost" style="{st}">{name}</a>')
    wrap = ('flex-shrink: 0; display: flex; gap: 0; padding: 0 4px; border-bottom: 1px solid var(--border); overflow-x: auto' if phone
            else 'display: flex; gap: 4px; margin-top: -8px; border-bottom: 1px solid var(--border)')
    return f'<nav aria-label="{label}" style="{wrap}">' + ''.join(out) + '</nav>'


# ---------------------------------------------------------------- lists, sheets, page heads (from access.py)
I.update({'up': '<path d="m18 15-6-6-6 6"></path>', 'down': '<path d="m6 9 6 6 6-6"></path>', 'check': '<path d="M20 6 9 17l-5-5"></path>',
          'shield': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"></path>'})
DOTS16, DOTS18 = svg(I['dots']), svg(I['dots'], 18)
def frame_scroll(head, body, foot, labelled, role, submit, phone, width=560):
    """Dialog / bottom sheet whose middle scrolls (long permission list)."""
    if phone:
        return f'''<button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{closeDlg}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <form role="{role}" aria-modal="true" aria-labelledby="{labelled}" novalidate onSubmit="{{{{{submit}}}}}" onKeyDown="{{{{dlgKey}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; max-height: 92%; display: flex; flex-direction: column; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <div style="flex-shrink: 0; display: flex; flex-direction: column; gap: 12px; padding: 8px 16px 12px"><span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>
{head}
      </div>
      <div style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 0 16px 12px">
{body}
      </div>
      <div style="flex-shrink: 0; display: flex; flex-direction: column; gap: 12px; padding: 12px 16px 16px; border-top: 1px solid var(--border)">
{foot}
      </div>
    </form>'''
    return f'''<div style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box">
      <button type="button" tabIndex="-1" aria-label="Close dialog" onClick="{{{{closeDlg}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; border: 0; padding: 0; background: rgba(0,0,0,0.5); cursor: default"></button>
      <form role="{role}" aria-modal="true" aria-labelledby="{labelled}" novalidate onSubmit="{{{{{submit}}}}}" onKeyDown="{{{{dlgKey}}}}" style="position: relative; width: 100%; max-width: {width}px; max-height: calc(100vh - 32px); display: flex; flex-direction: column; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--card); box-shadow: 0 16px 48px rgba(0,0,0,0.2)">
        <div style="flex-shrink: 0; display: flex; flex-direction: column; gap: 16px; padding: 24px 24px 16px">
{head}
        </div>
        <div style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; padding: 0 24px 16px">
{body}
        </div>
        <div style="flex-shrink: 0; display: flex; flex-direction: column; gap: 12px; padding: 16px 24px 24px; border-top: 1px solid var(--border)">
{foot}
        </div>
      </form>
    </div>'''

def opt_field(pfx, k, label, phone, required=True, hint=''):
    """Text field; required ones get the star and an error slot, optional ones say so in the label (app copy)."""
    if required: return text_field(pfx, k, label, 'text', phone)
    h = '44px' if phone else '36px'; fs = '16px' if phone else '14px'
    return f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <label for="{pfx}-{k}" style="font-size: 14px; font-weight: 500">{label}</label>
          <input id="{pfx}-{k}" type="text" autocomplete="off" value="{{{{{pfx}.{k}}}}}" onChange="{{{{{pfx}.on_{k}}}}}" style="width: 100%; height: {h}; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">{f'<span style="font-size: 12px; color: var(--muted-fg)">{hint}</span>' if hint else ''}
        </div>'''

def page_head(title, lead, btn_label, btn_click, phone):
    if phone:
        return T('''    <div style="display: flex; align-items: flex-start; gap: 8px; padding: 12px 16px 8px">
      <div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px">
        <h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">[[title]]</h1>
        <span style="font-size: 13px; color: var(--muted-fg)">[[lead]]</span>
      </div>
      <button type="button" onClick="{{[[click]]}}" style="flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 40px; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 14px; font-weight: 500">[[icon]][[btn]]</button>
    </div>''', title=title, lead=lead, click=btn_click, icon=svg(I['plus']), btn=btn_label)
    return T('''      <div style="display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 12px">
        <div style="display: flex; flex-direction: column; gap: 2px; max-width: 720px">
          <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">[[title]]</h1>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">[[lead]]</p>
        </div>
        <button type="button" onClick="{{[[click]]}}" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 13px; font-weight: 500; cursor: pointer">[[icon]][[btn]]</button>
      </div>''', title=title, lead=lead, click=btn_click, icon=svg(I['plus'], 14), btn=btn_label)

MENU = '''<sc-if value="{{r.menuOpen}}" hint-placeholder-val="{{false}}">
                      <button type="button" tabIndex="-1" aria-label="Close menu" onClick="{{closeMenu}}" style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 6; border: 0; padding: 0; background: transparent; cursor: default"></button>
                      <div role="menu" aria-label="{{r.menuLabel}}" onKeyDown="{{menuKey}}" style="position: absolute; z-index: 7; top: 34px; right: 8px; width: 200px; padding: 4px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 8px 24px rgba(0,0,0,0.10); text-align: left">
                        <sc-for list="{{r.items}}" as="m" hint-placeholder-count="2">
                          <button type="button" role="menuitem" class="ghost" onClick="{{m.pick}}" style="{{m.style}}">{{m.label}}</button>
                        </sc-for>
                      </div>
                    </sc-if>'''
MENU_BTN = '<button type="button" class="ghost" aria-label="{{r.menuLabel}}" aria-haspopup="menu" aria-expanded="{{r.menuAria}}" onClick="{{r.toggleMenu}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; color: var(--text-3)">' + DOTS16 + '</button>'
SHEET = '''  <sc-if value="{{sheetOpen}}" hint-placeholder-val="{{false}}">
    <button type="button" tabIndex="-1" aria-label="Close" onClick="{{closeMenu}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 20; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <div role="dialog" aria-modal="true" aria-labelledby="ac-sheet" onKeyDown="{{menuKey}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 21; display: flex; flex-direction: column; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; margin-bottom: 8px; border-radius: 2px; background: var(--chip)"></span>
      <h2 id="ac-sheet" style="margin: 0 4px 6px; font-size: 16px; font-weight: 600">{{sheet.name}}</h2>
      <sc-for list="{{sheet.items}}" as="m" hint-placeholder-count="3">
        <button type="button" class="ghost" onClick="{{m.pick}}" style="{{m.sheetStyle}}">{{m.label}}</button>
      </sc-for>
      <button type="button" onClick="{{closeMenu}}" style="margin-top: 8px; height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Close</button>
    </div>
  </sc-if>
'''
FOOT_NOTE = '<span style="font-size: 13px; color: var(--muted-fg)">{{footNote}}</span>'

BASE_JS = r"""
    const H = PHONE ? '44px' : '36px', BFS = PHONE ? '15px' : '14px';
    const okStyle = (danger) => 'height: ' + H + '; padding: 0 14px; border: 0; border-radius: 8px; font-size: ' + BFS + '; font-weight: 500; cursor: pointer; ' + (danger ? 'background: var(--destructive); color: var(--on-destructive)' : 'background: var(--primary); color: var(--primary-fg)');
    const closeDlg = () => this.setState({ dlg: '', err: 'none', form: null });
    const closeMenu = () => this.setState({ menu: '' });
    const itemStyle = (danger) => 'display: flex; align-items: center; width: 100%; height: 32px; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; font-size: 14px; text-align: left; cursor: pointer; color: ' + (danger ? 'var(--danger-fg)' : 'var(--fg)');
    const sheetStyle = (danger) => 'display: flex; align-items: center; min-height: 52px; padding: 0 4px; border: 0; border-top: 1px solid var(--hover); background: transparent; font-size: 15px; text-align: left; color: ' + (danger ? 'var(--danger-fg)' : 'var(--fg)');
    const item = (label, pick, danger) => ({ label: label, pick: pick, style: itemStyle(danger), sheetStyle: sheetStyle(danger) });
    const dlgProp = this.props.dialog || 'none';
    const dlg = st.dlg !== undefined ? st.dlg : (dlgProp === 'none' ? '' : dlgProp);
    const err = st.err !== undefined ? st.err : (this.props.error || 'none');
    const fieldVals = (pfx, k, val, msg) => { const o = {}; o[k] = val; o[k + 'HasErr'] = !!msg; o[k + 'Err'] = msg; o[k + 'Invalid'] = msg ? 'true' : 'false'; o[k + 'Border'] = msg ? 'var(--destructive)' : 'var(--border)'; return o; };
    const form = st.form || {};
    const setForm = (k) => (ev) => { const n = Object.assign({}, form); n[k] = ev && ev.target ? ev.target.value : ''; this.setState({ form: n, err: 'none' }); };
    const base = { closeDlg: closeDlg, dlgKey: (ev) => { if (ev && ev.key === 'Escape') closeDlg(); }, closeMenu: closeMenu, menuKey: (ev) => { if (ev && ev.key === 'Escape') closeMenu(); },
      hasToast: !!st.toast, toast: st.toast || '', hideToast: () => this.setState({ toast: '' }) };
"""
