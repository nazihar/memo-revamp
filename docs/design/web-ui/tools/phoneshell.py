"""Top-level phone pages share the Inbox's top bar and drawer; only pages reached from a list keep a back button."""
import re
from pathlib import Path

proj = Path('canvas/project')
phone = (proj / 'Phone.dc.html').read_text()

h0 = phone.index('  <!-- Top bar -->\n')
h1 = phone.index('  </header>\n', h0) + len('  </header>\n')
header = phone[h0:h1]
d0 = phone.index('  <!-- Navigation drawer -->\n')
d1 = phone.index('  </sc-if>\n', phone.index('    </nav>\n', d0)) + len('  </sc-if>\n')
drawer = phone[d0:d1]

# The search icon in the bar goes to the Search page.
SEARCH_BTN = re.compile(r'<button type="button" class="ghost" aria-label="Search memos" style="([^"]*)">(.*?)</button>', re.S)
header = SEARCH_BTN.sub(lambda m: f'<a href="SearchPhone.dc.html" class="ghost" aria-label="Search memos" style="{m.group(1)}">{m.group(2)}</a>', header)

# Drawer: real links, and no item marked as current by default.
ACTIVE_INBOX = re.compile(r'<a href="#" aria-current="page" style="([^"]*?); background: var\(--active\); font-weight: 500">')
m = ACTIVE_INBOX.search(drawer)
assert m, 'active inbox'
drawer = drawer[:m.start()] + f'<a href="Phone.dc.html" class="nav-item" style="{m.group(1)}">' + drawer[m.end():]
SAVED_ITEM = '<a href="#" class="nav-item" style="display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var(--fg)">\n        <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m19 21-7-4-7 4V5a2'
assert SAVED_ITEM in drawer
drawer = drawer.replace(SAVED_ITEM, SAVED_ITEM.replace('<a href="#" class="nav-item"', '<a href="SavedPhone.dc.html" class="nav-item"'))

ACTIVE_STYLE = '; background: var(--active); font-weight: 500" aria-current="page"'


def mark_active(drw, href):
    tag = f'<a href="{href}" class="nav-item" style="display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var(--fg)">'
    assert tag in drw, href
    return drw.replace(tag, tag[:-2] + ACTIVE_STYLE + '>')


def mark_bar(hdr, label):
    # The bar icon for the page you are on looks pressed and says so.
    i = hdr.index(f'aria-label="{label}')
    j = hdr.index('style="', i) + len('style="')
    hdr = hdr[:i] + 'aria-current="page" ' + hdr[i:]
    j += len('aria-current="page" ')
    k = hdr.index('"', j)
    style = hdr[j:k].replace('background: transparent', 'background: var(--muted)')
    if 'background: var(--muted)' not in style:
        style += '; background: var(--muted)'
    return hdr[:j] + style + hdr[k:]


NAV_JS = '''    const navOpen = !!(this.state && this.state.navOpen);
    const closeNav = () => this.setState({ navOpen: false });
'''
NAV_VALS = '''      navOpen: navOpen,
      navAria: navOpen ? 'true' : 'false',
      openNav: () => this.setState({ navOpen: true }),
      closeNav: closeNav,
      navKey: (ev) => { if (ev && ev.key === 'Escape') closeNav(); },
'''


def convert(name, row_style, back_label, page_header, page_drawer):
    p = proj / name
    s = p.read_text()
    a = s.index('  <header ')
    b = s.index('  </header>\n', a) + len('  </header>\n')
    old = s[a:b]
    # Drop the back button, keep the rest as the page's own title row.
    old = re.sub(r'\s*<a href="[^"]*" class="ghost" aria-label="' + back_label + r'".*?</a>', '', old, count=1, flags=re.S)
    old = re.sub(r'<header style="[^"]*">', f'<div style="{row_style}">', old, count=1).replace('  </header>\n', '  </div>\n')
    s = s[:a] + page_header + old + s[b:]
    end = '</div>\n</x-dc>'
    assert s.count(end) == 1
    s = s.replace(end, page_drawer + end)
    script = s.index('data-dc-script')
    r = s.index('  renderVals() {\n', script) + len('  renderVals() {\n')
    s = s[:r] + NAV_JS + s[r:]
    ret = s.rindex('    return {')
    nl = s.index('\n', ret) + 1
    s = s[:nl] + NAV_VALS + s[nl:]
    p.write_text(s)


convert('NotificationsPhone.dc.html',
        'flex-shrink: 0; display: flex; align-items: center; gap: 4px; padding: 8px 4px 0 16px',
        'Back', mark_bar(header, 'Notifications'), drawer)
convert('SavedPhone.dc.html',
        'flex-shrink: 0; display: flex; align-items: center; gap: 4px; padding: 8px 4px 0 16px',
        'Back', header, mark_active(drawer, 'SavedPhone.dc.html'))
convert('SearchPhone.dc.html',
        'flex-shrink: 0; display: flex; align-items: center; gap: 4px; padding: 8px 4px 0 12px',
        'Back', mark_bar(header, 'Search memos'), drawer)

# The Inbox keeps its own bar, but its search icon and drawer now lead somewhere too.
s = (proj / 'Phone.dc.html').read_text()
s = s[:h0] + header + s[h1:]
d0 = s.index('  <!-- Navigation drawer -->\n')
d1 = s.index('  </sc-if>\n', s.index('    </nav>\n', d0)) + len('  </sc-if>\n')
s = s[:d0] + mark_active(drawer, 'Phone.dc.html') + s[d1:]
(proj / 'Phone.dc.html').write_text(s)

# Rule.
r = proj / 'Rules.dc.html'
s = r.read_text()
old = '<li>No breadcrumbs on top-level pages. Deeper pages show a "‹ Back" link above the title.</li>'
assert old in s
s = s.replace(old, old + '\n          <li>Phones: every top-level page (Inbox, Search, Notifications, Saved searches) has the same top bar with the menu button. Only pages opened from a list (a memo, a new memo, a draft) show a back button, and it returns to that list.</li>', 1)
r.write_text(s)
print('ok')
