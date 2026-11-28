"""Adds the patterns the admin pages introduced to the design rules page (section 9) and three wording rules to
section 7. Idempotent: does nothing when section 9 is already there."""
import json
from pathlib import Path
proj = Path('canvas/project'); p = proj / 'Rules.dc.html'; s = p.read_text()
if 'id="r-admin"' not in s:
    words = ['Filter fields end with “…” (Filter by name or code…); pickers do not (Search people).',
             'Back links say Back to and the page name: Back to Users, Back to Memo types.',
             'Lists page with Show more everywhere; never Load more.',
             'One success word per kind of action: Saved. after saving a change, Done. after an action that is not a save.']
    anchor = '<li>References in Geist Mono: CR/2026/0142.</li>'
    assert s.count(anchor) == 1
    s = s.replace(anchor, anchor + ''.join(f'\n          <li>{w}</li>' for w in words))
    card = lambda title, items: ('      <div style="padding: 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); display: flex; flex-direction: column; gap: 8px">'
                                 f'<h3 style="margin: 0; font-size: 14px; font-weight: 600">{title}</h3><ul style="margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-1)">'
                                 + ''.join(f'<li>{i}</li>' for i in items) + '</ul></div>\n')
    cards = [
        ('Section links', ['Pages of one area share a row of underlined links under the top bar: People and access, Memos, Workflows, Organisation-wide settings, Records.',
                           'The current page is underlined and marked with aria-current. The sidebar opens the first page of an area.']),
        ('Admin lists', ['Segmented tabs for the main filter, a 240px filter field, the table on a card.',
                         'Each row has a ⋯ menu; a destructive item comes last, in red.',
                         'The footer has a count or a note on the left and Show more on the right.',
                         'Phones: a full-width list, pills for the tabs, and ⋯ opens a bottom sheet.']),
        ('Empty states', ['A dashed box that says what happened, what to do, and offers the action that fixes it (Add queue, New batch).',
                          'When a filter finds nothing: “No … matches.” with Clear the filter.']),
        ('Saving', ['Personal preferences save as they change (Notification settings: “Changes save as you make them.”).',
                    'Organisation settings use a Save bar at the bottom that says when there are unsaved changes, because saving asks you to confirm it is you.',
                    'Two independent parts of one page each have their own Save (Language: Defaults and Your terms).']),
        ('Versions and review', ['Memo types, workflows and the delegation of authority share one model: a version picker and a state badge (Draft grey, In review amber, Published green, Retired grey).',
                                 'The action bar at the bottom changes with the state. Draft: Save, Submit for review. In review: Send back, Approve and publish. Published: New draft.',
                                 'A sent-back draft shows the reviewer’s remark in amber. Changes list before → after; the remark is required only to send back.']),
        ('Read-only', ['Controls stay where they are but greyed: subtle background, muted text, not-allowed cursor.',
                       'Add and remove buttons are hidden rather than disabled. A note says why nothing can change and what to do instead.']),
        ('Steps', ['A job in several steps (an import batch) shows numbered pills. Done steps carry a tick, and “(done)” for screen readers.',
                   'Any step can be opened; the batch opens on the next thing to do.']),
        ('Refusals', ['A server refusal shows inside the dialog as an alert titled Not done.',
                      'Field problems sit under the field, in the form’s own words. The submit button stays enabled.']),
        ('Who sees what', ['Navigation shows only what a person’s roles allow (app/navigation.ts).',
                           'On these boards, user pages are seen by Farhana Rahman (Head of Credit; Auditor, Dashboard viewer), so her Administration group is Overview and Audit.',
                           'Admin pages are seen by Arif Rahman, organisation administrator.']),
    ]
    section = ('\n  <!-- Admin and settings patterns -->\n  <section aria-labelledby="r-admin" style="display: flex; flex-direction: column; gap: 16px">\n'
               '    <h2 id="r-admin" style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600">9. Admin and settings patterns</h2>\n'
               '    <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(380px, 1fr)); gap: 12px">\n' + ''.join(card(*c) for c in cards) + '    </div>\n  </section>\n')
    end = '\n</div>\n</div>\n</x-dc>'
    assert s.count(end) == 1
    s = s.replace(end, section.rstrip('\n') + end)
    p.write_text(s)
    c = proj / 'canvas.json'; d = json.loads(c.read_text())
    if d['boards']['Rules.dc.html'].get('h', 0) < 7200: d['boards']['Rules.dc.html']['h'] = 7200
    c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
    print('section 9 added')
else:
    print('already there')
