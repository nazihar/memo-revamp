"""Put the canvas rows back in reading order. Run after any script that adds or moves a board.

Each row is one screen: the desktop board at x 0 and its phone board at x 1520, with a title note above.
Rows are spaced by the taller board of the pair, so long galleries never run into the next row.
"""
import json
from pathlib import Path

proj = Path('canvas/project')
ROWS = [  # (desktop board, phone board, title note key, title text or None to keep)
    ('Home', 'HomePhone', 'homeTitle', 'Home'),
    ('Main', 'Phone', 'inboxTitle', 'Inbox · My tasks'),
    ('InboxWatching', 'InboxWatchingPhone', 'InboxWatchingTitle', None),
    ('InboxTeam', 'InboxTeamPhone', 'InboxTeamTitle', None),
    ('NewMemo', 'NewMemoPhone', 'newMemoTitle', None),
    ('Draft', 'DraftPhone', 'draftTitle', None),
    ('Sent', 'SentPhone', 'sentTitle', None),
    ('Memo', 'MemoPhone', 'memoTitle', 'Memo in review (approver)'),
    ('Returned', 'ReturnedPhone', 'returnedTitle', None),
    ('Approved', 'ApprovedPhone', 'approvedTitle', None),
    ('Closed', 'ClosedPhone', 'closedTitle', None),
    ('Rejected', 'RejectedPhone', 'rejectedTitle', None),
    ('Withdrawn', 'WithdrawnPhone', 'withdrawnTitle', None),
    ('Cancelled', 'CancelledPhone', 'cancelledTitle', None),
    ('Discarded', 'DiscardedPhone', 'discardedTitle', None),
    ('Imported', 'ImportedPhone', 'importedTitle', None),
    ('Document', 'DocumentPhone', 'docTitle', None),
    ('MemoParts', 'MemoPartsPhone', 'memoPartsTitle', None),
    ('MemoDialogs', 'MemoDialogsPhone', 'memoDialogsTitle', None),
    ('StepUp', 'StepUpPhone', 'StepUpTitle', None),
    ('Search', 'SearchPhone', 'searchTitle', None),
    ('Saved', 'SavedPhone', 'savedTitle', None),
    ('Notifications', 'NotificationsPhone', 'notifTitle', None),
    ('Dashboard', 'DashboardPhone', 'dashTitle', None),
    ('Org', 'OrgPhone', 'orgTitle', None),
    ('Position', 'PositionPhone', 'positionTitle', None),
    ('Security', 'SecurityPhone', 'secTitle', None),
    ('Prefs', 'PrefsPhone', 'prefsTitle', None),
    ('SignIn', 'SignInPhone', 'signinTitle', None),
    ('Status', 'StatusPhone', 'statusTitle', None),
    ('States', 'StatesPhone', 'StatesTitle', None),
    ('Tasks', 'TasksPhone', 'tasksTitle', None),
]

c = proj / 'canvas.json'
d = json.loads(c.read_text())
B, N = d['boards'], d['notes']
listed = {r[0] + '.dc.html' for r in ROWS} | {r[1] + '.dc.html' for r in ROWS} | {'Rules.dc.html'}
missing = set(B) - listed
if missing:
    raise SystemExit(f'Add these boards to ROWS first: {sorted(missing)}')
y = -1860
order = []
for desk, phone, note, title in ROWS:
    N[note]['y'], N[note]['x'] = y, 0
    if title:
        N[note]['text'] = title
    by = y + 260
    for b in (desk, phone):
        B[b + '.dc.html']['y'] = by
    order += [desk + '.dc.html', phone + '.dc.html']
    y = by + max(B[desk + '.dc.html'].get('h', 900), B[phone + '.dc.html'].get('h', 844)) + 360
d['order'] = order + ['Rules.dc.html']
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
print(f'{len(ROWS)} rows laid out')
