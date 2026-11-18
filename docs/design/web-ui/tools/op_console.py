"""Operator console (platform administration): sign-in, two-step setup from the invitation link, Organisations (the
console's overview), New organisation, Organisation detail, Platform audit. Desktop and phone.

Copy from web/src/features/platform (platform-messages.ts and the pages) and features/auth/StepUpProvider.tsx; audit
action names from the backend. A separate app from the organisations' one, so it has its own top bar, which stays dark
in both themes and says Memo & Meetings · Platform administration on every page. Sign-in and two-step setup use the
organisation app's sign-in frame (the platform pages use the same AuthLayout). Lists are full width and use the admin
patterns: status tabs and search in one row, "N shown" and Show more, an empty state for "none yet" (`empty`) and one for
"no match" (`noMatch`). Builds the Op* boards and places them on the Operator canvas page; safe to rerun.

Not named operator.py, which would hide Python's own `operator` module.
"""
from kit import *
from wf_parts import SMALL, H, FS, obtn, icon_btn

I.update({'out': '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" x2="9" y1="12" y2="12"></line>',
          'copy': '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"></rect><path d="M4 16c0-1.1.9-2 2-2h2"></path><path d="M4 8V6c0-1.1.9-2 2-2h8c1.1 0 2 .9 2 2"></path>'})

# The console bar keeps one dark colour in both themes: inverted from the page in light, a raised surface in dark.
BAR_CSS = '.th{--bar-bg:#18181b;--bar-fg:#fafafa;--bar-muted:#a1a1aa;--bar-line:rgba(255,255,255,0.14)}\n.ed-main>*{flex-shrink:0}\n'
t = (proj / 'Tasks.dc.html').read_text()
HEAD = t[:t.index('</helmet>') + len('</helmet>\n')]
def head(title): return re.sub(r'<title>[^<]*</title>', f'<title>{title}</title>', HEAD, count=1).replace('</style>', BAR_CSS + '</style>', 1)
ROOT = lambda phone: (f'<div class="th {{{{themeClass}}}}" style="position: relative; {"width: 390px; height: 844px; overflow: hidden" if phone else "min-height: 1000px"}; display: flex; flex-direction: column; '
                      "background: var(--bg); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px\">")
PRODUCT = 'Memo &amp; Meetings'
MARK = lambda size, fs: f'<span aria-hidden="true" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: {size}px; height: {size}px; border-radius: {size // 4}px; background: var(--bar-fg); color: var(--bar-bg); font-weight: 700; font-size: {fs}px">M</span>'
WHO, WHO_MAIL = 'Tahmid Chowdhury', 't.chowdhury@[platform]'

def topbar(active, phone):
    """The console's own top bar: product and console name, its two pages, the operator and Sign out."""
    links = [('Organisations', 'OpTenants'), ('Platform audit', 'OpAudit')]
    def link(l, b):
        on = l == active
        return (f'<a href="{b}{"Phone" if phone else ""}.dc.html"{" aria-current=\"page\"" if on else ""} style="display: inline-flex; align-items: center; height: {"44px" if phone else "56px"}; padding: 0 {"12px" if phone else "4px"}; box-sizing: border-box; '
                f'border-bottom: 2px solid {"var(--bar-fg)" if on else "transparent"}; color: {"var(--bar-fg)" if on else "var(--bar-muted)"}; font-size: 14px; font-weight: 500; text-decoration: none; white-space: nowrap">{l}</a>')
    bar = 'flex-shrink: 0; background: var(--bar-bg); color: var(--bar-fg); border-bottom: 1px solid var(--bar-line)'
    if phone:
        return (f'  <header style="{bar}">\n'
                f'    <div style="display: flex; align-items: center; gap: 10px; height: 52px; padding: 0 4px 0 16px">{MARK(28, 14)}<span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="font-size: 12px; color: var(--bar-muted)">{PRODUCT}</span><span style="font-weight: 600; line-height: 18px">Platform administration</span></span>'
                f'<button type="button" class="ghost" aria-label="Sign out, {WHO}" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--bar-fg)">{svg(I["out"], 18)}</button></div>\n'
                f'    <nav aria-label="Platform administration" style="display: flex; padding: 0 4px; border-top: 1px solid var(--bar-line)">{"".join(link(*x) for x in links)}</nav>\n  </header>\n')
    return (f'  <header style="{bar}">\n'
            f'    <div style="display: flex; align-items: center; gap: 28px; height: 56px; padding: 0 24px">'
            f'<span style="display: inline-flex; align-items: center; gap: 10px">{MARK(28, 14)}<span style="font-weight: 600">{PRODUCT}</span><span aria-hidden="true" style="width: 1px; height: 16px; background: var(--bar-line)"></span><span style="font-weight: 500; color: var(--bar-muted)">Platform administration</span></span>'
            f'<nav aria-label="Platform administration" style="display: flex; gap: 20px; align-self: stretch">{"".join(link(*x) for x in links)}</nav>'
            f'<span style="margin-left: auto; display: flex; align-items: center; gap: 12px"><span style="display: flex; flex-direction: column; align-items: flex-end"><span style="font-size: 13px; font-weight: 500">{WHO}</span><span style="font-size: 11px; color: var(--bar-muted)">Operator · {WHO_MAIL}</span></span>'
            f'<button type="button" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 1px solid var(--bar-line); border-radius: 8px; background: transparent; color: var(--bar-fg); font-size: 13px; font-weight: 500; cursor: pointer">{svg(I["out"], 14)}Sign out</button></span></div>\n  </header>\n')

def main(body, phone):
    if phone:
        return f'  <main class="ed-main" style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 16px 16px 24px">\n{body}\n  </main>\n'
    return f'  <main style="flex: 1; min-width: 0; padding: 20px 24px 32px; display: flex; flex-direction: column; gap: 16px">\n{body}\n  </main>\n'

def title_row(title, lead, phone, btns='', chip_html=''):
    h1 = f'<h1 style="margin: 0; font-size: {"18px" if phone else "20px"}; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">{title}</h1>'
    left = f'<div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; max-width: 760px"><div style="display: flex; flex-wrap: wrap; align-items: center; gap: 10px">{h1}{chip_html}</div>' + (f'<p style="margin: 0; font-size: {"13px" if phone else "14px"}; color: var(--muted-fg)">{lead}</p>' if lead else '') + '</div>'
    return f'    <div style="display: flex; flex-wrap: {"nowrap" if phone else "wrap"}; align-items: flex-end; justify-content: space-between; gap: 12px">{left}<div style="display: flex; flex-wrap: wrap; gap: 8px">{btns}</div></div>'

PB = lambda l, c, phone, icon='plus': f'<button type="button" onClick="{{{{{c}}}}}" style="display: inline-flex; align-items: center; gap: 6px; height: {"40px" if phone else "32px"}; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 13px; font-weight: 500; cursor: pointer">{svg(I[icon], 14) if icon else ""}{l}</button>'
PA = lambda l, href, phone: f'<a href="{href}" style="flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: {"40px" if phone else "32px"}; padding: 0 12px; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 13px; font-weight: 500; text-decoration: none; white-space: nowrap">{svg(I["plus"], 14)}{l}</a>'
OB = lambda l, c, phone, extra='': f'<button type="button" class="ghost" onClick="{{{{{c}}}}}" style="{OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}{extra}">{l}</button>'
BACK = lambda l, href, phone: f'    <a href="{href}" style="align-self: flex-start; display: inline-flex; align-items: center; gap: 4px; {"min-height: 44px; margin: -12px 0 -8px;" if phone else ""} font-size: 13px; color: var(--muted-fg); text-decoration: none">{svg(I["back"], 14)}{l}</a>'
SHOW_MORE = lambda phone: f'<button type="button" class="ghost" style="{obtn(True) + "; align-self: center; margin-top: 12px" if phone else OUTLINE}">Show more</button>'

# ================================================================ the "Confirm it is you" step (server-required before create, suspend, resume, invite)
SIGNIN_SRC = (proj / 'SignIn.dc.html').read_text()
MKOTP = SIGNIN_SRC[SIGNIN_SRC.index('    const mkOtp = (k, fid, o) => {'):SIGNIN_SRC.index('    const OTP = {')]
def otp(key, fid, label='Code'):
    blk = lambda part: (f'<span aria-hidden="true" style="display: flex"><sc-for list="{{{{otp.{key}.{part}}}}}" as="sl" hint-placeholder-count="3"><span style="{{{{sl.style}}}}">{{{{sl.ch}}}}'
                        '<sc-if value="{{sl.caret}}" hint-placeholder-val="{{false}}"><span class="otp-caret" style="position: absolute; width: 1px; height: 45%; background: var(--fg)"></span></sc-if></span></sc-for></span>')
    return (f'<div style="display: flex; flex-direction: column; gap: 6px; align-items: center; text-align: center"><label for="{fid}" style="font-size: 14px; font-weight: 500">{label}</label>'
            f'<div style="position: relative; align-self: center; display: inline-flex; align-items: center; gap: 8px">'
            f'<input id="{fid}" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" value="{{{{otp.{key}.value}}}}" onChange="{{{{otp.{key}.onInput}}}}" onFocus="{{{{otp.{key}.onFocus}}}}" onBlur="{{{{otp.{key}.onBlur}}}}" aria-invalid="{{{{otp.{key}.invalid}}}}" aria-describedby="{{{{otp.{key}.describedBy}}}}" '
            'style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 2; width: 100%; height: 100%; margin: 0; padding: 0; border: 0; opacity: 0; background: transparent; color: transparent; caret-color: transparent; font-size: 16px; cursor: text">'
            + blk('a') + '<span aria-hidden="true" style="display: flex; color: var(--muted-fg)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 12h14"></path></svg></span>' + blk('b') + '</div>'
            f'<sc-if value="{{{{codeHasErr}}}}" hint-placeholder-val="{{{{false}}}}"><span id="{fid}-err" style="font-size: 12px; color: var(--danger-fg)">{{{{codeErr}}}}</span></sc-if></div>')
def stepup_dialog(phone):
    su = frame('\n'.join([heading('su-title', 'Confirm it is you', 'Enter the 6-digit code from your authenticator app to continue.'), '      ' + otp('su_code', 'op-su-code'), alert('su'), buttons('Confirm', primary(phone), phone)]), 'su-title', 'dialog', 'su.submit', phone)
    return f'\n  <sc-if value="{{{{isStepUp}}}}" hint-placeholder-val="{{{{false}}}}">\n    {su}\n  </sc-if>\n'
def STEPUP_JS(done):
    return (MKOTP + "    const suVals = { isStepUp: dlg === 'stepUp', otp: { su_code: mkOtp('su_code', 'op-su-code', { init: err === 'wrong' ? '551203' : '55', error: err === 'wrong' }) }, codeHasErr: dlg === 'stepUp' && err === 'wrong', codeErr: 'That did not match. Try again.',\n"
            "      su: { hasAlert: err === 'tooMany', alert: 'Too many attempts. Wait a minute and try again.', submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (err === 'wrong' || err === 'tooMany') { this.setState({ err: err }); return; } "
            + done + " } } };\n")

# ================================================================ sign-in and two-step setup: the organisation app's sign-in frame
def auth_frame(phone):
    src = (proj / ('SignInPhone.dc.html' if phone else 'SignIn.dc.html')).read_text()
    i = src.index('<div class="th'); j = src.index('      <sc-if value="{{is.signin}}"')
    f = src[i:j]
    f = f.replace('>N</span>', '>M</span>').replace('<span style="font-weight: 600">[Organisation name]</span>', f'<span style="font-weight: 600">{PRODUCT}</span>')
    return f.replace(f'<span style="font-size: 12px; color: var(--muted-fg)">{PRODUCT}</span>', '<span style="font-size: 12px; color: var(--muted-fg)">Platform administration</span>')
AUTH_FOOT = ('      <p style="margin: 24px 0 0; padding-top: 16px; border-top: 1px solid var(--hover); font-size: 12px; color: var(--muted-fg)">For the people who run the platform. '
             'Organisations sign in at their own address, such as <span style="white-space: nowrap">meghna-leasing.memo.[domain]</span>.</p>\n')
def auth_page(card, phone): return card + '\n' + AUTH_FOOT + '    </div>\n  </main>\n'

def card_head(title, lead): return f'        <div style="display: flex; flex-direction: column; gap: 6px"><h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">{title}</h1><p style="margin: 0; font-size: 14px; color: var(--muted-fg)">{lead}</p></div>'
def submit(label, phone, outline=False):
    look = 'border: 1px solid var(--border); background: var(--bg); color: var(--fg)' if outline else 'border: 0; background: var(--primary); color: var(--primary-fg)'
    return f'        <button type="submit" style="width: 100%; height: {"44px" if phone else "36px"}; {look}; border-radius: 8px; font-size: {"15px" if phone else "14px"}; font-weight: 500; cursor: pointer">{label}</button>'
LINKBTN = 'padding: 0; border: 0; background: transparent; color: var(--fg); font-size: 13px; font-weight: 500; text-decoration: underline; text-underline-offset: 3px; cursor: pointer'
def screen(flag, form_submit, body):
    return f'      <sc-if value="{{{{is.{flag}}}}}" hint-placeholder-val="{{{{{"true" if flag in ("signin", "newpw") else "false"}}}}}">\n      <form novalidate onSubmit="{{{{{form_submit}}}}}" style="display: flex; flex-direction: column; gap: 18px">\n{body}\n      </form>\n      </sc-if>'

def signin_card(phone):
    pw = text_field('si', 'password', 'Password', 'password', phone).replace('autocomplete="off"', 'autocomplete="current-password"')
    em = text_field('si', 'email', 'Email', 'email', phone).replace('autocomplete="off"', 'autocomplete="username"')
    s1 = '\n'.join([card_head('Operator sign-in', 'Platform operators only. Two-step sign-in is always required.'), alert('si'), em, pw, submit('Sign in', phone)])
    s2 = '\n'.join([card_head('Enter your code', 'Enter the 6-digit code from your authenticator app.'), alert('sc'), '        ' + otp('si_code', 'op-code'), submit('Continue', phone),
                    f'        <div style="display: flex; justify-content: flex-end"><button type="button" onClick="{{{{go.back}}}}" style="{LINKBTN}">Sign in as someone else</button></div>',
                    '        <p style="margin: 0; padding: 10px 12px; border-radius: 8px; background: var(--subtle); font-size: 13px; color: var(--text-2)"><strong style="font-weight: 600; color: var(--fg)">Lost your authenticator app?</strong> Operators have no recovery codes. Ask whoever runs the platform’s servers to reset your two-step sign-in.</p>'])
    return screen('signin', 'si.submit', s1) + '\n' + screen('code', 'sc.submit', s2)
SIGNIN_JS = MKOTP + r"""
    const scr = st.screen || this.props.screen || 'signin';
    const tried = !!form.tried || err === 'required';
    const email = form.email !== undefined ? form.email : (err === 'required' ? '' : 't.chowdhury@[platform]');
    const password = form.password !== undefined ? form.password : (err === 'required' ? '' : 'correct horse battery');
    const SI = { invalid: 'The email or password is incorrect, or the account is locked for a while.', tooMany: 'Too many attempts. Wait a few minutes and try again.', failed: 'That did not work. Try again.' };
    const siAlert = scr === 'signin' ? (SI[err] || '') : '';
    const codeErr = err === 'codeInvalid' && scr === 'code' ? 'That code is not valid. Try the next one.' : '';
    const vals = Object.assign(base, {
      is: { signin: scr === 'signin', code: scr === 'code' }, go: { back: () => this.setState({ screen: 'signin', err: 'none' }) },
      otp: { si_code: mkOtp('si_code', 'op-code', { init: err === 'codeInvalid' ? '481920' : '4819', error: !!codeErr }) }, codeHasErr: !!codeErr, codeErr: codeErr,
      si: Object.assign({ on_email: setForm('email'), on_password: setForm('password'), hasAlert: !!siAlert, alert: siAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!email.trim() || !password) { this.setState({ form: Object.assign({}, form, { email: email, password: password, tried: true }) }); return; }
          if (siAlert) { this.setState({ err: err }); return; } this.setState({ screen: 'code', err: 'none' }); } },
        fieldVals('si', 'email', email, tried && !email.trim() ? 'Required.' : ''), fieldVals('si', 'password', password, tried && !password ? 'Required.' : '')),
      // On success the app opens Organisations; there is no message.
      sc: { hasAlert: scr === 'code' && err === 'failed', alert: 'That did not work. Try again.', submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (codeErr) this.setState({ err: err }); } }
    });
"""
SIGNIN_PROPS = '"screen":{"editor":"enum","options":["signin","code"],"default":"signin"},"error":{"editor":"enum","options":["none","required","invalid","tooMany","codeInvalid","failed"],"default":"none"},'

QR = ('<svg role="img" aria-label="QR code for your authenticator app" width="132" height="132" viewBox="0 0 21 21" style="flex-shrink: 0; padding: 8px; border: 1px solid var(--border); border-radius: 8px; background: #ffffff" shape-rendering="crispEdges">'
      + ''.join(f'<rect x="{x}" y="{y}" width="1" height="1" fill="#0a0a0a"></rect>' for y in range(21) for x in range(21)
                if (x < 7 and y < 7 and (x in (0, 6) or y in (0, 6) or (2 <= x <= 4 and 2 <= y <= 4))) or (x > 13 and y < 7 and (x in (14, 20) or y in (0, 6) or (16 <= x <= 18 and 2 <= y <= 4)))
                or (x < 7 and y > 13 and (x in (0, 6) or y in (14, 20) or (2 <= x <= 4 and 16 <= y <= 18))) or (7 < x < 21 and 7 < y < 21 and (x * 7 + y * 11 + x * y) % 5 < 2) or (x == 6 and y % 2 == 0 and 7 < y < 14) or (y == 6 and x % 2 == 0 and 7 < x < 14))
      + '</svg>')
def enrol_card(phone):
    pw = text_field('lp', 'password', 'New password (at least 15 characters)', 'password', phone).replace('autocomplete="off"', 'autocomplete="new-password"')
    s1 = '\n'.join([card_head('Choose your password', '{{who}}'), alert('lp'), pw, submit('Set password', phone)])
    key = (f'<div style="display: flex; flex-direction: column; gap: 6px; min-width: 0; flex: 1 1 160px"><span style="font-size: 13px; color: var(--muted-fg)">Cannot scan? Type this key in the app instead:</span>'
           f'<span style="display: flex; align-items: flex-start; gap: 4px"><code style="flex: 1; min-width: 0; {MONO}; font-size: 14px; letter-spacing: 0.06em; word-break: break-all">JBSW Y3DP EHPK 3PXP K4ZT 7QAB</code>{icon_btn("Copy the key", "copy", phone)}</span></div>')
    s2 = '\n'.join([card_head('Set up two-step sign-in', 'Scan the code with an authenticator app, or type the key, then enter the 6-digit code it shows.'),
                    f'        <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 16px">{QR}{key}</div>', '        ' + otp('en_code', 'op-en-code', 'Code'), alert('ec'), submit('Continue', phone)])
    s3 = '\n'.join([card_head('This link no longer works', 'This link is no longer valid. Ask for a new one to be generated on the server.'),
                    '        <p style="margin: 0; font-size: 13px; color: var(--muted-fg)">A link works once, and only for a short time. Whoever runs the platform’s servers can make a new one.</p>', submit('Go to sign in', phone, True)])
    return screen('newpw', 'lp.submit', s1) + '\n' + screen('enrol', 'ec.submit', s2) + '\n' + screen('expired', 'go.signin', s3)
ENROL_JS = MKOTP + r"""
    const scr = st.screen || this.props.screen || 'newpw';
    const tried = !!form.tried || err === 'required';
    const pw = form.password !== undefined ? form.password : (err === 'required' ? '' : (err === 'weak' ? 'tahmid2026' : 'river lantern copper autumn'));
    const pwErr = tried && !pw ? 'Required.' : (err === 'weak' ? 'Choose a longer password that is not on a list of breached passwords and does not contain your name.' : '');
    const codeErr = err === 'codeInvalid' && scr === 'enrol' ? 'That code is not valid. Try the next one.' : '';
    const vals = Object.assign(base, {
      who: 'Operator account for Tahmid Chowdhury (t.chowdhury@[platform]).',
      is: { newpw: scr === 'newpw', enrol: scr === 'enrol', expired: scr === 'expired' }, go: { signin: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); } },
      otp: { en_code: mkOtp('en_code', 'op-en-code', { init: err === 'codeInvalid' ? '120934' : '', error: !!codeErr }) }, codeHasErr: !!codeErr, codeErr: codeErr,
      lp: Object.assign({ on_password: setForm('password'), hasAlert: err === 'failed' && scr === 'newpw', alert: 'That did not work. Try again.',
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!pw || pwErr) { this.setState({ form: Object.assign({}, form, { password: pw, tried: true }) }); return; } this.setState({ screen: 'enrol', err: 'none' }); } },
        fieldVals('lp', 'password', pw, pwErr)),
      // On success the app opens Organisations; there is no message.
      ec: { hasAlert: err === 'failed' && scr === 'enrol', alert: 'That did not work. Try again.', submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (codeErr) this.setState({ err: err }); } }
    });
"""
ENROL_PROPS = '"screen":{"editor":"enum","options":["newpw","enrol","expired"],"default":"newpw"},"error":{"editor":"enum","options":["none","required","weak","codeInvalid","failed"],"default":"none"},'

# ================================================================ Organisations (the console's overview)
TEN_JS = r"""
    const O = (id, name, slug, status, admin, created) => ({ id: id, name: name, slug: slug, status: status, admin: admin, created: created });
    const ALL = [O('t1', '[Organisation name]', 'organisation', 'Active', true, '2 Jan 2026'), O('t2', 'Meghna Leasing', 'meghna-leasing', 'Active', true, '14 Mar 2026'),
      O('t3', 'Padma Insurance', 'padma-insurance', 'Active', true, '9 Jun 2026'), O('t4', 'Rupsha Cooperative', 'rupsha', 'Active', false, '15 Nov 2026'),
      O('t5', 'Surma Microfinance', 'surma-mf', 'Suspended', true, '20 Apr 2026'), O('t6', 'Teesta Logistics', 'teesta', 'Active', true, '3 Aug 2026'), O('t7', 'Training', 'training', 'Active', true, '2 Jan 2026')];
    const q = (st.q !== undefined ? st.q : (this.props.noMatch ? 'karnaphuli' : '')).toLowerCase();
    const tab = st.tab || '';
    const pass = (o) => !tab || (tab === 'noAdmin' ? !o.admin : o.status === tab);
    const shown = this.props.empty ? [] : ALL.filter((o) => pass(o) && (!q || (o.name + ' ' + o.slug).toLowerCase().indexOf(q) >= 0));
    const rows = shown.map((o) => ({ name: o.name, slug: o.slug, host: o.slug + '.memo.[domain]', status: o.status, statusStyle: TONE[o.status === 'Active' ? 'ok' : 'warn'],
      admin: o.admin ? 'Yes' : 'No administrator yet', adminStyle: o.admin ? 'color: var(--text-2)' : 'color: var(--warn-fg); font-weight: 500' }));
    const TABS = [['', 'All'], ['Active', 'Active'], ['Suspended', 'Suspended'], ['noAdmin', 'No administrator']];
    const tabBase = 'display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; border-radius: 7px; font-size: 13px; font-weight: 500; cursor: pointer; white-space: nowrap; ';
    const pill = 'flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 14px; border-radius: 18px; font-size: 13px; font-weight: 500; white-space: nowrap; ';
    const tabs = TABS.map(([id, label]) => { const on = id === tab; return { label: label, aria: on ? 'true' : 'false', pick: () => this.setState({ tab: id }),
      style: PHONE ? pill + (on ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: var(--text-2)')
                   : tabBase + (on ? 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.06)' : 'border: 1px solid transparent; background: transparent; color: var(--muted-fg)') }; });
    const none = !!this.props.empty;
    const vals = Object.assign(base, {
      rows: rows.map((r, i) => Object.assign(r, { created: shown[i].created })), hasRows: rows.length > 0, noRows: rows.length === 0 && !none, noneYet: none, tabs: tabs,
      q: q, onQ: (ev) => this.setState({ q: ev && ev.target ? ev.target.value : '' }), clearSearch: () => this.setState({ q: '', tab: '' }),
      footNote: rows.length + ' shown'
    });
"""
TEN_PROPS = '"empty":{"editor":"boolean","default":false},"noMatch":{"editor":"boolean","default":false},'
def tenants(phone):
    tb = toolbar(phone, 'Search by name or address…')
    none = empty_state('No organisations yet.', 'Create the first one. Its administrator gets an invitation by email and sets everything else up.', PA('New organisation', f'OpNewTenant{"Phone" if phone else ""}.dc.html', phone), phone)
    nomatch = empty_state('No organisations match.', 'Try another name or address, or show all.', OB('Show all', 'clearSearch', phone), phone)
    if phone:
        tb = f'<div style="margin: 0 -16px -14px">{tb}</div>'
        lst = ('<ul aria-label="Organisations, by address" style="list-style: none; margin: 0 -16px; padding: 0; border-top: 1px solid var(--hover)"><sc-for list="{{rows}}" as="r" hint-placeholder-count="7"><li><a href="OpTenantPhone.dc.html" style="display: flex; align-items: center; gap: 8px; padding: 12px 8px 12px 16px; border-bottom: 1px solid var(--hover); color: var(--fg); text-decoration: none">'
               f'<span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-size: 15px; font-weight: 500">{{{{r.name}}}}</span><span style="{{{{r.statusStyle}}}}">{{{{r.status}}}}</span></span>'
               f'<span style="{MONO}; font-size: 12px; color: var(--text-2)">{{{{r.host}}}}</span><span style="font-size: 12px; color: var(--muted-fg)">Administrator: <span style="{{{{r.adminStyle}}}}">{{{{r.admin}}}}</span></span></span>{svg(I["right"], 16, " style=\"color: var(--muted-fg)\"")}</a></li></sc-for></ul>')
        listing = (f'<sc-if value="{{{{hasRows}}}}" hint-placeholder-val="{{{{true}}}}">{lst}{SHOW_MORE(True)}</sc-if>'
                   f'<sc-if value="{{{{noRows}}}}" hint-placeholder-val="{{{{false}}}}">{nomatch}</sc-if><sc-if value="{{{{noneYet}}}}" hint-placeholder-val="{{{{false}}}}">{none}</sc-if>')
        body = '\n'.join([title_row('Organisations', 'Each has its own address and data.', True, PA('New', 'OpNewTenantPhone.dc.html', True)), tb, '    ' + listing])
        return topbar('Organisations', True) + main(body, True)
    th = TH
    table = (f'<div style="{CARD}; overflow-x: auto"><table style="width: 100%; min-width: 900px; border-collapse: collapse; font-size: 14px"><caption style="{SR}">Organisations, by address</caption>'
             f'<thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 40px; {th.replace("0 12px", "0 16px", 1)}">Name</th><th scope="col" style="{th}; width: 300px">Address</th><th scope="col" style="{th}; width: 130px">Status</th><th scope="col" style="{th}; width: 200px">Administrator</th><th scope="col" style="{th}; width: 130px">Created</th></tr></thead>'
             '<tbody><sc-for list="{{rows}}" as="r" hint-placeholder-count="7"><tr class="lrow" style="border-bottom: 1px solid var(--hover)"><td style="height: 48px; padding: 0 16px"><a href="OpTenant.dc.html" style="font-weight: 500; color: var(--fg); text-decoration: none">{{r.name}}</a></td>'
             f'<td style="padding: 0 12px; {MONO}; font-size: 13px; color: var(--text-2); white-space: nowrap">{{{{r.host}}}}</td><td style="padding: 0 12px"><span style="{{{{r.statusStyle}}}}">{{{{r.status}}}}</span></td><td style="padding: 0 12px; {{{{r.adminStyle}}}}">{{{{r.admin}}}}</td><td style="padding: 0 12px; color: var(--muted-fg); white-space: nowrap">{{{{r.created}}}}</td></tr></sc-for></tbody></table></div>')
    listing = (f'<sc-if value="{{{{hasRows}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; flex-direction: column; gap: 8px">{table}<div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px">{FOOT_NOTE}{SHOW_MORE(False)}</div></div></sc-if>'
               f'<sc-if value="{{{{noRows}}}}" hint-placeholder-val="{{{{false}}}}">{nomatch}</sc-if><sc-if value="{{{{noneYet}}}}" hint-placeholder-val="{{{{false}}}}">{none}</sc-if>')
    body = '\n'.join([title_row('Organisations', 'Each organisation has its own address and data. You see its registry entry only.', False, PA('New organisation', 'OpNewTenant.dc.html', False)), tb, '    ' + listing])
    return topbar('Organisations', False) + main(body, False)

# ================================================================ New organisation
NEW_JS = STEPUP_JS("this.setState({ dlg: '', toast: 'Organisation created. The invitation is on its way.' });") + r"""
    const f = (k, d) => form[k] !== undefined ? form[k] : (err === 'required' ? '' : d);
    const name = f('name', 'Karnaphuli Finance'), slug = f('slug', err === 'slugTaken' ? 'meghna-leasing' : (err === 'slugBad' ? 'Karnaphuli_Finance' : 'karnaphuli')), an = f('adminName', 'Shamima Nasrin'), ae = f('adminEmail', 's.nasrin@karnaphuli.example');
    const tried = !!form.tried || err === 'required';
    const slugErr = tried && !slug.trim() ? 'Required.' : (err === 'slugTaken' ? 'Another organisation has this address.' : (err === 'slugBad' ? '3 to 63 lower-case letters, digits and hyphens.' : ''));
    const alertText = !dlg ? (({ failed: 'That did not work. Try again.' })[err] || '') : '';
    const vals = Object.assign(base, suVals, {
      host: (slug || 'address') + '.memo.[domain]', adminEmail: ae || 'the first administrator',
      nt: Object.assign({ on_name: setForm('name'), on_slug: setForm('slug'), on_adminName: setForm('adminName'), on_adminEmail: setForm('adminEmail'), hasAlert: !!alertText, alert: alertText,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!name.trim() || !slug.trim() || !an.trim() || !ae.trim() || slugErr) { this.setState({ form: Object.assign({}, form, { name: name, slug: slug, adminName: an, adminEmail: ae, tried: true }) }); return; }
          if (alertText) { this.setState({ err: err }); return; } this.setState({ dlg: 'stepUp', err: 'none' }); } },
        fieldVals('nt', 'name', name, tried && !name.trim() ? 'Required.' : ''), fieldVals('nt', 'slug', slug, slugErr), fieldVals('nt', 'adminName', an, tried && !an.trim() ? 'Required.' : ''), fieldVals('nt', 'adminEmail', ae, tried && !ae.trim() ? 'Required.' : ''))
    });
"""
NEW_PROPS = '"dialog":{"editor":"enum","options":["none","stepUp"],"default":"none"},"error":{"editor":"enum","options":["none","required","slugTaken","slugBad","wrong","tooMany","failed"],"default":"none"},'
def new_tenant(phone):
    sel = lambda id_, l, opts: (f'<div style="display: flex; flex-direction: column; gap: 6px"><label for="{id_}" style="font-size: 14px; font-weight: 500">{l}</label><span style="position: relative; display: flex; align-items: center"><select id="{id_}" style="width: 100%; height: {H(phone)}; padding: 0 30px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}">'
                                + ''.join(f'<option{" selected" if i == 0 else ""}>{o}</option>' for i, o in enumerate(opts)) + '</select>' + svg(I['chev'], 14, ' style="position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none"') + '</span></div>')
    slug = text_field('nt', 'slug', 'Address', 'text', phone).replace('outline: none;', 'outline: none; ' + MONO + ';', 1)
    slug = slug.replace('</div>', f'  <span style="font-size: 12px; color: var(--muted-fg)">3 to 63 lower-case letters, digits and hyphens. Becomes the start of the organisation’s web address: <span style="{MONO}; color: var(--text-2); white-space: nowrap">{{{{host}}}}</span></span>\n        </div>', 1)
    g = f'display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 14px'
    fields = (f'<section style="{CARD}; padding: {"16px" if phone else "20px"}; display: flex; flex-direction: column; gap: 16px"><h2 style="margin: 0; font-size: 15px; font-weight: 600">The organisation</h2>'
              + text_field('nt', 'name', 'Name', 'text', phone) + slug + f'<div style="{g}">' + sel('nt-lang', 'Default language', ['English', 'العربية']) + sel('nt-tz', 'Time zone', ['Asia/Dhaka', 'Asia/Dubai', 'Europe/London', 'UTC']) + '</div>'
              + '<h2 style="margin: 8px 0 0; font-size: 15px; font-weight: 600">Its first administrator</h2>'
              + f'<div style="{g}">' + text_field('nt', 'adminName', 'First administrator’s name', 'text', phone) + text_field('nt', 'adminEmail', 'First administrator’s email', 'email', phone) + '</div>'
              + alert('nt') + f'<div style="display: flex; {"flex-direction: column-reverse" if phone else "justify-content: flex-end"}; gap: 8px"><a href="OpTenants{"Phone" if phone else ""}.dc.html" class="ghost" style="display: inline-flex; align-items: center; justify-content: center; {obtn(phone)}; text-decoration: none">Cancel</a><button type="submit" style="{primary(phone)}">Create and invite</button></div></section>')
    what = (f'<aside aria-labelledby="wh" style="border: 1px solid var(--border); border-radius: 10px; background: var(--subtle); padding: 16px; display: flex; flex-direction: column; gap: 10px{"" if phone else "; position: sticky; top: 16px"}">'
            '<h2 id="wh" style="margin: 0; font-size: 14px; font-weight: 600">What happens</h2><ol style="margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 8px; font-size: 13px; color: var(--text-2)">'
            '<li>You confirm it is you with a code from your authenticator app.</li>'
            f'<li>The organisation is created at <span style="{MONO}; white-space: nowrap">{{{{host}}}}</span>, with its own data. Nobody else can see it.</li>'
            '<li>{{adminEmail}} gets an invitation to set a password.</li><li>They set up two-step sign-in, then invite everyone else and set the organisation up.</li>'
            '<li>You see only its registry entry: name, address, status, and whether it has an administrator.</li></ol></aside>')
    form_ = f'<form novalidate onSubmit="{{{{nt.submit}}}}" style="display: contents">{fields}</form>'
    lay = f'<div style="display: flex; flex-direction: column; gap: 14px">{form_}{what}</div>' if phone else f'<div style="display: grid; grid-template-columns: minmax(0, 880px) 340px; gap: 16px; align-items: start">{form_}{what}</div>'
    body = '\n'.join([BACK('Back to Organisations', 'OpTenants' + ('Phone' if phone else '') + '.dc.html', phone), title_row('New organisation', 'Creates the organisation and emails its first administrator an invitation.', phone), '    ' + lay])
    return topbar('Organisations', phone) + main(body, phone)

# ================================================================ Organisation detail
ONE_JS = STEPUP_JS("const next = st.pending || 'suspend'; const T = { suspend: 'Organisation suspended.', resume: 'Organisation resumed.', invite: 'Invitation sent.' }; this.setState({ dlg: '', kind: next === 'suspend' ? 'suspended' : (next === 'resume' ? 'active' : kindNow), toast: T[next] });") + r"""
    const kindNow = st.kind || this.props.org || 'noAdmin';
    const kind = kindNow;
    const O = { active: { name: 'Meghna Leasing', slug: 'meghna-leasing', status: 'Active', admin: true, created: '14 Mar 2026', changed: '' },
                noAdmin: { name: 'Rupsha Cooperative', slug: 'rupsha', status: 'Active', admin: false, created: '15 Nov 2026', changed: '' },
                suspended: { name: 'Surma Microfinance', slug: 'surma-mf', status: 'Suspended', admin: true, created: '20 Apr 2026', changed: '12 Nov 2026' } }[kind];
    const isSusp = O.status === 'Suspended';
    const typed = form.typed !== undefined ? form.typed : (err === 'typedWrong' ? 'rupsa' : '');
    const typedErr = (form.tried || err === 'typedWrong') && typed.trim() !== O.slug ? 'Type the address exactly: ' + O.slug + '.' : '';
    const ALERTS = { stale: 'Someone changed this meanwhile. The page shows the current state.', hasAdmin: 'The organisation already has an active administrator.', failed: 'That did not work. Try again.' };
    const a = ALERTS[err] || '';
    const ai = f0 => form[f0] !== undefined ? form[f0] : (err === 'required' ? '' : (f0 === 'name' ? 'Rafiqul Islam' : 'r.islam@rupsha.example'));
    const iname = ai('name'), iemail = ai('email');
    const itried = !!form.itried || err === 'required';
    const nm = form.rename !== undefined ? form.rename : O.name;
    const stepUp = (what) => this.setState({ dlg: 'stepUp', pending: what, err: 'none', more: false });
    const openSuspend = () => this.setState({ dlg: 'suspend', form: {}, err: 'none', more: false }), openResume = () => this.setState({ dlg: 'resume', err: 'none', more: false }), openRename = () => this.setState({ dlg: 'rename', form: {}, err: 'none', more: false });
    const vals = Object.assign(base, suVals, {
      o: { name: O.name, host: O.slug + '.memo.[domain]', slug: O.slug, status: O.status, statusStyle: TONE[isSusp ? 'warn' : 'ok'], created: O.created, changed: O.changed || 'Never',
           admin: O.admin ? 'Has an active administrator' : 'No administrator yet', adminStyle: O.admin ? 'color: var(--fg)' : 'color: var(--warn-fg); font-weight: 600' },
      noAdmin: !O.admin, isSusp: isSusp, isActive: !isSusp, suspNote: 'Nobody can sign in. Its background work waits until you resume it. Its data stays as it is.',
      openSuspend: openSuspend, openResume: openResume, openRename: openRename,
      acts: [item('Rename', openRename, false), isSusp ? item('Resume', openResume, false) : item('Suspend', openSuspend, true)],
      sheetOpen: !!st.more, openMore: () => this.setState({ more: true }), closeMore: () => this.setState({ more: false }), moreKey: (ev) => { if (ev && ev.key === 'Escape') this.setState({ more: false }); },
      isSuspend: dlg === 'suspend', isResume: dlg === 'resume', isRename: dlg === 'rename',
      sd: Object.assign({ slug: O.slug, on_typed: setForm('typed'), hasAlert: !!a && dlg === 'suspend', alert: a,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (typed.trim() !== O.slug) { this.setState({ form: Object.assign({}, form, { typed: typed, tried: true }) }); return; } if (a) { this.setState({ err: err }); return; } stepUp('suspend'); } },
        fieldVals('sd', 'typed', typed, typedErr)),
      cf: { title: 'Resume ' + O.name + '?', explain: 'People can sign in again, and its background work continues.', confirm: 'Resume', cancel: 'Cancel', btnStyle: okStyle(false), hasDetail: false, detail: '', detailSub: '', hasAlert: !!a && dlg === 'resume', alert: a,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (a) { this.setState({ err: err }); return; } stepUp('resume'); } },
      rn: Object.assign({ on_rename: setForm('rename'), hasAlert: !!a && dlg === 'rename', alert: a,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!nm.trim()) { this.setState({ form: Object.assign({}, form, { rename: nm, tried: true }) }); return; } if (a) { this.setState({ err: err }); return; } this.setState({ dlg: '', toast: 'Name saved.' }); } },
        fieldVals('rn', 'rename', nm, (form.tried || err === 'required') && !nm.trim() ? 'Required.' : '')),
      iv: Object.assign({ on_name: setForm('name'), on_email: setForm('email'), hasAlert: !!a && !dlg, alert: a,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!iname.trim() || !iemail.trim()) { this.setState({ form: Object.assign({}, form, { name: iname, email: iemail, itried: true }) }); return; } if (a) { this.setState({ err: err }); return; } stepUp('invite'); } },
        fieldVals('iv', 'name', iname, itried && !iname.trim() ? 'Required.' : ''), fieldVals('iv', 'email', iemail, itried && !iemail.trim() ? 'Required.' : ''))
    });
"""
ONE_PROPS = '"org":{"editor":"enum","options":["noAdmin","active","suspended"],"default":"noAdmin"},"dialog":{"editor":"enum","options":["none","suspend","stepUp","resume","rename"],"default":"none"},"error":{"editor":"enum","options":["none","required","typedWrong","wrong","tooMany","hasAdmin","stale","failed"],"default":"none"},'
def tenant(phone):
    if phone:
        actions = f'<button type="button" class="ghost" aria-haspopup="dialog" onClick="{{{{openMore}}}}" aria-label="Organisation actions" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--fg); cursor: pointer">{svg(I["dots"], 20)}</button>'
    else:
        actions = (OB('Rename', 'openRename', phone) + f'<sc-if value="{{{{isActive}}}}" hint-placeholder-val="{{{{true}}}}">{OB("Suspend", "openSuspend", phone, "; color: var(--danger-fg)")}</sc-if>'
                   + f'<sc-if value="{{{{isSusp}}}}" hint-placeholder-val="{{{{false}}}}">{PB("Resume", "openResume", phone, None)}</sc-if>')
    cell = lambda k, v, first=False: f'<div style="min-width: 0; display: flex; flex-direction: column; gap: 4px; padding: 12px 16px; {"" if first else ("border-top: 1px solid var(--hover)" if phone else "border-left: 1px solid var(--border)")}"><dt style="font-size: 12px; font-weight: 500; color: var(--muted-fg)">{k}</dt><dd style="margin: 0">{v}</dd></div>'
    addr = f'<span style="display: flex; align-items: center; gap: 4px; margin: {"-12px 0" if phone else "-6px 0"}"><span style="min-width: 0; {MONO}; font-size: 13px; overflow-wrap: anywhere">{{{{o.host}}}}</span>{icon_btn("Copy the address", "copy", phone)}</span>'
    dl = (f'<dl style="margin: 0; display: {"flex; flex-direction: column" if phone else "grid; grid-template-columns: repeat(4, minmax(0, 1fr))"}; {CARD}; overflow: hidden">'
          + cell('Address', addr, True) + cell('Administrator', '<span style="{{o.adminStyle}}">{{o.admin}}</span>') + cell('Created', '{{o.created}}') + cell('Status changed', '{{o.changed}}') + '</dl>')
    susp = '<sc-if value="{{isSusp}}" hint-placeholder-val="{{false}}"><div role="status" style="padding: 10px 12px; border: 1px solid var(--warn-border); border-radius: 8px; background: var(--warn-bg); color: var(--warn-fg); font-size: 13px"><strong style="font-weight: 600">Suspended since {{o.changed}}.</strong> {{suspNote}}</div></sc-if>'
    g = f'display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 14px'
    invite = (f'<sc-if value="{{{{noAdmin}}}}" hint-placeholder-val="{{{{true}}}}"><form novalidate onSubmit="{{{{iv.submit}}}}" style="{CARD}; padding: {"16px" if phone else "20px"}; display: flex; flex-direction: column; gap: 14px; max-width: 760px">'
              '<div style="display: flex; flex-direction: column; gap: 4px"><h2 style="margin: 0; font-size: 15px; font-weight: 600">Invite the first administrator</h2><span style="font-size: 13px; color: var(--muted-fg)">Only possible while the organisation has no active administrator. A new invitation replaces the one sent before.</span></div>'
              + f'<div style="{g}">' + text_field('iv', 'name', 'First administrator’s name', 'text', phone) + text_field('iv', 'email', 'First administrator’s email', 'email', phone) + '</div>' + alert('iv')
              + f'<div style="display: flex; justify-content: flex-end"><button type="submit" style="{primary(phone)}{"; flex: 1" if phone else ""}">Send invitation</button></div></form></sc-if>')
    sees = ('<p style="margin: 0; font-size: 13px; color: var(--muted-fg)">You see the registry entry only. What happens inside the organisation is for its own administrators; '
            f'<a href="OpAudit{"Phone" if phone else ""}.dc.html" style="color: var(--fg)">platform actions are in the platform audit</a>.</p>')
    body = '\n'.join([BACK('Back to Organisations', 'OpTenants' + ('Phone' if phone else '') + '.dc.html', phone), title_row('{{o.name}}', '', phone, actions, '<span style="{{o.statusStyle}}">{{o.status}}</span>'), '    ' + susp, '    ' + dl, '    ' + invite, '    ' + sees])
    return topbar('Organisations', phone) + main(body, phone)
PHONE_SHEET = f'''
  <sc-if value="{{{{sheetOpen}}}}" hint-placeholder-val="{{{{false}}}}">
    <button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{closeMore}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 20; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <div role="dialog" aria-modal="true" aria-labelledby="ot-sheet" onKeyDown="{{{{moreKey}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 21; display: flex; flex-direction: column; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--card); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; margin-bottom: 8px; border-radius: 2px; background: var(--chip)"></span>
      <h2 id="ot-sheet" style="margin: 0 4px 6px; font-size: 16px; font-weight: 600">{{{{o.name}}}}</h2>
      <sc-for list="{{{{acts}}}}" as="a" hint-placeholder-count="2">
        <button type="button" class="ghost" onClick="{{{{a.pick}}}}" style="{{{{a.sheetStyle}}}}">{{{{a.label}}}}</button>
      </sc-for>
      <button type="button" onClick="{{{{closeMore}}}}" style="margin-top: 8px; height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Close</button>
    </div>
  </sc-if>
'''
def tenant_dialogs(phone):
    sd = frame('\n'.join([heading('sd-title', 'Suspend this organisation?', 'Nobody can sign in, everyone signed in is signed out, and its background work waits until you resume it. Its data stays as it is.'),
                          text_field('sd', 'typed', 'Type its address to confirm', 'text', phone).replace('outline: none;', 'outline: none; ' + MONO + ';', 1).replace('</label>', ' <span style="font-weight: 400; color: var(--muted-fg)">({{sd.slug}})</span></label>', 1),
                          alert('sd'), buttons('Suspend', 'height: ' + ('44px' if phone else '36px') + '; padding: 0 14px; border: 0; border-radius: 8px; background: var(--destructive); color: var(--on-destructive); font-size: 14px; font-weight: 500; cursor: pointer', phone)]),
               'sd-title', 'alertdialog', 'sd.submit', phone)
    rn = frame('\n'.join([heading('rn-title', 'Rename', 'The address stays the same.'), text_field('rn', 'rename', 'Name', 'text', phone), alert('rn'), buttons('Save', primary(phone), phone)]), 'rn-title', 'dialog', 'rn.submit', phone)
    return (stepup_dialog(phone) + f'''  <sc-if value="{{{{isSuspend}}}}" hint-placeholder-val="{{{{false}}}}">
    {sd}
  </sc-if>
  <sc-if value="{{{{isResume}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm_dialog(phone)}
  </sc-if>
  <sc-if value="{{{{isRename}}}}" hint-placeholder-val="{{{{false}}}}">
    {rn}
  </sc-if>
''' + (PHONE_SHEET if phone else '') + toast(phone))

# ================================================================ Platform audit
AUD_JS = r"""
    const E = (time, who, action, type, label, link, kind) => ({ time: time, who: who, action: action, type: type, label: label, link: link, kind: kind });
    const ORG = 'OpTenant' + (PHONE ? 'Phone' : '') + '.dc.html';
    const ALL = [E('Today, 10:02', 'Tahmid Chowdhury', 'tenant.first_admin.invited', 'organisation', 'Rupsha Cooperative', ORG, ''), E('Today, 09:58', 'Tahmid Chowdhury', 'platform.operator.signed_in', 'operator', 't.chowdhury@[platform]', '', ''),
      E('Today, 09:41', 'Not signed in', 'platform.operator.sign_in_failed', 'operator', 't.chowdhury@[platform]', '', 'anon'), E('Yesterday, 11:40', 'Tahmid Chowdhury', 'tenant.renamed', 'organisation', '[Organisation name]', ORG, ''),
      E('16 Nov, 09:12', 'Tahmid Chowdhury', 'platform.operator.mfa_enrolled', 'operator', 't.chowdhury@[platform]', '', ''), E('15 Nov, 16:20', 'Nadia Karim', 'tenant.created', 'organisation', 'Rupsha Cooperative', ORG, ''),
      E('12 Nov, 18:05', 'Nadia Karim', 'tenant.suspended', 'organisation', 'Surma Microfinance', ORG, ''), E('3 Aug, 10:12', 'Nadia Karim', 'tenant.created', 'organisation', 'Teesta Logistics', ORG, '')];
    const filtered = !!this.props.filtered || !!this.props.noMatch;
    const q = (st.q !== undefined ? st.q : (this.props.noMatch ? 'platform.job.' : (filtered ? 'tenant.' : ''))).toLowerCase();
    const rows = this.props.empty ? [] : ALL.filter((e) => !q || e.action.indexOf(q) === 0).map((e) => ({ time: e.time, who: e.who, action: e.action, type: e.type, label: e.label, href: e.link, isLink: !!e.link, isText: !e.link,
      whoStyle: e.kind ? 'color: var(--muted-fg); font-style: italic' : 'color: var(--fg)' }));
    const none = !!this.props.empty;
    const vals = Object.assign(base, { rows: rows, hasRows: rows.length > 0, noRows: rows.length === 0 && !none, noneYet: none, q: q, from: filtered && st.q === undefined ? '1 Nov 2026' : '',
      onQ: (ev) => this.setState({ q: ev && ev.target ? ev.target.value : '' }), clearFilters: () => this.setState({ q: '' }) });
"""
AUD_PROPS = '"filtered":{"editor":"boolean","default":false},"noMatch":{"editor":"boolean","default":false},"empty":{"editor":"boolean","default":false},'
def audit(phone):
    fld = lambda id_, l, v, w, ph='', extra='', mono=False: (f'<div style="display: flex; flex-direction: column; gap: 6px; {"" if phone else "width: " + w}"><label for="pa-{id_}" style="font-size: 13px; font-weight: 500">{l}</label>'
                                                         f'<input id="pa-{id_}" type="text" value="{v}" placeholder="{ph}"{extra} style="width: 100%; height: {"44px" if phone else "32px"}; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); {MONO + "; " if mono else "font-family: inherit; "}font-size: {FS(phone)}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)"></div>')
    filters = (f'<form role="search" novalidate style="display: {"grid; grid-template-columns: 1fr 1fr" if phone else "flex; flex-wrap: wrap; align-items: flex-end"}; gap: 10px">'
               + fld('from', 'From', '{{from}}', '140px') + fld('to', 'To', '', '140px', 'Today')
               + (f'<div style="grid-column: 1 / -1">' if phone else '') + fld('action', 'Action starts with', '{{q}}', '220px', 'for example tenant.', ' onChange="{{onQ}}"', True) + ('</div>' if phone else '')
               + f'<button type="button" class="ghost" onClick="{{{{clearFilters}}}}" style="height: {"44px" if phone else "32px"}; padding: 0 10px; border: 0; border-radius: 8px; background: transparent; color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer; {"grid-column: 1 / -1" if phone else ""}">Clear filters</button></form>')
    obj = ('<span style="color: var(--muted-fg)">{{r.type}} · </span><sc-if value="{{r.isLink}}" hint-placeholder-val="{{true}}"><a href="{{r.href}}" style="color: var(--fg)">{{r.label}}</a></sc-if>'
           '<sc-if value="{{r.isText}}" hint-placeholder-val="{{false}}"><span style="color: var(--text-2)">{{r.label}}</span></sc-if>')
    nomatch = empty_state('Nothing in this period.', 'Try a longer period, or clear the filters.', OB('Clear filters', 'clearFilters', phone), phone)
    none = empty_state('No entries yet.', 'Operator sign-ins and changes to organisations appear here as they happen.', '', phone)
    foot = 'Times are in Asia/Dhaka. Organisations also see the actions that concern them, in their own audit.'
    if phone:
        lst = ('<ul aria-label="Platform audit entries, newest first" style="list-style: none; margin: 0 -16px; padding: 0; border-top: 1px solid var(--hover)"><sc-for list="{{rows}}" as="r" hint-placeholder-count="8"><li style="display: flex; flex-direction: column; gap: 3px; padding: 10px 16px; border-bottom: 1px solid var(--hover)">'
               f'<span style="{MONO}; font-size: 12px; font-weight: 500">{{{{r.action}}}}</span><span style="font-size: 14px">{obj}</span><span style="font-size: 12px; color: var(--muted-fg)"><span style="{{{{r.whoStyle}}}}">{{{{r.who}}}}</span> · {{{{r.time}}}}</span></li></sc-for></ul>')
        listing = (f'<sc-if value="{{{{hasRows}}}}" hint-placeholder-val="{{{{true}}}}">{lst}<span style="font-size: 12px; color: var(--muted-fg); margin-top: 8px">{foot}</span>{SHOW_MORE(True)}</sc-if>'
                   f'<sc-if value="{{{{noRows}}}}" hint-placeholder-val="{{{{false}}}}">{nomatch}</sc-if><sc-if value="{{{{noneYet}}}}" hint-placeholder-val="{{{{false}}}}">{none}</sc-if>')
        body = '\n'.join([title_row('Platform audit', 'What operators and platform jobs did, newest first.', True), '    ' + filters, '    ' + listing])
        return topbar('Platform audit', True) + main(body, True)
    th = TH
    table = (f'<div style="{CARD}; overflow-x: auto"><table style="width: 100%; min-width: 820px; border-collapse: collapse; font-size: 14px"><caption style="{SR}">Platform audit entries, newest first</caption>'
             f'<thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 40px; {th.replace("0 12px", "0 16px", 1)}; width: 150px">Time</th><th scope="col" style="{th}; width: 200px">Who</th><th scope="col" style="{th}; width: 300px">Action</th><th scope="col" style="{th}">Object</th></tr></thead>'
             '<tbody><sc-for list="{{rows}}" as="r" hint-placeholder-count="8"><tr class="lrow" style="border-bottom: 1px solid var(--hover)"><td style="height: 44px; padding: 0 16px; color: var(--muted-fg); white-space: nowrap">{{r.time}}</td><td style="padding: 0 12px"><span style="{{r.whoStyle}}">{{r.who}}</span></td>'
             f'<td style="padding: 0 12px; {MONO}; font-size: 12px">{{{{r.action}}}}</td><td style="padding: 0 12px">{obj}</td></tr></sc-for></tbody></table></div>')
    listing = (f'<sc-if value="{{{{hasRows}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; flex-direction: column; gap: 8px">{table}<div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px"><span style="font-size: 13px; color: var(--muted-fg)">{foot}</span>{SHOW_MORE(False)}</div></div></sc-if>'
               f'<sc-if value="{{{{noRows}}}}" hint-placeholder-val="{{{{false}}}}">{nomatch}</sc-if><sc-if value="{{{{noneYet}}}}" hint-placeholder-val="{{{{false}}}}">{none}</sc-if>')
    body = '\n'.join([title_row('Platform audit', 'What operators and platform jobs did, newest first. Organisations also see actions that concern them.', False), '    ' + filters, '    ' + listing])
    return topbar('Platform audit', False) + main(body, False)

# ================================================================ assemble
JS_PRE = "    const st = this.state || {};\n    const PHONE = /*P*/;\n" + BASE_JS + '    ' + TONE_JS
def board(name, title, html_fn, js, props, dialogs=lambda p: toast(p), h=1000, auth=False):
    for phone in (False, True):
        pre = JS_PRE.replace('/*P*/', 'true' if phone else 'false')
        body = js + "    return Object.assign(vals, { themeClass: st.theme || this.props.theme || 'system' });\n"
        hd = head(title + (' on a phone' if phone else ''))
        root = auth_frame(phone) if auth else ROOT(phone) + '\n'
        (proj / f'{name}{"Phone" if phone else ""}.dc.html').write_text(hd + root + html_fn(phone) + dialogs(phone) + '</div>\n</x-dc>\n'
                                                                        + script(props, 390 if phone else 1440, 844 if phone else h, pre + body))

board('OpSignIn', 'Operator sign-in', lambda p: auth_page(signin_card(p), p), SIGNIN_JS, SIGNIN_PROPS, h=900, auth=True)
board('OpEnrol', 'Operator two-step setup', lambda p: auth_page(enrol_card(p), p), ENROL_JS, ENROL_PROPS, h=900, auth=True)
board('OpTenants', 'Organisations · Platform administration', tenants, TEN_JS, TEN_PROPS)
board('OpNewTenant', 'New organisation · Platform administration', new_tenant, NEW_JS, NEW_PROPS, lambda p: stepup_dialog(p) + toast(p))
board('OpTenant', 'Organisation · Platform administration', tenant, ONE_JS, ONE_PROPS, tenant_dialogs)
board('OpAudit', 'Platform audit', audit, AUD_JS, AUD_PROPS)

# canvas: the Operator page
c = proj / 'canvas.json'; d = json.loads(c.read_text())
y = 260
rows = [('OpSignIn', 'Operator sign-in (password, then code)', 900), ('OpEnrol', 'Operator two-step setup (from the invitation link)', 1000), ('OpTenants', 'Organisations (the console’s overview)', 1000),
        ('OpNewTenant', 'New organisation', 1000), ('OpTenant', 'Organisation detail', 1000), ('OpAudit', 'Platform audit', 1000)]
for n, t2, h in rows:
    short = t2.split(' (')[0]
    for k, v in ((n + '.dc.html', {'x': 0, 'y': y, 'w': 1440, 'h': h, 'title': short + ' — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'operator'}),
                 (n + 'Phone.dc.html', {'x': 1520, 'y': y, 'w': 390, 'h': 844, 'title': short + ' — Phone', 'is_interactive': True, 'page': 'operator'})):
        d['boards'].setdefault(k, v)
        if k not in d['order']: d['order'].append(k)
    key = 'op' + n + 'Title'
    d['notes'].setdefault(key, {'page': 'operator', 'x': 0, 'y': y - 260, 'text': t2, 'kind': 'title1', 'maxW': 1910})
    if 'noteOrder' in d and key not in d['noteOrder']: d['noteOrder'].append(key)
    y += h + 360
todo = d['notes'].get('operatorTodo')
if todo:
    todo.update({'x': 2030, 'y': 260, 'fill': 'blue', 'text':
        'Operator console: all six pages designed\n\n'
        '• A separate app for the people who run the platform. Its top bar stays dark in both themes and says Memo & Meetings · Platform administration, with its two pages (Organisations, Platform audit), the operator and Sign out. Pages are full width, like the admin pages.\n'
        '• Sign-in and two-step setup use the organisation sign-in frame (the platform pages share its AuthLayout): language and theme at the top, the product inside the card. A new operator arrives through a one-time link from the server: choose a password, then set up two-step sign-in. An expired link says what to do. Operators have no recovery codes, so the code screen says who can reset it.\n'
        '• Organisations: status tabs (All, Active, Suspended, No administrator) and search, like Users; the registry table; “N shown” and Show more. Operators see registry entries only, never what is inside an organisation.\n'
        '• New organisation is a page with “What happens” beside the form, and shows the web address as it is typed.\n'
        '• Suspending asks for the organisation’s address to be typed (new): it locks everyone out at once. Resume asks first. Suspended is a warning (amber), like a locked account.\n'
        '• Create, Suspend, Resume and Send invitation ask “Confirm it is you” with a code first, as the server requires (the `stepUp` dialog tweak on New organisation and Organisation detail).\n'
        '• Platform audit has the same filters and columns as the organisation’s Audit (From, To, Action starts with; Time, Who, Action, Object). Organisations in it link to their page.\n'
        '• API: the Administrator column and the Suspended and No administrator tabs need hasAdmin and a status filter on the list (today hasAdmin is only on the single view); audit entries need the organisation’s name. Everything else exists.'})
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok', y)
