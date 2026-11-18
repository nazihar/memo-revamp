"""Short forms open in a dialog (desktop) or a bottom sheet (phone), never inline."""
import re
from pathlib import Path

proj = Path('canvas/project')

CLOSE_X = '''<button type="button" class="ghost" aria-label="Close" onClick="{{__CLOSE__}}" style="position: absolute; top: 12px; right: 12px; display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; color: var(--muted-fg)">
          <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg>
        </button>'''


def dialog(cond, close, key, submit, title_id, title, desc, body, confirm, confirm_style='{{__BTN__}}'):
    return f'''
  <sc-if value="{{{{{cond}}}}}" hint-placeholder-val="{{{{false}}}}">
    <div style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box">
      <button type="button" tabIndex="-1" aria-label="Close dialog" onClick="{{{{{close}}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; border: 0; padding: 0; background: rgba(0,0,0,0.5); cursor: default"></button>
      <form role="dialog" aria-modal="true" aria-labelledby="{title_id}" aria-describedby="{title_id}-desc" novalidate onSubmit="{{{{{submit}}}}}" onKeyDown="{{{{{key}}}}}" style="position: relative; width: 100%; max-width: 440px; display: flex; flex-direction: column; gap: 16px; padding: 24px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--bg); box-shadow: 0 16px 48px rgba(0,0,0,0.2)">
        {CLOSE_X.replace('__CLOSE__', close)}
        <div style="display: flex; flex-direction: column; gap: 6px; padding-right: 24px">
          <h2 id="{title_id}" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{title}</h2>
          <p id="{title_id}-desc" style="margin: 0; font-size: 14px; color: var(--muted-fg)">{desc}</p>
        </div>
{body}
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <button type="button" class="ghost" onClick="{{{{{close}}}}}" style="height: 36px; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 14px; font-weight: 500; color: var(--fg); cursor: pointer">Cancel</button>
          <button type="submit" style="height: 36px; padding: 0 14px; border: 0; border-radius: 8px; background: var(--primary); font-size: 14px; font-weight: 500; color: var(--primary-fg); cursor: pointer">{confirm}</button>
        </div>
      </form>
    </div>
  </sc-if>
'''


def sheet(cond, close, key, submit, title_id, title, desc, body, confirm):
    return f'''
  <sc-if value="{{{{{cond}}}}}" hint-placeholder-val="{{{{false}}}}">
    <button type="button" tabIndex="-1" aria-label="Close" onClick="{{{{{close}}}}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <form role="dialog" aria-modal="true" aria-labelledby="{title_id}" aria-describedby="{title_id}-desc" novalidate onSubmit="{{{{{submit}}}}}" onKeyDown="{{{{{key}}}}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; display: flex; flex-direction: column; gap: 14px; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--bg); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>
      <div style="display: flex; flex-direction: column; gap: 4px">
        <h2 id="{title_id}" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{title}</h2>
        <p id="{title_id}-desc" style="margin: 0; font-size: 14px; color: var(--muted-fg)">{desc}</p>
      </div>
{body}
      <div style="display: flex; flex-direction: column; gap: 8px">
        <button type="submit" style="height: 44px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 15px; font-weight: 500">{confirm}</button>
        <button type="button" class="ghost" onClick="{{{{{close}}}}}" style="height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Cancel</button>
      </div>
    </form>
  </sc-if>
'''


def field(fid, label, typ, val, on, invalid, style, msg_id, msg, msg_style, extra=''):
    return f'''        <div style="display: flex; flex-direction: column; gap: 6px">
          <label for="{fid}" style="font-size: 14px; font-weight: 500">{label}</label>
          <input id="{fid}" type="{typ}" {extra}value="{{{{{val}}}}}" onChange="{{{{{on}}}}}" aria-invalid="{{{{{invalid}}}}}" aria-describedby="{msg_id}" style="{{{{{style}}}}}">
          <span id="{msg_id}" style="{{{{{msg_style}}}}}">{msg}</span>
        </div>'''


def insert_before_end(s, block):
    end = '</div>\n</x-dc>'
    assert s.count(end) == 1
    return s.replace(end, block + end)


# ---------- My security, desktop ----------
p = proj / 'Security.dc.html'
s = p.read_text()
a = s.index('            <sc-if value="{{pwClosed}}"')
s = s.replace('            <sc-if value="{{pwClosed}}" hint-placeholder-val="{{true}}">\n', '', 1)
s = s.replace('aria-expanded="false" aria-controls="pw-form" style="height: 32px;', 'aria-haspopup="dialog" style="height: 32px;', 1)
# drop the </sc-if> that closed the button wrapper
b = s.index('Change password</button>', a)
c = s.index('            </sc-if>\n', b)
s = s[:c] + s[c + len('            </sc-if>\n'):]
# remove the inline form block
f0 = s.index('          <sc-if value="{{pwOpen}}" hint-placeholder-val="{{false}}">\n            <form id="pw-form"')
f1 = s.index('          </sc-if>\n', s.index('</form>', f0)) + len('          </sc-if>\n')
s = s[:f0] + s[f1:]
body = '\n'.join([
    field('pw-cur', 'Current password', 'password', 'curVal', 'onCur', 'curInvalid', 'curStyle', 'pw-cur-msg', '{{curMsg}}', 'curMsgStyle', 'autocomplete="current-password" '),
    field('pw-new', 'New password', 'password', 'newVal', 'onNew', 'newInvalid', 'newStyle', 'pw-new-msg', '{{newMsg}}', 'newMsgStyle', 'autocomplete="new-password" '),
])
s = insert_before_end(s, dialog('pwOpen', 'closePw', 'pwKey', 'submitPw', 'pw-title', 'Change password',
                                'Changing it signs you out on every other device.', body, 'Change password'))
s = s.replace("      closePw: () => this.setState({ pwOpen: false }),",
              "      closePw: () => this.setState({ pwOpen: false }),\n"
              "      pwKey: (ev) => { if (ev && ev.key === 'Escape') this.setState({ pwOpen: false }); },\n"
              "      curMsg: curError ? 'Enter your current password.' : '',\n"
              "      curMsgStyle: 'font-size: 12px; color: var(--danger-fg)' + (curError ? '' : '; display: none'),", 1)
s = s.replace("'height: 36px; padding: 0 10px; box-sizing: border-box; border-radius: 8px;", "'width: 100%; height: 36px; padding: 0 10px; box-sizing: border-box; border-radius: 8px;", 1)
assert 'pw-form' not in s and 'pwKey' in s
p.write_text(s)

# ---------- My security, phone ----------
p = proj / 'SecurityPhone.dc.html'
s = p.read_text()
s = s.replace('<button type="button" style="height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Change password</button>',
              '<button type="button" onClick="{{openPw}}" aria-haspopup="dialog" style="height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Change password</button>', 1)
body = '\n'.join([
    field('ppw-cur', 'Current password', 'password', 'curVal', 'onCur', 'curInvalid', 'curStyle', 'ppw-cur-msg', '{{curMsg}}', 'curMsgStyle', 'autocomplete="current-password" '),
    field('ppw-new', 'New password', 'password', 'newVal', 'onNew', 'newInvalid', 'newStyle', 'ppw-new-msg', '{{newMsg}}', 'newMsgStyle', 'autocomplete="new-password" '),
])
s = insert_before_end(s, sheet('pwOpen', 'closePw', 'pwKey', 'submitPw', 'ppw-title', 'Change password',
                               'Changing it signs you out on every other device.', body, 'Change password'))
js = '''    const pwOpen = !!st.pwOpen;
    const cur = st.cur || '';
    const nw = st.nw || '';
    const tried = !!st.tried;
    const curError = tried && !cur;
    const tooShort = nw.length < 12;
    const input = 'width: 100%; height: 44px; padding: 0 12px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: 16px; outline: none; border: 1px solid ';
'''
s = s.replace("    const closeNav = () => this.setState({ navOpen: false });\n", "    const closeNav = () => this.setState({ navOpen: false });\n" + js, 1)
vals = '''      pwOpen: pwOpen,
      openPw: () => this.setState({ pwOpen: true, tried: false, cur: '', nw: '' }),
      closePw: () => this.setState({ pwOpen: false }),
      pwKey: (ev) => { if (ev && ev.key === 'Escape') this.setState({ pwOpen: false }); },
      curVal: cur, newVal: nw,
      onCur: (e) => this.setState({ cur: e && e.target ? e.target.value : '' }),
      onNew: (e) => this.setState({ nw: e && e.target ? e.target.value : '' }),
      curInvalid: curError ? 'true' : 'false',
      curStyle: input + (curError ? 'var(--destructive)' : 'var(--border)'),
      curMsg: curError ? 'Enter your current password.' : '',
      curMsgStyle: 'font-size: 12px; color: var(--danger-fg)' + (curError ? '' : '; display: none'),
      newInvalid: (tried && tooShort) ? 'true' : 'false',
      newStyle: input + ((tried && tooShort) ? 'var(--destructive)' : 'var(--border)'),
      newMsg: (tried && tooShort) ? 'Use at least 12 characters.' : 'At least 12 characters. A long phrase is easier to remember than a complex word.',
      newMsgStyle: 'font-size: 12px; ' + ((tried && tooShort) ? 'color: var(--danger-fg)' : 'color: var(--muted-fg)'),
      submitPw: (e) => {
        if (e && e.preventDefault) e.preventDefault();
        if (cur && nw.length >= 12) this.setState({ pwOpen: false, out: { d2: true, d3: true, d4: true } });
        else this.setState({ tried: true });
      },
'''
s = s.replace("      devices: devices\n", vals + "      devices: devices\n", 1)
assert 'submitPw:' in s
p.write_text(s)

# ---------- Saved searches, desktop: rename in a dialog ----------
p = proj / 'Saved.dc.html'
s = p.read_text()
e0 = s.index('                  <sc-if value="{{s.editing}}" hint-placeholder-val="{{false}}">')
e1 = s.index('                  </sc-if>\n', s.index('</form>', e0)) + len('                  </sc-if>\n')
s = s[:e0] + s[e1:]
s = s.replace('onClick="{{s.rename}}" aria-label="{{s.renameLabel}}"', 'onClick="{{s.rename}}" aria-haspopup="dialog" aria-label="{{s.renameLabel}}"', 1)
body = field('rn-name', 'Name', 'text', 'renameDraft', 'onRenameDraft', 'renameInvalid', 'renameStyle', 'rn-msg', '{{renameMsg}}', 'renameMsgStyle', 'maxlength="100" ')
s = s.replace('  <!-- Delete confirm -->', dialog('renameOpen', 'cancelRename', 'renameKey', 'saveRename', 'rn-title', 'Rename saved search',
                                                  'Only the name changes; the words, filters and sort stay as they are.', body, 'Save').rstrip('\n') + '\n\n  <!-- Delete confirm -->', 1)
s = s.replace('        viewing: !isEditing,\n        editing: isEditing,\n', '        viewing: true,\n        editing: false,\n', 1)
rename_js = '''    const editItem = this.base().find((x) => x.id === editing);
    const renameName = st.draft !== undefined ? st.draft : (editItem ? (names[editItem.id] || editItem.name) : '');
    const renameEmpty = !!st.renameTried && !renameName.trim();
    const closeRename = () => this.setState({ editing: '', draft: undefined, renameTried: false });
'''
s = s.replace("    const confirmItem = ", rename_js + "    const confirmItem = ", 1)
rename_vals = '''      renameOpen: !!editing,
      renameDraft: renameName,
      onRenameDraft: (e) => this.setState({ draft: e && e.target ? e.target.value : '' }),
      renameInvalid: renameEmpty ? 'true' : 'false',
      renameStyle: 'width: 100%; height: 36px; padding: 0 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: 14px; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); border: 1px solid ' + (renameEmpty ? 'var(--destructive)' : 'var(--border)'),
      renameMsg: renameEmpty ? 'Enter a name.' : renameName.length + ' / 100',
      renameMsgStyle: 'font-size: 12px; ' + (renameEmpty ? 'color: var(--danger-fg)' : 'color: var(--muted-fg)'),
      cancelRename: closeRename,
      renameKey: (ev) => { if (ev && ev.key === 'Escape') closeRename(); },
      saveRename: (ev) => {
        if (ev && ev.preventDefault) ev.preventDefault();
        if (!renameName.trim()) { this.setState({ renameTried: true }); return; }
        const n = Object.assign({}, names); n[editing] = renameName.trim();
        this.setState({ names: n, editing: '', draft: undefined, renameTried: false });
      },
'''
s = s.replace("      confirmOpen: !!confirm,", rename_vals + "      confirmOpen: !!confirm,", 1)
assert 'saveRename:' in s and '{{s.editing}}' not in s
p.write_text(s)

# ---------- Saved searches, phone: rename in a sheet ----------
p = proj / 'SavedPhone.dc.html'
s = p.read_text()
old = '<button type="button" role="menuitem" class="ghost" onClick="{{close}}" style="display: flex; align-items: center; min-height: 48px; padding: 0 12px; border: 0; border-radius: 8px; background: transparent; font-size: 15px; color: var(--fg); text-align: left">Rename</button>'
assert old in s
s = s.replace(old, old.replace('onClick="{{close}}"', 'onClick="{{askRename}}"'))
body = field('prn-name', 'Name', 'text', 'renameDraft', 'onRenameDraft', 'renameInvalid', 'renameStyle', 'prn-msg', '{{renameMsg}}', 'renameMsgStyle', 'maxlength="100" ')
s = insert_before_end(s, sheet('renameOpen', 'cancelRename', 'renameKey', 'saveRename', 'prn-title', 'Rename saved search',
                               'Only the name changes; the words, filters and sort stay as they are.', body, 'Save'))
# names live in state so a rename shows in the list
s = s.replace("    const items = base.filter((s) => !deleted[s.id]).map((s) => Object.assign({}, s, {",
              "    const names = st.names || {};\n    const items = base.filter((s) => !deleted[s.id]).map((s) => Object.assign({}, s, {\n      name: names[s.id] || s.name,", 1)
s = s.replace("      sheetName: current ? current.name : '',", "      sheetName: current ? (names[current.id] || current.name) : '',", 1)
rjs = '''    const renaming = st.renaming || '';
    const renItem = base.find((x) => x.id === renaming);
    const renameName = st.draft !== undefined ? st.draft : (renItem ? (names[renItem.id] || renItem.name) : '');
    const renameEmpty = !!st.renameTried && !renameName.trim();
    const closeRename = () => this.setState({ renaming: '', draft: undefined, renameTried: false });
'''
s = s.replace("    const close = () => this.setState({ sheet: '', confirming: false });", rjs + "    const close = () => this.setState({ sheet: '', confirming: false });", 1)
rvals = '''      askRename: () => this.setState({ renaming: sheet, sheet: '', draft: undefined }),
      renameOpen: !!renaming,
      renameDraft: renameName,
      onRenameDraft: (e) => this.setState({ draft: e && e.target ? e.target.value : '' }),
      renameInvalid: renameEmpty ? 'true' : 'false',
      renameStyle: 'width: 100%; height: 44px; padding: 0 12px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: 16px; outline: none; border: 1px solid ' + (renameEmpty ? 'var(--destructive)' : 'var(--border)'),
      renameMsg: renameEmpty ? 'Enter a name.' : renameName.length + ' / 100',
      renameMsgStyle: 'font-size: 12px; ' + (renameEmpty ? 'color: var(--danger-fg)' : 'color: var(--muted-fg)'),
      cancelRename: closeRename,
      renameKey: (ev) => { if (ev && ev.key === 'Escape') closeRename(); },
      saveRename: (ev) => {
        if (ev && ev.preventDefault) ev.preventDefault();
        if (!renameName.trim()) { this.setState({ renameTried: true }); return; }
        const n = Object.assign({}, names); n[renaming] = renameName.trim();
        this.setState({ names: n, renaming: '', draft: undefined, renameTried: false });
      },
'''
s = s.replace("      confirming: !!st.confirming,", rvals + "      confirming: !!st.confirming,", 1)
assert 'askRename:' in s and 'const names = st.names' in s
p.write_text(s)

# ---------- Rule ----------
r = proj / 'Rules.dc.html'
s = r.read_text()
old = '<li>Menus close on Esc and on a click outside; keyboard focus returns to the button that opened them.</li>'
assert old in s
s = s.replace(old, '<li>Short forms (up to about three fields: change password, rename, reassign, save a search) open in a dialog on desktop and a bottom sheet on phones. Never expand a form inside a card or a list row: it pushes the page around. Only a page that is one long form (New memo) is a page of its own.</li>\n          ' + old, 1)
r.write_text(s)
print('ok')
