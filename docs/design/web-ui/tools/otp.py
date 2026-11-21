"""Code fields become six boxes (shadcn InputOTP): one real input for typing, paste and one-time-code autofill; boxes are visual."""
import re
from pathlib import Path
proj = Path('canvas/project')

# file -> {input id: options as JS object literal}
TARGETS = {
    'StepUp': {'su-code': "{}", 'su-wrong': "{ init: '481209', error: true }", 'su-tooMany': "{ init: '481209', error: true }", 'su-checking': "{ init: '481209', disabled: true }"},
    'StepUpPhone': {'su-code': "{}", 'su-wrong': "{ init: '481209', error: true }", 'su-tooMany': "{ init: '481209', error: true }", 'su-checking': "{ init: '481209', disabled: true }"},
    'MemoDialogs': {'su-code': "{ init: e ? '481209' : '', error: e }"},
    'MemoDialogsPhone': {'su-code': "{ init: e ? '481209' : '', error: e }"},
    'SignIn': {'si-code': "{}", 'en-code': "{}"},
    'SignInPhone': {'si-code': "{}", 'en-code': "{}"},
}
MINUS = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 12h14"></path></svg>'
CSS = '@keyframes caret-blink{0%,70%,100%{opacity:1}20%,50%{opacity:0}}\n.otp-caret{animation:caret-blink 1.2s ease-out infinite}\n@media (prefers-reduced-motion: reduce){.otp-caret{animation:none}}\n'

def key(fid): return fid.replace('-', '_')

def template(fid):
    k = key(fid)
    group = lambda part: f'''<span aria-hidden="true" style="display: flex">
      <sc-for list="{{{{otp.{k}.{part}}}}}" as="sl" hint-placeholder-count="3">
        <span style="{{{{sl.style}}}}">{{{{sl.ch}}}}<sc-if value="{{{{sl.caret}}}}" hint-placeholder-val="{{{{false}}}}"><span class="otp-caret" style="position: absolute; width: 1px; height: 45%; background: var(--fg)"></span></sc-if></span>
      </sc-for>
    </span>'''
    return f'''<div style="position: relative; align-self: center; display: inline-flex; align-items: center; gap: 8px">
    <input id="{fid}" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" value="{{{{otp.{k}.value}}}}" onChange="{{{{otp.{k}.onInput}}}}" onFocus="{{{{otp.{k}.onFocus}}}}" onBlur="{{{{otp.{k}.onBlur}}}}" disabled="{{{{otp.{k}.disabled}}}}" aria-invalid="{{{{otp.{k}.invalid}}}}" aria-describedby="{{{{otp.{k}.describedBy}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 2; width: 100%; height: 100%; margin: 0; padding: 0; border: 0; opacity: 0; background: transparent; color: transparent; caret-color: transparent; font-size: 16px; cursor: text">
    {group('a')}
    <span aria-hidden="true" style="display: flex; color: var(--muted-fg)">{MINUS}</span>
    {group('b')}
  </div>'''

def helper(phone, entries):
    w, h, fs = ('48px', '52px', '20px') if phone else ('40px', '40px', '16px')
    lines = ',\n      '.join(f"{key(fid)}: mkOtp('{key(fid)}', '{fid}', {opts})" for fid, opts in entries.items())
    return f"""    // One-time code: six boxes in two groups of three (shadcn InputOTP); the real input sits on top, invisible.
    const mkOtp = (k, fid, o) => {{
      const raw = st['otp_' + k] !== undefined ? st['otp_' + k] : (o.init || '');
      const v = String(raw).replace(/\\D/g, '').slice(0, 6);
      const focused = !!st['otpF_' + k] && !o.disabled;
      const line = o.error ? 'var(--destructive)' : 'var(--border-strong)';
      const slot = (i) => {{
        const active = focused && i === Math.min(v.length, 5);
        const first = i % 3 === 0, last = i % 3 === 2;
        return {{
          ch: v[i] || '', caret: active && !v[i],
          style: 'position: relative; display: flex; align-items: center; justify-content: center; width: {w}; height: {h}; box-sizing: border-box; '
            + 'border-top: 1px solid ' + line + '; border-bottom: 1px solid ' + line + '; border-right: 1px solid ' + line + '; '
            + (first ? 'border-left: 1px solid ' + line + '; border-radius: 8px 0 0 8px; ' : '') + (last ? 'border-radius: 0 8px 8px 0; ' : '')
            + "font-family: 'Geist Mono', ui-monospace, monospace; font-size: {fs}; font-weight: 500; box-shadow: 0 1px 2px rgba(0,0,0,0.05); "
            + (o.disabled ? 'background: var(--muted); color: var(--muted-fg); ' : 'background: var(--bg); color: var(--fg); ')
            + (active ? 'z-index: 1; box-shadow: 0 0 0 2px ' + (o.error ? 'var(--destructive)' : 'var(--ring)') + '; ' : '')
        }};
      }};
      const slots = [0, 1, 2, 3, 4, 5].map(slot);
      return {{
        a: slots.slice(0, 3), b: slots.slice(3), value: v,
        disabled: !!o.disabled, invalid: o.error ? 'true' : 'false', describedBy: o.error ? fid + '-err' : '',
        onInput: (ev) => {{ const n = {{}}; n['otp_' + k] = ev && ev.target ? String(ev.target.value).replace(/\\D/g, '').slice(0, 6) : ''; this.setState(n); }},
        onFocus: () => {{ const n = {{}}; n['otpF_' + k] = true; this.setState(n); }},
        onBlur: () => {{ const n = {{}}; n['otpF_' + k] = false; this.setState(n); }}
      }};
    }};
    const OTP = {{
      {lines}
    }};
"""

for name, entries in TARGETS.items():
    p = proj / f'{name}.dc.html'; s = p.read_text()
    if 'mkOtp' in s:
        print('skip', name); continue
    phone = name.endswith('Phone')
    for fid in entries:
        m = re.search(r'<input id="' + re.escape(fid) + r'" type="text" inputmode="numeric" autocomplete="one-time-code"[^>]*>', s)
        assert m, (name, fid)
        s = s[:m.start()] + template(fid) + s[m.end():]
    # caret animation
    s = s.replace('</style>\n</helmet>', CSS + '</style>\n</helmet>', 1)
    assert 'caret-blink' in s, name
    # script: helper before the final return, and expose OTP
    i0 = s.index('data-dc-script')
    end = s.index('\n  }\n}\n</script>', i0)
    r = s.rindex('\n    return ', i0, end)
    stmt = s[r + len('\n    return '):end]
    assert stmt.rstrip().endswith(';'), (name, stmt[-40:])
    stmt = stmt.rstrip()[:-1]
    if 'const st =' not in s[i0:r] and 'const st=' not in s[i0:r]:
        pre = "    const st = this.state || {};\n"
    else:
        pre = ''
    s = s[:r] + '\n' + pre + helper(phone, entries) + '    return Object.assign({ otp: OTP }, ' + stmt + ');' + s[end:]
    # Centre the field: the column holding the label, the boxes and the message.
    for fid in entries:
        lab = s.index(f'<label for="{fid}"')
        col = s.rfind('<div style="display: flex; flex-direction: column; gap: 6px', 0, lab)
        end = s.index('"', col + len('<div style="'))
        if 'align-items: center' not in s[col:end]:
            s = s[:end] + '; align-items: center; text-align: center' + s[end:]
    p.write_text(s)
    print('ok', name)
