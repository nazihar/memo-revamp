"""Admin · Organisation-wide settings: Security (sign-in policy), Email, Branding, Language and terms (desktop and phone).

Copy from web/src/features/admin (admin-messages.ts, SecuritySettingsPage, BrandingPage, LanguagePage),
features/notifications (MailSettingsPage, notification-messages.ts), lib/contrast.ts (BRAND_CHECKS) and the backend's
SecurityLimit ranges. Builds OrgSecurity, OrgEmail, OrgBranding, OrgLanguage and their phone boards; safe to rerun.
"""
from wf_parts import with_ro, SMALL, H, FS, obtn, icon_btn, JS_HEAD
from kit import *

I.update({'upload': '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" x2="12" y1="3" y2="15"></line>',
          'mail': '<rect width="20" height="16" x="2" y="4" rx="2"></rect><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"></path>',
          'server': '<rect width="20" height="8" x="2" y="2" rx="2"></rect><rect width="20" height="8" x="2" y="14" rx="2"></rect><line x1="6" x2="6.01" y1="6" y2="6"></line><line x1="6" x2="6.01" y1="18" y2="18"></line>'})
SET = [('Security', 'OrgSecurity'), ('Email', 'OrgEmail'), ('Branding', 'OrgBranding'), ('Language and terms', 'OrgLanguage')]
def nav(active, phone): return section_nav(active, phone, SET, 'Organisation-wide settings')

def num_field(pfx, k, label, rng, phone, unit=''):
    """A number field with its allowed range as the hint; form validation, never the browser's."""
    w = '120px' if phone else '110px'
    return (f'<div style="display: flex; flex-direction: column; gap: 6px"><label for="{pfx}-{k}" style="font-size: 14px; font-weight: 500">{label}</label>'
            f'<span style="display: flex; align-items: center; gap: 8px"><input id="{pfx}-{k}" type="text" inputmode="numeric" value="{{{{{pfx}.{k}}}}}" onChange="{{{{{pfx}.on_{k}}}}}" aria-invalid="{{{{{pfx}.{k}Invalid}}}}" aria-describedby="{pfx}-{k}-msg {pfx}-{k}-hint" '
            f'style="width: {w}; height: {H(phone)}; padding: 0 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}; font-variant-numeric: tabular-nums; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); border: 1px solid {{{{{pfx}.{k}Border}}}}">'
            f'<span id="{pfx}-{k}-hint" style="font-size: 12px; color: var(--muted-fg)">{rng}</span></span>'
            f'<sc-if value="{{{{{pfx}.{k}HasErr}}}}" hint-placeholder-val="{{{{false}}}}"><span id="{pfx}-{k}-msg" style="font-size: 12px; color: var(--danger-fg)">{{{{{pfx}.{k}Err}}}}</span></sc-if></div>')

def section(title, lead, body, phone):
    return (f'<section style="{CARD}; padding: 16px; display: flex; flex-direction: column; gap: 16px">'
            f'<div style="display: flex; flex-direction: column; gap: 4px"><h2 style="margin: 0; font-size: 15px; font-weight: 600">{title}</h2>' + (f'<span style="font-size: 13px; color: var(--muted-fg)">{lead}</span>' if lead else '') + f'</div>{body}</section>')

def save_bar(phone, note, label='Save', click='save'):
    pos = ('flex-shrink: 0; padding: 12px 16px 16px; border-top: 1px solid var(--border); background: var(--card)' if phone else
           'position: sticky; bottom: 0; z-index: 3; padding: 12px 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 -4px 16px rgba(0,0,0,0.06)')
    row = 'display: flex; flex-direction: column; gap: 8px' if phone else 'display: flex; align-items: center; gap: 12px'
    return (f'<div style="{pos}; display: flex; flex-direction: column; gap: 10px">{alert("bar")}<div style="{row}">'
            f'<span style="flex: 1; font-size: 13px; color: {{{{barNoteColor}}}}">{{{{barNote}}}}</span><button type="button" onClick="{{{{{click}}}}}" style="{primary(phone)}">{label}</button></div></div>')

def head(title, lead, phone):
    if phone:
        return f'    <div style="display: flex; flex-direction: column; gap: 2px; padding: 12px 16px 8px"><h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{title}</h1><span style="font-size: 13px; color: var(--muted-fg)">{lead}</span></div>'
    return f'      <div style="display: flex; flex-direction: column; gap: 2px; max-width: 820px"><h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">{title}</h1><p style="margin: 0; font-size: 14px; color: var(--muted-fg)">{lead}</p></div>'

def page(active, title, lead, phone_lead, body_desk, body_phone, bar_desk='', bar_phone=''):
    desk = (f'    <main style="flex: 1; padding: 20px 24px 24px; display: flex; flex-direction: column; gap: 16px; min-width: 0">\n      {nav(active, False)}\n{head(title, lead, False)}\n'
            f'      {body_desk}\n      {bar_desk}\n    </main>\n  </div>\n')
    phone = (f'  <main class="ed-main" style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">\n    {nav(active, True)}\n{head(title, phone_lead, True)}\n'
             f'    <div style="display: flex; flex-direction: column; gap: 14px; padding: 4px 16px 24px">{body_phone}</div>\n  </main>\n  {bar_phone}\n')
    return desk, phone

BAR_JS = r"""
    const dirty = !!st.dirty || this.props.unsaved === true || err !== 'none';
    const BAR = { stale: 'Someone changed these settings meanwhile. The page now shows their version.', failed: 'That did not work. Try again.', invalid: 'Check the highlighted fields.' };
    const barAlert = BAR[err] || '';
    const barVals = { bar: { hasAlert: !!barAlert, alert: barAlert }, barNote: dirty ? 'Unsaved changes. ' + SAVE_NOTE : SAVE_NOTE, barNoteColor: dirty ? 'var(--warn-fg)' : 'var(--muted-fg)',
      save: () => { if (barAlert && err !== 'stale') { this.setState({ err: err }); return; } this.setState({ dirty: false, err: 'none', toast: 'Saved.' }); } };
"""

# ================================================================ Security
SEC_JS = r"""
    const SAVE_NOTE = 'Changes apply to the next sign-in. Saving asks you to confirm it is you.';
""" + BAR_JS + r"""
    const V = Object.assign({ idleMinutes: '30', absoluteHours: '12', stepUpMinutes: '5', passwordMinLength: '14', throttleAfter: '5', lockAfter: '20', lockMinutes: '30' },
      err === 'invalid' ? { idleMinutes: '600', lockAfter: '4' } : {}, form);
    const R = { idleMinutes: [5, 480], absoluteHours: [1, 24], stepUpMinutes: [1, 30], passwordMinLength: [12, 128], throttleAfter: [3, 10], lockAfter: [5, 50], lockMinutes: [5, 1440] };
    const msg = (k) => { const v = String(V[k]).trim(); if (!v) return 'Required.'; const n = Number(v); if (!/^\d+$/.test(v) || n < R[k][0] || n > R[k][1]) return 'Between ' + R[k][0] + ' and ' + R[k][1] + '.';
      if (k === 'lockAfter' && n <= Number(V.throttleAfter)) return 'Lock after more failures than the waiting starts.'; return ''; };
    const show = err === 'invalid' || !!st.tried;
    const sc = { on: (k) => (ev) => { const n = Object.assign({}, form); n[k] = ev && ev.target ? ev.target.value : ''; this.setState({ form: n, dirty: true }); } };
    Object.keys(R).forEach((k) => { Object.assign(sc, fieldVals('sc', k, V[k], show ? msg(k) : '')); sc['on_' + k] = sc.on(k); });
    const mfaAll = (st.mfa || 'all') === 'all';
    const hrs = (m) => { const n = Number(m); return n % 60 === 0 && n >= 60 ? (n / 60) + (n === 60 ? ' hour' : ' hours') : n + ' minutes'; };
    const vals = Object.assign(base, barVals, {
      sc: sc, mfaAll: mfaAll, mfaAdmins: !mfaAll, pickAll: () => this.setState({ mfa: 'all', dirty: true }), pickAdmins: () => this.setState({ mfa: 'admins', dirty: true }),
      plain: [
        mfaAll ? 'Everyone sets up an authenticator app at their first sign-in.' : 'Only administrators must use an authenticator app; others may.',
        'People are signed out after ' + hrs(V.idleMinutes) + ' without activity, and after ' + V.absoluteHours + ' hours at most.',
        'Confirming it is you lasts ' + V.stepUpMinutes + ' minutes; approving memos has its own, longer confirmation.',
        'Passwords have at least ' + V.passwordMinLength + ' characters.',
        'After ' + V.throttleAfter + ' wrong passwords, each new try has to wait; after ' + V.lockAfter + ' in a row the account locks for ' + hrs(V.lockMinutes) + '.'
      ].map((t) => ({ t: t })),
      save: () => { if (Object.keys(R).some((k) => msg(k))) { this.setState({ tried: true }); return; } barVals.save(); }
    });
"""
SEC_PROPS = '"unsaved":{"editor":"boolean","default":false},"error":{"editor":"enum","options":["none","invalid","stale","failed"],"default":"none"},'
def sec_body(phone):
    s = 18 if phone else 16
    radio = lambda k, l, c: f'<label style="display: flex; align-items: flex-start; gap: 10px; font-size: 14px; cursor: pointer{"; min-height: 40px" if phone else ""}"><input type="radio" name="sc-mfa" checked="{{{{{k}}}}}" onChange="{{{{{c}}}}}" style="margin: 2px 0 0; width: {s}px; height: {s}px; accent-color: var(--primary)">{l}</label>'
    mfa = section('Two-step sign-in', '', f'<fieldset style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 10px"><legend style="margin-bottom: 8px; padding: 0; font-size: 14px; font-weight: 500">Who must use two-step sign-in</legend>'
                  + radio('mfaAll', 'Everyone', 'pickAll') + radio('mfaAdmins', 'Administrators only (administrators always need it)', 'pickAdmins') + '</fieldset>', phone)
    grid = f'display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 16px'
    sess = section('Sessions', '', f'<div style="{grid}">' + num_field('sc', 'idleMinutes', 'Sign out after inactivity (minutes)', '5–480', phone) + num_field('sc', 'absoluteHours', 'Longest session (hours)', '1–24', phone)
                   + num_field('sc', 'stepUpMinutes', 'A confirmation lasts (minutes)', '1–30', phone) + '</div>', phone)
    pw = section('Passwords and lockout', '', f'<div style="{grid}">' + num_field('sc', 'passwordMinLength', 'Shortest password (characters)', '12–128', phone)
                 + num_field('sc', 'throttleAfter', 'Wrong passwords before each attempt has to wait', '3–10', phone) + num_field('sc', 'lockAfter', 'Wrong passwords in a row that lock the account', '5–50', phone)
                 + num_field('sc', 'lockMinutes', 'Lock lasts (minutes)', '5–1440', phone) + '</div>', phone)
    plain = (f'<aside aria-labelledby="pl-title" style="border: 1px solid var(--border); border-radius: 10px; background: var(--subtle); padding: 16px; display: flex; flex-direction: column; gap: 10px{"" if phone else "; position: sticky; top: 16px"}">'
             '<h2 id="pl-title" style="margin: 0; font-size: 14px; font-weight: 600">What people will notice</h2><ul style="margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-2)">'
             '<sc-for list="{{plain}}" as="p" hint-placeholder-count="5"><li>{{p.t}}</li></sc-for></ul></aside>')
    if phone:
        return mfa + sess + pw + plain
    return f'<div style="display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 16px; align-items: start"><div style="display: flex; flex-direction: column; gap: 16px; min-width: 0">{mfa}{sess}{pw}</div>{plain}</div>'
SEC = page('Security', 'Security', 'Changes apply to the next sign-in. Saving asks you to confirm it is you.', 'Sign-in rules for everyone in the organisation.', sec_body(False), sec_body(True), save_bar(False, ''), save_bar(True, ''))

# ================================================================ Email
MAIL_JS = r"""
    const own = (st.mode || this.props.mode || 'own') === 'own';
    const L = (to, tpl, status, att, when) => ({ to: to, tpl: tpl, status: status, att: att, when: when });
    const LOG = [L('n.jahan@[domain]', 'Memo waiting for approval', 'SENT', 1, 'Today, 09:12'), L('l.begum@[domain]', 'Invitation', 'FAILED', 5, 'Today, 08:40'), L('f.rahman@[domain]', 'Memo returned', 'SENT', 1, 'Today, 08:31'),
      L('t.ahmed@[domain]', 'You were mentioned on a memo', 'PENDING', 2, 'Today, 08:29'), L('r.khan@[domain]', 'Memo approved', 'SENT', 1, 'Yesterday, 18:02'), L('a.rahman@[domain]', 'Test email', 'SENT', 1, 'Yesterday, 17:55'),
      L('s.akter@[domain]', 'New sign-in', 'SENT', 1, 'Yesterday, 11:40'), L('k.hasan@[domain]', 'Password reset', 'SENT', 1, '15 Nov, 17:50')];
    const SL = { SENT: ['Sent', 'ok'], PENDING: ['Waiting', 'neutral'], FAILED: ['Failed', 'danger'] };
    const filter = st.filter || 'all';
    const tabs = mkTabs([['all', 'All'], ['failed', 'Failed', LOG.filter((l) => l.status === 'FAILED').length]], filter, 'filter');
    const log = LOG.filter((l) => filter === 'all' || l.status === 'FAILED').map((l) => ({ to: l.to, tpl: l.tpl, status: SL[l.status][0], statusStyle: TONE[SL[l.status][1]], att: String(l.att), when: l.when,
      meta: l.tpl + ' · ' + l.when + (l.att > 1 ? ' · ' + l.att + ' attempts' : '') }));
    const f = (k, d) => form[k] !== undefined ? form[k] : (err === 'required' ? (k === 'host' || k === 'from' ? '' : d) : d);
    const host = f('host', 'smtp.[domain]'), port = f('port', '587'), from = f('from', 'memo@[domain]');
    const tried = !!form.tried || err === 'required';
    const mdAlert = ({ invalid: 'Check the mail server settings: the server refused the user name and password.', failed: 'That did not work. Try again.' })[err] || '';
    const testFailed = err === 'testFailed';
    const vals = Object.assign(base, {
      own: own, platform: !own, tabs: tabs, log: log, hasTest: testFailed, testText: 'The mail server did not accept the test email (535 Authentication failed).',
      test: () => { if (testFailed) { this.setState({ err: err }); return; } this.setState({ toast: 'Test email sent. Check your inbox.' }); },
      usePlatform: () => this.setState({ dlg: 'platform' }), openServer: () => this.setState({ dlg: 'server', form: {}, err: this.props.error === 'testFailed' ? 'none' : (this.props.error || 'none') }),
      isServer: dlg === 'server', isConfirm: dlg === 'platform',
      md: Object.assign({ title: own ? 'Change the mail server' : 'Use your own mail server', on_host: setForm('host'), on_port: setForm('port'), on_from: setForm('from'), on_fromName: setForm('fromName'), on_user: setForm('user'), on_password: setForm('password'),
        fromName: 'Memo & Meetings', user: 'memo@[domain]', password: '', passwordHint: own ? 'A password is stored. Leave empty to keep it.' : '', hasAlert: !!mdAlert, alert: mdAlert,
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); if (!host.trim() || !port.trim() || !from.trim()) { this.setState({ form: Object.assign({}, form, { host: host, port: port, from: from, tried: true }) }); return; }
          if (mdAlert) { this.setState({ err: err }); return; } this.setState({ dlg: '', form: null, mode: 'own', toast: 'Mail server saved.' }); } },
        fieldVals('md', 'host', host, tried && !host.trim() ? 'Required.' : ''), fieldVals('md', 'port', port, tried && !port.trim() ? 'Required.' : ''), fieldVals('md', 'from', from, tried && !from.trim() ? 'Required.' : '')),
      cf: { title: 'Use the platform’s mail server?', explain: 'Your organisation’s email goes through the platform’s server again. Your server’s settings are removed.', confirm: "Use the platform's server", cancel: 'Keep mine', btnStyle: okStyle(false),
        hasDetail: false, detail: '', detailSub: '', hasAlert: false, alert: '', submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); this.setState({ dlg: '', mode: 'platform', toast: "Email goes through the platform's server again." }); } }
    });
"""
MAIL_PROPS = '"mode":{"editor":"enum","options":["own","platform"],"default":"own"},"dialog":{"editor":"enum","options":["none","server","platform"],"default":"none"},"error":{"editor":"enum","options":["none","required","invalid","testFailed","failed"],"default":"none"},'
def mail_body(phone):
    from wf_parts import tabs_markup
    btn = lambda l, c, extra='': f'<button type="button" class="ghost" onClick="{{{{{c}}}}}" style="{OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}{extra}">{l}</button>'
    row = lambda k, v: f'<div style="display: flex; flex-direction: column; gap: 2px"><dt style="font-size: 12px; font-weight: 500; color: var(--muted-fg)">{k}</dt><dd style="margin: 0; font-size: 14px">{v}</dd></div>'
    dl = f'<dl style="margin: 0; display: grid; grid-template-columns: {"1fr 1fr" if phone else "repeat(4, minmax(0, 1fr))"}; gap: 12px 16px">' + row('Mail server', f'<span style="{MONO}; font-size: 13px">smtp.[domain]:587</span>') + row('Encryption', 'STARTTLS') + row('Sender', 'Memo &amp; Meetings &lt;memo@[domain]&gt;') + row('User name', f'<span style="{MONO}; font-size: 13px">memo@[domain]</span>') + '</dl>'
    status = (f'<section style="{CARD}; padding: 16px; display: flex; flex-direction: column; gap: 14px">'
              f'<div style="display: flex; align-items: flex-start; gap: 12px"><span aria-hidden="true" style="flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; border-radius: 8px; background: var(--muted); color: var(--text-2)">{svg(I["server"], 18)}</span>'
              '<span style="flex: 1; display: flex; flex-direction: column; gap: 2px"><sc-if value="{{own}}" hint-placeholder-val="{{true}}"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px"><strong style="font-size: 15px; font-weight: 600">Your own mail server</strong>' + chip('In use', 'ok') + '</span><span style="font-size: 13px; color: var(--muted-fg)">Email from the app comes from your domain.</span></sc-if>'
              "<sc-if value=\"{{platform}}\" hint-placeholder-val=\"{{false}}\"><strong style=\"font-size: 15px; font-weight: 600\">The platform's mail server</strong><span style=\"font-size: 13px; color: var(--muted-fg)\">Email goes through the platform's mail server.</span></sc-if></span></div>"
              f'<sc-if value="{{{{own}}}}" hint-placeholder-val="{{{{true}}}}">{dl}</sc-if>'
              '<sc-if value="{{hasTest}}" hint-placeholder-val="{{false}}"><div role="alert" style="display: flex; gap: 8px; padding: 10px 12px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px">' + svg(I['alert'], 16, ' style="flex-shrink: 0"') + '<span>{{testText}}</span></div></sc-if>'
              f'<div style="display: flex; flex-wrap: wrap; gap: 8px"><sc-if value="{{{{own}}}}" hint-placeholder-val="{{{{true}}}}">{btn("Change", "openServer")}{btn("Send a test email to me", "test")}{btn("Use the platform&#39;s server", "usePlatform", "; color: var(--text-2)")}</sc-if>'
              f'<sc-if value="{{{{platform}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{openServer}}}}" style="{primary(phone)}">Use your own mail server</button>{btn("Send a test email to me", "test")}</sc-if></div></section>')
    if phone:
        logl = ('<ul aria-label="Delivery of recent emails, newest first" style="list-style: none; margin: 0; padding: 0; ' + CARD + '; overflow: hidden"><sc-for list="{{log}}" as="l" hint-placeholder-count="8"><li style="display: flex; flex-direction: column; gap: 3px; padding: 10px 12px; border-top: 1px solid var(--hover)">'
                '<span style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><span style="font-weight: 500; ' + MONO + '; font-size: 13px; overflow: hidden; text-overflow: ellipsis">{{l.to}}</span><span style="{{l.statusStyle}}">{{l.status}}</span></span><span style="font-size: 12px; color: var(--muted-fg)">{{l.meta}}</span></li></sc-for></ul>')
    else:
        th = TH
        logl = (f'<div style="{CARD}; overflow-x: auto"><table style="width: 100%; border-collapse: collapse; font-size: 14px"><caption style="{SR}">Delivery of recent emails, newest first</caption>'
                f'<thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 40px; {th.replace("0 12px", "0 16px", 1)}">To</th><th scope="col" style="{th}">Email</th><th scope="col" style="{th}; width: 110px">Status</th><th scope="col" style="{th}; width: 90px">Attempts</th><th scope="col" style="{th}; width: 150px">Queued</th></tr></thead>'
                '<tbody><sc-for list="{{log}}" as="l" hint-placeholder-count="8"><tr class="lrow" style="border-bottom: 1px solid var(--hover)"><td style="height: 44px; padding: 0 16px; ' + MONO + '; font-size: 13px">{{l.to}}</td><td style="padding: 0 12px">{{l.tpl}}</td><td style="padding: 0 12px"><span style="{{l.statusStyle}}">{{l.status}}</span></td><td style="padding: 0 12px; font-variant-numeric: tabular-nums; color: var(--text-2)">{{l.att}}</td><td style="padding: 0 12px; color: var(--muted-fg); white-space: nowrap">{{l.when}}</td></tr></sc-for></tbody></table></div>')
    log = (f'<section aria-labelledby="ml-title" style="display: flex; flex-direction: column; gap: 10px"><div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px"><h2 id="ml-title" style="margin: 0; font-size: 15px; font-weight: 600">Recent emails</h2>{tabs_markup(phone)}</div>{logl}'
           f'<div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px"><span style="font-size: 13px; color: var(--muted-fg)">Failed emails are tried again up to five times.</span><button type="button" class="ghost" style="{OUTLINE}">Show more</button></div></section>')
    return status + log
def mail_dialogs(phone):
    sel = (f'<div style="display: flex; flex-direction: column; gap: 6px"><label for="md-tls" style="font-size: 14px; font-weight: 500">Encryption <span aria-hidden="true" style="color: var(--danger-fg)">*</span></label><span style="position: relative; display: flex; align-items: center">'
           f'<select id="md-tls" style="width: 100%; height: {H(phone)}; padding: 0 30px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}"><option selected>STARTTLS (usually port 587)</option><option>TLS (usually port 465)</option><option>None (test installations only)</option></select>'
           + svg(I['chev'], 14, ' style="position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none"') + '</span></div>')
    g = f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 120px"}; gap: 12px">'
    g2 = f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 12px">'
    pw = opt_field('md', 'password', 'Password (optional)', phone, required=False, hint='{{md.passwordHint}}').replace('type="text"', 'type="password"', 1)
    body = '\n'.join([g + text_field('md', 'host', 'Mail server', 'text', phone) + text_field('md', 'port', 'Port', 'text', phone).replace('type="text"', 'type="text" inputmode="numeric"', 1) + '</div>', sel,
                      g2 + text_field('md', 'from', 'Sender address', 'email', phone) + opt_field('md', 'fromName', 'Sender name (optional)', phone, required=False) + '</div>',
                      g2 + opt_field('md', 'user', 'User name (optional)', phone, required=False) + pw + '</div>', alert('md')])
    md = frame_scroll('<div style="display: flex; flex-direction: column; gap: 6px"><h2 id="md-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{{md.title}}</h2><p style="margin: 0; font-size: 14px; color: var(--muted-fg)">The server checks the settings when you save. Saving asks you to confirm it is you.</p></div>',
                      body, buttons('Save', primary(phone), phone), 'md-title', 'dialog', 'md.submit', phone, width=560)
    return f'''
  <sc-if value="{{{{isServer}}}}" hint-placeholder-val="{{{{false}}}}">
    {md}
  </sc-if>
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm_dialog(phone)}
  </sc-if>
''' + toast(phone)
MAIL = page('Email', 'Email delivery', "Send this organisation's email through its own mail server. Without one, the platform's server is used.", 'Your mail server and the delivery log.', mail_body(False), mail_body(True))

# ================================================================ Branding
BRAND_JS = r"""
    const SAVE_NOTE = 'The logo and colours apply to everyone at once.';
""" + BAR_JS + r"""
    const low = err === 'contrast';
    const light = low ? '#5b9bd5' : '#0f6b4f', dark = '#7fd1b0';
    const CH = (ok, ratio, min, against) => ({ ok: ok, bad: !ok, label: ok ? 'Readable' : 'Too little contrast', style: TONE[ok ? 'ok' : 'danger'], text: ratio + ':1 (at least ' + min + ':1)', against: against });
    const lightChecks = low ? [CH(false, '2.96', '4.5', 'On the page'), CH(false, '2.76', '3', 'On panels')] : [CH(true, '6.49', '4.5', 'On the page'), CH(true, '6.05', '3', 'On panels')];
    const darkChecks = [CH(true, '9.98', '4.5', 'On the page'), CH(true, '10.16', '3', 'On panels')];
    const logoErr = err === 'logoSize' ? 'The file is larger than 512 KB.' : '';
    const vals = Object.assign(base, barVals, {
      light: light, dark: dark, lightChecks: lightChecks, darkChecks: darkChecks, lightBorder: low ? 'var(--destructive)' : 'var(--border)', lightInvalid: low ? 'true' : 'false',
      hasLogoErr: !!logoErr, logoErr: logoErr, hasLogo: !st.noLogo, noLogo: !!st.noLogo, removeLogo: () => this.setState({ noLogo: true, dirty: true }),
      swLight: 'width: 36px; height: 36px; border-radius: 8px; border: 1px solid var(--border); flex-shrink: 0; background: ' + light, swDark: 'width: 36px; height: 36px; border-radius: 8px; border: 1px solid var(--border); flex-shrink: 0; background: ' + dark,
      markLight: 'display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 7px; color: #ffffff; font-weight: 700; font-size: 13px; background: ' + light,
      markDark: 'display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 7px; color: #0d1726; font-weight: 700; font-size: 13px; background: ' + dark,
      btnLight: 'height: 34px; padding: 0 14px; border: 0; border-radius: 8px; color: #ffffff; font-size: 13px; font-weight: 500; background: ' + light,
      btnDark: 'height: 34px; padding: 0 14px; border: 0; border-radius: 8px; color: #0d1726; font-size: 13px; font-weight: 500; background: ' + dark,
      linkLight: 'font-size: 13px; font-weight: 500; color: ' + light, linkDark: 'font-size: 13px; font-weight: 500; color: ' + dark,
      save: () => { if (low) { this.setState({ err: 'contrast' }); return; } barVals.save(); }
    });
    vals.barNote = low ? 'A colour is too hard to read. Choose a darker light-theme colour.' : vals.barNote;
    vals.barNoteColor = low ? 'var(--danger-fg)' : vals.barNoteColor;
"""
BRAND_PROPS = '"unsaved":{"editor":"boolean","default":false},"error":{"editor":"enum","options":["none","contrast","logoSize","stale","failed"],"default":"none"},'
def brand_body(phone):
    logo_box = lambda bg, fg, mark: (f'<div style="flex: 1; min-width: {"100%" if phone else "150px"}; display: flex; align-items: center; justify-content: center; height: 72px; border-radius: 8px; border: 1px solid var(--border); background: {bg}">'
                                     f'<span style="display: inline-flex; align-items: center; gap: 8px; color: {fg}"><span style="{{{{{mark}}}}}">N</span><span style="font-weight: 700; font-size: 15px; letter-spacing: -0.01em">[Organisation logo]</span></span></div>')
    ob = OUTLINE.replace('height: 32px', 'height: ' + ('40px' if phone else '32px'))
    logo = section('Logo', 'Your logo appears on the sign-in page and in the top bar.',
                   '<sc-if value="{{hasLogo}}" hint-placeholder-val="{{true}}"><div style="display: flex; flex-wrap: wrap; gap: 10px">' + logo_box('#ffffff', '#0a0a0a', 'markLight') + logo_box('#0d1726', '#fafafa', 'markDark') + '</div></sc-if>'
                   '<sc-if value="{{noLogo}}" hint-placeholder-val="{{false}}"><div style="padding: 20px; border: 1px dashed var(--border); border-radius: 8px; text-align: center; font-size: 13px; color: var(--muted-fg)">No logo yet.</div></sc-if>'
                   f'<div style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px"><button type="button" class="ghost" style="{ob}">{svg(I["upload"], 14)}Upload a logo</button><sc-if value="{{{{hasLogo}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" onClick="{{{{removeLogo}}}}" style="{ob.replace("color: var(--fg)", "color: var(--danger-fg)")}">Remove logo</button></sc-if></div>'
                   '<span style="font-size: 12px; color: var(--muted-fg)">PNG, JPEG or WebP, up to 512 KB. Shown at most 160 × 40.</span>'
                   '<sc-if value="{{hasLogoErr}}" hint-placeholder-val="{{false}}"><span role="alert" style="font-size: 12px; color: var(--danger-fg)">{{logoErr}}</span></sc-if>', phone)
    def colour(k, label, value, sw, checks, border, invalid):
        return (f'<div style="display: flex; flex-direction: column; gap: 8px"><label for="br-{k}" style="font-size: 14px; font-weight: 500">{label}</label>'
                f'<span style="display: flex; align-items: center; gap: 8px"><span aria-hidden="true" style="{{{{{sw}}}}}"></span><input id="br-{k}" type="text" value="{{{{{value}}}}}" aria-invalid="{{{{{invalid}}}}}" style="width: 140px; height: {H(phone)}; padding: 0 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); {MONO}; font-size: {FS(phone)}; outline: none; border: 1px solid {{{{{border}}}}}"></span>'
                '<span style="font-size: 12px; color: var(--muted-fg)">As #rrggbb. Leave empty for the default.</span>'
                f'<ul style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px"><sc-for list="{{{{{checks}}}}}" as="c" hint-placeholder-count="2"><li style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 13px"><span style="width: 84px; color: var(--muted-fg)">{{{{c.against}}}}</span><span style="{{{{c.style}}}}">{{{{c.label}}}}</span><span style="color: var(--text-2); font-variant-numeric: tabular-nums">{{{{c.text}}}}</span></li></sc-for></ul></div>')
    colours = section('Colours', 'Colours must stay readable for everyone. They mark links, the logo tile and the sign-in button.',
                      f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 20px">' + colour('light', 'Colour, light theme', 'light', 'swLight', 'lightChecks', 'lightBorder', 'lightInvalid')
                      + colour('dark', 'Colour, dark theme', 'dark', 'swDark', 'darkChecks', 'border', 'false').replace('{{border}}', 'var(--border)').replace('{{false}}', 'false') + '</div>', phone)
    def preview(bg, fg, mark, btn, link, sub, border):
        return (f'<div style="flex: 1; min-width: 220px; display: flex; flex-direction: column; border-radius: 10px; overflow: hidden; border: 1px solid var(--border); background: {bg}; color: {fg}">'
                f'<div style="display: flex; align-items: center; gap: 8px; height: 44px; padding: 0 12px; border-bottom: 1px solid {border}"><span style="{{{{{mark}}}}}">N</span><span style="font-weight: 600; font-size: 13px">[Organisation name]</span></div>'
                f'<div style="display: flex; flex-direction: column; gap: 10px; padding: 16px"><span style="font-weight: 600">Sign in</span><span style="height: 32px; border-radius: 6px; border: 1px solid {border}"></span><span style="height: 32px; border-radius: 6px; border: 1px solid {border}"></span>'
                f'<span style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><span style="{{{{{link}}}}}">Forgot your password?</span><button type="button" tabIndex="-1" style="{{{{{btn}}}}}">Sign in</button></span><span style="font-size: 11px; color: {sub}">Preview</span></div></div>')
    prev = section('Preview', '', '<div style="display: flex; flex-wrap: wrap; gap: 12px">' + preview('#ffffff', '#0a0a0a', 'markLight', 'btnLight', 'linkLight', '#737373', '#e5e5e5') + preview('#0d1726', '#fafafa', 'markDark', 'btnDark', 'linkDark', '#a1a1aa', '#27324a') + '</div>', phone)
    if phone:
        return logo + colours + prev
    return f'<div style="display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; align-items: start"><div style="display: flex; flex-direction: column; gap: 16px; min-width: 0">{logo}{colours}</div>{prev}</div>'
BRAND = page('Branding', 'Branding', 'Your logo appears on the sign-in page and in the top bar. Colours must stay readable for everyone.', 'Logo and colours.', brand_body(False), brand_body(True), save_bar(False, ''), save_bar(True, ''))

# ================================================================ Language and terms
LANG_JS = r"""
    const SAVE_NOTE = 'New people get these defaults; everyone can change their own language.';
""" + BAR_JS + r"""
    const lang = st.lang || this.props.terms || 'en';
    const tabs = mkTabs([['en', 'English'], ['ar', 'العربية (Arabic)']], lang, 'lang');
    const TERMS = { en: [['Unit (department, branch)', 'department', 'departments', ''], ['Position', 'designation', 'designations', ''], ['Memo', '', '', '']],
                    ar: [['Unit (department, branch)', 'إدارة', 'إدارات', 'Feminine'], ['Position', 'وظيفة', 'وظائف', 'Feminine'], ['Memo', 'مذكرة', 'مذكرات', 'Feminine']] };
    const isAr = lang === 'ar';
    const STD = { en: [['unit', 'units'], ['position', 'positions'], ['memo', 'memos']], ar: [['وحدة', 'وحدات'], ['منصب', 'مناصب'], ['مذكرة', 'مذكرات']] };
    const terms = TERMS[lang].map((t, i) => ({ term: t[0], one: t[1], other: t[2], phOne: STD[lang][i][0], phOther: STD[lang][i][1], masc: t[3] === 'Masculine', fem: t[3] === 'Feminine',
      oneLabel: t[0] + ': One', otherLabel: t[0] + ': Several', genderLabel: t[0] + ': Grammatical gender' }));
    const vals = Object.assign(base, barVals, {
      tabs: tabs, terms: terms, isAr: isAr, dir: isAr ? 'rtl' : 'ltr', example: isAr ? '' : 'Nusrat Jahan moved to the Credit Risk department.', hasExample: !isAr,
      saveTerms: () => this.setState({ toast: 'Saved.' }), save: () => this.setState({ toast: 'Saved.' })
    });
"""
LANG_PROPS = '"terms":{"editor":"enum","options":["en","ar"],"default":"en"},"unsaved":{"editor":"boolean","default":false},"error":{"editor":"enum","options":["none","stale","failed"],"default":"none"},'
def lang_body(phone):
    from wf_parts import tabs_markup
    sel = lambda id_, l, opts: (f'<div style="display: flex; flex-direction: column; gap: 6px"><label for="{id_}" style="font-size: 14px; font-weight: 500">{l}</label><span style="position: relative; display: flex; align-items: center">'
                                f'<select id="{id_}" style="width: 100%; height: {H(phone)}; padding: 0 30px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}">'
                                + ''.join(f'<option{" selected" if i == 0 else ""}>{o}</option>' for i, o in enumerate(opts)) + '</select>' + svg(I['chev'], 14, ' style="position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none"') + '</span></div>')
    defaults = section('Defaults', 'For people who join from now on. Everyone can change their own language and time zone.',
                       f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 16px">' + sel('lg-lang', 'Default language', ['English', 'العربية (Arabic)']) + sel('lg-tz', 'Time zone', ['Asia/Dhaka (GMT+6)', 'Asia/Kolkata (GMT+5:30)', 'Asia/Dubai (GMT+4)', 'Europe/London (GMT)']) + '</div>'
                       + f'<div style="display: flex; justify-content: flex-end"><button type="button" onClick="{{{{save}}}}" style="{primary(phone)}{"; flex: 1" if phone else ""}">Save</button></div>', phone)
    inp = lambda v, ph, lbl: f'<input type="text" dir="{{{{dir}}}}" aria-label="{{{{t.{lbl}}}}}" value="{{{{t.{v}}}}}" placeholder="{{{{t.{ph}}}}}" style="width: 100%; height: {H(phone)}; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}; outline: none">'
    gender = (f'<span style="position: relative; display: flex; align-items: center"><select aria-label="{{{{t.genderLabel}}}}" style="width: 100%; height: {H(phone)}; padding: 0 30px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}">'
              '<option selected="{{t.masc}}">Masculine</option><option selected="{{t.fem}}">Feminine</option></select>' + svg(I['chev'], 14, ' style="position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none"') + '</span>')
    if phone:
        rows = ('<ul style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px"><sc-for list="{{terms}}" as="t" hint-placeholder-count="3"><li style="display: flex; flex-direction: column; gap: 8px; padding: 12px; border: 1px solid var(--border); border-radius: 8px">'
                '<span style="font-weight: 500">{{t.term}}</span><span style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px">' + inp('one', 'phOne', 'oneLabel') + inp('other', 'phOther', 'otherLabel') + '</span>'
                f'<sc-if value="{{{{isAr}}}}" hint-placeholder-val="{{{{false}}}}">{gender}</sc-if></li></sc-for></ul>')
    else:
        rows = (f'<table style="width: 100%; border-collapse: collapse; font-size: 14px"><caption style="{SR}">Terms for the chosen language</caption><thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 36px; {TH.replace("0 12px", "0 12px 0 0", 1)}; width: 220px">Term</th><th scope="col" style="{TH}">One</th><th scope="col" style="{TH}">Several</th>'
                '<sc-if value="{{isAr}}" hint-placeholder-val="{{false}}"><th scope="col" style="' + TH + '; width: 170px">Grammatical gender</th></sc-if></tr></thead>'
                '<tbody><sc-for list="{{terms}}" as="t" hint-placeholder-count="3"><tr style="border-bottom: 1px solid var(--hover)"><td style="padding: 8px 12px 8px 0; font-weight: 500">{{t.term}}</td><td style="padding: 8px 12px">' + inp('one', 'phOne', 'oneLabel') + '</td><td style="padding: 8px 12px">' + inp('other', 'phOther', 'otherLabel') + '</td>'
                f'<sc-if value="{{{{isAr}}}}" hint-placeholder-val="{{{{false}}}}"><td style="padding: 8px 0 8px 12px">{gender}</td></sc-if></tr></sc-for></tbody></table>')
    rows = rows.replace('{{t.', '{{tm.').replace('as="t"', 'as="tm"')
    terms = section('Your terms', 'Write the words as they appear in a sentence. Empty rows use the standard words.',
                    f'<div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px"><span style="font-size: 14px; font-weight: 500">Language</span>{tabs_markup(phone, "tabs", "Language")}</div>{rows}'
                    '<sc-if value="{{hasExample}}" hint-placeholder-val="{{true}}"><div style="padding: 10px 12px; border-radius: 8px; background: var(--subtle); font-size: 13px; color: var(--text-2)">For example: “{{example}}”</div></sc-if>'
                    f'<div style="display: flex; justify-content: flex-end"><button type="button" onClick="{{{{saveTerms}}}}" style="{primary(phone)}{"; flex: 1" if phone else ""}">Save terms</button></div>', phone)
    return defaults + terms
LANG = page('Language and terms', 'Language and terms', 'The defaults for new people, and the words your organisation uses for some things.', 'Defaults and your own words.', lang_body(False), lang_body(True))

# ================================================================ assemble
BOARDS = [('OrgSecurity', 'Security settings', SEC, lambda p: toast(p), SEC_JS, SEC_PROPS, 1000),
          ('OrgEmail', 'Email delivery', MAIL, mail_dialogs, MAIL_JS, MAIL_PROPS, 1000),
          ('OrgBranding', 'Branding', BRAND, lambda p: toast(p), BRAND_JS, BRAND_PROPS, 1000),
          ('OrgLanguage', 'Language and terms', LANG, lambda p: toast(p), LANG_JS, LANG_PROPS, 1000)]
for name, title, (desk, phone), dialogs, js, props, h in BOARDS:
    js = JS_HEAD + js
    pre = with_ro(desk_pre(title, 'Security', 'OrgSecurity.dc.html'))
    (proj / f'{name}.dc.html').write_text(pre + desk + dialogs(False) + '</div>\n</x-dc>\n' + script(props, 1440, h, DESK_HEAD_JS + BASE_JS + js + DESK_SHELL_JS))
    hd, drawer = phone_parts(f'{title} on a phone', 'Security', 'OrgSecurityPhone.dc.html')
    (proj / f'{name}Phone.dc.html').write_text(with_ro(hd) + phone + dialogs(True) + drawer + script(props, 390, 844, PHONE_HEAD_JS + BASE_JS + js + PHONE_SHELL_JS))

# Every other sidebar's Administration › Security links here (idempotent)
for p in sorted(proj.glob('*.dc.html')):
    s = p.read_text()
    s2 = s.replace('<a href="#" class="nav-item" title="Security" style=', '<a href="OrgSecurity.dc.html" class="nav-item" title="Security" style=')
    s2 = re.sub(r'<a href="#" class="nav-item" style="(display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var\(--fg\))">((?:(?!</a>).)*?\n\s*Security\n)', r'<a href="OrgSecurityPhone.dc.html" class="nav-item" style="\1">\2', s2, flags=re.S)
    if s2 != s: p.write_text(s2)

boards, notes, y = {}, {}, 21060
for n, t in [('OrgSecurity', 'Security (sign-in policy)'), ('OrgEmail', 'Email delivery'), ('OrgBranding', 'Branding'), ('OrgLanguage', 'Language and terms')]:
    boards[n + '.dc.html'] = {'x': 0, 'y': y, 'w': 1440, 'h': 1000, 'title': t + ' — Desktop', 'expand': 'fill', 'is_interactive': True, 'page': 'admin'}
    boards[n + 'Phone.dc.html'] = {'x': 1520, 'y': y, 'w': 390, 'h': 844, 'title': t + ' — Phone', 'is_interactive': True, 'page': 'admin'}
    notes['set' + n + 'Title'] = {'page': 'admin', 'x': 0, 'y': y - 260, 'text': t, 'kind': 'title1', 'maxW': 1910}
    y += 1360
notes['settingsNote'] = {'page': 'admin', 'x': 2030, 'y': 21060, 'w': 620, 'fill': 'blue', 'size': 'm', 'text':
  'Organisation-wide settings: Security, Email, Branding, Language and terms\n\n'
  '• The four pages share a row of section links; the sidebar’s Administration › Security entry opens them.\n'
  '• Settings pages are forms on the page with a Save bar that stays at the bottom and says when there are unsaved changes. Saving asks for step-up, as the app does. “Someone changed these settings meanwhile” shows in the bar.\n'
  '• Security: each number shows the range the server allows (for example 5–480); errors sit under the field, including “Lock after more failures than the waiting starts.” A side panel says in plain words what people will notice (new copy).\n'
  '• Email: the mail server is a summary card with Change (a dialog), Send a test email, and Use the platform’s server (asks first). A failed test shows on the card. The delivery log has All / Failed tabs.\n'
  '• Branding: logo shown on light and dark, colours with live contrast checks from lib/contrast.ts, and a sign-in preview in both themes.\n'
  '• Language and terms: default language and time zone; the organisation’s own words per language, with grammatical gender for Arabic and right-to-left fields.\n'
  '• API: nothing new.'}
canvas_add(boards, notes, done=('Security (sign-in policy)', 'Email', 'Branding', 'Language and terms'))
print('ok', y)
