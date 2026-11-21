import re
from pathlib import Path
proj = Path('canvas/project')
st = (proj / 'StatusPhone.dc.html').read_text().split('\n')
head = '\n'.join(st[:46]) + '\n'
drawer_and_end = '\n'.join(st[92:157]) + '\n'
assert drawer_and_end.startswith('  <!-- Navigation drawer -->')
assert drawer_and_end.rstrip().endswith('</x-dc>')
head = head.replace('<title>Platform status on a phone</title>', '<title>Organisation on a phone</title>')
assert '<title>Organisation on a phone</title>' in head
tag = '<a href="OrgPhone.dc.html" class="nav-item" style="display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 10px; border-radius: 8px; text-decoration: none; color: var(--fg)">'
assert drawer_and_end.count(tag) == 1 and 'aria-current' not in drawer_and_end
drawer_and_end = drawer_and_end.replace(tag, tag[:-2] + '; background: var(--active); font-weight: 500" aria-current="page">')
main = Path('tools/orgphone-main.html').read_text()
# Reuse the desktop data and logic, minus the desktop-only bits.
desk = Path('tools/org-body.html').read_text()
js = desk[desk.index('<script type="text/x-dc"'):]
js = js.replace('"$preview":{"width":1440,"height":900}', '"$preview":{"width":390,"height":844}')
js = js.replace("    const menuOpen = !!st.menu;\n    const closeMenu = () => this.setState({ menu: false });\n",
                "    const sheetOpen = !!st.sheet;\n    const closeSheet = () => this.setState({ sheet: false });\n    const row = (i) => i > 0 ? 'border-top: 1px solid var(--hover)' : '';\n")
js = js.replace("      menuOpen: menuOpen, menuAria: menuOpen ? 'true' : 'false',\n      toggleMenu: () => this.setState({ menu: !menuOpen }), closeMenu: closeMenu,\n      menuKey: (ev) => { if (ev && ev.key === 'Escape') closeMenu(); },\n",
                "      sheetOpen: sheetOpen, openSheet: () => this.setState({ sheet: true }), closeSheet: closeSheet,\n      sheetKey: (ev) => { if (ev && ev.key === 'Escape') closeSheet(); },\n")
assert 'sheetOpen: sheetOpen' in js and 'const sheetOpen' in js
js = js.replace("positions: positions, hasPositions", "positions: positions.map((p, i) => Object.assign({}, p, { rowStyle: row(i) })), hasPositions")
js = js.replace("subunits: subunits, hasSubunits", "subunits: subunits.map((x, i) => Object.assign({}, x, { rowStyle: row(i) })), hasSubunits")
js = js.replace("      history: [", "      history: [").replace("by: 'System' }\n      ],", "by: 'System' }\n      ].map((h, i) => Object.assign({}, h, { rowStyle: 'display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; ' + row(i) })),")
assert 'rowStyle: row(i)' in js and "padding: 10px 12px; ' + row(i)" in js
# Phone nav: replace the desktop sidebar logic.
js = js.replace("    const navOpen = !!st.navOpen, collapsed = !!st.collapsed;\n", "    const navOpen = !!st.navOpen;\n")
a = js.index("      shortcut:"); b = js.index("      closeNav: closeNav,")
js = js[:a] + "      navOpen: navOpen, navAria: navOpen ? 'true' : 'false', openNav: () => this.setState({ navOpen: true }),\n" + js[b:]
js = js.replace("this.setState({ sel: u.id, menu: false })", "this.setState({ sel: u.id })").replace("this.setState({ sel: id, open: o, menu: false })", "this.setState({ sel: id, open: o })")
(proj / 'OrgPhone.dc.html').write_text(head + '\n' + main + drawer_and_end + js)
print('ok')
