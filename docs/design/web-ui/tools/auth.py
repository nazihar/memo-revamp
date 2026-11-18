"""Sign-in flow: one desktop page and one phone frame, generated from the same markup."""
import json
from pathlib import Path

proj = Path('canvas/project')
tokens = Path('tools/tokens.css').read_text()

INPUT = "width: 100%; height: __H__; padding: 0 12px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: __FS__; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)"
BTN = "width: 100%; height: __H__; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 14px; font-weight: 500; cursor: pointer"
OUTLINE = "width: 100%; height: __H__; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 14px; font-weight: 500; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: 8px"
LINK = "padding: 0; border: 0; background: transparent; color: var(--fg); font-size: 13px; font-weight: 500; text-decoration: underline; text-underline-offset: 3px; cursor: pointer"


def head(title, lead):
    return f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">{title}</h1>
          <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">{lead}</p>
        </div>'''


def field(fid, label, typ, extra='', right='', hint='', mono=False):
    st = INPUT + ("; font-family: 'Geist Mono', ui-monospace, monospace; letter-spacing: 0.2em" if mono else '')
    lab = f'<label for="{fid}" style="font-size: 14px; font-weight: 500">{label}</label>'
    if right:
        lab = f'<span style="display: flex; align-items: baseline; justify-content: space-between; gap: 8px">{lab}{right}</span>'
    h = f'\n          <span id="{fid}-hint" style="font-size: 12px; color: var(--muted-fg)">{hint}</span>' if hint else ''
    desc = f' aria-describedby="{fid}-hint"' if hint else ''
    return f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          {lab}
          <input id="{fid}" type="{typ}" {extra}{desc} style="{st}">{h}
        </div>'''


def screen(name, inner):
    return f'''      <sc-if value="{{{{is.{name}}}}}" hint-placeholder-val="{{{{{'true' if name == 'signin' else 'false'}}}}}">
      <form novalidate onSubmit="{{{{go.{name}}}}}" style="display: flex; flex-direction: column; gap: 18px">
{inner}
      </form>
      </sc-if>
'''


QR = '''        <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 16px">
          <svg role="img" aria-label="QR code for your authenticator app" width="132" height="132" viewBox="0 0 21 21" style="flex-shrink: 0; padding: 8px; border: 1px solid var(--border); border-radius: 8px; background: #ffffff" shape-rendering="crispEdges">
            <path fill="#0a0a0a" d="M0 0h7v7H0zM14 0h7v7h-7zM0 14h7v7H0z"></path>
            <path fill="#ffffff" d="M1 1h5v5H1zM15 1h5v5h-5zM1 15h5v5H1z"></path>
            <path fill="#0a0a0a" d="M2 2h3v3H2zM16 2h3v3h-3zM2 16h3v3H2zM8 0h1v1H8zM10 1h2v1h-2zM9 3h1v2H9zM11 4h1v1h-1zM8 6h1v1H8zM10 6h1v1h-1zM12 6h1v1h-1zM0 8h1v1H0zM2 8h2v1H2zM6 8h3v1H6zM11 8h2v1h-2zM15 8h1v1h-1zM18 8h3v1h-3zM1 10h2v1H1zM5 9h1v2H5zM8 10h2v1H8zM12 10h1v2h-1zM14 9h2v1h-2zM17 10h2v1h-2zM0 12h1v1H0zM3 12h2v1H3zM7 12h1v1H7zM9 12h2v1H9zM14 12h1v2h-1zM16 12h3v1h-3zM20 11h1v2h-1zM8 14h2v1H8zM11 14h1v1h-1zM9 16h1v2H9zM12 16h2v1h-2zM8 18h1v3H8zM10 19h2v1h-2zM14 15h1v1h-1zM16 14h2v1h-2zM19 15h2v1h-2zM15 17h2v1h-2zM18 17h1v2h-1zM14 19h2v2h-2zM17 20h4v1h-4zM20 18h1v1h-1z"></path>
          </svg>
          <div style="display: flex; flex-direction: column; gap: 6px; min-width: 0; flex: 1 1 160px">
            <span style="font-size: 13px; color: var(--muted-fg)">Cannot scan? Enter this key in the app instead:</span>
            <code style="font-family: 'Geist Mono', ui-monospace, monospace; font-size: 14px; letter-spacing: 0.06em; word-break: break-all">JBSW Y3DP EHPK 3PXP K4MX 2QTA</code>
          </div>
        </div>'''

CODES = '''        <ol aria-label="Recovery codes" style="margin: 0; padding: 14px 16px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 24px; list-style: none; border-radius: 8px; background: var(--subtle); border: 1px solid var(--border); font-family: 'Geist Mono', ui-monospace, monospace; font-size: 14px; letter-spacing: 0.04em">
          <sc-for list="{{codes}}" as="c" hint-placeholder-count="10"><li>{{c}}</li></sc-for>
        </ol>
        <div style="display: flex; gap: 8px">
          <button type="button" onClick="{{copy}}" style="__OUTLINE_SM__">{{copyLabel}}</button>
          <button type="button" style="__OUTLINE_SM__">Download as text</button>
        </div>
        <label style="display: flex; align-items: center; gap: 10px; font-size: 14px; cursor: pointer">
          <input type="checkbox" checked="{{savedCodes}}" onChange="{{toggleSaved}}" style="margin: 0; width: 16px; height: 16px; accent-color: var(--primary)">
          I have saved my recovery codes
        </label>'''


def screens(h, fs):
    b = BTN.replace('__H__', h)
    o = OUTLINE.replace('__H__', h)
    def F(*a, **k):
        return field(*a, **k).replace('__H__', h).replace('__FS__', fs)
    alert = '''        <sc-if value="{{showError}}" hint-placeholder-val="{{false}}">
          <div role="alert" style="display: flex; gap: 8px; padding: 10px 12px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px; line-height: 18px">
            <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink: 0; margin-top: 1px"><circle cx="12" cy="12" r="10"></circle><line x1="12" x2="12" y1="8" y2="12"></line><line x1="12" x2="12.01" y1="16" y2="16"></line></svg>
            <span>{{errorText}}</span>
          </div>
        </sc-if>'''
    sso = f'''        <sc-if value="{{{{sso}}}}" hint-placeholder-val="{{{{false}}}}">
          <button type="button" onClick="{{{{go.sso}}}}" style="{o}">
            <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="16" height="20" x="4" y="2" rx="2" ry="2"></rect><path d="M9 22v-4h6v4"></path><path d="M8 6h.01"></path><path d="M16 6h.01"></path><path d="M12 6h.01"></path><path d="M12 10h.01"></path><path d="M12 14h.01"></path><path d="M16 10h.01"></path><path d="M16 14h.01"></path><path d="M8 10h.01"></path><path d="M8 14h.01"></path></svg>
            Sign in with [Organisation name] single sign-on
          </button>
          <div aria-hidden="true" style="display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--muted-fg)"><span style="flex: 1; height: 1px; background: var(--border)"></span>or with email<span style="flex: 1; height: 1px; background: var(--border)"></span></div>
        </sc-if>'''
    forgot = f'<button type="button" onClick="{{{{go.forgot}}}}" style="{LINK}">Forgot your password?</button>'
    back = f'''        <div style="display: flex; justify-content: center"><button type="button" onClick="{{{{go.back}}}}" style="{LINK}">Back to sign in</button></div>'''
    out = ''
    out += screen('signin', '\n'.join([
        head('Sign in', 'Use your work email and password.'), sso, alert,
        F('si-email', 'Email', 'email', 'autocomplete="username" value="f.rahman@[domain]" '),
        F('si-pass', 'Password', 'password', 'autocomplete="current-password" ', right=forgot),
        f'        <button type="submit" style="{b}">Sign in</button>']))
    out += screen('code', '\n'.join([
        head('Enter your code', 'Open your authenticator app and enter the 6-digit code for memo.'), alert,
        F('si-code', 'Code', 'text', 'inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="123456" ', mono=True),
        f'        <button type="submit" style="{b}">Continue</button>',
        f'''        <div style="display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px"><button type="button" onClick="{{{{go.useRecovery}}}}" style="{LINK}">Use a recovery code instead</button><button type="button" onClick="{{{{go.back}}}}" style="{LINK}">Sign in as someone else</button></div>''']))
    out += screen('recovery', '\n'.join([
        head('Enter your code', 'Enter one of the recovery codes you saved when you set up two-step sign-in. Each code works once.'),
        F('si-rec', 'Recovery code', 'text', 'autocomplete="one-time-code" placeholder="XXXX-XXXX" ', mono=True),
        f'        <button type="submit" style="{b}">Continue</button>',
        f'''        <div style="display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px"><button type="button" onClick="{{{{go.useCode}}}}" style="{LINK}">Use the code from my app</button><button type="button" onClick="{{{{go.back}}}}" style="{LINK}">Sign in as someone else</button></div>''']))
    out += screen('reset', '\n'.join([
        head('Reset your password', 'Enter your work email. If it has an account, we will email you a link.'),
        F('rs-email', 'Email', 'email', 'autocomplete="username" '),
        f'        <button type="submit" style="{b}">Email me a link</button>', back]))
    out += screen('sent', '\n'.join([
        '        <span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 10px; background: var(--muted)"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="16" x="2" y="4" rx="2"></rect><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"></path></svg></span>',
        head('Check your email', 'If an account exists for that address, a link is on its way. It works once and expires in 30 minutes.'),
        back]))
    out += screen('newpw', '\n'.join([
        head('Choose a new password', 'Use at least 12 characters. A long phrase is easier to remember than a complex word.'),
        F('rs-new', 'New password', 'password', 'autocomplete="new-password" '),
        f'        <button type="submit" style="{b}">Set password</button>']))
    out += screen('done', '\n'.join([
        '        <span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 10px; background: var(--ok-bg); color: var(--ok-fg)"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"></path></svg></span>',
        head('Password changed', 'You were signed out everywhere. Sign in with your new password.'),
        f'        <button type="submit" style="{b}">Go to sign in</button>']))
    out += screen('enrol', '\n'.join([
        head('Set up two-step sign-in', 'Scan the QR code with an authenticator app (for example Microsoft Authenticator, Google Authenticator or 1Password), then enter the code it shows.'),
        QR,
        F('en-code', 'Code from the app', 'text', 'inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="123456" ', mono=True),
        f'        <button type="submit" style="{b}">Turn on two-step sign-in</button>']))
    out += screen('codes', '\n'.join([
        head('Save your recovery codes', 'If you lose your phone, each of these codes lets you sign in once. Store them somewhere safe, such as a password manager. They are shown only now.'),
        CODES.replace('__OUTLINE_SM__', 'height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer'),
        f'        <button type="submit" disabled="{{{{codesNotSaved}}}}" style="{{{{continueStyle}}}}">Continue</button>']))
    out += screen('invite', '\n'.join([
        head('Join [Organisation name]', 'You were invited as Farhana Rahman (f.rahman@[domain]). Choose a password to finish.'),
        F('in-pass', 'Password', 'password', 'autocomplete="new-password" ', hint='At least 12 characters. A long phrase is easier to remember than a complex word.'),
        f'        <button type="submit" style="{b}">Set password and continue</button>']))
    out += screen('expired', '\n'.join([
        head('This invitation is no longer valid', 'It was already used or has expired. Ask your administrator to send a new one.'),
        f'        <button type="submit" style="{o}">Go to sign in</button>']))
    return out


JS = '''class Component extends DCLogic {
  renderVals() {
    const st = this.state || {};
    const screen = st.screen || this.props.screen || 'signin';
    const to = (s) => (e) => { if (e && e.preventDefault) e.preventDefault(); this.setState({ screen: s, tried: false }); };
    const names = ['signin', 'code', 'recovery', 'reset', 'sent', 'newpw', 'done', 'enrol', 'codes', 'invite', 'expired'];
    const is = {};
    names.forEach((n) => { is[n] = n === screen; });
    const next = { signin: 'code', code: 'enrol', recovery: 'enrol', reset: 'sent', sent: 'newpw', newpw: 'done', done: 'signin', enrol: 'codes', codes: 'signin', invite: 'enrol', expired: 'signin' };
    const go = {};
    names.forEach((n) => { go[n] = to(next[n]); });
    go.signin = (e) => {
      if (e && e.preventDefault) e.preventDefault();
      if (!st.tried) { this.setState({ tried: true }); return; }
      this.setState({ screen: 'code', tried: false });
    };
    go.forgot = to('reset'); go.back = to('signin'); go.useRecovery = to('recovery'); go.useCode = to('code'); go.sso = to('signin');
    const saved = !!st.savedCodes;
    const steps = [['signin', 'Sign in'], ['code', 'Code'], ['recovery', 'Recovery code'], ['reset', 'Reset'], ['sent', 'Email sent'], ['newpw', 'New password'], ['done', 'Changed'], ['enrol', 'Set up two-step'], ['codes', 'Recovery codes'], ['invite', 'Invitation'], ['expired', 'Invitation expired']];
    return {
      themeClass: (this.state && this.state.theme) || this.props.theme || 'system',
      is: is, go: go,
      sso: !!this.props.sso,
      showError: is.signin && !!st.tried,
      errorText: 'The email or password is incorrect, or the account is temporarily locked. Try again or reset your password.',
      codes: ['7KQ4-M9TX', 'B2ZP-4HWN', 'R8DC-JV3E', 'Q5LN-7YFA', 'T3GW-K6PM', 'H9XE-2RUC', 'V4MJ-8NDS', 'C6YT-5BQL', 'P2HF-9WKA', 'E7NR-3ZGV'],
      copyLabel: st.copied ? 'Copied' : 'Copy all',
      copy: () => this.setState({ copied: true }),
      savedCodes: saved,
      toggleSaved: () => this.setState({ savedCodes: !saved }),
      codesNotSaved: !saved,
      continueStyle: 'width: 100%; height: __H__; border: 0; border-radius: 8px; font-size: 14px; font-weight: 500; ' + (saved ? 'background: var(--primary); color: var(--primary-fg); cursor: pointer' : 'background: var(--muted); color: var(--muted-fg); cursor: not-allowed'),
      steps: steps.map((s) => ({ label: s[1], aria: s[0] === screen ? 'true' : 'false', pick: () => this.setState({ screen: s[0], tried: false }), style: 'height: 26px; padding: 0 8px; border-radius: 6px; font-size: 12px; cursor: pointer; ' + (s[0] === screen ? 'border: 1px solid var(--border-strong); background: var(--bg); color: var(--fg); font-weight: 500' : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)') }))
    };
  }
}'''

PROPS = '{"theme":{"editor":"enum","options":["system","light","dark"],"default":"system"},"screen":{"editor":"enum","options":["signin","code","recovery","reset","sent","newpw","done","enrol","codes","invite","expired"],"default":"signin"},"sso":{"editor":"boolean","default":false},"$preview":{"width":__W__,"height":__HH__}}'

BRAND = '''      <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 24px">
        <span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 10px; background: var(--primary); color: var(--primary-fg); font-weight: 600; font-size: 16px">N</span>
        <span style="display: flex; flex-direction: column">
          <span style="font-weight: 600">[Organisation name]</span>
          <span style="font-size: 12px; color: var(--muted-fg)">Memo &amp; Meetings</span>
        </span>
      </div>'''

TOPBAR = '''    <div style="display: flex; justify-content: flex-end; gap: 4px; padding: 8px 12px">
      <button type="button" class="ghost" aria-label="Language: English" style="display: inline-flex; align-items: center; gap: 6px; height: __BH__; padding: 0 10px; border: 0; border-radius: 8px; background: transparent; color: var(--fg); font-size: 13px; cursor: pointer">
        <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m5 8 6 6"></path><path d="m4 14 6-6 2-3"></path><path d="M2 5h12"></path><path d="M7 2h1"></path><path d="m22 22-5-10-5 10"></path><path d="M14 18h6"></path></svg>
        English
      </button>
      <button type="button" class="ghost" aria-label="Theme: System" style="display: inline-flex; align-items: center; justify-content: center; width: __BH__; height: __BH__; border: 0; border-radius: 8px; background: transparent; color: var(--fg); cursor: pointer">
        <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="14" x="2" y="3" rx="2"></rect><line x1="8" x2="16" y1="21" y2="21"></line><line x1="12" x2="12" y1="17" y2="21"></line></svg>
      </button>
    </div>'''

STEPS = '''    <nav aria-label="Design states (not part of the page)" style="display: flex; flex-wrap: wrap; justify-content: center; gap: 2px; padding: 12px 16px 16px; border-top: 1px dashed var(--border)">
      <sc-for list="{{steps}}" as="s" hint-placeholder-count="11">
        <button type="button" aria-current="{{s.aria}}" onClick="{{s.pick}}" style="{{s.style}}">{{s.label}}</button>
      </sc-for>
    </nav>'''


def page(title, root_style, card_style, h, fs, bh, w, hh, steps=True):
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
{tokens}body{{margin:0;background:#ffffff}}
a{{color:var(--fg)}}
button,input{{font-family:inherit}}
.ghost:hover{{background:var(--muted)}}
</style>
</helmet>
<div class="th {{{{themeClass}}}}" style="{root_style}">
{TOPBAR.replace('__BH__', bh)}
  <main style="flex: 1; display: flex; justify-content: center; padding: 0 16px">
    <div style="{card_style}">
{BRAND}
{screens(h, fs)}    </div>
  </main>
{STEPS if steps else ''}
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{PROPS.replace('__W__', str(w)).replace('__HH__', str(hh))}'>
{JS.replace('__H__', h)}
</script>
</body>
</html>
'''


desk = page('Sign in',
            "display: flex; flex-direction: column; min-height: 900px; background: var(--subtle); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px",
            'width: 100%; max-width: 400px; box-sizing: border-box; margin-top: 64px; margin-bottom: 48px; padding: 28px; align-self: flex-start; border: 1px solid var(--border); border-radius: 12px; background: var(--bg); box-shadow: 0 1px 2px rgba(0,0,0,0.05)',
            '36px', '14px', '32px', 1440, 900, steps=False)
phone = page('Sign in on a phone',
             "position: relative; width: 390px; height: 844px; overflow: hidden; display: flex; flex-direction: column; background: var(--bg); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px",
             'width: 100%; box-sizing: border-box; padding: 16px 8px 24px; overflow-y: auto',
             '44px', '16px', '44px', 390, 844, steps=False)
(proj / 'SignIn.dc.html').write_text(desk)
(proj / 'SignInPhone.dc.html').write_text(phone)

c = proj / 'canvas.json'
d = json.loads(c.read_text())
d['boards']['SignIn.dc.html'] = {"x": 0, "y": 23060, "w": 1440, "h": 900, "title": "Sign in — Desktop", "expand": "fill", "is_interactive": True}
d['boards']['SignInPhone.dc.html'] = {"x": 1520, "y": 23060, "w": 390, "h": 844, "title": "Sign in — Phone", "is_interactive": True}
for b in ('SignIn.dc.html', 'SignInPhone.dc.html'):
    if b not in d['order']:
        d['order'].insert(d['order'].index('Rules.dc.html'), b)
d['notes']['signinTitle'] = {"x": 0, "y": 22800, "text": "Sign in, reset password, two-step setup, invitation", "kind": "title1", "maxW": 1910}
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
