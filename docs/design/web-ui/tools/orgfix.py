from pathlib import Path
p = Path('tools/org-body.html'); s = p.read_text()
def rep(a, b, n=1):
    global s
    assert s.count(a) == n, (a[:80], s.count(a)); s = s.replace(a, b)
rep('Units, positions and who holds them. Approval routes and access follow this structure.', 'Units, positions and who holds them.')
rep('Find a unit or person</span>', 'Find a unit</span>')
rep('placeholder="Find a unit or person"', 'placeholder="Find a unit"')
rep('<div style="padding: 8px 4px 6px 12px; font-size: 12px; font-weight: 500; color: var(--muted-fg)">[Organisation name] PLC</div>', '<div style="padding: 8px 4px 6px 12px; font-size: 12px; font-weight: 500; color: var(--muted-fg)">Units</div>')
rep('''                  <span style="font-size: 11px; color: var(--muted-fg); font-variant-numeric: tabular-nums">{{n.count}}</span>\n''', '')
# Path as breadcrumb links
rep('<span style="font-size: 12px; color: var(--muted-fg)">{{unit.path}}</span>', '''<nav aria-label="Unit path" style="display: flex; flex-wrap: wrap; align-items: center; gap: 4px; font-size: 12px; color: var(--muted-fg)">
                <sc-for list="{{crumbs}}" as="c" hint-placeholder-count="1">
                  <button type="button" onClick="{{c.pick}}" style="padding: 0; border: 0; background: transparent; color: var(--muted-fg); font-size: 12px; cursor: pointer; text-decoration: underline; text-underline-offset: 2px">{{c.name}}</button>
                  <span aria-hidden="true">›</span>
                </sc-for>
                <span style="color: var(--text-2)">{{unit.name}}</span>
              </nav>''')
rep('<span style="font-size: 12px; color: var(--muted-fg)">{{p.reports}}</span>', '<sc-if value="{{p.hasCode}}" hint-placeholder-val="{{false}}"><span style="font-size: 12px; color: var(--muted-fg); font-family: \'Geist Mono\', ui-monospace, monospace">{{p.code}}</span></sc-if>')
rep('<td style="padding: 10px 16px">', '<td style="padding: 10px 16px; vertical-align: middle">')
rep('No positions in this unit yet.', 'No positions in this unit.')
# history section after subunits
rep('''          </sc-if>
        </section>
      </div>
    </main>''', '''          </sc-if>

          <div style="border: 1px solid var(--border); border-radius: 10px; overflow: hidden">
            <button type="button" class="ghost" aria-expanded="{{histAria}}" aria-controls="org-hist" onClick="{{toggleHist}}" style="display: flex; align-items: center; gap: 8px; width: 100%; height: 44px; padding: 0 16px; border: 0; background: transparent; color: var(--fg); font-size: 14px; font-weight: 500; text-align: left; cursor: pointer">
              <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="{{histChev}}"><path d="m9 18 6-6-6-6"></path></svg>
              Change history
            </button>
            <sc-if value="{{histOpen}}" hint-placeholder-val="{{false}}">
              <div id="org-hist" style="border-top: 1px solid var(--border); overflow-x: auto">
                <table style="width: 100%; min-width: 560px; border-collapse: collapse; font-size: 13px">
                  <caption style="position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)">Changes, newest first</caption>
                  <thead>
                    <tr style="text-align: left; color: var(--muted-fg)">
                      <th scope="col" style="height: 36px; padding: 0 16px; font-weight: 500; width: 160px">When</th>
                      <th scope="col" style="padding: 0 12px; font-weight: 500">Change</th>
                      <th scope="col" style="padding: 0 16px 0 12px; font-weight: 500; width: 160px">By</th>
                    </tr>
                  </thead>
                  <tbody>
                    <sc-for list="{{history}}" as="h" hint-placeholder-count="3">
                      <tr style="border-top: 1px solid var(--hover)">
                        <td style="padding: 8px 16px; color: var(--muted-fg); white-space: nowrap">{{h.when}}</td>
                        <td style="padding: 8px 12px">{{h.change}}</td>
                        <td style="padding: 8px 16px 8px 12px">{{h.by}}</td>
                      </tr>
                    </sc-for>
                  </tbody>
                </table>
              </div>
            </sc-if>
          </div>
        </section>
      </div>
    </main>''')
# JS
rep("{ id: 'ho', name: 'Head Office', parent: null, code: 'HO', since: '1 Jan 2010', positions: 3 }", "{ id: 'ho', name: 'Head Office', parent: null, code: 'HO', since: '1 Jan 2010', positions: 3 }")
rep("name: u.name, count: u.positions ? String(u.positions) : '',", "name: u.name,")
rep('''    const path = [];
    for (let p = byId[u.parent]; p; p = byId[p.parent]) path.unshift(p.name);''', '''    const path = [];
    for (let p = byId[u.parent]; p; p = byId[p.parent]) path.unshift(p);
    const pickUnit = (id) => { const o = Object.assign({}, open); for (let q = byId[id]; q; q = byId[q.parent]) o[q.id] = true; this.setState({ sel: id, open: o, menu: false }); };''')
rep('''        ['Head of Credit', 'Farhana Rahman', '1 Mar 2023', true, 'Reports to Chief Risk Officer'],
        ['Credit Risk Manager', 'Nusrat Jahan', '15 Aug 2024', false, 'Reports to Head of Credit'],
        ['Credit Risk Analyst', 'Sadia Islam', '2 Jan 2025', false, 'Reports to Credit Risk Manager'],
        ['Credit Risk Analyst', 'Imran Hossain', '10 Feb 2025', false, 'Reports to Credit Risk Manager'],
        ['Senior Credit Officer', '', '', false, 'Reports to Head of Credit']''', '''        ['Head of Credit', 'Farhana Rahman', '1 Mar 2023', true, 'CR-H'],
        ['Credit Risk Manager', 'Nusrat Jahan', '15 Aug 2024', false, 'CR-M1'],
        ['Credit Risk Analyst', 'Sadia Islam', '2 Jan 2025', false, ''],
        ['Credit Risk Analyst', 'Imran Hossain', '10 Feb 2025', false, ''],
        ['Senior Credit Officer', '', '', false, 'CR-S1']''')
rep('''        ['Head of Corporate Banking', 'Mahmud Karim', '1 Jan 2021', true, 'Reports to Managing Director'],
        ['Relationship Manager', 'Tanvir Ahmed', '5 May 2022', false, 'Reports to Head of Corporate Banking']''', '''        ['Head of Corporate Banking', 'Mahmud Karim', '1 Jan 2021', true, 'CB-H'],
        ['Relationship Manager', 'Tanvir Ahmed', '5 May 2022', false, '']''')
rep('''        ['Chief Financial Officer', 'Rafiq Chowdhury', '1 Jul 2020', true, 'Reports to Managing Director'],
        ['Deputy CFO', 'Karim Reza', '1 Jan 2024', false, 'Reports to Chief Financial Officer']''', '''        ['Chief Financial Officer', 'Rafiq Chowdhury', '1 Jul 2020', true, 'FIN-H'],
        ['Deputy CFO', 'Karim Reza', '1 Jan 2024', false, '']''')
rep("head: p[3], reports: p[4],", "head: p[3], code: p[4], hasCode: !!p[4],")
rep("const subunits = kids(sel).map((k) => ({ name: k.name, meta: k.positions + ' positions · ' + kids(k.id).length + ' sub-units', pick: () => { const o = Object.assign({}, open); o[sel] = true; this.setState({ sel: k.id, open: o }); } }));",
    "const subunits = kids(sel).map((k) => { const n = kids(k.id).length; return { name: k.name, meta: k.code + (n ? ' · ' + n + (n === 1 ? ' sub-unit' : ' sub-units') : ''), pick: () => pickUnit(k.id) }; });")
rep("pick: () => this.setState({ sel: u.id, menu: false })", "pick: () => this.setState({ sel: u.id, menu: false })")
rep("unit: { name: u.name, code: u.code, since: u.since, path: path.length ? path.join(' › ') : '[Organisation name] PLC', summary: u.positions + ' positions' + (vacant ? ', ' + vacant + ' vacant' : '') },",
    "unit: { name: u.name, code: u.code, since: u.since, summary: positions.length + (positions.length === 1 ? ' position' : ' positions') + (vacant ? ', ' + vacant + ' vacant' : '') },\n      crumbs: path.map((q) => ({ name: q.name, pick: () => pickUnit(q.id) })),\n      histOpen: !!st.hist, histAria: st.hist ? 'true' : 'false', toggleHist: () => this.setState({ hist: !st.hist }),\n      histChev: 'transition: transform 0.15s; color: var(--muted-fg); transform: rotate(' + (st.hist ? '90deg' : '0') + ')',\n      history: [\n        { when: '12 Oct 2026, 15:40', change: 'Changed (name: Credit Risk Management → Credit Risk)', by: 'Rafiq Chowdhury' },\n        { when: '3 Mar 2025, 10:02', change: 'Moved', by: 'Rafiq Chowdhury' },\n        { when: '1 Jan 2018, 09:00', change: 'Created', by: 'System' }\n      ],")
rep('''      menuItems: [
        { label: 'Add sub-unit', hint: 'A unit inside this one' },
        { label: 'Rename', hint: 'Name and code' },
        { label: 'Move', hint: 'Its sub-units and positions move with it.' },
        { label: 'Close', hint: 'It stays in the history.' }
      ].map(''', '''      menuItems: [
        { label: 'Add sub-unit', hint: 'A unit inside this one' },
        { label: 'Rename', hint: 'Name and code' },
        { label: 'Move', hint: 'Its sub-units and positions move with it.', skip: !u.parent },
        { label: 'Close', hint: 'It stays in the history.' }
      ].filter((m) => !m.skip).map(''')
p.write_text(s)

proj = Path('canvas/project')
memo = (proj / 'Memo.dc.html').read_text()
marker = '    <!-- Content -->\n'
prefix = memo[:memo.index(marker) + len(marker)].replace('<title>Memo detail</title>', '<title>Organisation</title>')
assert '<title>Organisation</title>' in prefix
assert prefix.count('; background: var(--active); font-weight: 500"') == 1
prefix = prefix.replace('; background: var(--active); font-weight: 500"', '"')
old = 'title="Organisation" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg)">'
assert prefix.count(old) == 1
prefix = prefix.replace(old, 'title="Organisation" aria-current="page" style="display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 6px; text-decoration: none; color: var(--fg); background: var(--active); font-weight: 500">')
(proj / 'Org.dc.html').write_text(prefix + s)
print('ok')
