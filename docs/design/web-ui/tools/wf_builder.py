"""Workflow builder (one definition) and Delegation of authority boards, for tools/workflows.py."""
from wf_parts import *

# ================================================================ Builder
NODE = ('''<div role="button" tabIndex="0" aria-label="{{n.aria}}" onClick="{{n.select}}" style="{{n.style}}">
                <span style="display: flex; align-items: center; justify-content: space-between; gap: 6px"><span style="font-size: 11px; font-weight: 500; color: var(--muted-fg); text-transform: uppercase; letter-spacing: 0.04em">{{n.type}}</span><span style="display: flex; gap: 4px">'''
        + '<sc-if value="{{n.added}}" hint-placeholder-val="{{false}}">' + SMALL('+ Added', 'ok') + '</sc-if>'
        + '<sc-if value="{{n.changed}}" hint-placeholder-val="{{false}}">' + SMALL('~ Changed', 'warn') + '</sc-if>'
        + '<sc-if value="{{n.reached}}" hint-placeholder-val="{{false}}"><span style="display: inline-flex; align-items: center; gap: 3px; height: 20px; padding: 0 6px; border-radius: 6px; background: var(--info-bg); color: var(--info-fg); font-size: 11px; font-weight: 500">' + svg(I['check'], 11) + 'Reached</span></sc-if>'
        + '<sc-if value="{{n.hasProblem}}" hint-placeholder-val="{{false}}">' + SMALL('1 problem', 'danger') + '</sc-if>'
        + '''</span></span>
                <span style="font-weight: 600; font-size: 14px; line-height: 18px">{{n.name}}</span>
                <span style="font-size: 12px; line-height: 16px; color: var(--text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{n.who}}</span>
                <sc-if value="{{n.hasDue}}" hint-placeholder-val="{{true}}"><span style="font-size: 11px; line-height: 14px; color: var(--muted-fg)">{{n.due}}</span></sc-if>
              </div>''')

LABEL = 'position: absolute; padding: 0 6px; border-radius: 4px; background: var(--subtle); font-size: 11px; color: var(--text-2)'
PILL = 'position: absolute; display: inline-flex; align-items: center; justify-content: center; width: 72px; height: 28px; border-radius: 14px; font-size: 12px; font-weight: 600'
EDGES = f'''<svg aria-hidden="true" width="760" height="600" viewBox="0 0 760 600" style="position: absolute; left: 0; top: 0; pointer-events: none">
              <defs><marker id="wf-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--border-strong)"></path></marker></defs>
              <g fill="none" stroke="var(--border-strong)" stroke-width="1.5" marker-end="url(#wf-arrow)">
                <path d="M380 48 V78"></path><path d="M380 160 V192"></path>
                <path d="M380 274 V300 H160 V330"></path><path d="M380 274 V300 H600 V330"></path>
                <path d="M160 412 V440 H380 V466"></path><path d="M600 412 V440 H382"></path>
                <path d="M380 548 V570"></path>
              </g>
            </svg>
            <span style="{LABEL}; left: 182px; top: 286px">If amount &gt; 5 crore</span>
            <span style="{LABEL}; left: 498px; top: 286px">Otherwise</span>
            <span style="{PILL}; left: 344px; top: 20px; background: var(--primary); color: var(--primary-fg)">Start</span>
            <span style="{PILL}; left: 344px; top: 570px; border: 1.5px solid var(--border-strong); background: var(--card)">End</span>'''

def step_panel(phone):
    label, field, text, select, check, radios = ctl(phone)
    pad = '8px 4px 8px 10px' if phone else '6px 4px 6px 10px'
    def who(t, first=False):
        return (f'<li style="display: flex; align-items: center; gap: 8px; padding: {pad}; border-top: {"0" if first else "1px solid var(--hover)"}; font-size: 13px"><span style="flex: 1">{t}</span>'
                f'<sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}">{icon_btn("Remove " + t, "x", phone)}</sc-if></li>')
    def act(a, need, first=False):
        return (f'<li style="display: flex; align-items: center; gap: 10px; padding: {"10px" if phone else "6px 10px"}; border-top: {"0" if first else "1px solid var(--hover)"}; font-size: 13px">'
                f'<input type="checkbox" checked disabled="{{{{ro}}}}" aria-label="{a}" style="width: 16px; height: 16px; accent-color: var(--primary)"><span style="flex: 1">{a}</span>'
                f'<label style="display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-fg)"><input type="checkbox"{" checked" if need else ""} disabled="{{{{ro}}}}" style="width: 14px; height: 14px; accent-color: var(--primary)">needs a remark</label></li>')
    add_btn = lambda t: f'<sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" style="align-self: flex-start; {OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "28px"))}">{svg(I["plus"], 12)}{t}</button></sc-if>'
    box = 'list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden'
    goes = (f'<div style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 14px; font-weight: 500">Goes to</span><ul style="{box}">'
            + who('The holder of Chief Financial Officer', True) + who('The holder of Deputy CFO') + who('The holder of Head of Credit') + who('The holder of Head of Corporate Banking')
            + who('Credit committee member in Head Office or above') + '</ul>' + add_btn('Add someone') + '</div>')
    decided = ('<div style="display: flex; flex-direction: column; gap: 8px">' + radios('Decided when', 'sp-mode', ['Everyone has approved', 'Anyone has approved', 'A number of them have approved'], 2)
               + f'<div style="display: flex; align-items: center; gap: 8px; padding-left: 26px"><span style="font-size: 13px; color: var(--muted-fg)">How many</span>{text("3", w="72px")}</div></div>')
    actions = (f'<div style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 14px; font-weight: 500">What the holder can do</span><ul style="{box}">'
               + act('Approve', False, True) + act('Return to the initiator', True) + act('Reject', True) + act('Ask for opinions', False)
               + '</ul><span style="font-size: 12px; color: var(--muted-fg)">Tick “needs a remark” for actions the holder must explain.</span></div>')
    row = lambda l, c: f'<div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap"><span style="font-size: 13px; color: var(--muted-fg); width: 110px">{l}</span>{c}</div>'
    units = lambda sel: select(['days', 'hours', 'minutes'], sel, w='110px')
    sla = ('<div style="display: flex; flex-direction: column; gap: 10px">' + check('This step has a deadline', True)
           + '<div style="display: flex; flex-direction: column; gap: 10px; padding-left: 26px">'
           + row('Due after', text('3', w='64px') + units(0)) + check('Count working time only (the business calendar)', True)
           + row('Remind before', text('1', w='64px') + units(0))
           + row('When it is late', select(['Nothing more', 'Tell the holder’s supervisor', 'Move the task to someone', 'Move the task one level up'], 1, w='230px'))
           + '</div></div>')
    leads = field('Leads to', select(['Tell the initiator', 'End'], 0))
    remove = (f'<sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" onClick="{{{{openRemoveStep}}}}" style="align-self: flex-start; '
              f'{OUTLINE.replace("color: var(--fg)", "color: var(--danger-fg)").replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">Remove this step</button></sc-if>')
    typ = '<div style="display: flex; align-items: center; gap: 8px; font-size: 13px"><span style="color: var(--muted-fg)">Type of step</span><span style="font-weight: 500">Parallel decision</span></div>'
    return '\n'.join([field('Name', text('Credit committee')), typ, goes, decided, actions, sla, leads, remove])

def sim_panel(phone):
    label, field, text, select, check, radios = ctl(phone)
    free = lambda s: s.replace('disabled="{{ro}}"', '')
    fields = (f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 12px">'
              + field('Started by', free(text('Tanvir Ahmed'))) + field('Memo type', free(select(['Credit proposal', 'Budget request'], 0)))
              + field('Classification rank', free(select(['0 · Internal', '1 · Restricted', '2 · Confidential', '3 · Strictly confidential'], 2)))
              + field('Facility amount (amount)', free(text('7,50,00,000'))) + '</div>')
    return T('''<section style="display: flex; flex-direction: column; gap: 12px">
            <span style="font-size: 13px; color: var(--muted-fg)">Who this workflow would reach for an item like this, started now, if everyone approves. Nothing is saved or sent.</span>
            [[fields]]
            <button type="button" onClick="{{runSim}}" style="align-self: flex-start; display: inline-flex; align-items: center; gap: 6px; [[pb]]">[[play]]Simulate</button>
            <sc-if value="{{simRan}}" hint-placeholder-val="{{true}}">
              <div style="display: flex; align-items: center; gap: 8px; padding: 10px 12px; border: 1px solid var(--ok-border); border-radius: 8px; background: var(--ok-bg); color: var(--ok-fg); font-size: 13px; font-weight: 500">[[okic]]It would be approved.</div>
              <span style="font-size: 13px; font-weight: 500">Steps it would reach, in order</span>
              <ol style="list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden"><sc-for list="{{simPath}}" as="sp" hint-placeholder-count="4"><li style="{{sp.style}}"><span style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><span style="font-weight: 500">{{sp.n}}. {{sp.name}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{sp.what}}</span></span><span style="font-size: 12px; color: var(--text-2)">{{sp.detail}}</span></li></sc-for></ol>
            </sc-if>
          </section>''', fields=fields, pb=primary(phone), play=svg(I['play'], 12), okic=svg(I['checkc'], 16))

def steps_view(phone):
    return T('''<ol aria-label="Steps, in the order they are written" style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px">
            <sc-for list="{{stepList}}" as="s" hint-placeholder-count="5"><li style="[[card]]; padding: [[pad]]; display: flex; flex-direction: column; gap: 6px">
              <span style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px"><span style="font-weight: 600">{{s.n}}. {{s.name}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{s.type}}</span><sc-if value="{{s.added}}" hint-placeholder-val="{{false}}">[[added]]</sc-if><sc-if value="{{s.changed}}" hint-placeholder-val="{{false}}">[[changed]]</sc-if></span><button type="button" class="ghost" onClick="{{s.select}}" style="flex-shrink: 0; height: [[bh]]; padding: 0 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">{{s.btn}}</button></span>
              <dl style="margin: 0; display: grid; grid-template-columns: 90px 1fr; gap: 4px 10px; font-size: 13px"><sc-for list="{{s.lines}}" as="ln" hint-placeholder-count="3"><dt style="color: var(--muted-fg)">{{ln.k}}</dt><dd style="margin: 0">{{ln.v}}</dd></sc-for></dl>
            </li></sc-for>
          </ol>''', card=CARD, pad='12px' if phone else '12px 16px', bh='40px' if phone else '28px', added=SMALL('+ Added', 'ok'), changed=SMALL('~ Changed', 'warn'))

def json_view(phone):
    return T('''<div style="display: flex; flex-direction: column; gap: 8px"><label for="wf-json" style="font-size: 14px; font-weight: 500">Definition</label>
            <textarea id="wf-json" rows="[[rows]]" disabled="{{ro}}" spellcheck="false" style="width: 100%; padding: 10px 12px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); [[mono]]; font-size: 12px; line-height: 18px; resize: vertical">{{json}}</textarea>
            <span style="font-size: 12px; color: var(--muted-fg)">The definition as JSON (schema version 2). Save keeps a draft even while it has problems.</span></div>''', rows='14' if phone else '22', mono=MONO)

CHANGES = T('''<div style="display: flex; flex-direction: column; gap: 12px">
            <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 13px"><span style="color: var(--muted-fg)">Compared with</span><span style="font-weight: 500">Version 6 · Published</span></div>
            <div style="padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--subtle); font-size: 13px">1 step added. 2 steps changed.</div>
            <span style="font-size: 13px; font-weight: 500">Changes, step by step</span>
            <ul style="list-style: none; margin: 0; padding: 0; [[card]]; overflow: hidden"><sc-for list="{{changeList}}" as="c" hint-placeholder-count="3"><li style="{{c.style}}"><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px"><span style="{{c.kindStyle}}">{{c.kind}}</span><span style="font-weight: 500">{{c.what}}</span></span><sc-for list="{{c.lines}}" as="cl" hint-placeholder-count="1"><span style="font-size: 13px; color: var(--text-2)">{{cl.t}}</span></sc-for></li></sc-for></ul>
          </div>''', card=CARD)

def builder_area():
    diagram = f'<div style="position: relative; width: 760px; height: 600px; margin: 0 auto">{EDGES}<sc-for list="{{{{nodes}}}}" as="n" hint-placeholder-count="5">{NODE}</sc-for></div>'
    ob30 = OUTLINE.replace('height: 32px', 'height: 30px')
    tools = (f'<div style="display: flex; flex-wrap: wrap; align-items: center; gap: 4px">'
             f'<sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><span role="toolbar" aria-label="Change the workflow" style="display: inline-flex; align-items: center; gap: 2px; padding-right: 6px; margin-right: 4px; border-right: 1px solid var(--border)">{icon_btn("Undo", "undo", False)}{icon_btn("Redo", "redo", False)}<button type="button" class="ghost" style="{ob30}; margin-left: 4px">{svg(I["plus"], 12)}Add step</button></span></sc-if>'
             f'<button type="button" class="ghost" onClick="{{{{check}}}}" style="{ob30}">Check</button>'
             f'<button type="button" onClick="{{{{toggleSim}}}}" aria-pressed="{{{{simAria}}}}" style="{{{{simBtnStyle}}}}">{svg(I["play"], 12)}Simulate</button></div>')
    zoom = ('<span style="position: absolute; right: 10px; bottom: 8px; display: inline-flex; gap: 2px; padding: 2px; border: 1px solid var(--border); border-radius: 8px; background: var(--card)">'
            + icon_btn('Zoom in', 'plus', False) + icon_btn('Fit the workflow to the view', 'check', False).replace(svg(I['check'], 16), '<span style="font-size: 11px; font-weight: 600">Fit</span>') + '</span>')
    dots = 'background: var(--subtle); background-image: radial-gradient(var(--border) 1px, transparent 1px); background-size: 16px 16px'
    area = f'''<div style="{CARD}; display: flex; flex-direction: column; min-width: 0; overflow: hidden">
          <div style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--border)">{tabs_markup(False, "views", "Views of this version")}{tools}</div>
          <sc-if value="{{{{hasCheck}}}}" hint-placeholder-val="{{{{false}}}}"><div role="status" style="{{{{checkStyle}}}}">{{{{checkText}}}}</div></sc-if>
          <sc-if value="{{{{viewDiagram}}}}" hint-placeholder-val="{{{{true}}}}"><div aria-label="Workflow diagram" style="position: relative; overflow: auto; padding: 16px 16px 40px; {dots}">{diagram}<span style="position: absolute; left: 12px; bottom: 12px; font-size: 11px; color: var(--muted-fg)">The Steps view shows the same workflow as a list, for keyboard and screen reader use.</span>{zoom}</div></sc-if>
          <sc-if value="{{{{viewSteps}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 16px">{steps_view(False)}</div></sc-if>
          <sc-if value="{{{{viewJson}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 16px">{json_view(False)}</div></sc-if>
          <sc-if value="{{{{viewChanges}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 16px">{CHANGES}</div></sc-if>
        </div>'''
    panel = f'''<aside aria-labelledby="panel-title" style="{CARD}; display: flex; flex-direction: column; min-width: 0; max-height: 760px; position: sticky; top: 16px">
          <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px 10px 16px; border-bottom: 1px solid var(--border)"><h2 id="panel-title" style="margin: 0; font-size: 14px; font-weight: 600">{{{{panelTitle}}}}</h2>{icon_btn("Close", "x", False, "{{closePanel}}")}</div>
          <div style="flex: 1; min-height: 0; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 16px">
            <sc-if value="{{{{panelStep}}}}" hint-placeholder-val="{{{{true}}}}">{step_panel(False)}</sc-if>
            <sc-if value="{{{{panelSim}}}}" hint-placeholder-val="{{{{false}}}}">{sim_panel(False)}</sc-if>
            <sc-if value="{{{{panelNone}}}}" hint-placeholder-val="{{{{false}}}}"><p style="margin: 0; font-size: 13px; color: var(--muted-fg)">Select a step in the diagram to change it, or use the Steps view.</p></sc-if>
          </div>
        </aside>'''
    return f'<div style="display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start">{area}{panel}</div>'

MIGRATION = T('''<sc-if value="{{isPublished}}" hint-placeholder-val="{{false}}"><section aria-labelledby="mig-title" style="[[card]]; display: flex; flex-direction: column">
        <div style="display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 14px 16px"><span style="display: flex; flex-direction: column; gap: 2px; max-width: 720px"><h2 id="mig-title" style="margin: 0; font-size: 15px; font-weight: 600">Memos on older versions</h2><span style="font-size: 13px; color: var(--muted-fg)">Memos keep the version they started on. You can move those whose current step still exists to the published version.</span></span><span style="display: flex; gap: 8px"><button type="button" class="ghost" style="[[outline]]">Find memos</button><button type="button" style="display: inline-flex; align-items: center; height: 32px; padding: 0 12px; border: 0; border-radius: 8px; background: var(--primary); color: var(--primary-fg); font-size: 13px; font-weight: 500; cursor: pointer">Move selected</button></span></div>
        <table style="width: 100%; border-collapse: collapse; font-size: 14px"><caption style="[[sr]]">Running memos on other versions</caption>
          <thead><tr style="border-top: 1px solid var(--border); border-bottom: 1px solid var(--border)"><th scope="col" style="width: 40px; height: 36px; padding-left: 16px"><span style="[[sr]]">Select</span></th><th scope="col" style="[[th]]">Memo</th><th scope="col" style="[[th]]; width: 120px">On version</th><th scope="col" style="[[th]]; width: 200px">At</th><th scope="col" style="[[th]]; width: 300px">Can move</th></tr></thead>
          <tbody><sc-for list="{{migration}}" as="m" hint-placeholder-count="3"><tr style="border-bottom: 1px solid var(--hover)">
            <td style="padding: 8px 0 8px 16px"><input type="checkbox" checked="{{m.checked}}" disabled="{{m.cannot}}" aria-label="{{m.selectLabel}}" style="width: 16px; height: 16px; accent-color: var(--primary)"></td>
            <td style="padding: 8px 12px"><span style="display: flex; flex-direction: column"><span style="font-weight: 500">{{m.subject}}</span><span style="[[mono]]; font-size: 12px; color: var(--muted-fg)">{{m.ref}}</span></span></td>
            <td style="padding: 8px 12px">{{m.from}}</td><td style="padding: 8px 12px; color: var(--text-2)">{{m.at}}</td><td style="padding: 8px 12px; {{m.canStyle}}">{{m.can}}</td>
          </tr></sc-for></tbody></table>
      </section></sc-if>''', card=CARD, outline=OUTLINE, sr=SR, th=TH, mono=MONO)

def version_select(phone, list_key='versions'):
    return (f'<span style="position: relative; display: flex; align-items: center; width: {"100%" if phone else "240px"}"><select aria-label="Version" style="width: 100%; height: {"44px" if phone else "32px"}; padding: 0 30px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {"16px" if phone else "13px"}; font-weight: 500; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">'
            f'<sc-for list="{{{{{list_key}}}}}" as="v" hint-placeholder-count="4"><option selected="{{{{v.sel}}}}">{{{{v.label}}}}</option></sc-for></select>{svg(I["chev"], 14, " style=\"position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none\"")}</span>')

REMARK_BOX = '<sc-if value="{{hasRemark}}" hint-placeholder-val="{{false}}"><div style="display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border: 1px solid var(--warn-border); border-radius: 8px; background: var(--warn-bg); font-size: 13px"><strong style="font-weight: 600; color: var(--warn-fg)">Reviewer’s remark</strong><span>{{remark}}</span></div></sc-if>'
KEYCHIP = f'<span style="display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); {MONO}; font-size: 12px; color: var(--text-2)">credit-approval</span>'

def builder_bar(phone):
    g1, g2 = ('; flex: 1', '; flex: 2') if phone else ('', '')
    draft = (f'<button type="button" class="ghost" onClick="{{{{save}}}}" style="{obtn(phone, g1)}">Save draft</button>'
             + ('' if phone else f'<button type="button" class="ghost" onClick="{{{{check}}}}" style="{obtn(phone)}">Check</button>')
             + f'<button type="button" onClick="{{{{submit}}}}" style="{primary(phone)}{g2}">Submit for review</button>')
    pub = (f'<button type="button" class="ghost" style="{obtn(phone, g1)}">Export</button>'
           f'<button type="button" class="ghost" onClick="{{{{openRetire}}}}" style="{obtn(phone, g1).replace("color: var(--fg)", "color: var(--danger-fg)")}">Retire</button>'
           f'<button type="button" onClick="{{{{newDraft}}}}" style="{primary(phone)}{g1}">New draft</button>')
    return review_bar(phone, draft, pub)

BUILDER_DESK = f'''    <main style="flex: 1; padding: 20px 24px 24px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      <a href="Workflows.dc.html" style="align-self: flex-start; display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--muted-fg); text-decoration: none">{svg(I["back"], 14)}All workflows</a>
      <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: -8px"><h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em">Credit approval</h1>{KEYCHIP}</div>
      <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 12px">{version_select(False)}<span style="{{{{stateChip}}}}">{{{{stateLabel}}}}</span><span style="font-size: 13px; color: var(--muted-fg)">{{{{versionNote}}}}</span></div>
      {REMARK_BOX}
      {builder_area()}
      {MIGRATION}
      {builder_bar(False)}
    </main>
  </div>
'''

PHONE_VIEWS = f'''<sc-if value="{{{{viewSteps}}}}" hint-placeholder-val="{{{{true}}}}">{steps_view(True)}</sc-if>
    <sc-if value="{{{{viewDiagram}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 16px; border: 1px dashed var(--border); border-radius: 10px; font-size: 13px; color: var(--muted-fg); text-align: center">The diagram needs a wider screen. The Steps view shows the same workflow as a list.</div></sc-if>
    <sc-if value="{{{{viewJson}}}}" hint-placeholder-val="{{{{false}}}}">{json_view(True)}</sc-if>
    <sc-if value="{{{{viewChanges}}}}" hint-placeholder-val="{{{{false}}}}">{CHANGES}</sc-if>'''

BUILDER_PHONE = f'''  <main class="ed-main" style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 16px 16px 24px">
    <div style="display: flex; flex-direction: column; gap: 6px"><h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Credit approval</h1><span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px">{KEYCHIP}<span style="{{{{stateChip}}}}">{{{{stateLabel}}}}</span></span></div>
    <div style="display: flex; flex-direction: column; gap: 6px">{version_select(True)}<span style="font-size: 13px; color: var(--muted-fg)">{{{{versionNote}}}}</span></div>
    {REMARK_BOX}
    {tabs_markup(True, "views", "Views of this version")}
    <div style="display: flex; gap: 8px"><button type="button" class="ghost" onClick="{{{{check}}}}" style="{obtn(True)}; flex: 1">Check</button><button type="button" class="ghost" onClick="{{{{toggleSim}}}}" style="{obtn(True)}; flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 6px">{svg(I["play"], 12)}Simulate</button></div>
    <sc-if value="{{{{hasCheck}}}}" hint-placeholder-val="{{{{false}}}}"><div role="status" style="{{{{checkStyle}}}}">{{{{checkText}}}}</div></sc-if>
    {PHONE_VIEWS}
  </main>
  {builder_bar(True)}
'''

def builder_dialogs(phone):
    out = f'''
  <sc-if value="{{{{isRemark}}}}" hint-placeholder-val="{{{{false}}}}">
    {remark_dialog(phone)}
  </sc-if>
  <sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}">
    {confirm_dialog(phone)}
  </sc-if>
'''
    if phone:
        close = lambda style: f'<div style="display: flex; gap: 8px"><button type="button" onClick="{{{{closeDlg}}}}" style="{style}; flex: 1">Close</button></div>'
        out += f'''  <sc-if value="{{{{phoneStep}}}}" hint-placeholder-val="{{{{false}}}}">
    {frame_scroll('<h2 id="ps-title" style="margin: 0; font-size: 18px; font-weight: 600">Credit committee</h2>', step_panel(True), close(primary(True)), 'ps-title', 'dialog', 'noop', True)}
  </sc-if>
  <sc-if value="{{{{phoneSim}}}}" hint-placeholder-val="{{{{false}}}}">
    {frame_scroll('<h2 id="sm-title" style="margin: 0; font-size: 18px; font-weight: 600">Simulate</h2>', sim_panel(True), close(obtn(True)), 'sm-title', 'dialog', 'noop', True)}
  </sc-if>
'''
    return out + toast(phone)

BUILDER_JS = r"""
    const APPROVE_LEAD = 'New memos use it from now on; the version published before is retired. You may be asked to confirm it is you.';
    const RETIRE_LEAD = 'New memos cannot use this workflow any more. Memos already on it keep running.';
    const REMARK_REQUIRED = 'Say what needs to change.';
    const RK_ALERTS = { approve: { samePerson: 'Someone other than the person who submitted it has to review it.', conflict: 'Someone changed this version meanwhile. It was reloaded.' }, retire: { inUse: 'A memo type still uses this workflow.' }, sendBack: {} };
    const state = st.state || this.props.state || 'review';
    const isDraft = state === 'draft', isReview = state === 'review', isPub = state === 'published';
    const editable = isDraft;
    const view = st.view || (this.props.view && this.props.view !== 'auto' ? this.props.view : (isReview ? 'changes' : (PHONE ? 'steps' : 'diagram')));
    const panel = st.panel !== undefined ? st.panel : (this.props.panel || 'step');
    const sel = st.sel || 'committee';
    const simOn = panel === 'sim';
    const ND = [
      { id: 'line', x: 270, y: 78, type: 'Review', name: 'Line manager review', who: 'The initiator’s supervisor', due: 'Due in 2 working days', changed: true },
      { id: 'size', x: 270, y: 192, type: 'Branch', name: 'Size of the facility', who: 'If amount > 5 crore · Otherwise', due: '', changed: true },
      { id: 'committee', x: 50, y: 330, type: 'Parallel decision', name: 'Credit committee', who: '3 of 5: CFO, Deputy CFO, Head of Credit…', due: 'Due in 3 working days', added: true },
      { id: 'doa', x: 490, y: 330, type: 'Delegation of authority', name: 'Approval by authority', who: 'The approval groups of the delegation of authority', due: 'Due in 3 working days' },
      { id: 'tell', x: 270, y: 466, type: 'Notify', name: 'Tell the initiator', who: 'Tells the initiator: creditDecided', due: '' }
    ];
    const reached = ['line', 'size', 'committee', 'tell'];
    const problem = err === 'invalid' ? 'doa' : '';
    const nodes = ND.map((n, i) => ({ name: n.name, type: n.type, who: n.who, due: n.due, hasDue: !!n.due, added: !!n.added && !isPub, changed: !!n.changed && !isPub, reached: simOn && reached.indexOf(n.id) >= 0, hasProblem: problem === n.id,
      select: () => this.setState({ sel: n.id, panel: 'step' }),
      aria: 'Step ' + (i + 1) + ' of ' + ND.length + ', ' + n.type + ', ' + n.name + '.',
      style: 'position: absolute; left: ' + n.x + 'px; top: ' + n.y + 'px; width: 220px; box-sizing: border-box; display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; border-radius: 10px; background: var(--card); cursor: pointer; box-shadow: 0 1px 3px rgba(0,0,0,0.08); '
        + (problem === n.id ? 'border: 2px solid var(--destructive)' : (sel === n.id && panel === 'step' ? 'border: 2px solid var(--fg)' : (simOn && reached.indexOf(n.id) >= 0 ? 'border: 2px solid var(--info-fg)' : 'border: 1px solid var(--border-strong)'))) }));
    const L = (k, v) => ({ k: k, v: v });
    const stepList = [
      { name: 'Line manager review', type: 'Review', changed: true, lines: [L('Goes to', 'The initiator’s supervisor'), L('Deadline', 'Due in 2 working days, reminder 1 day before'), L('Leads to', 'Size of the facility')] },
      { name: 'Size of the facility', type: 'Branch', changed: true, lines: [L('If', 'amount > 50000000 → Credit committee'), L('Otherwise', 'Approval by authority')] },
      { name: 'Credit committee', type: 'Parallel decision', added: true, lines: [L('Goes to', '3 of: CFO, Deputy CFO, Head of Credit, Head of Corporate Banking, Credit committee members'), L('Deadline', 'Due in 3 working days; the supervisor is told 1 day late'), L('Leads to', 'Tell the initiator')] },
      { name: 'Approval by authority', type: 'Delegation of authority', lines: [L('Goes to', 'The approval groups of the delegation of authority'), L('Deadline', 'Due in 3 working days'), L('Leads to', 'Tell the initiator')] },
      { name: 'Tell the initiator', type: 'Notify', lines: [L('Tells', 'The initiator'), L('Message', 'creditDecided'), L('Leads to', 'End')] }
    ].map((s, i) => Object.assign(s, { n: String(i + 1), added: !!s.added && !isPub, changed: !!s.changed && !isPub, btn: editable ? 'Change' : 'Details', select: () => this.setState({ sel: 'committee', panel: 'step', dlg: PHONE ? 'step' : '' }) }));
    const CH = (kind, what, lines, i) => ({ kind: kind, what: what, kindStyle: TONE[kind === 'Added' ? 'ok' : (kind === 'Removed' ? 'danger' : 'warn')], lines: lines.map((t) => ({ t: t })),
      style: 'display: flex; flex-direction: column; gap: 4px; padding: 12px 14px' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') });
    const changeList = [CH('Added', 'Credit committee (Parallel decision)', ['3 of: CFO, Deputy CFO, Head of Credit, Head of Corporate Banking, Credit committee members', 'Reached when the amount is over 5 crore'], 0),
      CH('Changed', 'Size of the facility', ['Condition: amount > 30000000 → amount > 50000000', 'If it holds: Approval by authority → Credit committee'], 1),
      CH('Changed', 'Line manager review', ['Deadline: Due in 3 working days → Due in 2 working days'], 2)];
    const json = '{\n  "schemaVersion": 2,\n  "start": "line",\n  "steps": [\n    { "id": "line", "type": "review", "name": "Line manager review",\n      "assignee": { "kind": "supervisor", "levels": 1 },\n      "sla": { "due": "P2D", "workingTime": true, "remindBefore": "P1D" },\n      "next": "size" },\n    { "id": "size", "type": "branch", "name": "Size of the facility",\n      "when": [ { "if": "item.amount > 50000000", "next": "committee" } ],\n      "otherwise": "doa" },\n    { "id": "committee", "type": "parallel", "name": "Credit committee",\n      "mode": { "kind": "n", "n": 3 }, ...\n';
    const views = mkTabs([['diagram', 'Diagram'], ['steps', 'Steps'], ['json', 'JSON']].concat(isPub ? [] : [['changes', 'Changes', 3]]), view, 'view');
    const checkRan = !!st.checked || err === 'invalid';
    const BAR = { invalid: 'Fix the problems first.', draftExists: 'There is already a draft or a version in review.', conflict: 'Someone changed this version meanwhile. It was reloaded.' };
    const barAlert = isDraft ? (BAR[err] || '') : '';
    const V = isPub ? [['Version 6 · Published', true], ['Version 5 · Retired', false], ['Version 4 · Retired', false]] : [['Version 7 · ' + (isDraft ? 'Draft' : 'In review'), true], ['Version 6 · Published', false], ['Version 5 · Retired', false]];
    const MIG = [['CR/2026/0118', 'Term loan, Rupsha Cold Storage', 'Version 5', 'Line manager review', ''], ['CR/2026/0121', 'Overdraft limit, Karnaphuli Traders', 'Version 5', 'Approval by authority', ''], ['CR/2026/0097', 'Restricted memo', 'Version 4', 'Risk sign-off', 'Its current step is not in the new version']];
    const vals = Object.assign(base, {
      editable: editable, ro: !editable, isDraft: isDraft, isReview: isReview, isPublished: isPub, showBar: true,
      versions: V.map((v) => ({ label: v[0], sel: v[1] })), stateLabel: isPub ? 'Published' : (isDraft ? 'Draft' : 'In review'), stateChip: TONE[isPub ? 'ok' : (isDraft ? 'neutral' : 'warn')],
      versionNote: isReview ? 'Submitted by Shirin Akter, 17 Nov.' : (isDraft ? 'Draft by Shirin Akter, sent back on 16 Nov.' : 'Published 2 Sep 2026; 14 memos run on it.'),
      hasRemark: isDraft, remark: 'Keep the line manager’s deadline at 2 working days, and let the committee ask for opinions.',
      views: views, viewDiagram: view === 'diagram', viewSteps: view === 'steps', viewJson: view === 'json', viewChanges: view === 'changes',
      nodes: nodes, stepList: stepList, changeList: changeList, json: json,
      panelStep: panel === 'step', panelSim: simOn, panelNone: panel === 'none', panelTitle: simOn ? 'Simulate' : 'Selected step',
      closePanel: () => this.setState({ panel: 'none' }), toggleSim: () => PHONE ? this.setState({ dlg: 'sim' }) : this.setState({ panel: simOn ? 'step' : 'sim' }),
      simAria: simOn ? 'true' : 'false', simBtnStyle: 'display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 12px; border-radius: 8px; font-size: 13px; font-weight: 500; cursor: pointer; ' + (simOn ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: var(--fg)'),
      runSim: () => this.setState({ simRan: true }), simRan: st.simRan !== undefined ? st.simRan : true,
      simPath: [['Line manager review', 'Decided', 'Farhana Rahman'], ['Size of the facility', 'Branch', 'Condition 1 held: amount > 50000000'], ['Credit committee', 'Decided', 'Rafiq Chowdhury, Karim Reza, Farhana Rahman'], ['Tell the initiator', 'Told', 'Tanvir Ahmed']]
        .map((p, i) => ({ n: String(i + 1), name: p[0], what: p[1], detail: p[2], style: 'display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; font-size: 13px' + (i > 0 ? '; border-top: 1px solid var(--hover)' : '') })),
      check: () => this.setState({ checked: true }), hasCheck: checkRan, checkText: err === 'invalid' ? '1 problem found. Approval by authority: the delegation of authority has no version in force for Credit proposal.' : 'No problems found.',
      checkStyle: 'margin: 0; padding: 8px 12px; font-size: 13px; ' + (err === 'invalid' ? 'background: var(--danger-bg); color: var(--danger-fg); border-bottom: 1px solid var(--danger-border)' : 'background: var(--ok-bg); color: var(--ok-fg); border-bottom: 1px solid var(--ok-border)') + (PHONE ? '; border-radius: 8px' : ''),
      draftNote: 'Unsaved changes. Save the draft to keep them.', reviewNote: 'Check the changes and simulate it, then publish it or send it back.', publishedNote: 'Only a draft can change. Start a new draft to change this workflow.',
      save: () => this.setState({ toast: 'Saved.' }), submit: () => { if (barAlert) { this.setState({ err: err }); return; } this.setState({ state: 'review', toast: 'Submitted for review.' }); },
      newDraft: () => this.setState({ state: 'draft', toast: 'Version 7 started as a draft.' }),
      openApprove: () => this.setState({ dlg: 'approve', err: this.props.error || 'none', form: {} }), openSendBack: () => this.setState({ dlg: 'sendBack', err: 'none', form: {} }), openRetire: () => this.setState({ dlg: 'retire', err: this.props.error || 'none', form: {} }),
      openRemoveStep: () => this.setState({ dlg: 'removeStep' }),
      isRemark: dlg === 'approve' || dlg === 'sendBack' || dlg === 'retire', isConfirm: dlg === 'removeStep',
      phoneStep: PHONE && dlg === 'step', phoneSim: PHONE && dlg === 'sim', noop: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); },
      bar: { hasAlert: !!barAlert, alert: barAlert },
      migration: MIG.map((m) => ({ ref: m[0], subject: m[1], from: m[2], at: m[3], can: m[4] || 'Yes', cannot: !!m[4], checked: !m[4], canStyle: m[4] ? 'color: var(--muted-fg)' : 'color: var(--ok-fg); font-weight: 500', selectLabel: 'Select ' + m[0] })),
      cf: { title: 'Remove “Credit committee”?', explain: 'What led to this step will lead where it led, when that is one place. You can undo this until you save.', confirm: 'Remove', cancel: 'Cancel', btnStyle: okStyle(true), hasDetail: false, detail: '', detailSub: '', hasAlert: false, alert: '',
        submit: (ev) => { if (ev && ev.preventDefault) ev.preventDefault(); this.setState({ dlg: '', toast: 'Credit committee removed.' }); } }
    });
""" + REMARK_JS
BUILDER_PROPS = '"state":{"editor":"enum","options":["review","draft","published"],"default":"review"},"view":{"editor":"enum","options":["auto","diagram","steps","json","changes"],"default":"auto"},"panel":{"editor":"enum","options":["step","sim","none"],"default":"step"},"dialog":{"editor":"enum","options":["none","approve","sendBack","retire","removeStep","step","sim"],"default":"none"},"error":{"editor":"enum","options":["none","invalid","required","samePerson","inUse","conflict","draftExists"],"default":"none"},'

# ================================================================ Delegation of authority
def rule_card(phone):
    label, field, text, select, check, radios = ctl(phone)
    group = '''<li style="display: flex; flex-direction: column; gap: 6px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--card)">
                  <span style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><span style="font-size: 13px; font-weight: 600">{{g.title}}</span><span style="font-size: 12px; color: var(--muted-fg)">{{g.needs}}</span></span>
                  <span style="display: flex; flex-wrap: wrap; gap: 6px"><sc-for list="{{g.members}}" as="gm" hint-placeholder-count="2"><span style="display: inline-flex; align-items: center; gap: 6px; height: 26px; padding: 0 8px; border-radius: 6px; border: 1px solid var(--border); font-size: 12px"><span>{{gm.name}}</span><span style="color: var(--muted-fg)">{{gm.holder}}</span></span></sc-for></span>
                </li>'''
    edit = (f'<div style="display: grid; grid-template-columns: {"1fr" if phone else "1fr 1fr"}; gap: 12px">' + field('Name', text('Large credit'))
            + field('Memo type', select(['Any memo type', 'Credit proposal', 'Capital expenditure', 'Procurement'], 1))
            + field('Initiator’s unit', select(['Any unit', 'Head Office', 'Credit Risk', 'Corporate Banking'], 0))
            + '<div style="display: flex; align-items: flex-end; padding-bottom: 6px">' + check('Include the units below it', True) + '</div></div>'
            + field('Condition', text('item.amount > 50000000', mono=True), 'For example item.classificationRank >= 2. Leave empty for every memo.'))
    tb = f'height: {"44px" if phone else "28px"}; padding: 0 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer; margin-left: 4px'
    add_group = f'<sc-if value="{{{{r.open}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" class="ghost" style="align-self: flex-start; {OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "28px"))}">{svg(I["plus"], 12)}Add group</button></sc-if>'
    return (f'<li style="{{{{r.style}}}}">'
            f'<div style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><h4 style="margin: 0; font-size: 14px; font-weight: 600">{{{{r.title}}}}</h4>'
            f'<sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><span style="display: flex">{icon_btn("{{r.upLabel}}", "up", phone)}{icon_btn("{{r.downLabel}}", "down", phone)}{icon_btn("{{r.removeLabel}}", "trash", phone)}<button type="button" class="ghost" onClick="{{{{r.toggle}}}}" style="{tb}">{{{{r.toggleLabel}}}}</button></span></sc-if></div>'
            f'<sc-if value="{{{{r.collapsed}}}}" hint-placeholder-val="{{{{true}}}}"><dl style="margin: 0; display: grid; grid-template-columns: {"100px" if phone else "130px"} 1fr; gap: 4px 12px; font-size: 13px"><sc-for list="{{{{r.lines}}}}" as="ln" hint-placeholder-count="3"><dt style="color: var(--muted-fg)">{{{{ln.k}}}}</dt><dd style="margin: 0; {{{{ln.style}}}}">{{{{ln.v}}}}</dd></sc-for></dl></sc-if>'
            f'<sc-if value="{{{{r.open}}}}" hint-placeholder-val="{{{{false}}}}">{edit}</sc-if>'
            f'<div style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 13px; font-weight: 500">Approval groups, in order</span><ol style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px"><sc-for list="{{{{r.groups}}}}" as="g" hint-placeholder-count="2">{group}</sc-for></ol>{add_group}</div>'
            '</li>')

def doa_main(phone):
    label, field, text, select, check, radios = ctl(phone)
    free = lambda s: s.replace('disabled="{{ro}}"', '')
    versions = '' if phone else T('''<div style="[[card]]; overflow-x: auto"><table style="width: 100%; border-collapse: collapse; font-size: 14px"><caption style="[[sr]]">Versions, newest first</caption>
        <thead><tr style="border-bottom: 1px solid var(--border)"><th scope="col" style="height: 36px; [[th0]]; width: 120px">Version</th><th scope="col" style="[[th]]; width: 180px">State</th><th scope="col" style="[[th]]; width: 160px">Takes effect</th><th scope="col" style="[[th]]">Submitted by</th><th scope="col" style="[[th]]">Reviewed by</th><th scope="col" style="width: 96px"><span style="[[sr]]">Actions</span></th></tr></thead>
        <tbody><sc-for list="{{dversions}}" as="v" hint-placeholder-count="3"><tr style="{{v.rowStyle}}"><td style="height: 40px; padding: 0 16px; font-weight: 500">{{v.n}}</td><td style="padding: 0 12px"><span style="{{v.chip}}">{{v.state}}</span></td><td style="padding: 0 12px; white-space: nowrap">{{v.eff}}</td><td style="padding: 0 12px; color: var(--text-2)">{{v.by}}</td><td style="padding: 0 12px; color: var(--text-2)">{{v.rev}}</td><td style="padding: 0 12px 0 0; text-align: right"><button type="button" onClick="{{v.open}}" style="{{v.btnStyle}}">{{v.btn}}</button></td></tr></sc-for></tbody></table></div>''',
        card=CARD, sr=SR, th0=TH.replace('0 12px', '0 16px', 1), th=TH)
    eff_in = (f'<input type="text" value="{{{{effective}}}}" disabled="{{{{ro}}}}" aria-invalid="{{{{effInvalid}}}}" style="width: {"100%" if phone else "200px"}; height: {H(phone)}; padding: 0 10px; box-sizing: border-box; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {FS(phone)}; outline: none; border: 1px solid {{{{effBorder}}}}">'
              '<sc-if value="{{effHasErr}}" hint-placeholder-val="{{false}}"><span style="font-size: 12px; color: var(--danger-fg)">{{effErr}}</span></sc-if>')
    eff = field('Takes effect on', eff_in, 'Today or later. Memos started before then keep the version in force at their start.')
    add_rule = f'<sc-if value="{{{{editable}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" class="ghost" style="align-self: flex-start; {OUTLINE.replace("height: 32px", "height: " + ("40px" if phone else "32px"))}">{svg(I["plus"], 14)}Add rule</button></sc-if>'
    version = f'''<section aria-labelledby="dv-title" style="{CARD}; padding: 16px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
          <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 10px"><h2 id="dv-title" style="margin: 0; font-size: 15px; font-weight: 600">{{{{vTitle}}}}</h2><span style="{{{{stateChip}}}}">{{{{stateLabel}}}}</span><span style="font-size: 13px; color: var(--muted-fg)">{{{{versionNote}}}}</span></div>
          {REMARK_BOX}
          <sc-if value="{{{{hasProblems}}}}" hint-placeholder-val="{{{{false}}}}"><div role="alert" style="padding: 10px 12px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 13px"><strong style="font-weight: 600">The version has problems:</strong> Rule 4 · Everything else: group 1 has no members.</div></sc-if>
          {eff}
          <div style="display: flex; flex-direction: column; gap: 4px"><h3 style="margin: 0; font-size: 14px; font-weight: 600">Rules</h3><span style="font-size: 13px; color: var(--muted-fg)">The first rule that fits a memo applies. Order them from the most specific to the most general.</span></div>
          <ol style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px"><sc-for list="{{{{rules}}}}" as="r" hint-placeholder-count="4">{rule_card(phone)}</sc-for></ol>
          {add_rule}
        </section>'''
    simf = ('<div style="display: flex; flex-direction: column; gap: 12px">' + field('Memo type', free(select(['Credit proposal', 'Capital expenditure', 'Procurement'], 0)))
            + field('Initiator', free(text('Tanvir Ahmed'))) + field('Classification rank', free(select(['0', '1', '2', '3'], 2, w='120px')))
            + field('Version', free(select(['The one in force today', 'Version 5 (in review)'], 0))) + '</div>')
    grp = lambda n, t, who: f'<li style="padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; font-size: 13px"><strong style="font-weight: 600">Group {n}</strong> · {t}<span style="display: block; color: var(--text-2)">{who}</span></li>'
    sim = f'''<section aria-labelledby="ds-title" style="{CARD}; padding: 16px; display: flex; flex-direction: column; gap: 12px{"" if phone else "; position: sticky; top: 16px"}">
          <div style="display: flex; flex-direction: column; gap: 4px"><h2 id="ds-title" style="margin: 0; font-size: 15px; font-weight: 600">Who approves this?</h2><span style="font-size: 13px; color: var(--muted-fg)">Try a memo against a version: who would have to approve it, group by group, today.</span></div>
          {simf}
          <button type="button" onClick="{{{{simulate}}}}" style="align-self: flex-start; {primary(phone)}">Simulate</button>
          <div style="display: flex; flex-direction: column; gap: 8px; padding-top: 12px; border-top: 1px solid var(--border)"><span style="font-size: 13px; font-weight: 600">Version 4, rule “Credit”</span>
            <ol style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px">{grp(1, "1 approval from: Head of Credit", "Farhana Rahman · Nusrat Jahan acts for her until 24 Nov")}{grp(2, "1 approval from: Chief Financial Officer", "Rafiq Chowdhury")}</ol></div>
        </section>'''
    if phone:
        return f'{version}{sim}'
    return f'{versions}<div style="display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: 16px; align-items: start">{version}{sim}</div>'

def doa_bar(phone):
    g1, g2 = ('; flex: 1', '; flex: 2') if phone else ('', '')
    return review_bar(phone, f'<button type="button" class="ghost" onClick="{{{{save}}}}" style="{obtn(phone, g1)}">Save draft</button><button type="button" onClick="{{{{submit}}}}" style="{primary(phone)}{g2}">Submit for review</button>',
                      f'<button type="button" onClick="{{{{newDraft}}}}" style="{primary(phone)}{g1}">New draft</button>')

DOA_LEAD = 'Rules say who must approve which memos, group by group. A new version takes effect on its date once someone other than its author approves it; memos keep the version in force when they started.'
DOA_DESK = f'''    <main style="flex: 1; padding: 20px 24px 24px; display: flex; flex-direction: column; gap: 16px; min-width: 0">
      {nav("Delegation of authority", False)}
{title_only("Delegation of authority", DOA_LEAD, False)}
      {doa_main(False)}
      {doa_bar(False)}
    </main>
  </div>
'''
DOA_PHONE = f'''  <main class="ed-main" style="flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column">
    {nav("Delegation of authority", True)}
    <div style="display: flex; flex-direction: column; gap: 14px; padding: 12px 16px 24px">
      <div style="display: flex; flex-direction: column; gap: 2px"><h1 style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">Delegation of authority</h1><span style="font-size: 13px; color: var(--muted-fg)">Who must approve which memos, group by group.</span></div>
      {version_select(True, "dversions")}
      {doa_main(True)}
    </div>
  </main>
  {doa_bar(True)}
'''

def doa_dialogs(phone):
    return f'''
  <sc-if value="{{{{isRemark}}}}" hint-placeholder-val="{{{{false}}}}">
    {remark_dialog(phone)}
  </sc-if>
''' + toast(phone)

DOA_JS = r"""
    const APPROVE_LEAD = 'It takes effect on 1 Dec 2026. Memos started before then keep version 4. You may be asked to confirm it is you.';
    const RETIRE_LEAD = '';
    const REMARK_REQUIRED = 'Required to send back.';
    const RK_ALERTS = { approve: { samePerson: 'Someone other than the submitter has to review it.', wrongState: 'The version has moved on meanwhile.' }, sendBack: {} };
    const state = st.state || this.props.state || 'review';
    const isDraft = state === 'draft', isReview = state === 'review', isPub = state === 'published';
    const editable = isDraft;
    const openRule = st.openRule !== undefined ? st.openRule : (isDraft ? 0 : -1);
    const G = (title, needs, members) => ({ title: title, needs: needs, members: members.map((m) => ({ name: m[0], holder: m[1] })) });
    const RULES = [
      { name: 'Large credit', lines: [['Memo type', 'Credit proposal'], ['Initiator’s unit', 'Any unit'], ['Condition', 'item.amount > 50000000']], groups: [G('Group 1', '1 approval', [['Head of Credit', 'Farhana Rahman']]), G('Group 2', '2 approvals', [['Chief Financial Officer', 'Rafiq Chowdhury'], ['Deputy CFO', 'Karim Reza'], ['Head of Corporate Banking', 'Mahmud Karim']])] },
      { name: 'Credit', lines: [['Memo type', 'Credit proposal'], ['Initiator’s unit', 'Any unit'], ['Condition', 'Every memo']], groups: [G('Group 1', '1 approval', [['Head of Credit', 'Farhana Rahman']]), G('Group 2', '1 approval', [['Chief Financial Officer', 'Rafiq Chowdhury']])] },
      { name: 'Capital expenditure', lines: [['Memo type', 'Capital expenditure'], ['Initiator’s unit', 'Any unit'], ['Condition', 'item.amount > 10000000']], groups: [G('Group 1', '1 approval', [['Chief Financial Officer', 'Rafiq Chowdhury']])] },
      { name: 'Everything else', lines: [['Memo type', 'Any memo type'], ['Initiator’s unit', 'Head Office, with the units below it'], ['Condition', 'Every memo']], groups: [G('Group 1', '1 approval', [['Unit head (initiator’s unit or above)', '']])] }
    ];
    const rules = RULES.map((r, i) => ({ title: 'Rule ' + (i + 1) + ' · ' + r.name, lines: r.lines.map((l) => ({ k: l[0], v: l[1], style: l[0] === 'Condition' && l[1] !== 'Every memo' ? "font-family: 'Geist Mono', ui-monospace, monospace; font-size: 12px" : '' })), groups: r.groups,
      open: openRule === i, collapsed: openRule !== i, toggle: () => this.setState({ openRule: openRule === i ? -1 : i }), toggleLabel: openRule === i ? 'Done' : 'Change',
      upLabel: 'Move Rule ' + (i + 1) + ' up', downLabel: 'Move Rule ' + (i + 1) + ' down', removeLabel: 'Remove Rule ' + (i + 1),
      style: 'display: flex; flex-direction: column; gap: 12px; padding: ' + (PHONE ? '12px' : '14px 16px') + '; border: 1px solid ' + (openRule === i ? 'var(--border-strong)' : 'var(--border)') + '; border-radius: 10px; background: ' + (openRule === i ? 'var(--card)' : 'var(--subtle)') }));
    const btnS = (on) => 'height: 28px; padding: 0 10px; border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer; ' + (on ? 'border: 1px solid var(--primary); background: var(--primary); color: var(--primary-fg)' : 'border: 1px solid var(--border); background: var(--bg); color: var(--fg)');
    const cur = isPub ? 'v4' : 'v5';
    const DV = [['v5', 'Version 5', isPub ? '' : (isDraft ? 'Draft' : 'In review'), '1 Dec 2026', 'Shirin Akter', '—'], ['v4', 'Version 4', 'Published', '1 Jul 2026', 'Shirin Akter', 'Arif Rahman'], ['v3', 'Version 3', 'Retired', '1 Jan 2026', 'Arif Rahman', 'Shirin Akter']].filter((v) => v[2]);
    const tone = { Draft: 'neutral', 'In review': 'warn', Published: 'ok', Retired: 'neutral' };
    const effErr = err === 'dateTaken' ? 'Another version takes effect on that date.' : '';
    const vals = Object.assign(base, {
      editable: editable, ro: !editable, isDraft: isDraft, isReview: isReview, isPublished: isPub, showBar: true,
      dversions: DV.map((v) => ({ n: v[1], state: v[2] + (v[0] === 'v4' ? ' · in force' : ''), chip: TONE[tone[v[2]]], eff: v[3], by: v[4], rev: v[5], open: () => this.setState({}), btn: v[0] === cur ? 'Showing' : 'Open', btnStyle: btnS(v[0] === cur),
        rowStyle: 'border-bottom: 1px solid var(--hover)' + (v[0] === cur ? '; background: var(--subtle)' : ''), label: v[1] + ' · ' + v[2] + ' · ' + v[3], sel: v[0] === cur })),
      vTitle: isPub ? 'Version 4' : 'Version 5', stateLabel: isPub ? 'Published · in force' : (isDraft ? 'Draft' : 'In review'), stateChip: TONE[isPub ? 'ok' : (isDraft ? 'neutral' : 'warn')],
      versionNote: isReview ? 'Submitted by Shirin Akter, 16 Nov' : (isDraft ? 'Sent back by Arif Rahman, 15 Nov' : 'In force since 1 Jul 2026'),
      hasRemark: isDraft, remark: 'Large credit needs the Deputy CFO as well, not only the CFO.', hasProblems: err === 'invalid',
      effective: isPub ? '1 Jul 2026' : (err === 'dateTaken' ? '1 Jul 2026' : '1 Dec 2026'), effHasErr: !!effErr, effErr: effErr, effInvalid: effErr ? 'true' : 'false', effBorder: effErr ? 'var(--destructive)' : 'var(--border)',
      rules: rules, simulate: () => this.setState({ toast: 'Simulated.' }),
      draftNote: 'Unsaved changes. Save the draft to keep them.', reviewNote: 'Check the rules and try a few memos, then publish it or send it back.', publishedNote: 'In force. Start a new draft to change the rules.',
      save: () => this.setState({ toast: 'Done.' }), submit: () => { if (err === 'invalid') { this.setState({ err: err }); return; } this.setState({ state: 'review', toast: 'Done.' }); },
      newDraft: () => this.setState({ state: 'draft', toast: 'Done.' }),
      openApprove: () => this.setState({ dlg: 'approve', err: this.props.error || 'none', form: {} }), openSendBack: () => this.setState({ dlg: 'sendBack', err: 'none', form: {} }),
      isRemark: dlg === 'approve' || dlg === 'sendBack',
      bar: { hasAlert: isDraft && err === 'draftExists', alert: 'There is already a draft or a version in review.' }
    });
""" + REMARK_JS
DOA_PROPS = '"state":{"editor":"enum","options":["review","draft","published"],"default":"review"},"dialog":{"editor":"enum","options":["none","approve","sendBack"],"default":"none"},"error":{"editor":"enum","options":["none","required","samePerson","dateTaken","invalid","draftExists"],"default":"none"},'
