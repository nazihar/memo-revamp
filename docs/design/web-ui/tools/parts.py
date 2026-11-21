"""Memo page parts: access and copies, co-authors, links, attachments, preview, note corrections and checks,
final record versions, declared interests, and the top-bar search box. Desktop gallery + phone board."""
import json, re
from pathlib import Path
src = Path('tools/dialogs_gallery.py').read_text()
ns = {}
exec(src[:src.index('# ---------- desktop gallery')], ns)   # helpers only: K, label, err, textarea, person, people, select, text_input, note, surface, footer, avatar, esc, box, rich, HEAD, JS, X, SEARCH
g = lambda n: ns[n]
K, label, err, textarea, person, select, text_input, note, surface, avatar, esc, rich = [g(n) for n in ('K', 'label', 'err', 'textarea', 'person', 'select', 'text_input', 'note', 'surface', 'avatar', 'esc', 'rich')]
HEAD, JS, X, SEARCH = g('HEAD'), g('JS'), g('X'), g('SEARCH')
proj = Path('canvas/project')

def svg(p, s=16, extra=''): return f'<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"{extra}>{p}</svg>'
I = {
 'file': '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"></path><path d="M14 2v4a2 2 0 0 0 2 2h4"></path>',
 'eye': '<path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"></path><circle cx="12" cy="12" r="3"></circle>',
 'down': '<path d="M12 15V3"></path><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><path d="m7 10 5 5 5-5"></path>',
 'up': '<path d="M12 3v12"></path><path d="m17 8-5-5-5 5"></path><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>',
 'x': '<path d="M18 6 6 18"></path><path d="m6 6 12 12"></path>',
 'plus': '<path d="M5 12h14"></path><path d="M12 5v14"></path>',
 'link': '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path>',
 'dots': '<circle cx="12" cy="12" r="1"></circle><circle cx="19" cy="12" r="1"></circle><circle cx="5" cy="12" r="1"></circle>',
 'lock': '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path>',
 'shield': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"></path><path d="m9 12 2 2 4-4"></path>',
 'check': '<path d="M20 6 9 17l-5-5"></path>',
 'clock': '<circle cx="12" cy="12" r="10"></circle><path d="M12 6v6l4 2"></path>',
 'left': '<path d="m15 18-6-6 6-6"></path>', 'right': '<path d="m9 18 6-6-6-6"></path>',
 'search': '<circle cx="11" cy="11" r="8"></circle><path d="m21 21-4.3-4.3"></path>',
 'history': '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path><path d="M3 3v5h5"></path><path d="M12 7v5l4 2"></path>',
 'bookmark': '<path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z"></path>',
}
def ib(k, icon, labeltext, tone='var(--text-3)'):
    s = 44 if k.phone else 30
    return f'<button type="button" class="ghost" aria-label="{esc(labeltext)}" style="display: inline-flex; align-items: center; justify-content: center; width: {s}px; height: {s}px; border: 0; border-radius: 6px; background: transparent; color: {tone}; cursor: pointer; flex-shrink: 0">{svg(I[icon])}</button>'
def bt(k, text, icon=None, kind='outline', click=None):
    h = '44px' if k.phone else '32px'; fs = '15px' if k.phone else '13px'
    st = f'display: inline-flex; align-items: center; justify-content: center; gap: 6px; height: {h}; padding: 0 12px; border-radius: 8px; font-size: {fs}; font-weight: 500; cursor: pointer; '
    st += {'outline': 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.05)',
           'primary': 'border: 0; background: var(--primary); color: var(--primary-fg)',
           'ghost': 'border: 0; background: transparent; color: var(--fg)'}[kind]
    on = f' onClick="{{{{{click}}}}}"' if click else ''
    return f'<button type="button" class="{"ghost" if kind != "primary" else ""}"{on} style="{st}">{svg(I[icon], 14) if icon else ""}{text}</button>'
def chip(text, tone='neutral'):
    t = {'neutral': 'border: 1px solid var(--border); color: var(--text-2)', 'muted': 'background: var(--muted); color: var(--fg)',
         'ok': 'border: 1px solid var(--ok-border); background: var(--ok-bg); color: var(--ok-fg)', 'warn': 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)',
         'danger': 'border: 1px solid var(--danger-border); background: var(--danger-bg); color: var(--danger-fg)'}[tone]
    return f'<span style="display: inline-flex; align-items: center; gap: 4px; height: 20px; padding: 0 6px; border-radius: 6px; {t}; font-size: 11px; font-weight: 500; white-space: nowrap">{text}</span>'
def card(title, body, k, lead='', action=''):
    return f'''<section style="border: 1px solid var(--border); border-radius: 10px; padding: {'12px' if k.phone else '14px 16px'}; display: flex; flex-direction: column; gap: 10px; background: var(--bg)">
  <div style="display: flex; align-items: flex-start; justify-content: space-between; gap: 8px"><div style="display: flex; flex-direction: column; gap: 2px"><h3 style="margin: 0; font-size: 14px; font-weight: 600">{title}</h3>{f'<span style="font-size: 12px; color: var(--muted-fg)">{lead}</span>' if lead else ''}</div>{action}</div>
  {body}
</section>'''
def row(left, right='', first=False, k=None):
    return f'<div style="display: flex; align-items: center; gap: 10px; min-height: {44 if k and k.phone else 40}px; {"" if first else "border-top: 1px solid var(--hover);"}">{left}<span style="flex: 1"></span>{right}</div>'
def who(name, ini, sub=''):
    return f'<span style="display: flex; align-items: center; gap: 8px; min-width: 0">{avatar(ini)}<span style="display: flex; flex-direction: column; min-width: 0"><span style="font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{name}</span>{f"<span style=\"font-size: 12px; color: var(--muted-fg)\">{sub}</span>" if sub else ""}</span></span>'
def group_label(t): return f'<span style="font-size: 12px; font-weight: 500; color: var(--muted-fg); margin-top: 4px">{t}</span>'

# ------------------------------------------------------------------ A. access
def access_panel(k):
    appr = ''.join(row(who(n, i, p), '', idx == 0, k) for idx, (n, i, p) in enumerate([('Mahmud Karim', 'MK', 'Head of Corporate Banking'), ('Farhana Rahman', 'FR', 'Head of Credit'), ('Shirin Akter', 'SA', 'Chief Risk Officer')]))
    copies = row(who('Credit Administration', 'CA', 'position · 2 people · after approval'), chip('Sent', 'muted'), True, k) + row(who('Branch Gulshan', 'BG', 'list · 6 people · at submit'), chip('Sent', 'muted'), False, k)
    shared = row(who('Kamal Hasan', 'KH', 'by Tanvir Ahmed · until 31 Dec 2026 · Prepares the security documents'), bt(k, 'Revoke', kind='ghost'), True, k)
    body = f'''{group_label('Approvers')}<div>{appr}<span style="font-size: 12px; color: var(--muted-fg)">and 2 more</span></div>
  {group_label('Copies')}<div>{copies}</div>
  {group_label('Shared with')}<div>{shared}</div>
  <div style="display: flex; flex-wrap: wrap; gap: 8px; padding-top: 4px">{bt(k, 'Share', 'plus')}{bt(k, 'Copy to…')}{bt(k, 'Copies and receipts', kind='ghost')}</div>'''
    return card('People with access', body, k)
def recipient_view(k):
    return note('You received a copy of this memo for information. You cannot act on it.')

def share_body(k):
    return (person(k, 'sh-p', 'Person', ('Kamal Hasan', 'Credit Administration Officer', 'KH')).replace('Choose a person.', 'Choose a person.')
            + '\n<span style="font-size: 12px; color: var(--muted-fg); margin-top: -10px">Only people cleared for the memo’s classification can be chosen.</span>\n'
            + textarea(k, 'sh-r', 'Reason', True, 'Why they need to read it…').replace('A remark is required.', 'Give a reason.')
            + '\n' + text_input(k, 'sh-u', 'Until (optional, at most a year)', '31 Dec 2026'))
def copyto_body(k):
    def rec(name, ini, kind, when):
        opts = ''.join(f'<option{" selected" if o == when else ""}>{o}</option>' for o in ('At submit', 'After approval'))
        sel = f'<label style="position: relative; display: flex; align-items: center"><span style="position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)">When to send to {name}</span><select style="height: {k.h}; padding: 0 28px 0 10px; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 13px">{opts}</select><svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="position: absolute; right: 8px; color: var(--muted-fg); pointer-events: none"><path d="m6 9 6 6 6-6"></path></svg></label>'
        return f'<div style="display: flex; {"flex-wrap: wrap;" if k.phone else ""} align-items: center; gap: 8px; padding: 8px 0; border-top: 1px solid var(--hover)">{who(name, ini, kind)}<span style="flex: 1"></span>{sel}{ib(k, "x", "Remove " + name)}</div>'
    return f'''<div style="display: flex; flex-direction: column">{rec('Credit Administration', 'CA', 'position', 'After approval')}{rec('Branch Gulshan', 'BG', 'list · 6 people', 'At submit')}{rec('Rafiq Chowdhury', 'RC', 'person', 'After approval')}</div>
<div style="display: flex; flex-wrap: wrap; gap: 8px">{bt(k, 'Add a person', 'plus')}{bt(k, 'Add a position', 'plus')}{bt(k, 'Add a distribution list', 'plus')}</div>'''
def receipts_body(k):
    rows = [('Rafiq Chowdhury', 'Sent 18 Nov, 11:07', 'Opened 18 Nov, 14:20', 'ok'), ('Nusrat Jahan', 'Sent 18 Nov, 11:07', 'Not opened yet', 'neutral'), ('Kamal Hasan', 'Not sent: not cleared', '', 'danger')]
    out = ''.join(f'<div style="display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; padding: 8px 0; border-top: 1px solid var(--hover); font-size: 13px"><span style="flex: 1 1 140px; font-weight: 500">{n}</span><span style="color: var(--muted-fg)">{s}</span>{chip(o, t) if o else chip(s.split(":")[0], t) if t == "danger" else ""}</div>' for n, s, o, t in rows)
    return f'<span style="font-size: 13px; color: var(--muted-fg)">Branch Gulshan (list) · 3 people</span><div>{out}</div>'

# ------------------------------------------------------------------ B. co-authors
def coauthors_panel(k, coauthor=False):
    lead = 'The initiator named them to help write the draft.' if coauthor else 'They edit the document and the attachments with you while it is a draft. Only you submit it.'
    people = [('Nusrat Jahan', 'NJ', 'Credit Risk Manager'), ('Imran Hossain', 'IH', 'Credit Risk Analyst')]
    body = ''.join(row(who(n, i, p + (' · you' if coauthor and n == 'Imran Hossain' else '')), '' if coauthor else ib(k, 'x', 'Remove ' + n), idx == 0, k) for idx, (n, i, p) in enumerate(people))
    if coauthor:
        body = note('You are a co-author: you can edit the document and the attachments.') + f'<div>{body}</div>'
    else:
        body = f'<div>{body}</div><div>{bt(k, "Add a co-author", "plus")}</div>'
    return card('Co-authors', body, k, lead)
def coauthor_add_body(k):
    return person(k, 'ca-p', 'Person', ('Sadia Islam', 'Credit Risk Analyst', 'SI')) + '\n<span style="font-size: 12px; color: var(--muted-fg); margin-top: -10px">Type at least two letters of a name or title.</span>'

# ------------------------------------------------------------------ C. links
def links_panel(k):
    def lrow(kind, ref, subj, status, tone, removable, first):
        right = chip(status, tone) + (ib(k, 'x', f'Remove the link to {ref}') if removable else '')
        return f'<div style="display: flex; align-items: center; gap: 10px; padding: 8px 0; {"" if first else "border-top: 1px solid var(--hover);"}"><span style="width: {"auto" if k.phone else "130px"}; flex-shrink: 0; font-size: 12px; color: var(--muted-fg)">{kind}</span><a href="#" style="flex: 1; min-width: 0; display: flex; flex-direction: column; text-decoration: none; color: var(--fg)"><span style="font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{subj}</span><span style="font-family: \'Geist Mono\', ui-monospace, monospace; font-size: 12px; color: var(--muted-fg)">{ref}</span></a>{right}</div>'
    mine = lrow('Follows up', 'CR/2025/0388', 'Credit line for Meghna Agro, 2025', 'Approved', 'ok', True, True) + lrow('Refers to', 'RSK/2026/0012', 'Agro sector exposure limits', 'Closed', 'muted', True, False)
    theirs = lrow('Refers to this memo', 'CR/2026/0151', 'Covenant waiver: Padma Textiles', 'In review', 'warn', False, True)
    return card('Linked memos', f'<div>{mine}</div>{group_label("Linked from")}<div>{theirs}</div><div>{bt(k, "Link a memo", "link")}</div>', k)
def link_body(k):
    radios = ''.join(f'<label style="display: flex; align-items: center; gap: 10px; min-height: {"44px" if k.phone else "28px"}; font-size: 14px"><input type="radio" name="lk-kind"{" checked" if i == 0 else ""} style="margin: 0; width: 16px; height: 16px; accent-color: var(--primary)">{t}</label>' for i, t in enumerate(['Refers to', 'Supersedes', 'Follows up']))
    return (text_input(k, 'lk-n', 'Memo number', '', required=True, msg='Enter a memo number.', hint='The number of a memo you can open.').replace('value=""', 'value="" placeholder="CR/2026/0151"')
            + f'\n<fieldset style="margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; gap: 2px"><legend style="padding: 0; margin-bottom: 6px; font-size: 14px; font-weight: 500">How it relates</legend>{radios}</fieldset>')

# ------------------------------------------------------------------ D. attachments
def attach_panel(k):
    def classsel(title, val):
        opts = ''.join(f'<option{" selected" if o == val else ""}>{o}</option>' for o in ('Same as the memo', 'Confidential', 'Restricted'))
        return f'<label style="position: relative; display: flex; align-items: center"><span style="position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)">Classification of {title}</span><select style="height: {"40px" if k.phone else "30px"}; padding: 0 26px 0 8px; appearance: none; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 12px">{opts}</select><svg aria-hidden="true" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="position: absolute; right: 8px; color: var(--muted-fg); pointer-events: none"><path d="m6 9 6 6 6-6"></path></svg></label>'
    def frow(title, meta, cls, first=False):
        acts = classsel(title, cls) + ib(k, 'eye', 'Preview ' + title) + ib(k, 'down', 'Download ' + title) + ib(k, 'x', 'Remove ' + title)
        lead = f'<span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 6px; background: var(--muted); color: var(--text-2); flex-shrink: 0">{svg(I["file"])}</span><span style="display: flex; flex-direction: column; min-width: 0; flex: 1"><span style="font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{title}</span><span style="font-size: 12px; color: var(--muted-fg)">{meta}</span></span>'
        if k.phone:
            return f'<div style="display: flex; flex-direction: column; gap: 6px; padding: 10px 0; {"" if first else "border-top: 1px solid var(--hover);"}"><div style="display: flex; align-items: center; gap: 10px">{lead}</div><div style="display: flex; align-items: center; gap: 4px">{acts}</div></div>'
        return f'<div style="display: flex; align-items: center; gap: 10px; padding: 8px 0; {"" if first else "border-top: 1px solid var(--hover);"}">{lead}{acts}</div>'
    restricted = f'<div style="display: flex; align-items: center; gap: 10px; padding: 8px 0; border-top: 1px solid var(--hover)"><span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 6px; background: var(--muted); color: var(--text-3); flex-shrink: 0">{svg(I["lock"])}</span><span style="display: flex; flex-direction: column"><span style="font-size: 13px; font-weight: 500; color: var(--text-3)">Restricted attachment</span><span style="font-size: 12px; color: var(--muted-fg)">Classified above your clearance.</span></span></div>'
    uploading = f'''<div style="display: flex; align-items: center; gap: 10px; padding: 8px 0; border-top: 1px solid var(--hover)"><span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 6px; background: var(--muted); color: var(--text-2); flex-shrink: 0">{svg(I["up"])}</span><span style="flex: 1; display: flex; flex-direction: column; gap: 6px; min-width: 0"><span style="display: flex; justify-content: space-between; gap: 8px; font-size: 13px"><span style="font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">Stock audit schedule.xlsx</span><span style="color: var(--muted-fg)">Uploading · 62%</span></span><span role="progressbar" aria-valuenow="62" aria-valuemin="0" aria-valuemax="100" aria-label="Uploading Stock audit schedule.xlsx" style="height: 4px; border-radius: 2px; background: var(--muted); overflow: hidden"><span style="display: block; width: 62%; height: 100%; background: var(--primary)"></span></span></span>{ib(k, "x", "Cancel the upload")}</div>'''
    drop = f'''<div style="display: flex; {"flex-direction: column; align-items: stretch;" if k.phone else "align-items: center;"} gap: 10px 12px; padding: 12px; border: 1px dashed var(--border-strong); border-radius: 8px; background: var(--subtle)">{bt(k, "Add an attachment", "up")}<span style="font-size: 12px; color: var(--muted-fg)">{"" if k.phone else "Or drop files here. "}PDF, images, Word, Excel, PowerPoint, OpenDocument, text or ZIP; up to 50 MB.</span></div>'''
    rows = frow('Panel valuation report, October 2026.pdf', 'PDF · 1.8 MB · version 1', 'Same as the memo', True) + frow('Group exposure summary.xlsx', 'Excel · 96 KB · version 2', 'Restricted') + restricted + uploading
    return card('Attachments', f'<div>{rows}</div>{drop}', k)

# ------------------------------------------------------------------ E. preview
def preview(k, state):
    head = f'''<div style="display: flex; align-items: center; gap: 8px; padding: {'8px 8px 8px 16px' if k.phone else '10px 12px 10px 16px'}; border-bottom: 1px solid var(--border)">
  <span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><h2 id="pv-{state}" style="margin: 0; font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">Panel valuation report, October 2026.pdf</h2><span style="font-size: 12px; color: var(--muted-fg)">PDF · 1.8 MB · version 1</span></span>
  {bt(k, '' if k.phone else 'Download', 'down') if not k.phone else ib(k, 'down', 'Download Panel valuation report, October 2026.pdf', 'var(--fg)')}
  {ib(k, 'x', 'Close', 'var(--fg)')}
</div>'''
    if state == 'ready':
        lines = ''.join(f'<span style="display: block; height: 6px; width: {w}%; border-radius: 3px; background: var(--chip)"></span>' for w in [70, 92, 88, 95, 60, 0, 90, 85, 93, 40])
        body = f'''<div style="flex: 1; display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 20px; background: var(--muted); overflow: auto">
  <div aria-label="Page 1" style="width: 100%; max-width: 420px; aspect-ratio: 1 / 1.3; padding: 28px; box-sizing: border-box; display: flex; flex-direction: column; gap: 10px; background: #ffffff; box-shadow: 0 2px 8px rgba(0,0,0,0.12)"><span style="display: block; height: 10px; width: 55%; border-radius: 3px; background: #d4d4d4"></span>{lines.replace("var(--chip)", "#e5e5e5")}</div>
</div>
<div style="display: flex; align-items: center; justify-content: center; gap: 8px; padding: 8px; border-top: 1px solid var(--border); font-size: 13px">{ib(k, 'left', 'Previous page', 'var(--fg)')}<span>Page 1 of 4</span>{ib(k, 'right', 'Next page', 'var(--fg)')}</div>'''
    else:
        msg = {'loading': ('Loading the preview…', None), 'preparing': ('The preview is being prepared. Try again in a moment.', 'Try again'), 'cannot': ('This file cannot be previewed. Download it to open it.', 'Download')}[state]
        inner = (f'<span role="status" style="display: flex; flex-direction: column; align-items: center; gap: 10px; color: var(--muted-fg)"><svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" style="animation: spin 0.8s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"></path></svg>{msg[0]}</span>' if state == 'loading'
                 else f'<span style="display: flex; flex-direction: column; align-items: center; gap: 12px; max-width: 320px; text-align: center; font-size: 14px; color: var(--text-1)">{msg[0]}{bt(k, msg[1], "down" if msg[1] == "Download" else None, "primary" if msg[1] == "Download" else "outline")}</span>')
        body = f'<div style="flex: 1; display: flex; align-items: center; justify-content: center; padding: 24px; background: var(--subtle); min-height: 240px">{inner}</div>'
    if k.phone:
        return f'<div role="dialog" aria-modal="true" aria-labelledby="pv-{state}" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 31; display: flex; flex-direction: column; background: var(--bg)">{head}{body}</div>'
    return f'<div role="dialog" aria-labelledby="pv-{state}" style="width: 100%; max-width: {880 if state == "ready" else 560}px; height: {560 if state == "ready" else 320}px; display: flex; flex-direction: column; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; background: var(--bg); box-shadow: 0 16px 48px rgba(0,0,0,0.12)">{head}{body}</div>'

# ------------------------------------------------------------------ F. note sheet extras
def notes_panel(k):
    def stamp():
        return f'''<div style="display: flex; flex-direction: column; gap: 4px; margin-top: 6px; padding: 8px 10px; border: 1px solid var(--ok-border); border-radius: 8px; background: var(--ok-bg); color: var(--ok-fg); font-size: 12px">
  <span style="display: flex; align-items: center; gap: 6px; font-weight: 600">{svg(I["shield"], 14)}Approved · authenticated</span>
  <span>Authenticated with password and authenticator, verified 2 minutes before</span>
  <span style="font-family: 'Geist Mono', ui-monospace, monospace">Document v3 · 9f2c4e1a…a6e0</span>
  <span>From 10.20.4.17 · Chrome on Windows</span>
  <span>{bt(k, 'Check', 'check')}</span>
</div>'''
    def n(name, ini, pos, kind, time, text, extra='', menu=True, corrected=False, correction=False):
        tags = (chip('Corrected ↓', 'warn') if corrected else '') + (chip('Correction', 'muted') if correction else '')
        return f'''<div style="display: flex; gap: 10px; padding: 12px 0; border-top: 1px solid var(--hover)">
  {avatar(ini, 28)}
  <div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px">
    <span style="display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 13px"><span style="font-weight: 500">{name}</span><span style="color: var(--muted-fg)">{pos}</span>{chip(kind, 'muted')}{tags}<span style="color: var(--muted-fg)">{time}</span></span>
    {f'<span style="font-size: 12px; color: var(--muted-fg)">Correction of an earlier note</span>' if correction else ''}
    <p style="margin: 0; font-size: 14px; color: {'var(--muted-fg); text-decoration: line-through' if corrected else 'var(--text-1)'}">{text}</p>
    {extra}
  </div>
  {ib(k, 'dots', 'More actions for this note') if menu else ''}
</div>'''
    body = (n('Abdul Kader', 'AK', 'Managing Director', 'Approved', '18 Nov, 11:05', 'Approved at the proposed limit.', stamp(), menu=False)
            + n('Tanvir Ahmed', 'TA', 'Relationship Manager', 'Comment', '16 Nov, 15:10', 'Valuation shows cover of 1.4× on the October panel report.', correction=True)
            + n('Tanvir Ahmed', 'TA', 'Relationship Manager', 'Comment', '16 Nov, 14:52', 'Valuation shows cover of 1.2× on the October panel report.', corrected=True))
    menu = f'''<div role="menu" aria-label="Note actions" style="align-self: flex-end; width: 200px; margin-top: -8px; padding: 4px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg); box-shadow: 0 8px 24px rgba(0,0,0,0.10)"><button type="button" role="menuitem" class="ghost" style="display: flex; flex-direction: column; align-items: flex-start; width: 100%; padding: 6px 8px; border: 0; border-radius: 6px; background: transparent; color: var(--fg); font-size: 14px; text-align: left; cursor: pointer">Correct<span style="font-size: 12px; color: var(--muted-fg)">Post a correction under your note</span></button></div>'''
    return card('Note sheet', f'<div>{body}</div>' + ('' if k.phone else menu), k, 'Round 2 (current) · newest first')
def correct_body(k):
    return (note('<span style="font-size: 12px; color: var(--muted-fg)">Your note</span><br><span style="text-decoration: line-through">Valuation shows cover of 1.2× on the October panel report.</span>')
            + '\n' + rich(k, 'co-n', 'Note', True, 'The corrected note…', value='Valuation shows cover of 1.4× on the October panel report.', count='58').replace('A remark is required.', 'Write the corrected note.').replace('Goes on the note sheet with your name and position.', 'The original stays, marked “Corrected ↓”.')
            + '\n' + textarea(k, 'co-r', 'Reason', True, 'What was wrong…').replace('A remark is required.', 'Give a reason.'))
def check_body(k, ok=True):
    if ok:
        return f'<div role="status" style="display: flex; gap: 10px; padding: 12px; border: 1px solid var(--ok-border); border-radius: 8px; background: var(--ok-bg); color: var(--ok-fg); font-size: 14px"><span style="flex-shrink: 0">{svg(I["shield"])}</span>Checked just now: the document and attachments match what was approved, and the record has not changed.</div>'
    return f'<div role="alert" style="display: flex; flex-direction: column; gap: 6px; padding: 12px; border: 1px solid var(--danger-border); border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 14px"><strong style="font-weight: 600">Something does not match:</strong><ul style="margin: 0; padding-left: 18px"><li>Attachment “Group exposure summary.xlsx” differs from what Farhana Rahman approved.</li></ul></div>'

# ------------------------------------------------------------------ G. final record versions
def record_panel(k):
    bad = f'<div style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 10px 12px; border-radius: 8px; background: var(--subtle); font-size: 12px">{chip("Not valid PDF/A", "danger")}<span style="color: var(--muted-fg)">veraPDF · 2 rules failed</span><span style="flex: 1"></span>{bt(k, "Make a new version", kind="outline")}</div>'
    earlier = ''.join(row(f'<span style="display: flex; flex-direction: column"><span style="font-size: 13px; font-weight: 500">Version {v}</span><span style="font-size: 12px; color: var(--muted-fg)">Made {t} · {r}</span></span>', ib(k, 'down', f'Download version {v}'), i == 0, k) for i, (v, t, r) in enumerate([(1, '18 Nov, 11:07', 'Made with renderer v4.2')]))
    head = f'<div style="display: flex; align-items: center; gap: 12px"><span aria-hidden="true" style="display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 8px; background: var(--danger-bg); color: var(--danger-fg); font-size: 10px; font-weight: 700; flex-shrink: 0">PDF</span><span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="font-size: 14px; font-weight: 500">CR-2026-0142 final record.pdf</span><span style="font-size: 12px; color: var(--muted-fg)">Version 2 · made 19 Nov, 09:30</span></span>{bt(k, "Download", "down", "primary")}</div>'
    making = f'<div role="status" style="display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--muted-fg)">{svg(I["clock"], 14)}A new version is being made.</div>'
    return card('Final record', f'{head}{bad}{making}{group_label("Earlier versions")}<div>{earlier}</div>', k, 'The record itself is in English.')
def newversion_body(k):
    return textarea(k, 'nv-r', 'Reason', True, 'Why a new version is needed…').replace('A remark is required.', 'Give a reason.')

# ------------------------------------------------------------------ H. declared interests
def interests_panel(k, empty=False):
    if empty:
        body = '<p style="margin: 0; font-size: 13px; color: var(--muted-fg)">Nobody has declared an interest.</p>'
    else:
        rows = ''.join(f'<div style="display: flex; {"flex-direction: column; gap: 4px" if k.phone else "gap: 12px"}; padding: 10px 0; {"" if i == 0 else "border-top: 1px solid var(--hover);"} font-size: 13px"><span style="width: {"auto" if k.phone else "200px"}; flex-shrink: 0">{who(n, ini, s)}</span><span style="flex: 1; color: var(--text-1)">{r}</span>{ib(k, "x", "Remove " + n)}</div>'
                       for i, (n, ini, s, r) in enumerate([('Shirin Akter', 'SA', '17 Nov, 10:12 · stood aside', 'My brother-in-law is a director of Meghna Agro.'), ('Rafiq Chowdhury', 'RC', '16 Nov, 09:40', 'Holds shares in a supplier of the client.')]))
        body = f'<div>{rows}</div>'
    return card('Declared interests', body + f'<div>{bt(k, "Declare interest", "plus")}</div>', k, 'People with an interest in this memo cannot approve it. The reason stays with the memo.')
def interest_body(k):
    return person(k, 'in-p', 'Person', ('Rafiq Chowdhury', 'Chief Financial Officer', 'RC')) + '\n' + textarea(k, 'in-r', 'Reason', True, 'Their interest in this memo…').replace('A remark is required.', 'Give a reason.')

# ------------------------------------------------------------------ I. search box (desktop only; phones use the Search page)
def searchbox():
    item = lambda icon, main, sub='', active=False, kbd='': f'<div role="option" aria-selected="{"true" if active else "false"}" style="display: flex; align-items: center; gap: 10px; min-height: 36px; padding: 4px 8px; border-radius: 6px; {"background: var(--active);" if active else ""} font-size: 14px">{svg(I[icon], 15, " style=" + chr(34) + "color: var(--muted-fg); flex-shrink: 0" + chr(34))}<span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{main}</span>{f"<span style=\"font-size: 12px; color: var(--muted-fg)\">{sub}</span>" if sub else ""}</span>{kbd}</div>'
    grp = lambda t: f'<div role="presentation" style="padding: 8px 8px 4px; font-size: 12px; font-weight: 500; color: var(--muted-fg)">{t}</div>'
    kbd = lambda t: f'<kbd style="font-family: \'Geist Mono\', ui-monospace, monospace; font-size: 11px; padding: 1px 5px; border: 1px solid var(--border); border-radius: 4px; background: var(--muted); color: var(--text-3)">{t}</kbd>'
    return f'''<div style="width: 100%; max-width: 560px; display: flex; flex-direction: column; gap: 6px">
  <label style="position: relative; display: flex; align-items: center"><span style="position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0)">Search memos</span>{svg(I["search"], 16, ' style="position: absolute; left: 10px; color: var(--muted-fg)"')}<input type="search" role="combobox" aria-expanded="true" aria-controls="sb-list" value="meghna" style="width: 100%; height: 36px; padding: 0 60px 0 32px; box-sizing: border-box; border: 1px solid var(--ring); border-radius: 8px; background: var(--bg); color: var(--fg); font-size: 14px; outline: none; box-shadow: 0 0 0 3px var(--muted)"><span style="position: absolute; right: 8px">{kbd("Esc")}</span></label>
  <div id="sb-list" role="listbox" aria-label="5 suggestions" style="padding: 4px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg); box-shadow: 0 8px 24px rgba(0,0,0,0.10)">
    {item('search', 'Search for “meghna”', 'In details, notes and documents', True, kbd('↵'))}
    {grp('Memos')}
    {item('file', 'Credit line renewal for Meghna Agro', 'CR/2026/0142 · In review')}
    {item('file', 'Meghna Agro collateral revaluation', 'RSK/2026/0031 · Closed')}
    {item('file', 'Meghna Agro overdraft', 'CR/2019/1180 · Imported')}
    {grp('Recent')}
    {item('history', 'padma textiles covenant')}
    {grp('Saved searches')}
    {item('bookmark', 'Meghna valuation')}
    <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 4px; padding: 8px 8px 4px; border-top: 1px solid var(--hover); font-size: 12px; color: var(--muted-fg)"><a href="Saved.dc.html" style="color: var(--text-2)">All saved searches</a><span style="display: flex; align-items: center; gap: 6px">{kbd("↑")}{kbd("↓")} to move · {kbd("↵")} to open</span></div>
  </div>
  <span style="font-size: 12px; color: var(--muted-fg)">Before typing it shows Recent and Saved searches, with the hint “Type words from a memo’s details, notes or documents, or a memo number.”</span>
</div>'''

# ------------------------------------------------------------------ dialog specs (surface() format)
DIALOGS = {
 'share': ('share', '', 'Share · from People with access', 'Share this memo', '', share_body, 'Share', 'primary', 460),
 'copyto': ('copyto', '', 'Copy to… · while a draft or returned; after approval for those allowed', 'Copy to', 'Copies can read the memo and its notes. They cannot act on it.', copyto_body, 'Save', 'primary', 560),
 'receipts': ('receipts', '', 'Copies and receipts', 'Copies and receipts', '', receipts_body, None, 'close', 560),
 'coauthor': ('coauthor', '', 'Add a co-author', 'Add a co-author', 'They can edit the document and the attachments while it is a draft.', coauthor_add_body, 'Add', 'primary', 460),
 'link': ('link', '', 'Link a memo · from Linked memos', 'Link a memo', '', link_body, 'Link', 'primary', 460),
 'correct': ('correct', '', 'Correct · from a note’s ⋯ menu (your own notes only)', 'Correction of your note of 16 Nov, 14:52', 'Notes cannot be edited. The correction appears under the original.', correct_body, 'Post note', 'primary', 560),
 'checkok': ('checkok', '', 'Check · from an authenticated approval', 'Check the authenticated approvals', '', lambda k: check_body(k, True), None, 'close', 460),
 'checkbad': ('checkbad', '', 'Check · when something does not match', 'Check the authenticated approvals', '', lambda k: check_body(k, False), None, 'close', 460),
 'newversion': ('newversion', '', 'Make a new version · from the final record', 'Make a new version of the final record', 'The current version stays as it is and can still be opened. Say why a new version is needed.', newversion_body, 'Make version 2', 'primary', 460),
 'interest': ('interest', '', 'Declare interest · the initiator, while the memo is a draft or returned', 'Declare interest', 'People with an interest in this memo cannot approve it. The reason stays with the memo.', interest_body, 'Declare interest', 'primary', 460),
}

# ------------------------------------------------------------------ desktop gallery
k = K(False)
def section(title, lead, items):
    return f'''  <section style="display: flex; flex-direction: column; gap: 12px">
    <div style="display: flex; flex-direction: column; gap: 2px"><h2 style="margin: 0; font-size: 16px; font-weight: 600">{title}</h2><span style="font-size: 13px; color: var(--muted-fg)">{lead}</span></div>
    <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(420px, 1fr)); gap: 24px 20px; align-items: start">
{chr(10).join(items)}
    </div>
  </section>'''
def fig(cap, html, wide=False):
    return f'<figure style="margin: 0; display: flex; flex-direction: column; gap: 8px; {"grid-column: 1 / -1" if wide else ""}"><figcaption style="font-size: 12px; color: var(--muted-fg)">{cap}</figcaption>{html}</figure>'
D = lambda key: surface(k, DIALOGS[key])
sections = [
    section('1. People with access', 'Right column of every memo page, under the approvers. Replaces the one-line “Can read” summary.',
            [fig('Initiator’s view', access_panel(k)), fig('A person who received a copy sees this above the overview', recipient_view(k)), D('share'), D('copyto'), D('receipts')]),
    section('2. Co-authors', 'Draft and returned memos, right column.',
            [fig('Initiator’s view', coauthors_panel(k)), fig('Co-author’s view', coauthors_panel(k, True)), D('coauthor')]),
    section('3. Linked memos', 'The Linked memos tab: links this memo makes, and memos that link to it.',
            [fig('Tab content', links_panel(k)), D('link')]),
    section('4. Attachments', 'The Attachments tab while the memo can still be changed. Afterwards the classification, remove and upload controls go.',
            [fig('Tab content with a file uploading', attach_panel(k), True)]),
    section('5. File preview', 'Opens from the eye button on an attachment or document. Wide dialog on desktop, full screen on phones.',
            [fig('Ready', preview(k, 'ready'), True), fig('Loading', preview(k, 'loading')), fig('Being prepared', preview(k, 'preparing')), fig('Cannot be previewed', preview(k, 'cannot'))]),
    section('6. Note sheet: corrections and checks', 'Notes are final. A person can post a correction under their own note; anyone can check an authenticated approval.',
            [fig('Note sheet with a correction and an authenticated approval', notes_panel(k)), D('correct'), D('checkok'), D('checkbad')]),
    section('7. Final record versions', 'For an approved or closed memo, when the record failed validation or must be remade.',
            [fig('Final record with a failed check and an earlier version', record_panel(k)), D('newversion')]),
    section('8. Declared interests', 'Right column, below People with access. The initiator lists who has an interest while the memo is a draft or returned; everyone who can see the memo reads the list.',
            [fig('With interests', interests_panel(k)), fig('None yet', interests_panel(k, True)), D('interest')]),
    section('9. Search box (⌘K)', 'In the top bar on desktop. On phones the search icon opens the Search page instead.',
            [fig('Typing “meghna”', searchbox(), True)]),
]
desk = HEAD('Memo page parts').replace('</style>', '@keyframes spin{to{transform:rotate(360deg)}}\n</style>') + f'''<div class="th {{{{themeClass}}}}" style="min-height: 100vh; display: flex; flex-direction: column; gap: 36px; padding: 32px; box-sizing: border-box; background: var(--subtle); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="display: flex; flex-direction: column; gap: 4px; max-width: 900px">
    <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600">Memo page parts</h1>
    <p style="margin: 0; font-size: 14px; color: var(--muted-fg)">The smaller pieces of the memo page, each as it sits on the page and with the dialog it opens. Turn on the <strong>errors</strong> tweak to see the dialogs submitted incomplete.</p>
  </header>
{chr(10).join(sections)}
</div>
</x-dc>
''' + JS('{"width":1440,"height":6200}', '', '    const extra = {};\n')
(proj / 'MemoParts.dc.html').write_text(desk)

# ------------------------------------------------------------------ phone: one part at a time
kp = K(True)
def page(html, title='CR/2026/0142'):
    return f'<div style="display: flex; flex-direction: column; gap: 12px; padding: 16px">{html}</div>'
def sheet(key):
    return '<span aria-hidden="true" style="position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; background: rgba(0,0,0,0.5)"></span>' + surface(kp, DIALOGS[key])
PARTS = [
    ('access', page(access_panel(kp))), ('share', sheet('share')), ('copyto', sheet('copyto')), ('receipts', sheet('receipts')), ('copyNotice', page(recipient_view(kp))),
    ('coauthors', page(coauthors_panel(kp))), ('coauthorView', page(coauthors_panel(kp, True))), ('coauthorAdd', sheet('coauthor')),
    ('links', page(links_panel(kp))), ('link', sheet('link')),
    ('attachments', page(attach_panel(kp))),
    ('preview', preview(kp, 'ready')), ('previewLoading', preview(kp, 'loading')), ('previewPreparing', preview(kp, 'preparing')), ('previewCannot', preview(kp, 'cannot')),
    ('notes', page(notes_panel(kp))), ('correct', sheet('correct')), ('checkOk', sheet('checkok')), ('checkBad', sheet('checkbad')),
    ('record', page(record_panel(kp))), ('newVersion', sheet('newversion')),
    ('interests', page(interests_panel(kp))), ('interest', sheet('interest')),
]
keys = [p[0] for p in PARTS]
blocks = '\n'.join(f'<sc-if value="{{{{is.{key}}}}}" hint-placeholder-val="{{{{{"true" if key == "access" else "false"}}}}}">\n{html}\n</sc-if>' for key, html in PARTS)
phone = HEAD('Memo page parts on a phone').replace('</style>', '@keyframes spin{to{transform:rotate(360deg)}}\n</style>') + f'''<div class="th {{{{themeClass}}}}" style="position: relative; width: 390px; height: 844px; overflow: hidden; display: flex; flex-direction: column; background: var(--bg); color: var(--fg); font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="flex-shrink: 0; height: 56px; display: flex; align-items: center; gap: 4px; padding: 0 8px; border-bottom: 1px solid var(--border)">
    <a href="MemoPhone.dc.html" class="ghost" aria-label="Back to the memo" style="display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border-radius: 8px; color: var(--fg)">{svg(I['left'], 20)}</a>
    <span style="flex: 1; font-family: 'Geist Mono', ui-monospace, monospace; font-size: 14px; font-weight: 500">CR/2026/0142</span>
  </header>
  <main style="flex: 1; min-height: 0; overflow-y: auto">
{blocks}
  </main>
</div>
</x-dc>
''' + JS('{"width":390,"height":844}', '"part":{"editor":"enum","options":' + json.dumps(keys) + ',"default":"access"},',
         "    const d = this.props.part || 'access';\n    const is = {};\n    " + json.dumps(keys) + ".forEach((x) => { is[x] = x === d; });\n    const extra = { is: is };\n")
(proj / 'MemoPartsPhone.dc.html').write_text(phone)

c = proj / 'canvas.json'; d = json.loads(c.read_text())
y = 49500
d['boards']['MemoParts.dc.html'] = {"x": 0, "y": y, "w": 1440, "h": 6200, "title": "Memo page parts — Desktop", "expand": "fill", "is_interactive": True}
d['boards']['MemoPartsPhone.dc.html'] = {"x": 1520, "y": y, "w": 390, "h": 844, "title": "Memo page parts — Phone (pick with the part tweak)", "is_interactive": True}
d['notes']['memoPartsTitle'] = {"x": 0, "y": y - 260, "text": "Memo page parts", "kind": "title1", "maxW": 1910}
for f in ('MemoParts.dc.html', 'MemoPartsPhone.dc.html'):
    if f not in d['order']: d['order'].insert(d['order'].index('Rules.dc.html'), f)
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print('ok', len(PARTS))
