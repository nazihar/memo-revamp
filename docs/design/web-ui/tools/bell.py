"""Bell: a dropdown on the desktop Inbox, a link to the Notifications page elsewhere; API note and rule updated."""
import json
import re
from pathlib import Path

proj = Path('canvas/project')
BELL_RE = re.compile(r'<button type="button" class="ghost" aria-label="Notifications, 3 unread"( style="[^"]*")>(.*?)</button>', re.S)

# Other pages: the bell goes to the Notifications page.
for name, target in [('Approved', 'Notifications'), ('Draft', 'Notifications'), ('Memo', 'Notifications'), ('NewMemo', 'Notifications'),
                     ('Rejected', 'Notifications'), ('Returned', 'Notifications'), ('Phone', 'NotificationsPhone')]:
    f = proj / f'{name}.dc.html'
    s = f.read_text()
    s, n = BELL_RE.subn(lambda m: f'<a href="{target}.dc.html" class="ghost" aria-label="Notifications, 4 unread"{m.group(1)}>{m.group(2)}</a>', s)
    assert n == 1, name
    f.write_text(s)

# Desktop Inbox: the bell opens a dropdown.
f = proj / 'Main.dc.html'
s = f.read_text()
m = BELL_RE.search(s)
style = m.group(1)
inner = m.group(2).replace(
    '<span aria-hidden="true" style="position: absolute; top: 6px; right: 7px;',
    '<sc-if value="{{bellHasUnread}}" hint-placeholder-val="{{true}}"><span aria-hidden="true" style="position: absolute; top: 6px; right: 7px;', 1)
inner = inner.replace('</span>\n      ', '</span></sc-if>\n      ', 1)
bell = (f'<button type="button" class="ghost" aria-label="{{{{bellLabel}}}}" aria-haspopup="dialog" aria-expanded="{{{{bellAria}}}}" onClick="{{{{toggleBell}}}}"{style}>{inner}</button>\n'
        '''      <sc-if value="{{bellOpen}}" hint-placeholder-val="{{false}}">
        <button type="button" tabIndex="-1" aria-label="Close notifications" onClick="{{closeBell}}" style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 1; border: 0; padding: 0; background: transparent; cursor: default"></button>
        <div role="dialog" aria-label="Recent notifications" onKeyDown="{{bellKey}}" style="position: absolute; z-index: 2; top: 44px; right: 64px; width: 380px; max-width: calc(100vw - 32px); box-sizing: border-box; border: 1px solid var(--border); border-radius: 10px; background: var(--bg); box-shadow: 0 8px 24px rgba(0,0,0,0.10), 0 1px 2px rgba(0,0,0,0.05); overflow: hidden">
          <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 8px 10px 14px; border-bottom: 1px solid var(--border)">
            <span style="font-size: 14px; font-weight: 600">Notifications</span>
            <button type="button" class="ghost" onClick="{{bellMarkAll}}" disabled="{{bellNoUnread}}" style="{{bellMarkStyle}}">Mark all as read</button>
          </div>
          <ul style="list-style: none; margin: 0; padding: 0">
            <sc-for list="{{bellItems}}" as="b" hint-placeholder-count="5">
              <li style="{{b.rowStyle}}">
                <span aria-hidden="true" style="{{b.dotStyle}}"></span>
                <a href="{{b.href}}" onClick="{{b.read}}" style="display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; text-decoration: none; color: var(--fg)">
                  <span style="{{b.textStyle}}"><span style="position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)">{{b.sr}}</span>{{b.text}}</span>
                  <span style="font-size: 12px; line-height: 16px; color: var(--muted-fg)">{{b.meta}}</span>
                </a>
              </li>
            </sc-for>
          </ul>
          <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 6px 6px 8px; border-top: 1px solid var(--border)">
            <a href="Notifications.dc.html" class="ghost" style="display: inline-flex; align-items: center; gap: 4px; height: 32px; padding: 0 8px; border-radius: 6px; color: var(--fg); font-size: 13px; font-weight: 500; text-decoration: none">
              See all notifications
              <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m9 18 6-6-6-6"></path></svg>
            </a>
            <a href="#" class="ghost" aria-label="Notification settings" style="display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 6px; color: var(--text-3)">
              <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 7h-9"></path><path d="M14 17H5"></path><circle cx="17" cy="17" r="3"></circle><circle cx="7" cy="7" r="3"></circle></svg>
            </a>
          </div>
        </div>
      </sc-if>''')
s = s[:m.start()] + bell + s[m.end():]

js = '''    const bellOpen = !!st.bellOpen;
    const bellRead = st.bellRead || {};
    const bellData = [
      { id: 'n1', text: 'Reminder: memo CR/2026/0142 is waiting for your decision', meta: 'Memo approvals · 09:00', href: 'Memo.dc.html' },
      { id: 'n3', text: 'You were mentioned in a note on memo PRC/2026/0087', meta: 'Memos you follow · 08:42', href: '#' },
      { id: 'n2', text: 'Memo CR/2026/0142 is overdue', meta: 'Memo approvals · 08:00', href: 'Memo.dc.html', urgent: true },
      { id: 'n4', text: 'Memo FIN/2026/0298 is waiting for your approval', meta: 'Memo approvals · yesterday', href: '#', seen: true },
      { id: 'n6', text: 'New sign-in to your account from Chrome on Windows', meta: 'Security · yesterday', href: '#', seen: true }
    ].map((b) => Object.assign({}, b, { unread: !b.seen && !bellRead[b.id] }));
    const bellUnread = bellData.filter((b) => b.unread).length + 1;
    const closeBell = () => this.setState({ bellOpen: false });
    const bellItems = bellData.map((b, i) => ({
      text: b.text, meta: b.meta, href: b.href,
      sr: b.unread ? 'Unread: ' : '',
      read: () => { const n = Object.assign({}, bellRead); n[b.id] = true; this.setState({ bellRead: n }); },
      rowStyle: 'display: flex; align-items: flex-start; gap: 10px; padding: 10px 14px 10px 10px' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') + (b.unread ? '; background: var(--subtle)' : ''),
      dotStyle: 'width: 6px; height: 6px; margin-top: 7px; border-radius: 50%; flex-shrink: 0; background: ' + (b.unread ? (b.urgent ? 'var(--destructive)' : 'var(--primary)') : 'transparent'),
      textStyle: 'font-size: 13px; line-height: 18px; ' + (b.unread ? 'font-weight: 600' : 'color: var(--text-1)')
    }));
'''
anchor = "    const closeMenu = () => this.setState({ menuOpen: false });\n"
assert anchor in s
s = s.replace(anchor, anchor + js, 1)
s = s.replace("      toggleMenu: () => this.setState({ menuOpen: !menuOpen }),",
              "      toggleMenu: () => this.setState({ menuOpen: !menuOpen, bellOpen: false }),\n"
              "      bellOpen: bellOpen,\n"
              "      bellAria: bellOpen ? 'true' : 'false',\n"
              "      bellLabel: bellUnread > 0 ? 'Notifications, ' + bellUnread + ' unread' : 'Notifications',\n"
              "      bellHasUnread: bellUnread > 0,\n"
              "      bellNoUnread: bellUnread === 0,\n"
              "      toggleBell: () => this.setState({ bellOpen: !bellOpen, menuOpen: false }),\n"
              "      closeBell: closeBell,\n"
              "      bellKey: (ev) => { if (ev && ev.key === 'Escape') closeBell(); },\n"
              "      bellItems: bellItems,\n"
              "      bellMarkAll: () => this.setState({ bellRead: { n1: true, n2: true, n3: true, n8: true } }),\n"
              "      bellMarkStyle: 'height: 28px; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; font-size: 12px; font-weight: 500; ' + (bellUnread === 0 ? 'color: var(--muted-fg); cursor: default' : 'color: var(--fg); cursor: pointer'),", 1)
assert 'bellItems: bellItems' in s
f.write_text(s)

# The fourth unread item (the failed save) is older than the five shown; mark-all clears it too.
s = f.read_text()
s = s.replace("const bellUnread = bellData.filter((b) => b.unread).length + 1;",
              "const bellUnread = bellData.filter((b) => b.unread).length + (bellRead.n8 ? 0 : 1);")
f.write_text(s)

# Rule.
r = proj / 'Rules.dc.html'
s = r.read_text()
old = '<li>Top bar 48px: navigation toggle, search, then bell and account at the right.</li>'
assert old in s
s = s.replace(old, old + '\n          <li>Bell: a dot while anything is unread. On desktop it opens a dropdown of the five latest notifications with Mark all as read, See all and settings; on phones it opens the Notifications page.</li>', 1)
r.write_text(s)

# API note.
c = proj / 'canvas.json'
d = json.loads(c.read_text())
d['notes']['apiChanges']['text'] = (
    "API changes this design needs\n\n"
    "Inbox (GET /api/v1/inbox/tasks)\n"
    "1. Counts — total open tasks for the My tasks tab, the sidebar Inbox badge and the \"x of N selected\" footer.\n"
    "2. Action per task — DECIDE does not say whether the step is Approve or Recommend.\n"
    "3. Memo type — add the memo type's name to InboxMemo.\n"
    "4. Sender — who sent the task (previous holder or author).\n"
    "5. Filters — text (subject or reference), memo type, due (overdue / due soon / none), working with sort and the cursor.\n\n"
    "Notifications (GET /api/v1/me/notifications)\n"
    "6. Filters — unread only, and category (approvals, following, security, administration), working with the cursor.\n"
    "7. Context — the second line needs a little more in params: the memo subject, a snippet of the note that mentions you, the sign-in place and browser.")
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok')
