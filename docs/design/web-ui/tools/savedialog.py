"""Add the "Save this search" dialog to the desktop Search page and a bottom-sheet version on the phone."""
from pathlib import Path

proj = Path('canvas/project')

# Shared logic: summary chips, name validation, the limit state and the toast.
JS = '''
    const saveOpen = !!st.saveOpen;
    const saveName = st.saveName !== undefined ? st.saveName : 'Meghna valuation';
    const saveTried = !!st.saveTried;
    const nameEmpty = !saveName.trim();
    const nameLong = saveName.length > 100;
    const atLimit = !!this.props.atLimit;
    const saveError = saveTried && (nameEmpty || nameLong);
    const summary = [{ text: '“Meghna valuation”', strong: true }]
      .concat(__SOURCES__)
      .concat(__CHIPS__)
      .concat([{ text: (st.sort || 'relevance') === 'newest' ? 'Newest first' : 'Relevance', strong: false }])
      .map((c) => ({ text: c.text, style: 'display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; font-size: 12px; ' + (c.strong ? 'background: var(--muted); color: var(--fg); font-weight: 600' : 'border: 1px solid var(--border); color: var(--text-2)') }));
    const closeSave = () => this.setState({ saveOpen: false, saveTried: false });
'''
VALS = '''      saveOpen: saveOpen,
      openSave: () => this.setState({ saveOpen: true, saveTried: false, savedToast: false }),
      closeSave: closeSave,
      saveKey: (ev) => { if (ev && ev.key === 'Escape') closeSave(); },
      saveName: saveName,
      onSaveName: (e) => this.setState({ saveName: e && e.target ? e.target.value : '' }),
      saveError: saveError,
      saveOk: !saveError,
      saveErrorText: nameEmpty ? 'Enter a name.' : 'Use at most 100 characters.',
      saveInvalid: saveError ? 'true' : 'false',
      saveCounter: saveName.length + ' / 100',
      saveInputStyle: 'width: 100%; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; outline: none; ' + (saveError ? 'border: 1px solid var(--destructive)' : 'border: 1px solid var(--border)'),
      summary: summary,
      atLimit: atLimit,
      notAtLimit: !atLimit,
      doSave: (e) => {
        if (e && e.preventDefault) e.preventDefault();
        if (atLimit) return;
        if (nameEmpty || nameLong) { this.setState({ saveTried: true }); return; }
        this.setState({ saveOpen: false, saveTried: false, savedToast: true });
      },
      savedToast: !!st.savedToast,
      hideToast: () => this.setState({ savedToast: false }),
'''

DIALOG_BODY = '''        <div style="display: flex; flex-direction: column; gap: 6px">
          <label for="__ID__-name" style="font-size: 14px; font-weight: 500">Name</label>
          <input id="__ID__-name" type="text" value="{{saveName}}" onChange="{{onSaveName}}" aria-invalid="{{saveInvalid}}" aria-describedby="__ID__-msg __ID__-count" style="{{saveInputStyle}}; __INPUT__">
          <span style="display: flex; justify-content: space-between; gap: 8px; font-size: 12px">
            <sc-if value="{{saveError}}" hint-placeholder-val="{{false}}"><span id="__ID__-msg" role="alert" style="color: var(--danger-fg)">{{saveErrorText}}</span></sc-if>
            <sc-if value="{{saveOk}}" hint-placeholder-val="{{true}}"><span id="__ID__-msg" style="color: var(--muted-fg)">Only you see your saved searches. Each time you open one, it shows what you may read then.</span></sc-if>
            <span id="__ID__-count" style="flex-shrink: 0; color: var(--muted-fg); font-variant-numeric: tabular-nums">{{saveCounter}}</span>
          </span>
        </div>
        <div style="display: flex; flex-direction: column; gap: 8px">
          <span style="font-size: 13px; font-weight: 500">What is saved</span>
          <div style="display: flex; flex-wrap: wrap; gap: 6px">
            <sc-for list="{{summary}}" as="c" hint-placeholder-count="4"><span style="{{c.style}}">{{c.text}}</span></sc-for>
          </div>
          <span style="font-size: 12px; color: var(--muted-fg)">The words, filters and sort, not the results: they are worked out again each time you open it.</span>
        </div>
        <sc-if value="{{atLimit}}" hint-placeholder-val="{{false}}">
          <div role="alert" style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 10px 12px; border: 1px solid var(--warn-border); border-radius: 8px; background: var(--warn-bg); color: var(--warn-fg); font-size: 13px">
            <span style="flex: 1 1 240px">You have as many saved searches as you can keep. Delete one on the Saved searches page first.</span>
            <a href="__SAVED__" style="color: var(--fg); font-weight: 500">Saved searches</a>
          </div>
        </sc-if>
'''

# ---- Desktop ----
p = proj / 'Search.dc.html'
s = p.read_text()
btn = '<button type="button" class="ghost" style="display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">Save this search</button>'
assert btn in s
s = s.replace(btn, btn.replace('<button type="button" class="ghost" style=', '<button type="button" class="ghost" aria-haspopup="dialog" onClick="{{openSave}}" style=').replace('>Save this search</button>', '>\n            <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z"></path></svg>\n            Save this search\n          </button>'))
dialog = '''
  <!-- Save this search -->
  <sc-if value="{{saveOpen}}" hint-placeholder-val="{{false}}">
    <div style="position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box">
      <button type="button" tabIndex="-1" aria-label="Close dialog" onClick="{{closeSave}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; border: 0; padding: 0; background: rgba(0,0,0,0.5); cursor: default"></button>
      <form role="dialog" aria-modal="true" aria-labelledby="sv-title" novalidate onSubmit="{{doSave}}" onKeyDown="{{saveKey}}" style="position: relative; width: 100%; max-width: 480px; display: flex; flex-direction: column; gap: 18px; padding: 24px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--bg); box-shadow: 0 16px 48px rgba(0,0,0,0.2)">
        <button type="button" class="ghost" aria-label="Close" onClick="{{closeSave}}" style="position: absolute; top: 12px; right: 12px; display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; color: var(--muted-fg)">
          <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg>
        </button>
        <h2 id="sv-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Save this search</h2>
''' + DIALOG_BODY.replace('__ID__', 'sv').replace('__INPUT__', 'height: 36px; padding: 0 10px; font-size: 14px; box-shadow: 0 1px 2px rgba(0,0,0,0.05)').replace('__SAVED__', 'Saved.dc.html') + '''        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <button type="button" class="ghost" onClick="{{closeSave}}" style="height: 36px; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); font-size: 14px; font-weight: 500; color: var(--fg); cursor: pointer">Cancel</button>
          <sc-if value="{{notAtLimit}}" hint-placeholder-val="{{true}}">
            <button type="submit" style="height: 36px; padding: 0 14px; border: 0; border-radius: 8px; background: var(--primary); font-size: 14px; font-weight: 500; color: var(--primary-fg); cursor: pointer">Save</button>
          </sc-if>
        </div>
      </form>
    </div>
  </sc-if>

  <!-- Toast -->
  <sc-if value="{{savedToast}}" hint-placeholder-val="{{false}}">
    <div role="status" style="position: fixed; right: 24px; bottom: 24px; z-index: 40; display: flex; align-items: center; gap: 12px; padding: 10px 10px 10px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg); box-shadow: 0 8px 24px rgba(0,0,0,0.12); font-size: 14px">
      <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--ok-fg)"><circle cx="12" cy="12" r="10"></circle><path d="m9 12 2 2 4-4"></path></svg>
      <span>Search saved.</span>
      <a href="Saved.dc.html" style="font-weight: 500">View saved searches</a>
      <button type="button" class="ghost" aria-label="Dismiss" onClick="{{hideToast}}" style="display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--muted-fg); cursor: pointer">
        <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg>
      </button>
    </div>
  </sc-if>
'''
end = '</div>\n</x-dc>'
assert s.count(end) == 1
s = s.replace(end, dialog + end)
js = JS.replace('__SOURCES__', "[{ text: 'In: ' + srcDefs.filter((x) => sources[x.id]).map((x) => x.label).join(', '), strong: false }]") \
       .replace('__CHIPS__', "chips.map((c) => ({ text: c.label, strong: false }))")
anchor = "    const navOpen = !!st.navOpen;"
assert anchor in s
s = s.replace(anchor, js + anchor, 1)
s = s.replace("      countLabel: shown.length", VALS + "      countLabel: shown.length", 1)
s = s.replace(""""$preview":{"width":1440""", """"atLimit":{"editor":"boolean","default":false},"$preview":{"width":1440""", 1)
p.write_text(s)

# ---- Phone ----
p = proj / 'SearchPhone.dc.html'
s = p.read_text()
hdr = '    </form>\n  </header>'
assert hdr in s
s = s.replace(hdr, '''    </form>
    <button type="button" class="ghost" aria-label="Save this search" aria-haspopup="dialog" onClick="{{openSave}}" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: 8px; background: transparent; color: var(--fg); flex-shrink: 0">
      <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z"></path></svg>
    </button>
  </header>''')
s = s.replace('padding: 0 12px 0 4px; border-bottom', 'padding: 0 4px; border-bottom', 1)
sheet = '''
  <sc-if value="{{saveOpen}}" hint-placeholder-val="{{false}}">
    <button type="button" tabIndex="-1" aria-label="Close" onClick="{{closeSave}}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; border: 0; padding: 0; background: rgba(0,0,0,0.5)"></button>
    <form role="dialog" aria-modal="true" aria-labelledby="psv-title" novalidate onSubmit="{{doSave}}" onKeyDown="{{saveKey}}" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; display: flex; flex-direction: column; gap: 16px; padding: 8px 16px 16px; border-radius: 16px 16px 0 0; background: var(--bg); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
      <span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip)"></span>
      <h2 id="psv-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Save this search</h2>
''' + DIALOG_BODY.replace('__ID__', 'psv').replace('__INPUT__', 'height: 44px; padding: 0 12px; font-size: 16px').replace('__SAVED__', 'SavedPhone.dc.html') + '''      <div style="display: flex; flex-direction: column; gap: 8px">
        <sc-if value="{{notAtLimit}}" hint-placeholder-val="{{true}}">
          <button type="submit" style="width: 100%; height: 44px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 15px; font-weight: 500">Save</button>
        </sc-if>
        <button type="button" class="ghost" onClick="{{closeSave}}" style="height: 44px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 15px; font-weight: 500">Cancel</button>
      </div>
    </form>
  </sc-if>
  <sc-if value="{{savedToast}}" hint-placeholder-val="{{false}}">
    <div role="status" style="position: absolute; left: 16px; right: 16px; bottom: 16px; z-index: 25; display: flex; align-items: center; gap: 10px; padding: 10px 8px 10px 14px; border-radius: 10px; background: var(--primary); color: var(--primary-fg); font-size: 14px; box-shadow: 0 8px 24px rgba(0,0,0,0.2)">
      <span style="flex: 1">Search saved.</span>
      <a href="SavedPhone.dc.html" style="color: var(--primary-fg); font-weight: 500; text-decoration: underline">View</a>
      <button type="button" aria-label="Dismiss" onClick="{{hideToast}}" style="display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; border: 0; border-radius: 6px; background: transparent; color: var(--primary-fg)">
        <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg>
      </button>
    </div>
  </sc-if>
'''
end = '</div>\n</x-dc>'
assert s.count(end) == 1
s = s.replace(end, sheet + end)
js = JS.replace('__SOURCES__', "[{ text: 'In: ' + [['metadata', 'Details'], ['notes', 'Notes'], ['documents', 'Documents']].filter((x) => sources[x[0]]).map((x) => x[1]).join(', '), strong: false }]") \
       .replace('__CHIPS__', "chips.map((c) => ({ text: c.label, strong: false }))")
anchor = "    const closeFilters = () => this.setState({ sheetOpen: false });"
assert anchor in s
s = s.replace(anchor, js + anchor, 1)
s = s.replace("      showLabel: 'Show '", VALS + "      showLabel: 'Show '", 1)
s = s.replace(""""$preview":{"width":390""", """"atLimit":{"editor":"boolean","default":false},"$preview":{"width":390""", 1)
p.write_text(s)
print('ok')
