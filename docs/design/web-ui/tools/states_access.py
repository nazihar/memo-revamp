"""Adds the "No access to this page" state to the Page states boards (States, StatesPhone), next to Page not found.

For an address that needs a permission the person does not have: the menu hides such pages, but a shared link or a
bookmark still reaches them, and the API answers 403. Built from the Page not found card, so it looks the same.
Idempotent: does nothing when the state is already there.
"""
import re
from pathlib import Path
proj = Path('canvas/project')
LOCK = '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path>'
TITLE, NOTE = 'No access to this page', 'For a page that needs a permission the person does not have, reached from a shared link or a bookmark. The menu never shows it to them.'
HEAD = 'You do not have access to this page.'
HINT = 'It needs a permission your roles do not give you. If you need it, ask an administrator of your organisation.'

def swap(block, home_label='Go to Home'):
    b = block.replace('This page does not exist.', HEAD).replace('Check the address, or start again from Home.', HINT)
    return re.sub(r'(<svg[^>]*>).*?(</svg>)', lambda m: m.group(1) + LOCK + m.group(2), b, count=1, flags=re.S)

# desktop: a figure after Page not found
p = proj / 'States.dc.html'; s = p.read_text()
if TITLE not in s:
    i = s.index('Page not found'); a = s.rfind('<figure', 0, i); b = s.index('</figure>', i) + len('</figure>')
    fig = swap(s[a:b]).replace('Page not found', TITLE).replace('For an address the app does not know.', NOTE)
    s = s[:b] + '\n    ' + fig + s[b:]
    p.write_text(s); print('States: added')

# phone: a state in the picker, shown with the same block
p = proj / 'StatesPhone.dc.html'; s = p.read_text()
if 'noAccess' not in s:
    i = s.index('<sc-if value="{{is.page404}}"'); b = s.index('</sc-if>', i) + len('</sc-if>')
    blk = swap(s[i:b]).replace('is.page404', 'is.noAccess')
    s = s[:b] + '\n' + blk + s[b:]
    assert s.count('"page404", "offline"') == 2
    s = s.replace('"page404", "offline"', '"page404", "noAccess", "offline"')
    p.write_text(s); print('StatesPhone: added')

# the behaviour note lists the states the app does not handle yet
import json
OLD = 'Page not found (unknown address), Connection lost (offline bar) and Signed out (session ended mid-task)'
NEW = 'Page not found (unknown address), No access to this page (a page the person’s roles do not allow; today the API answers 403), Connection lost (offline bar) and Signed out (session ended mid-task)'
c = proj / 'canvas.json'; d = json.loads(c.read_text())
t = d['notes']['behaviour']['text']
if OLD in t:
    d['notes']['behaviour']['text'] = t.replace(OLD, NEW); c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':'))); print('behaviour note updated')
n = Path('NOTES.md'); s = n.read_text()
if OLD in s: n.write_text(s.replace(OLD, NEW)); print('NOTES.md updated')
