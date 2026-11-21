"""Step-up prompt, page states (desktop gallery + phone), and two phone gaps: Search filters, Organisation New top unit."""
import json, re
from pathlib import Path
proj = Path('canvas/project')
TOKENS = Path('tools/tokens.css').read_text().strip()
def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:100], s.count(a)); return s.replace(a, b)
SR = 'position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)'

def head(title):
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600&amp;family=Geist+Mono:wght@400;500&amp;display=swap">
<style>
{TOKENS}
body{{margin:0;background:#ffffff}}
a{{color:var(--fg)}}
button,input,select,textarea{{font-family:inherit}}
.ghost:hover{{background:var(--hover)}}
.ghost:active{{background:var(--muted)}}
@keyframes spin{{to{{transform:rotate(360deg)}}}}
@keyframes pulse{{50%{{opacity:.55}}}}
.sk{{background:var(--muted);border-radius:6px;animation:pulse 1.6s ease-in-out infinite}}
@media (prefers-reduced-motion: reduce){{.sk,.spin{{animation:none}}}}
</style>
</helmet>
'''

def script(props, body):
    return f'''<script type="text/x-dc" data-dc-script data-props='{{"theme":{{"editor":"enum","options":["system","light","dark"],"default":"system"}},{props}}}'>
class Component extends DCLogic {{
  renderVals() {{
    const st = this.state || {{}};
{body}
  }}
}}
</script>
</body>
</html>
'''

ALERT = '<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" x2="12" y1="8" y2="12"></line><line x1="12" x2="12.01" y1="16" y2="16"></line></svg>'
INBOX = '<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 16 12 14 15 10 15 8 12 2 12"></polyline><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"></path></svg>'
FILEQ = '<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17h.01"></path><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z"></path><path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3"></path></svg>'
COMPASS = '<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><path d="m16.24 7.76-2.12 6.36-6.36 2.12 2.12-6.36 6.36-2.12z"></path></svg>'
WIFIOFF = '<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h.01"></path><path d="M8.5 16.429a5 5 0 0 1 7 0"></path><path d="M5 12.859a10 10 0 0 1 5.17-2.69"></path><path d="M19 12.859a10 10 0 0 0-2.007-1.523"></path><path d="M2 8.82a15 15 0 0 1 4.177-2.643"></path><path d="M22 8.82a15 15 0 0 0-11.288-3.764"></path><path d="m2 2 20 20"></path></svg>'
LOCK = '<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>'
SHIELD = '<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"></path></svg>'
def ic(t, s=20): return t.format(s=s)

def btn(label, kind='outline', phone=False, href=None, click=None, extra=''):
    h = '44px' if phone else '32px'; fs = '15px' if phone else '13px'
    st = f'display: inline-flex; align-items: center; justify-content: center; gap: 6px; height: {h}; padding: 0 {14 if phone else 12}px; border-radius: 8px; font-size: {fs}; font-weight: 500; text-decoration: none; cursor: pointer; '
    st += 'border: 0; background: var(--primary); color: var(--primary-fg)' if kind == 'primary' else 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.05)'
    st += extra
    on = f' onClick="{{{{{click}}}}}"' if click else ''
    if href: return f'<a href="{href}" class="ghost" style="{st}">{label}</a>'
    return f'<button type="button" class="{"" if kind == "primary" else "ghost"}"{on} style="{st}">{label}</button>'

# ------------------------------------------------------------------ page states (shared fragments)
def empty_block(icon, title, text, actions, phone, role=None, tone='muted'):
    iconbg = 'background: var(--danger-bg); color: var(--danger-fg)' if tone == 'danger' else 'background: var(--muted); color: var(--fg)'
    r = f' role="{role}"' if role else ''
    acts = f'<div style="display: flex; {"flex-direction: column; align-self: stretch;" if phone else "flex-wrap: wrap; justify-content: center;"} gap: 8px; margin-top: 4px">{"".join(actions)}</div>' if actions else ''
    return f'''<div{r} style="display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: {'40px 20px' if phone else '56px 24px'}; text-align: center">
  <span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 8px; {iconbg}">{icon}</span>
  <span style="font-weight: 500; font-size: 16px">{title}</span>
  {f'<span style="max-width: 460px; font-size: 14px; color: var(--muted-fg)">{text}</span>' if text else ''}
  {acts}
</div>'''

def sk(w, h=12, extra=''): return f'<span class="sk" style="display: block; width: {w}; height: {h}px; {extra}"></span>'

def loading_list(phone):
    if phone:
        rows = ''.join(f'<div style="display: flex; flex-direction: column; gap: 8px; padding: 14px 16px; border-bottom: 1px solid var(--hover)">{sk(w1, 14)}{sk("40%", 10)}{sk(w2, 12)}</div>' for w1, w2 in [('85%', '60%'), ('70%', '55%'), ('90%', '50%'), ('65%', '62%'), ('80%', '48%')])
        return f'<div role="status" aria-label="Loading" style="display: flex; flex-direction: column; border-top: 1px solid var(--border)">{rows}</div>'
    rows = ''.join(f'''<div style="display: grid; grid-template-columns: 16px minmax(0, 1fr) 160px 120px 110px; align-items: center; gap: 16px; height: 52px; padding: 0 16px; border-top: 1px solid var(--hover)">{sk('16px', 16)}<span style="display: flex; flex-direction: column; gap: 6px">{sk(w, 12)}{sk('30%', 10)}</span>{sk('70%')}{sk('60%')}{sk('70%', 20, 'border-radius: 6px')}</div>''' for w in ['62%', '48%', '70%', '55%', '40%', '66%'])
    return f'''<div role="status" aria-label="Loading" style="border: 1px solid var(--border); border-radius: 10px; overflow: hidden">
  <div style="display: grid; grid-template-columns: 16px minmax(0, 1fr) 160px 120px 110px; align-items: center; gap: 16px; height: 40px; padding: 0 16px">{sk('16px', 16)}{sk('60px', 10)}{sk('70px', 10)}{sk('50px', 10)}{sk('40px', 10)}</div>
  {rows}
</div>'''

def loading_memo(phone):
    if phone:
        return f'''<div role="status" aria-label="Loading" style="display: flex; flex-direction: column; gap: 12px; padding: 16px">
  <div style="display: flex; gap: 6px">{sk('72px', 22)}{sk('90px', 22)}</div>{sk('85%', 20)}{sk('55%', 12)}
  <div style="display: flex; gap: 6px">{sk('84px', 36, 'border-radius: 18px')}{sk('96px', 36, 'border-radius: 18px')}{sk('84px', 36, 'border-radius: 18px')}</div>
  {sk('100%', 120, 'border-radius: 10px')}{sk('100%', 80, 'border-radius: 10px')}
</div>'''
    return f'''<div role="status" aria-label="Loading" style="display: flex; flex-direction: column; gap: 14px">
  {sk('60px', 10)}<div style="display: flex; align-items: center; gap: 10px">{sk('360px', 22)}{sk('70px', 22)}</div>{sk('280px', 12)}
  <div style="display: flex; gap: 20px">
    <div style="flex: 1; display: flex; flex-direction: column; gap: 12px">{sk('380px', 36, 'border-radius: 10px')}{sk('100%', 120, 'border-radius: 10px')}{sk('100%', 64, 'border-radius: 10px')}{sk('100%', 100, 'border-radius: 10px')}</div>
    <div style="flex: 0 0 300px; display: flex; flex-direction: column; gap: 12px">{sk('100%', 150, 'border-radius: 10px')}{sk('100%', 200, 'border-radius: 10px')}</div>
  </div>
</div>'''

def section_error(phone):
    return f'''<div role="alert" style="display: flex; {'flex-direction: column; align-items: flex-start' if phone else 'align-items: center'}; gap: 10px; padding: 12px 14px; border: 1px solid var(--danger-border); border-radius: 10px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px">
  <span style="display: flex; align-items: flex-start; gap: 10px; flex: 1"><span style="flex-shrink: 0; margin-top: 1px">{ic(ALERT, 16)}</span><span style="display: flex; flex-direction: column; gap: 2px"><strong style="font-weight: 600">The note sheet could not be loaded.</strong><span>The rest of the memo is fine. Reference: 7f3a9c2e</span></span></span>
  {btn('Try again', phone=phone, click='noop')}
</div>'''

def offline_banner(phone):
    return f'''<div role="status" style="display: flex; {'flex-direction: column; align-items: stretch' if phone else 'align-items: center'}; gap: 10px; padding: 10px 14px; border: 1px solid var(--warn-border); border-radius: 10px; background: var(--warn-bg); color: var(--warn-fg); font-size: 13px">
  <span style="display: flex; align-items: flex-start; gap: 10px; flex: 1"><span style="flex-shrink: 0; margin-top: 1px">{ic(WIFIOFF, 16)}</span><span><strong style="font-weight: 600">You are offline.</strong> You can keep reading. Nothing you send is saved until the connection is back; this page checks again by itself.</span></span>
</div>'''

def signed_out(phone):
    if phone:
        return f'''<div role="alertdialog" aria-modal="true" aria-labelledby="so-t" aria-describedby="so-d" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; display: flex; flex-direction: column; gap: 14px; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--bg); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
  <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>
  <h2 id="so-t" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">You were signed out</h2>
  <p id="so-d" style="margin: 0; font-size: 14px; color: var(--muted-fg)">Your session ended. Sign in again to carry on; you come back to this page.</p>
  {btn('Sign in again', 'primary', True, href='SignInPhone.dc.html')}
</div>'''
    return f'''<div role="alertdialog" aria-labelledby="so-t" aria-describedby="so-d" style="width: 100%; max-width: 460px; display: flex; flex-direction: column; gap: 16px; padding: 24px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--bg); box-shadow: 0 16px 48px rgba(0,0,0,0.12)">
  <div style="display: flex; flex-direction: column; gap: 6px"><h2 id="so-t" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">You were signed out</h2><p id="so-d" style="margin: 0; font-size: 14px; color: var(--muted-fg)">Your session ended. Sign in again to carry on; you come back to this page.</p></div>
  <div style="display: flex; justify-content: flex-end">{btn('Sign in again', 'primary', href='SignIn.dc.html', extra='; height: 36px; font-size: 14px')}</div>
</div>'''

def states(phone):
    return [
        ('loadList', 'Loading a list', 'Skeleton rows in the shape of the list; no spinner. After 10 seconds a line says “Still loading…”.', False, loading_list(phone)),
        ('loadMemo', 'Loading a memo', 'The page’s own layout in grey: header, tabs, cards, right column.', False, loading_memo(phone)),
        ('emptyFirst', 'Empty: nothing yet', 'Says what will appear here, and the one action that fills it.', False,
            empty_block(ic(INBOX), 'You have no drafts.', 'Memos you start but have not submitted yet appear here.', [btn('New memo', 'primary', phone, href='NewMemo.dc.html')], phone)),
        ('emptyFilter', 'Empty: filters match nothing', 'Keeps the filters on screen and offers to clear them.', False,
            empty_block(ic(INBOX), 'No task matches.', 'Change the unit, status or person to see more.', [btn('Clear filters', phone=phone, click='noop')], phone)),
        ('error', 'Error: the page could not load', 'A plain title and the reference for support, never technical details.', False,
            empty_block(ic(ALERT), 'Something went wrong', '<span style="font-size: 12px">Reference: 7f3a9c2e</span>', [btn('Try again', phone=phone, click='noop')], phone, role='alert', tone='danger')),
        ('errorSection', 'Error: one part could not load', 'The rest of the page stays usable; only that section shows the error.', False, section_error(phone)),
        ('notFound', 'Memo not found or not yours to see', 'The same message for both, so a link never reveals that a memo exists.', False,
            empty_block(ic(FILEQ), 'This memo does not exist, or you cannot see it.', 'If someone sent you this link, ask them to share the memo with you.', [btn('Go to Inbox', 'primary', phone, href='Main.dc.html' if not phone else 'Phone.dc.html'), btn('Search memos', phone=phone, href='Search.dc.html' if not phone else 'SearchPhone.dc.html')], phone)),
        ('page404', 'Page not found', 'For an address the app does not know.', True,
            empty_block(ic(COMPASS), 'This page does not exist.', 'Check the address, or start again from Home.', [btn('Go to Home', 'primary', phone, href='Home.dc.html' if not phone else 'HomePhone.dc.html')], phone)),
        ('offline', 'Connection lost', 'A bar above the content while offline; it goes away by itself when the connection is back.', True, offline_banner(phone)),
        ('signedOut', 'Signed out', 'When the session ends mid-task. One way forward: sign in again and return to the same page.', True, signed_out(phone)),
    ]

# ---------- desktop gallery
cards = []
for key, title, note, proposed, body in states(False):
    tag = '<span style="display: inline-flex; align-items: center; height: 20px; padding: 0 6px; border-radius: 6px; border: 1px solid var(--info-fg); color: var(--info-fg); font-size: 11px; font-weight: 500">New, not in the app yet</span>' if proposed else ''
    wide = key in ('loadList', 'loadMemo')
    inner = body if key != 'signedOut' else f'<div style="display: flex; justify-content: center; padding: 32px 16px; border-radius: 8px; background: rgba(0,0,0,0.45)">{body}</div>'
    cards.append(f'''    <figure style="margin: 0; display: flex; flex-direction: column; gap: 8px; {'grid-column: 1 / -1' if wide else ''}">
      <figcaption style="display: flex; flex-direction: column; gap: 2px">
        <span style="display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600">{title}{tag}</span>
        <span style="font-size: 13px; color: var(--muted-fg)">{note}</span>
      </figcaption>
      <div style="padding: 20px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg)">
{inner}
      </div>
    </figure>''')
desk = head('Page states') + f'''<div class="th {{{{themeClass}}}}" style="min-height: 100vh; display: flex; flex-direction: column; gap: 24px; padding: 32px; box-sizing: border-box; background: var(--subtle); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="display: flex; flex-direction: column; gap: 4px; max-width: 900px">
    <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600">Page states</h1>
    <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">What any page or section shows while it loads, when it has nothing, when it fails, and when the link or the session is no good. Each sits in the content area; the sidebar and top bar stay.</p>
  </header>
  <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(420px, 1fr)); gap: 28px 24px; align-items: start">
{chr(10).join(cards)}
  </div>
</div>
</x-dc>
''' + script('"$preview":{"width":1440,"height":2600}', "    return { themeClass: st.theme || this.props.theme || 'system', noop: () => {} };")
(proj / 'States.dc.html').write_text(desk)

# ---------- phone: one state at a time
ps = states(True)
keys = [s[0] for s in ps]
blocks = []
for key, title, note, proposed, body in ps:
    if key == 'signedOut':
        content = '<span aria-hidden="true" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; background: rgba(0,0,0,0.5)"></span>\n' + body
    elif key == 'offline':
        content = f'<div style="padding: 12px 16px 0">{body}</div><div aria-hidden="true" style="display: flex; flex-direction: column; gap: 10px; padding: 16px">{sk("80%", 18)}{sk("100%", 100, "border-radius: 10px")}{sk("100%", 140, "border-radius: 10px")}</div>'
    elif key == 'errorSection':
        content = f'<div style="padding: 16px; display: flex; flex-direction: column; gap: 12px"><div aria-hidden="true" style="display: flex; flex-direction: column; gap: 10px">{sk("80%", 18)}{sk("100%", 90, "border-radius: 10px")}</div>{body}</div>'
    elif key in ('loadList', 'emptyFirst', 'emptyFilter'):
        tabs = ''.join(f'<span style="flex-shrink: 0; display: inline-flex; align-items: center; height: 36px; padding: 0 14px; border-radius: 18px; font-size: 13px; font-weight: 500; {"background: var(--primary); color: var(--primary-fg); border: 1px solid var(--primary)" if i == 0 else "border: 1px solid var(--border); color: var(--text-2)"}">{t}</span>' for i, t in enumerate(['My tasks' if key != 'emptyFirst' else 'Drafts', 'Sent', 'Closed']))
        content = f'<div style="padding: 16px 16px 8px; display: flex; flex-direction: column; gap: 12px"><span style="font-size: 18px; font-weight: 600">Inbox</span><div aria-hidden="true" style="display: flex; gap: 6px; overflow: hidden">{tabs}</div></div>' + (body if key == 'loadList' else f'<div style="margin: 8px 16px; border: 1px dashed var(--border); border-radius: 10px">{body}</div>')
    else:
        content = body
    blocks.append(f'<sc-if value="{{{{is.{key}}}}}" hint-placeholder-val="{{{{{"true" if key == "error" else "false"}}}}}">\n{content}\n</sc-if>')
phone = head('Page states on a phone') + f'''<div class="th {{{{themeClass}}}}" style="position: relative; width: 390px; height: 844px; overflow: hidden; display: flex; flex-direction: column; background: var(--bg); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="flex-shrink: 0; height: 56px; display: flex; align-items: center; gap: 8px; padding: 0 8px; border-bottom: 1px solid var(--border)">
    <span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; color: var(--fg)"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12h16"></path><path d="M4 6h16"></path><path d="M4 18h16"></path></svg></span>
    <span style="flex: 1; font-weight: 600">[Organisation name]</span>
  </header>
  <main style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
{chr(10).join(blocks)}
  </main>
</div>
</x-dc>
''' + script('"state":{"editor":"enum","options":' + json.dumps(keys) + ',"default":"error"},"$preview":{"width":390,"height":844}',
             "    const s = this.props.state || 'error';\n    const is = {};\n    " + json.dumps(keys) + ".forEach((k) => { is[k] = k === s; });\n    return { themeClass: st.theme || this.props.theme || 'system', is: is, noop: () => {} };")
(proj / 'StatesPhone.dc.html').write_text(phone)

# ------------------------------------------------------------------ step-up
def stepup(variant, phone):
    h = '44px' if phone else '36px'; fs = '16px' if phone else '14px'; bfs = '15px' if phone else '14px'
    pw = variant == 'password'
    busy = variant == 'checking'
    err = {'wrong': 'That did not match. Try again.', 'tooMany': 'Too many attempts. Wait a minute and try again.'}.get(variant)
    lead = 'Enter your password to continue.' if pw else 'Enter the 6-digit code from your authenticator app to continue.'
    fid = 'su-' + variant
    border = 'var(--destructive)' if err else 'var(--border)'
    value = '' if variant in ('code', 'password') else ('481 209'.replace(' ', '') if variant != 'password' else '')
    if pw:
        field = f'<input id="{fid}" type="password" autocomplete="current-password" aria-invalid="{"true" if err else "false"}"{f" aria-describedby={chr(34)}{fid}-err{chr(34)}" if err else ""} style="width: 100%; height: {h}; padding: 0 12px; box-sizing: border-box; border: 1px solid {border}; border-radius: 8px; background: var(--bg); color: var(--fg); font-size: {fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">'
    else:
        field = f'<input id="{fid}" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="123456" value="{value}"{" disabled" if busy else ""} aria-invalid="{"true" if err else "false"}"{f" aria-describedby={chr(34)}{fid}-err{chr(34)}" if err else ""} style="width: 100%; height: {h}; padding: 0 12px; box-sizing: border-box; border: 1px solid {border}; border-radius: 8px; background: {"var(--muted)" if busy else "var(--bg)"}; color: var(--fg); font-family: \'Geist Mono\', ui-monospace, monospace; font-size: {"18px" if phone else "16px"}; letter-spacing: 0.3em; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">'
    errhtml = f'<span id="{fid}-err" role="alert" style="font-size: 12px; color: var(--danger-fg)">{err}</span>' if err else ''
    spinner = '<svg class="spin" aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" style="animation: spin 0.8s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"></path></svg>'
    confirm = f'<button type="submit"{" aria-busy=" + chr(34) + "true" + chr(34) if busy else ""} style="display: inline-flex; align-items: center; justify-content: center; gap: 8px; height: {h}; padding: 0 14px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: {bfs}; font-weight: 500; cursor: pointer">{spinner + "Checking…" if busy else "Confirm"}</button>'
    cancel = f'<button type="button" class="ghost" style="height: {h}; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: {bfs}; font-weight: 500; cursor: pointer">Cancel</button>'
    inner = f'''<div style="display: flex; align-items: flex-start; gap: 12px">
    <span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 36px; height: 36px; border-radius: 8px; background: var(--muted); color: var(--fg); flex-shrink: 0">{ic(SHIELD, 18)}</span>
    <div style="display: flex; flex-direction: column; gap: 4px"><h2 id="{fid}-t" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Confirm it is you</h2><p id="{fid}-d" style="margin: 0; font-size: 14px; color: var(--muted-fg)">{lead}</p></div>
  </div>
  <div style="display: flex; flex-direction: column; gap: 6px">
    <label for="{fid}" style="font-size: 14px; font-weight: 500">{"Password" if pw else "Code"}</label>
    {field}
    {errhtml}
  </div>
  {('<div style="display: flex; flex-direction: column; gap: 8px">' + confirm + cancel + '</div>') if phone else ('<div style="display: flex; justify-content: flex-end; gap: 8px">' + cancel + confirm + '</div>')}'''
    if phone:
        return f'<form role="dialog" aria-modal="true" aria-labelledby="{fid}-t" aria-describedby="{fid}-d" novalidate style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; display: flex; flex-direction: column; gap: 16px; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--bg); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)"><span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>{inner}</form>'
    return f'<form role="dialog" aria-labelledby="{fid}-t" aria-describedby="{fid}-d" novalidate style="width: 100%; max-width: 460px; display: flex; flex-direction: column; gap: 16px; padding: 24px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--bg); box-shadow: 0 16px 48px rgba(0,0,0,0.12)">{inner}</form>'

VARIANTS = [
    ('code', 'With an authenticator app', 'The usual case. The field takes the code from the app; the phone keyboard shows numbers.'),
    ('password', 'Without an authenticator app', 'For people who have not set one up: they confirm with their password instead.'),
    ('wrong', 'Wrong code', 'The field stays filled so a mistyped digit is easy to fix.'),
    ('tooMany', 'Too many attempts', 'After repeated wrong codes. Confirm stays pressable and repeats the message until the minute is up.'),
    ('checking', 'Checking', 'While the server checks; the field locks so the code cannot change mid-check.'),
]
cards = '\n'.join(f'''    <figure style="margin: 0; display: flex; flex-direction: column; gap: 8px">
      <figcaption style="display: flex; flex-direction: column; gap: 2px"><span style="font-size: 14px; font-weight: 600">{t}</span><span style="font-size: 13px; color: var(--muted-fg)">{n}</span></figcaption>
      {stepup(k, False)}
    </figure>''' for k, t, n in VARIANTS)
desk = head('Confirm it is you') + f'''<div class="th {{{{themeClass}}}}" style="min-height: 100vh; display: flex; flex-direction: column; gap: 24px; padding: 32px; box-sizing: border-box; background: var(--subtle); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="display: flex; flex-direction: column; gap: 6px; max-width: 920px">
    <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600">Confirm it is you</h1>
    <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">The server asks for it before sensitive actions: approving when the policy requires it, reassigning tasks, cancelling a workflow, and changes to security and administration. It opens over the dialog that asked for it; after Confirm, the original action carries on by itself with the same request, so nothing is sent twice. Cancel returns to that dialog with everything still filled in.</p>
  </header>
  <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(420px, 1fr)); gap: 28px 24px; align-items: start">
{cards}
  </div>
</div>
</x-dc>
''' + script('"$preview":{"width":1440,"height":1100}', "    return { themeClass: st.theme || this.props.theme || 'system' };")
(proj / 'StepUp.dc.html').write_text(desk)
vkeys = [v[0] for v in VARIANTS]
sheets = '\n'.join(f'<sc-if value="{{{{is.{k}}}}}" hint-placeholder-val="{{{{{"true" if k == "code" else "false"}}}}}">{stepup(k, True)}</sc-if>' for k in vkeys)
phone = head('Confirm it is you on a phone') + f'''<div class="th {{{{themeClass}}}}" style="position: relative; width: 390px; height: 844px; overflow: hidden; display: flex; flex-direction: column; background: var(--bg); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="flex-shrink: 0; height: 56px; display: flex; align-items: center; padding: 0 16px; border-bottom: 1px solid var(--border); font-family: 'Geist Mono', ui-monospace, monospace; font-size: 14px; font-weight: 500">CR/2026/0142</header>
  <div aria-hidden="true" style="display: flex; flex-direction: column; gap: 10px; padding: 16px">{sk('80%', 18)}{sk('100%', 100, 'border-radius: 10px')}{sk('100%', 160, 'border-radius: 10px')}</div>
  <span aria-hidden="true" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; background: rgba(0,0,0,0.5)"></span>
{sheets}
</div>
</x-dc>
''' + script('"variant":{"editor":"enum","options":' + json.dumps(vkeys) + ',"default":"code"},"$preview":{"width":390,"height":844}',
             "    const v = this.props.variant || 'code';\n    const is = {};\n    " + json.dumps(vkeys) + ".forEach((k) => { is[k] = k === v; });\n    return { themeClass: st.theme || this.props.theme || 'system', is: is };")
(proj / 'StepUpPhone.dc.html').write_text(phone)

# ------------------------------------------------------------------ phone gaps
# 1) Search on a phone: Unit, Date, Amount and Origin, as on desktop.
s = Path('backup-inbox/SearchPhone.dc.html').read_text()
if 'ps-date' not in s:
    lab = 'display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--muted-fg)'
    inp = 'width: 100%; height: 44px; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: 16px; outline: none'
    extra = f'''        <fieldset id="ps-date" style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 8px">
          <legend style="padding: 0; margin-bottom: 8px; font-size: 14px; font-weight: 600">Date</legend>
          <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px">
            <label style="{lab}">From<input type="date" style="{inp}"></label>
            <label style="{lab}">To<input type="date" style="{inp}"></label>
          </div>
        </fieldset>
        <fieldset style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 8px">
          <legend style="padding: 0; margin-bottom: 8px; font-size: 14px; font-weight: 600">Amount (BDT)</legend>
          <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px">
            <label style="{lab}">Minimum<input type="text" inputmode="decimal" placeholder="0" style="{inp}"></label>
            <label style="{lab}">Maximum<input type="text" inputmode="decimal" placeholder="Any" style="{inp}"></label>
          </div>
        </fieldset>
        <fieldset style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column">
          <legend style="padding: 0; margin-bottom: 4px; font-size: 14px; font-weight: 600">Origin</legend>
          <sc-for list="{{{{origins}}}}" as="og" hint-placeholder-count="3">
            <label style="display: flex; align-items: center; gap: 12px; min-height: 44px; font-size: 15px">
              <input type="radio" name="ps-origin" checked="{{{{og.on}}}}" onChange="{{{{og.pick}}}}" style="margin: 0; width: 18px; height: 18px; accent-color: var(--primary)">
              {{{{og.label}}}}
            </label>
          </sc-for>
        </fieldset>
'''
    anchor = '''        </sc-for>
      </div>
      <div style="padding: 10px 16px 12px; border-top: 1px solid var(--border)">'''
    s = rep(s, anchor, '        </sc-for>\n' + extra + anchor[len('        </sc-for>\n'):])
    s = rep(s, "    const sel = st.sel || { status: {}, type: {} };", "    const sel = st.sel || { status: {}, type: {}, unit: {} };\n    const origin = st.origin || 'all';")
    s = rep(s, "    const base = this.data().filter((r) => sources[r.src]);", "    const base = this.data().filter((r) => sources[r.src] && (origin === 'all' || (origin === 'imported' ? r.imported : !r.imported)));")
    s = rep(s, "    const shown = base.filter((r) => inFacet('status', r) && inFacet('type', r))", "    const shown = base.filter((r) => inFacet('status', r) && inFacet('type', r) && inFacet('unit', r))")
    s = rep(s, "    const facetDefs = [{ key: 'status', title: 'Status' }, { key: 'type', title: 'Type' }];", "    const facetDefs = [{ key: 'status', title: 'Status' }, { key: 'type', title: 'Type' }, { key: 'unit', title: 'Unit' }];")
    s = rep(s, "    const activeCount = chips.length + offSources;", "    const activeCount = chips.length + offSources + (origin === 'all' ? 0 : 1);")
    s = rep(s, "      clearFilters: () => this.setState({ sel: { status: {}, type: {} }, sources: { metadata: true, notes: true, documents: true } }),",
               "      clearFilters: () => this.setState({ sel: { status: {}, type: {}, unit: {} }, origin: 'all', sources: { metadata: true, notes: true, documents: true } }),\n      origins: [{ id: 'all', label: 'All memos' }, { id: 'native', label: 'Created here' }, { id: 'imported', label: 'Imported' }].map((o) => ({ label: o.label, on: o.id === origin, pick: () => this.setState({ origin: o.id }) })),")
    s = rep(s, "max-height: 80%;", "max-height: 88%;")
    (proj / 'SearchPhone.dc.html').write_text(s)

# 2) Organisation on a phone: New top unit
o = (proj / 'OrgPhone.dc.html').read_text()
if 'openNewTop}}" class="ghost"' not in o:
    a = '''      <div style="display: flex; flex-direction: column; gap: 2px">
        <h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Organisation</h1>
        <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">Units, positions and who holds them.</p>
      </div>'''
    b = '''      <div style="display: flex; align-items: flex-start; gap: 8px">
        <div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px">
          <h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Organisation</h1>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">Units, positions and who holds them.</p>
        </div>
        <sc-if value="{{canEdit}}" hint-placeholder-val="{{true}}">
          <button type="button" onClick="{{openNewTop}}" class="ghost" style="flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 40px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 14px; font-weight: 500; cursor: pointer">
            <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"></path><path d="M12 5v14"></path></svg>
            Top unit
          </button>
        </sc-if>
      </div>'''
    o = rep(o, a, b)
    (proj / 'OrgPhone.dc.html').write_text(o)

# canvas
c = proj / 'canvas.json'; d = json.loads(c.read_text())
rows = [('StepUp', 'Confirm it is you (step-up)', 1100), ('States', 'Page states', 2600)]
y = 45200
for name, title, h in rows:
    d['boards'][f'{name}.dc.html'] = {"x": 0, "y": y, "w": 1440, "h": h, "title": f'{title} — Desktop', "expand": "fill", "is_interactive": True}
    d['boards'][f'{name}Phone.dc.html'] = {"x": 1520, "y": y, "w": 390, "h": 844, "title": f'{title} — Phone (pick with the tweak)', "is_interactive": True}
    d['notes'][f'{name}Title'] = {"x": 0, "y": y - 260, "text": title, "kind": "title1", "maxW": 1910}
    for f in (f'{name}.dc.html', f'{name}Phone.dc.html'):
        if f not in d['order']: d['order'].insert(d['order'].index('Rules.dc.html'), f)
    y += h + 520
beh = d['notes']['behaviour']['text']
if 'Page not found' not in beh:
    d['notes']['behaviour']['text'] = beh + '\n• New states the app does not handle yet: Page not found (unknown address), Connection lost (offline bar) and Signed out (session ended mid-task). See the Page states row.'
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
