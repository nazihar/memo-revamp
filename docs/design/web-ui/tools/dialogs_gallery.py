"""Every memo dialog, with the app's copy: a desktop gallery and a phone board that shows one as a bottom sheet."""
import json, re
from pathlib import Path
proj = Path('canvas/project')
TOKENS = Path('tools/tokens.css').read_text().strip()
REF = 'CR/2026/0142'

class K:  # sizes per surface
    def __init__(s, phone):
        s.phone = phone
        s.h = '44px' if phone else '36px'
        s.fs = '16px' if phone else '14px'
        s.bfs = '15px' if phone else '14px'

def esc(t): return t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')

def label(k, text, fid, required=False, optional=False):
    req = ' <span aria-hidden="true" style="color: var(--danger-fg)">*</span>' if required else ''
    return f'<label for="{fid}" style="font-size: 14px; font-weight: 500">{esc(text)}{req}</label>'

def err(fid, msg):
    return f'<sc-if value="{{{{e}}}}" hint-placeholder-val="{{{{false}}}}"><span id="{fid}-err" style="font-size: 12px; color: var(--danger-fg)">{esc(msg)}</span></sc-if>'

def box(k, extra=''):
    return f'width: 100%; box-sizing: border-box; border: 1px solid {{{{b}}}}; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {k.fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); {extra}'

def plainbox(k, extra=''):
    return f'width: 100%; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {k.fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05); {extra}'

TOOL_ICONS = [
    ('Bold', '<path d="M6 12h9a4 4 0 0 1 0 8H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h7a4 4 0 0 1 0 8"></path>'),
    ('Italic', '<line x1="19" x2="10" y1="4" y2="4"></line><line x1="14" x2="5" y1="20" y2="20"></line><line x1="15" x2="9" y1="4" y2="20"></line>'),
    ('Bulleted list', '<path d="M3 12h.01"></path><path d="M3 18h.01"></path><path d="M3 6h.01"></path><path d="M8 12h13"></path><path d="M8 18h13"></path><path d="M8 6h13"></path>'),
    ('Numbered list', '<path d="M10 12h11"></path><path d="M10 18h11"></path><path d="M10 6h11"></path><path d="M4 10h2"></path><path d="M4 6h1v4"></path><path d="M6 18H4c0-1 2-2 2-3s-1-1.5-2-1"></path>'),
    ('Link', '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path>'),
    ('Mention someone', '<circle cx="12" cy="12" r="4"></circle><path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8"></path>'),
]

def rich(k, fid, text, required, placeholder, value='', count='0', check_errors=True):
    sz = 32 if k.phone else 28
    tools = ''.join(f'<button type="button" class="ghost" aria-label="{n}" style="display: inline-flex; align-items: center; justify-content: center; width: {sz}px; height: {sz}px; border: 0; border-radius: 6px; background: transparent; color: var(--text-2); cursor: pointer"><svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{p}</svg></button>' for n, p in TOOL_ICONS)
    border = '{{b}}' if (required and check_errors) else 'var(--border)'
    body = esc(value) if value else f'<span style="color: var(--muted-fg)">{esc(placeholder)}</span>'
    out = f'''<div style="display: flex; flex-direction: column; gap: 6px">
  {label(k, text, fid, required)}
  <div style="border: 1px solid {border}; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">
    <div role="toolbar" aria-label="Formatting" style="display: flex; gap: 2px; padding: 3px 4px; border-bottom: 1px solid var(--hover); background: var(--subtle)">{tools}</div>
    <div id="{fid}" role="textbox" aria-multiline="true" aria-label="{esc(text)}" contenteditable="true" style="min-height: {120 if k.phone else 96}px; padding: 8px 10px; font-size: {k.fs}; line-height: 22px; outline: none">{body}</div>
  </div>
  <span style="display: flex; justify-content: space-between; gap: 8px; font-size: 12px; color: var(--muted-fg)"><span>Goes on the note sheet with your name and position.</span><span>{count} / 4,000</span></span>'''
    if required and check_errors:
        out += '\n  ' + err(fid, 'A remark is required.')
    return out + '\n</div>'

def textarea(k, fid, text, required, placeholder, check_errors=True):
    border = '{{b}}' if (required and check_errors) else 'var(--border)'
    out = f'''<div style="display: flex; flex-direction: column; gap: 6px">
  {label(k, text, fid, required)}
  <textarea id="{fid}" rows="4" maxlength="4000" placeholder="{esc(placeholder)}" aria-invalid="{{{{eAria}}}}" style="width: 100%; box-sizing: border-box; padding: 8px 10px; border: 1px solid {border}; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {k.fs}; line-height: 20px; resize: vertical; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)"></textarea>'''
    if required and check_errors:
        out += '\n  ' + err(fid, 'A remark is required.')
    return out + '\n</div>'

SEARCH = '<svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="position: absolute; left: 10px; color: var(--muted-fg)"><circle cx="11" cy="11" r="8"></circle><path d="m21 21-4.3-4.3"></path></svg>'
X = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg>'

def avatar(ini, size=24):
    return f'<span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: {size}px; height: {size}px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: {10 if size < 28 else 11}px; font-weight: 600; flex-shrink: 0">{ini}</span>'

def person(k, fid, text, who, msg='Choose a person.'):
    name, pos, ini = who
    return f'''<div style="display: flex; flex-direction: column; gap: 6px">
  {label(k, text, fid, True)}
  <sc-if value="{{{{ok}}}}" hint-placeholder-val="{{{{true}}}}">
    <div style="display: flex; align-items: center; gap: 10px; min-height: {k.h}; padding: 4px 4px 4px 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px">
      {avatar(ini)}
      <span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="font-size: 14px; font-weight: 500">{name}</span><span style="font-size: 12px; color: var(--muted-fg)">{pos}</span></span>
      <button type="button" class="ghost" aria-label="Remove {name}" style="display: inline-flex; align-items: center; justify-content: center; width: {k.h}; height: {k.h}; border: 0; border-radius: 6px; background: transparent; color: var(--muted-fg); cursor: pointer">{X}</button>
    </div>
  </sc-if>
  <sc-if value="{{{{e}}}}" hint-placeholder-val="{{{{false}}}}">
    <span style="position: relative; display: flex; align-items: center">{SEARCH}<input id="{fid}" type="text" role="combobox" aria-expanded="false" aria-invalid="true" aria-describedby="{fid}-err" placeholder="Search people" style="{box(k, f'height: {k.h}; padding: 0 10px 0 32px')}"></span>
    <span id="{fid}-err" style="font-size: 12px; color: var(--danger-fg)">{esc(msg)}</span>
  </sc-if>
</div>'''

def people(k, fid, text, chips):
    chip_html = ''.join(f'<span style="display: inline-flex; align-items: center; gap: 6px; height: {"32px" if k.phone else "26px"}; padding: 0 4px 0 4px; border-radius: 999px; background: var(--muted); font-size: 13px">{avatar(i, 20)}{n}<button type="button" class="ghost" aria-label="Remove {n}" style="display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border: 0; border-radius: 50%; background: transparent; color: var(--muted-fg); cursor: pointer">{X}</button></span>' for n, i in chips)
    return f'''<div style="display: flex; flex-direction: column; gap: 6px">
  {label(k, text, fid, True)}
  <span style="position: relative; display: flex; align-items: center">{SEARCH}<input id="{fid}" type="text" role="combobox" aria-expanded="false" aria-invalid="{{{{eAria}}}}" aria-describedby="{fid}-hint" placeholder="Search people" style="{box(k, f'height: {k.h}; padding: 0 10px 0 32px')}"></span>
  <sc-if value="{{{{ok}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; flex-wrap: wrap; gap: 6px">{chip_html}</div></sc-if>
  <span id="{fid}-hint" style="font-size: 12px; color: var(--muted-fg)">Type at least two letters of a name or title. {len(chips)} of 10 chosen.</span>
  {err(fid, 'Choose at least one person.')}
</div>'''

def select(k, fid, text, options, required=True, msg='Choose a step.', disabled=False, hint=''):
    opts = ''.join(f'<option>{esc(o)}</option>' for o in options)
    b = '{{b}}' if required and msg else 'var(--border)'
    dis = ' disabled' if disabled else ''
    bg = 'var(--muted)' if disabled else 'var(--bg)'
    out = f'''<div style="display: flex; flex-direction: column; gap: 6px">
  {label(k, text, fid, required and not disabled)}
  <span style="position: relative; display: flex; align-items: center">
    <select id="{fid}"{dis} style="width: 100%; height: {k.h}; padding: 0 32px 0 10px; box-sizing: border-box; appearance: none; border: 1px solid {b}; border-radius: 8px; background: {bg}; color: var(--fg); font-family: inherit; font-size: {k.fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">{opts}</select>
    <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="position: absolute; right: 10px; color: var(--muted-fg); pointer-events: none"><path d="m6 9 6 6 6-6"></path></svg>
  </span>'''
    if hint: out += f'\n  <span style="font-size: 12px; color: var(--muted-fg)">{esc(hint)}</span>'
    if required and msg and not disabled: out += '\n  ' + err(fid, msg)
    return out + '\n</div>'

def text_input(k, fid, text, value, required=False, msg='Required.', hint=''):
    b = '{{b}}' if required else 'var(--border)'
    out = f'''<div style="display: flex; flex-direction: column; gap: 6px">
  {label(k, text, fid, required)}
  <input id="{fid}" type="text" value="{esc(value)}" style="width: 100%; height: {k.h}; padding: 0 10px; box-sizing: border-box; border: 1px solid {b}; border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: {k.fs}; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)">'''
    if hint: out += f'\n  <span style="font-size: 12px; color: var(--muted-fg)">{esc(hint)}</span>'
    if required: out += '\n  ' + err(fid, msg)
    return out + '\n</div>'

def note(text, tone='info'):
    st = {'info': 'border: 1px solid var(--border); background: var(--subtle); color: var(--text-1)',
          'warn': 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)'}[tone]
    return f'<div style="padding: 10px 12px; border-radius: 8px; {st}; font-size: 13px">{text}</div>'

# ---------- dialog specs: (key, group, caption, title, lead, body(k), confirm, kind, width)
WHO_FWD = ('Imran Hossain', 'Chief Risk Officer (acting)', 'IH')
WHO_DEL = ('Nusrat Jahan', 'Credit Risk Manager', 'NJ')
WHO_ASK = ('Tanvir Ahmed', 'Relationship Manager · initiator', 'TA')

def doa_body(k):
    g = lambda title, people, short: f'''<li style="display: flex; flex-direction: column; gap: 4px; padding: 10px 12px; border: 1px solid {'var(--danger-border)' if short else 'var(--border)'}; border-radius: 8px">
  <span style="font-size: 14px; font-weight: 500; color: {'var(--danger-fg)' if short else 'var(--fg)'}">{title}</span>
  <span style="font-size: 13px; color: var(--text-1)">{people}</span>
</li>'''
    return f'''<p style="margin: 0; font-size: 13px; color: var(--muted-fg)">Version 4, rule “Credit facilities above BDT 100 million”</p>
<ol style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px">
{g('Group 1: 1 approval needed; your route has 1.', 'Head of Credit · Farhana Rahman', False)}
{g('Group 2: 2 approvals needed; your route has 1.', 'Chief Risk Officer · Imran Hossain (acting); Managing Director · Abdul Kader', True)}
</ol>'''

def compare_body(k):
    old = 'The facility is renewed at BDT 250,000,000 for twelve months. Security cover is <del style="background: var(--danger-bg); color: var(--danger-fg)">1.2× on the 2024 valuation</del>. Pricing stays at <del style="background: var(--danger-bg); color: var(--danger-fg)">policy rate + 2.5%</del>.'
    new = 'The facility is renewed at BDT 250,000,000 for twelve months. Security cover is <ins style="background: var(--ok-bg); color: var(--ok-fg); text-decoration: underline">1.4× on the October 2026 panel valuation</ins>. Pricing stays at <ins style="background: var(--ok-bg); color: var(--ok-fg); text-decoration: underline">policy rate + 2.25%</ins>.'
    col = lambda lab, v, opts, txt: f'''<div style="flex: 1 1 260px; min-width: 0; display: flex; flex-direction: column; gap: 8px">
  {select(k, 'cmp-' + lab, lab, opts, required=False, msg='')}
  <div style="padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--subtle); font-size: 14px; line-height: 22px">{txt}</div>
</div>'''
    return f'''<div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 13px"><span style="font-weight: 600">2 changes</span><span style="color: var(--muted-fg)">Removed text is struck through in the older version; added text is underlined in the newer one.</span></div>
<div style="display: flex; flex-wrap: wrap; gap: 12px">
{col('Older version', v := None, ['Version 2 · Credit memo — Meghna Agro.docx · Tanvir Ahmed', 'Version 1 · Credit memo — Meghna Agro.docx · Tanvir Ahmed'], old)}
{col('Newer version', None, ['Version 3 · Credit memo — Meghna Agro.docx · Tanvir Ahmed'], new)}
</div>'''

def write_body(k):
    return f'''{rich(k, 'wn-note', 'Note', True, 'Write for the people who decide on this memo…', value='Spoke to the client today: the stock audit is booked for 28 Nov. @Farhana Rahman can you confirm the cover ratio holds if it comes in 10% lower?', count='142', check_errors=True).replace('A remark is required.', 'Write the note first.').replace('Goes on the note sheet with your name and position.', 'Draft saved 10:42 · only you can see it')}
<div style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px">
  <button type="button" class="ghost" style="display: inline-flex; align-items: center; gap: 6px; height: {k.h}; padding: 0 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer"><svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m16 6-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551"></path></svg>Add a file</button>
  <span style="font-size: 12px; color: var(--muted-fg)">Up to 10 files, as for attachments.</span>
</div>
{note('<span style="display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px"><span>Farhana Rahman cannot see this memo. Share it with them?</span><button type="button" class="ghost" style="height: 30px; padding: 0 10px; border: 1px solid var(--warn-border); border-radius: 6px; background: var(--bg); color: var(--fg); font-size: 13px; font-weight: 500; cursor: pointer">Share and mention</button></span>', 'warn')}
<span style="font-size: 12px; color: var(--muted-fg)">Notes cannot be edited. You can post a correction.</span>'''

def edit_body(k):
    return f'''{text_input(k, 'ed-subject', 'Subject', 'Credit line renewal for Meghna Agro', required=True)}
{select(k, 'ed-class', 'Classification', ['Confidential'], required=False, msg='', disabled=True, hint='Fixed once the memo is submitted.')}
<div style="display: grid; grid-template-columns: {'1fr' if k.phone else '1fr 1fr'}; gap: 12px">
{text_input(k, 'ed-amount', 'Amount (BDT)', '250,000,000')}
{text_input(k, 'ed-tenor', 'Tenor (months)', '12')}
</div>
{text_input(k, 'ed-facility', 'Facility type', 'Revolving credit line')}'''

def stepup_body(k):
    return f'''<div style="display: flex; flex-direction: column; gap: 6px">
  <label for="su-code" style="font-size: 14px; font-weight: 500">Code</label>
  <input id="su-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="123456" aria-invalid="{{{{eAria}}}}" aria-describedby="su-code-err" style="{box(k, f'height: {k.h}; padding: 0 12px; font-family: Geist Mono, ui-monospace, monospace; letter-spacing: 0.2em')}">
  {err('su-code', 'That did not match. Try again.')}
</div>'''

SPECS = [
    ('approve', 'Decide', 'Approve · from the task card', f'Approve {REF}?', 'You approve document version 3. You may be asked to confirm it is you.', lambda k: rich(k, 'ap-r', 'Remark (optional)', False, 'Add a remark for the note sheet…'), 'Approve', 'primary', 460),
    ('recommend', 'Decide', 'Recommend · a review step’s positive action', f'Recommend {REF}?', 'You recommend document version 3. The next step gets it with your recommendation.', lambda k: rich(k, 'rc-r', 'Remark (optional)', False, 'Add a remark for the note sheet…'), 'Recommend', 'primary', 460),
    ('return', 'Decide', 'Return · remark required', f'Return {REF} to its author?', 'Say what needs to change. They can resubmit it.', lambda k: rich(k, 'rt-r', 'Remark', True, 'What needs to change…'), 'Return', 'primary', 460),
    ('reject', 'Decide', 'Reject · ends the memo', f'Reject {REF}?', 'The memo ends here. Say why.', lambda k: rich(k, 'rj-r', 'Remark', True, 'Why it is rejected…'), 'Reject', 'danger', 460),
    ('sendBack', 'Hand it on', 'Send back · to an earlier step of this round', f'Send {REF} back?', 'The step you choose runs again, and the steps after it too. Say why.', lambda k: select(k, 'sb-step', 'Send back to', ['Credit risk analysts · any one (Sadia Islam)', 'Head of Corporate Banking (Mahmud Karim)']) + '\n' + textarea(k, 'sb-r', 'Remark', True, 'Why it needs another look…'), 'Send back', 'primary', 460),
    ('forward', 'Hand it on', 'Forward · someone else holds the step', f'Forward {REF}?', 'The person you choose holds the step instead of you.', lambda k: person(k, 'fw-p', 'Person', WHO_FWD) + '\n' + textarea(k, 'fw-r', 'Remark', True, 'Why you are handing it on…'), 'Forward', 'primary', 460),
    ('delegate', 'Hand it on', 'Delegate · someone acts for you', f'Delegate {REF}?', 'The person you choose acts for you; the step stays yours.', lambda k: person(k, 'dl-p', 'Person', WHO_DEL) + '\n' + textarea(k, 'dl-r', 'Remark (optional)', False, 'Anything they should know…'), 'Delegate', 'primary', 460),
    ('ask', 'Hand it on', 'Ask a question · you decide after the answer', f'Ask about {REF}?', 'You can decide once your question is answered.', lambda k: person(k, 'as-p', 'Person', WHO_ASK) + '\n' + textarea(k, 'as-r', 'Remark', True, 'Your question…'), 'Ask a question', 'primary', 460),
    ('refer', 'Hand it on', 'Ask for opinions · up to 10 people', f'Ask for opinions on {REF}?', 'You keep the task and decide when you are ready.', lambda k: people(k, 'rf-p', 'People (up to 10)', [('Shirin Akter', 'SA'), ('Rafiq Chowdhury', 'RC')]) + '\n' + textarea(k, 'rf-r', 'Remark', True, 'What you want their view on…'), 'Ask for opinions', 'primary', 460),
    ('answer', 'Answer and advise', 'Answer · when someone asked you', f'Answer the question on {REF}', 'Whoever asked can decide once you answer.', lambda k: note('<strong style="font-weight: 600">Farhana Rahman asked</strong> · 17 Nov, 09:12<br>Is the October valuation from the bank’s panel valuer?') + '\n' + textarea(k, 'an-r', 'Your answer', True, 'Your answer…').replace('A remark is required.', 'Write your answer.'), 'Answer', 'primary', 460),
    ('opinion', 'Answer and advise', 'Give your opinion · when asked for one', f'Your opinion on {REF}', 'It goes into the history for whoever decides.', lambda k: note('<strong style="font-weight: 600">Farhana Rahman asked for opinions</strong> · 17 Nov, 09:20<br>Is 1.4× cover enough for an agro client in this season?') + '\n' + textarea(k, 'op-r', 'Your opinion', True, 'Your opinion…').replace('A remark is required.', 'Write your opinion.'), 'Give your opinion', 'primary', 460),
    ('recuse', 'Answer and advise', 'Declare an interest · you stand aside', f'Stand aside from {REF}?', 'Say what your interest is. The task leaves you and the step goes to someone else.', lambda k: textarea(k, 'ru-r', 'Remark', True, 'Your interest in this memo…'), 'Declare an interest', 'primary', 460),
    ('write', 'Notes, details and checks', 'Write a note · from the note sheet', 'Write a note', '', write_body, 'Post note', 'primary', 560),
    ('edit', 'Notes, details and checks', 'Edit · a draft or a returned memo', 'Edit', '', edit_body, 'Save', 'primary', 560),
    ('doa', 'Notes, details and checks', 'Who approves this? · from the approvers list', 'Delegation of authority', '', doa_body, None, 'close', 460),
    ('stepup', 'Notes, details and checks', 'Confirm it is you · when the server asks, e.g. on Approve', 'Confirm it is you', 'Enter the 6-digit code from your authenticator app to continue.', stepup_body, 'Confirm', 'primary', 460),
    ('compare', 'Notes, details and checks', 'Compare versions · from the document', 'Compare versions', '', compare_body, None, 'close', 900),
]

def footer(k, confirm, kind):
    btn = f'height: {k.h}; padding: 0 14px; border-radius: 8px; font-size: {k.bfs}; font-weight: 500; cursor: pointer; '
    outline = btn + 'border: 1px solid var(--border); background: var(--bg); color: var(--fg)'
    if kind == 'close':
        btns = [f'<button type="button" class="ghost" style="{outline}">Close</button>']
    else:
        prim = btn + 'border: 0; ' + ('background: var(--destructive); color: var(--on-destructive)' if kind == 'danger' else 'background: var(--primary); color: var(--primary-fg)')
        btns = [f'<button type="button" class="ghost" style="{outline}">Cancel</button>', f'<button type="submit" style="{prim}">{confirm}</button>']
    if k.phone:
        return '<div style="display: flex; flex-direction: column; gap: 8px">' + ''.join(reversed(btns)) + '</div>'
    return '<div style="display: flex; justify-content: flex-end; gap: 8px">' + ''.join(btns) + '</div>'

def surface(k, spec):
    key, group, caption, title, lead, body, confirm, kind, width = spec
    lead_html = f'<p id="{key}-desc" style="margin: 0; font-size: 14px; color: var(--muted-fg)">{esc(lead)}</p>' if lead else ''
    role = 'alertdialog' if kind == 'danger' else 'dialog'
    head = f'''<div style="display: flex; flex-direction: column; gap: {'4' if k.phone else '6'}px; padding-right: {'0' if k.phone else '24px'}">
  <h2 id="{key}-title" style="margin: 0; font-size: 18px; line-height: 26px; font-weight: 600">{esc(title)}</h2>
  {lead_html}
</div>'''
    close_x = '' if k.phone else f'<button type="button" class="ghost" aria-label="Close" style="position: absolute; top: 12px; right: 12px; display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; color: var(--muted-fg)">{X.replace("14", "16")}</button>'
    inner = f'{close_x}\n{head}\n{body(k)}\n{footer(k, confirm, kind)}'
    if k.phone:
        return f'''<form role="{role}" aria-modal="true" aria-labelledby="{key}-title" novalidate style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 31; max-height: calc(100% - 24px); overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 8px 16px 16px; box-sizing: border-box; border-radius: 16px 16px 0 0; background: var(--bg); box-shadow: 0 -8px 32px rgba(0,0,0,0.2)">
<span aria-hidden="true" style="align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--chip); flex-shrink: 0"></span>
{inner}
</form>'''
    return f'''<figure style="margin: 0; display: flex; flex-direction: column; gap: 8px; {'grid-column: 1 / -1' if width > 600 else ''}">
<figcaption style="font-size: 12px; color: var(--muted-fg)">{esc(caption)}</figcaption>
<form role="{role}" aria-labelledby="{key}-title" novalidate style="position: relative; width: 100%; max-width: {width}px; display: flex; flex-direction: column; gap: 16px; padding: 24px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 12px; background: var(--bg); box-shadow: 0 16px 48px rgba(0,0,0,0.12)">
{inner}
</form>
</figure>'''

HEAD = lambda title: f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600&amp;family=Geist+Mono:wght@400;500&amp;display=swap">
<style>
{TOKENS}
body{{margin:0;background:#ffffff}}
a{{color:var(--fg)}}
button,input,select,textarea{{font-family:inherit}}
.ghost:hover{{background:var(--hover)}}
.ghost:active{{background:var(--muted)}}
</style>
</helmet>
'''
JS = lambda preview, extra_props, extra_js: f'''<script type="text/x-dc" data-dc-script data-props='{{"theme":{{"editor":"enum","options":["system","light","dark"],"default":"system"}},"errors":{{"editor":"boolean","default":false}},{extra_props}"$preview":{preview}}}'>
class Component extends DCLogic {{
  renderVals() {{
    const e = !!this.props.errors;
{extra_js}    return Object.assign({{
      themeClass: (this.state && this.state.theme) || this.props.theme || 'system',
      e: e, ok: !e, eAria: e ? 'true' : 'false',
      b: e ? 'var(--destructive)' : 'var(--border)'
    }}, extra);
  }}
}}
</script>
</body>
</html>
'''

# ---------- desktop gallery
k = K(False)
groups = []
for g in ['Decide', 'Hand it on', 'Answer and advise', 'Notes, details and checks']:
    items = '\n'.join(surface(k, s) for s in SPECS if s[1] == g)
    groups.append(f'''  <section aria-labelledby="g-{g[:4].lower()}" style="display: flex; flex-direction: column; gap: 12px">
    <h2 id="g-{g[:4].lower()}" style="margin: 0; font-size: 14px; font-weight: 600">{g}</h2>
    <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(400px, 1fr)); gap: 24px 20px; align-items: start">
{items}
    </div>
  </section>''')
desk = HEAD('Memo dialogs') + f'''<div class="th {{{{themeClass}}}}" style="min-height: 100vh; display: flex; flex-direction: column; gap: 28px; padding: 32px; box-sizing: border-box; background: var(--subtle); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="display: flex; flex-direction: column; gap: 4px; max-width: 900px">
    <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600">Memo dialogs</h1>
    <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">Every dialog a memo page opens, as it opens. Turn on the <strong>errors</strong> tweak to see what each shows when someone submits it incomplete. Required fields carry a red asterisk; the submit button never greys out. Decisions that go on the note sheet (approve, recommend, return, reject, and submit, resubmit and withdraw) use the formatting editor; the rest use a plain text box.</p>
  </header>
{chr(10).join(groups)}
</div>
</x-dc>
''' + JS('{"width":1440,"height":3600}', '', '    const extra = {};\n')
(proj / 'MemoDialogs.dc.html').write_text(desk)

# ---------- phone: one sheet at a time
k = K(True)
keys = [s[0] for s in SPECS]
sheets = '\n'.join(f'<sc-if value="{{{{is.{s[0]}}}}}" hint-placeholder-val="{{{{{"true" if s[0] == "return" else "false"}}}}}">\n{surface(k, s)}\n</sc-if>' for s in SPECS)
phone = HEAD('Memo dialogs on a phone') + f'''<div class="th {{{{themeClass}}}}" style="position: relative; width: 390px; height: 844px; overflow: hidden; display: flex; flex-direction: column; background: var(--bg); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="flex-shrink: 0; height: 56px; display: flex; align-items: center; padding: 0 16px; border-bottom: 1px solid var(--border); font-family: 'Geist Mono', ui-monospace, monospace; font-size: 14px; font-weight: 500">{REF}</header>
  <div aria-hidden="true" style="display: flex; flex-direction: column; gap: 8px; padding: 16px">
    <span style="font-size: 18px; line-height: 26px; font-weight: 600">Credit line renewal for Meghna Agro</span>
    <span style="height: 80px; border: 1px solid var(--border); border-radius: 10px"></span>
    <span style="height: 160px; border: 1px solid var(--border); border-radius: 10px"></span>
  </div>
  <span aria-hidden="true" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; background: rgba(0,0,0,0.5)"></span>
{sheets}
</div>
</x-dc>
''' + JS('{"width":390,"height":844}', '"dialog":{"editor":"enum","options":' + json.dumps(keys) + ',"default":"return"},',
         "    const d = this.props.dialog || 'return';\n    const is = {};\n    " + json.dumps(keys) + ".forEach((x) => { is[x] = x === d; });\n    const extra = { is: is };\n")
(proj / 'MemoDialogsPhone.dc.html').write_text(phone)

c = proj / 'canvas.json'; d = json.loads(c.read_text())
y = 38460
d['boards']['MemoDialogs.dc.html'] = {"x": 0, "y": y, "w": 1440, "h": 3600, "title": "Memo dialogs — Desktop", "expand": "fill", "is_interactive": True}
d['boards']['MemoDialogsPhone.dc.html'] = {"x": 1520, "y": y, "w": 390, "h": 844, "title": "Memo dialogs — Phone (pick one with the dialog tweak)", "is_interactive": True}
d['notes']['memoDialogsTitle'] = {"x": 0, "y": y - 260, "text": "Memo dialogs", "kind": "title1", "maxW": 1910}
for f in ('MemoDialogs.dc.html', 'MemoDialogsPhone.dc.html'):
    if f not in d['order']: d['order'].insert(d['order'].index('Rules.dc.html'), f)
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok', len(SPECS))
