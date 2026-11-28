"""Replace the old 'People and access' summary on every memo state with the People with access panel."""
import re
from pathlib import Path
proj = Path('canvas/project')
B = Path('backup-access')
def esc(t): return t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
def svg(p, s=14): return f'<svg aria-hidden="true" width="{s}" height="{s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{p}</svg>'
PLUS = '<path d="M5 12h14"></path><path d="M12 5v14"></path>'
def chip(text, tone):
    t = {'neutral': 'border: 1px solid var(--border); color: var(--text-2)', 'muted': 'background: var(--muted); color: var(--fg)', 'danger': 'border: 1px solid var(--danger-border); background: var(--danger-bg); color: var(--danger-fg)'}[tone]
    return f'<span style="flex-shrink: 0; display: inline-flex; align-items: center; height: 20px; padding: 0 6px; border-radius: 6px; {t}; font-size: 11px; font-weight: 500; white-space: nowrap">{text}</span>'
def avatar(ini): return f'<span aria-hidden="true" style="display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 50%; background: var(--muted); color: var(--text-2); font-size: 10px; font-weight: 600; flex-shrink: 0">{ini}</span>'
def person_row(name, ini, sub, right, first, phone):
    return f'<div style="display: flex; align-items: center; gap: 8px; min-height: {44 if phone else 40}px; {"" if first else "border-top: 1px solid var(--hover);"}">{avatar(ini)}<span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{name}</span><span style="font-size: 12px; color: var(--muted-fg)">{sub}</span></span>{right}</div>'
def btn(text, phone, kind='outline', icon=None):
    h = '44px' if phone else '30px'; fs = '15px' if phone else '13px'
    st = f'display: inline-flex; align-items: center; justify-content: center; gap: 6px; height: {h}; padding: 0 {12 if phone else 10}px; border-radius: 8px; font-size: {fs}; font-weight: 500; cursor: pointer; '
    st += 'border: 1px solid var(--border); background: var(--bg); color: var(--fg); box-shadow: 0 1px 2px rgba(0,0,0,0.05)' if kind == 'outline' else 'border: 0; background: transparent; color: var(--text-2)'
    return f'<button type="button" class="ghost" style="{st}">{svg(icon) if icon else ""}{text}</button>'
def grp(t): return f'<span style="font-size: 12px; font-weight: 500; color: var(--muted-fg); margin-top: 2px">{t}</span>'

def panel(hid, c, phone=False):
    authors = ''.join(person_row(n, i, s, '', idx == 0, phone) for idx, (n, i, s) in enumerate(c['authors']))
    copies = ''.join(person_row(n, i, s, chip(ch, t), idx == 0, phone) for idx, (n, i, s, ch, t) in enumerate(c['copies'])) or '<span style="font-size: 13px; color: var(--muted-fg)">Nobody</span>'
    shared = ''.join(person_row(n, i, s, btn('Revoke', phone, 'ghost') if rv else '', idx == 0, phone) for idx, (n, i, s, rv) in enumerate(c['shared'])) or '<span style="font-size: 13px; color: var(--muted-fg)">Nobody</span>'
    acts = []
    if 'share' in c['actions']: acts.append(btn('Share', phone, icon=PLUS))
    if 'copy' in c['actions']: acts.append(btn('Copy to…', phone))
    if 'receipts' in c['actions']: acts.append(btn('Copies and receipts', phone, 'ghost'))
    foot = f'<div style="display: flex; flex-wrap: wrap; gap: 8px; padding-top: 4px">{"".join(acts)}</div>' if acts else ''
    extra = f'<span style="font-size: 12px; color: var(--muted-fg)">{c["note"]}</span>' if c.get('note') else ''
    pad = '12px' if phone else '14px 16px'
    return f'''<section aria-labelledby="{hid}" style="border: 1px solid var(--border); border-radius: 10px; padding: {pad}; display: flex; flex-direction: column; gap: 8px">
            <h2 id="{hid}" style="margin: 0; font-size: 14px; font-weight: 600">People with access</h2>
            {grp('Author' if len(c['authors']) == 1 else 'Authors')}<div>{authors}</div>
            {grp('Copies')}<div>{copies}</div>
            {grp('Shared with')}<div>{shared}</div>
            <span style="font-size: 12px; color: var(--muted-fg)">{c.get('approvers', 'Approvers on the route can read it too.')}</span>
            {extra}{foot}
          </section>'''

TA = ('Tanvir Ahmed', 'TA'); NJ = ('Nusrat Jahan', 'NJ'); SI = ('Sadia Islam', 'SI')
CA_AFTER = ('Credit Administration', 'CA', 'position · 2 people · after approval')
BG_SENT = ('Branch Gulshan', 'BG', 'list · 6 people · at submit', 'Sent', 'muted')
COPIES_REVIEW = [BG_SENT, CA_AFTER + ('After approval', 'neutral')]
COPIES_DONE = [BG_SENT, CA_AFTER + ('Sent', 'muted')]
KH_BY_TA = 'by Tanvir Ahmed · until 31 Dec 2026'
FIN_COPIES_ENDED = [('Finance Controller', 'FC', 'position · at submit', 'Sent', 'muted'), ('Retail Operations', 'RO', 'list · 9 people · after approval', 'Not sent', 'neutral')]
CONF = {
    'Memo':      dict(authors=[(TA[0], TA[1], 'initiator'), (NJ[0], NJ[1], 'co-author')], copies=COPIES_REVIEW, shared=[('Kamal Hasan', 'KH', KH_BY_TA, False)], actions=['share', 'receipts']),
    'Sent':      dict(authors=[(TA[0] + ' (you)', TA[1], 'initiator'), (NJ[0], NJ[1], 'co-author')], copies=COPIES_REVIEW, shared=[('Kamal Hasan', 'KH', 'by you · until 31 Dec 2026', True)], actions=['share', 'receipts'], note='Copies can be changed again if the memo comes back to you.'),
    'Returned':  dict(authors=[(TA[0] + ' (you)', TA[1], 'initiator'), (NJ[0], NJ[1], 'co-author')], copies=[('Branch Gulshan', 'BG', 'list · 6 people · at submit', 'Sent in round 1', 'muted'), CA_AFTER + ('After approval', 'neutral')], shared=[], actions=['share', 'copy', 'receipts']),
    'Approved':  dict(authors=[(TA[0], TA[1], 'initiator'), (NJ[0], NJ[1], 'co-author')], copies=COPIES_DONE, shared=[('Kamal Hasan', 'KH', KH_BY_TA, False)], actions=['share', 'receipts'], approvers='The five approvers can read it too.'),
    'Closed':    dict(authors=[(TA[0] + ' (you)', TA[1], 'initiator'), (NJ[0], NJ[1], 'co-author')], copies=COPIES_DONE, shared=[('Kamal Hasan', 'KH', 'by you · until 31 Dec 2026', True)], actions=['share', 'receipts'], approvers='The five approvers can read it too.'),
    'Rejected':  dict(authors=[(SI[0] + ' (you)', SI[1], 'initiator')], copies=FIN_COPIES_ENDED, shared=[], actions=['share', 'receipts'], note='Copies due after approval are not sent: it was rejected.'),
    'Withdrawn': dict(authors=[(SI[0] + ' (you)', SI[1], 'initiator')], copies=FIN_COPIES_ENDED, shared=[], actions=['share', 'receipts'], note='Copies due after approval are not sent: it was withdrawn.'),
    'Cancelled': dict(authors=[(SI[0] + ' (you)', SI[1], 'initiator')], copies=FIN_COPIES_ENDED, shared=[], actions=['share', 'receipts'], note='Copies due after approval are not sent: it was cancelled.'),
    'Discarded': dict(authors=[(SI[0] + ' (you)', SI[1], 'initiator')], copies=[], shared=[], actions=[], approvers='Only you can see a discarded draft.'),
}

for name, c in CONF.items():
    s = (B / f'{name}.dc.html').read_text()
    m = re.search(r'<section aria-labelledby="([a-z-]*people-h)"', s)
    assert m, name
    end = s.index('</section>', m.start()) + len('</section>')
    s = s[:m.start()] + panel(m.group(1), c) + s[end:]
    (proj / f'{name}.dc.html').write_text(s)
    print('desktop', name)

# Phones with a People and access block
for name, base in (('MemoPhone', 'Memo'), ('SentPhone', 'Sent')):
    s = (B / f'{name}.dc.html').read_text()
    m = re.search(r'<section aria-labelledby="(ppeople-h)"', s)
    end = s.index('</section>', m.start()) + len('</section>')
    s = s[:m.start()] + panel('ppeople-h', CONF[base], True) + s[end:]
    (proj / f'{name}.dc.html').write_text(s)
    print('phone', name)

# Phones that list "More about this memo" rows: add a People with access row
ROWVAL = {'ApprovedPhone': '12 people · 2 copies sent · shared with 1', 'ClosedPhone': '12 people · 2 copies sent · shared with 1',
          'ReturnedPhone': '8 people · 1 copy sent, 1 after approval', 'RejectedPhone': '6 people · 1 copy sent',
          'WithdrawnPhone': '6 people · 1 copy sent', 'CancelledPhone': '6 people · 1 copy sent', 'DiscardedPhone': 'Only you'}
for name, val in ROWVAL.items():
    s = (B / f'{name}.dc.html').read_text()
    i = s.index('const more = [')
    j = s.index('\n    ]', i)
    if 'People with access' not in s[i:j]:
        last_nl = s.rindex('\n', i, j)
        line = s[last_nl + 1:j]
        indent = line[:len(line) - len(line.lstrip())]
        s = s[:j] + f",\n{indent}{{ title: 'People with access', value: '{val}' }}" + s[j:]
    (proj / f'{name}.dc.html').write_text(s)
    print('phone row', name)

# Draft: the Copies row now says when copies go, and interests get a row
d = (B / 'Draft.dc.html').read_text()
a = "        { title: 'Copies', value: 'Nobody is copied. Copies can read the memo but do not approve.', action: 'Add' }"
assert a in d
d = d.replace(a, "        { title: 'Copies', value: 'Nobody is copied. Copies can read the memo and its notes, sent at submit or after approval.', action: 'Copy to…' },\n        { title: 'Shared with', value: 'Nobody. Share it with someone who needs to read it.', action: 'Share' },\n        { title: 'Declared interests', value: 'Nobody. List anyone with an interest; they cannot approve it.', action: 'Add' }")
(proj / 'Draft.dc.html').write_text(d)
print('draft rows')
