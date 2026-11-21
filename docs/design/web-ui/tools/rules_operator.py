"""Adds the operator console's patterns to the design rules page (section 10, after the admin patterns). Idempotent:
does nothing when section 10 is already there. Run after rules_admin.py."""
import json
from pathlib import Path
proj = Path('canvas/project'); p = proj / 'Rules.dc.html'; s = p.read_text()
if 'id="r-operator"' not in s:
    assert 'id="r-admin"' in s, 'run rules_admin.py first'
    card = lambda title, items: ('      <div style="padding: 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); display: flex; flex-direction: column; gap: 8px">'
                                 f'<h3 style="margin: 0; font-size: 14px; font-weight: 600">{title}</h3><ul style="margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-1)">'
                                 + ''.join(f'<li>{i}</li>' for i in items) + '</ul></div>\n')
    cards = [
        ('A separate console', ['The operator console is its own app at its own address, not a part of an organisation. It has no organisation sidebar.',
                                'Its top bar stays dark in both themes (its own --bar-bg, --bar-fg, --bar-muted and --bar-line colours) and always says Memo &amp; Meetings · Platform administration, so an operator never mistakes it for an organisation.',
                                'Two pages, Organisations and Platform audit, as links in the bar; the operator’s name and Sign out on the right. Pages are full width, like the admin pages.']),
        ('Operator sign-in', ['The same frame as the organisation sign-in (the platform pages share its AuthLayout): language and theme at the top right, the product inside the card.',
                              'A password, then always a 6-digit code. A new operator arrives through a one-time link made on the server.',
                              'Operators have no recovery codes, so the code screen says who can reset two-step sign-in.']),
        ('Registry only', ['Operators see an organisation’s registry entry: name, address, status, whether it has an administrator. Never what is inside it.',
                           'The pages say so in plain words, and point to the platform audit for what operators did.']),
        ('Actions that lock people out', ['An action that signs out a whole organisation asks for its address to be typed (Suspend). The button stays enabled; a wrong address is a field error.',
                                          'Undoing it asks a plain question (Resume this organisation?).',
                                          'Suspended is amber: serious, but temporary and reversible.']),
        ('Confirm it is you', ['Every change the server guards with a step-up (create, suspend, resume, invite) asks for a code in a small dialog after the action’s own confirmation, never instead of it.',
                               'Wrong code: an error under the boxes. Too many tries: an alert in the dialog.']),
        ('Platform audit', ['The same filters and columns as the organisation’s Audit: From, To, Action starts with; Time, Who, Action, Object.',
                            'Action codes in Geist Mono. Organisations in the Object column link to their page. Entries without a signed-in person say Not signed in, in italics.']),
    ]
    section = ('\n  <!-- Operator console patterns -->\n  <section aria-labelledby="r-operator" style="display: flex; flex-direction: column; gap: 16px">\n'
               '    <h2 id="r-operator" style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600">10. Operator console</h2>\n'
               '    <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(380px, 1fr)); gap: 12px">\n' + ''.join(card(*c) for c in cards) + '    </div>\n  </section>\n')
    end = '\n</div>\n</div>\n</x-dc>'
    assert s.count(end) == 1
    s = s.replace(end, section.rstrip('\n') + end)
    p.write_text(s)
    c = proj / 'canvas.json'; d = json.loads(c.read_text())
    if d['boards']['Rules.dc.html'].get('h', 0) < 7800: d['boards']['Rules.dc.html']['h'] = 7800
    c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
    print('section 10 added')
else:
    print('already there')
