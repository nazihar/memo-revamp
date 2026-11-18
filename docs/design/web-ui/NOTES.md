# Canvas notes

The three notes beside the screens on the canvas, kept here so they can be read and reviewed without opening it.

## API changes this design needs

Inbox (GET /api/v1/inbox/tasks)
1. Counts — total open tasks for the My tasks tab, the sidebar Inbox badge and the "x of N selected" footer.
2. Action per task — DECIDE does not say whether the step is Approve or Recommend.
3. Memo type — add the memo type's name to InboxMemo.
4. Sender — who sent the task (previous holder or author).
5. Filters — text (subject or reference), memo type, due (overdue / due soon / none), working with sort and the cursor.

Notifications (GET /api/v1/me/notifications)
6. Filters — unread only, and category (approvals, following, security, administration), working with the cursor.
7. Context — the second line needs a little more in params: the memo subject, a snippet of the note that mentions you, the sign-in place and browser.

Watching (GET /api/v1/inbox/watching)
8. Status — add the memo's status to WatchingItem, so the list can show In review / Approved / Closed without opening each memo.

Any request (all of /api/v1)
9. Session ended — when a session has ended (timed out, signed out elsewhere, or ended from My security), answer 401 with a problem reason that says so, e.g. SESSION_ENDED, so the app shows “You were signed out” instead of “Something went wrong”.

Last seen (memo view, inbox, watching)
10. Last seen — record when a person opens a memo (per person, per memo). The memo view returns lastSeenAt and what changed since: resubmission and its remark, document versions, attachments added, notes added, and the person's own last action (e.g. their return remark).
11. Unread — inbox task and watching items carry unread: true when the memo has news since the person last opened it. Opening the memo clears it.
Next task and the reading pane need nothing new: they follow the inbox order the client already has, fetching the next page by cursor when needed.

## Behaviour notes

• Team tab appears only for people who oversee a unit (as today); everyone else sees five tabs.
• Organisation name and logo come from tenant branding; it is a label, not a switcher.
• Under the user's name we show the email: the user record has no job title.
• Row "⋯" menu: Claim, Release, Watch for that one task (existing endpoints).
• Show more loads the next batch by cursor; 50 per batch on desktop, 25 on phones.
• Forms validate in the app (TanStack Form), never with browser HTML validation: noValidate on every form, our own messages under the field, focus to the first error, submit stays enabled.
• New states the app does not handle yet: Page not found (unknown address), Connection lost (offline bar) and Signed out (session ended mid-task). See the Page states row.
• Next task: “Open my next task after this” sits in every decision dialog, on by default and remembered per person. It follows My tasks in its current sort and filters, and skips restricted and blocked tasks. After the last one: “That was your last task: My tasks is clear.” and back to Inbox.
• Opened from My tasks, the memo header shows “1 of 7 in My tasks” with previous and next (J and K).
• Since you last looked: shown on a memo you have opened before when something changed. It starts with what you asked for last time, then resubmission, document versions (with Compare), attachments and new notes. Notes added since your last visit carry a “New” tag on the note sheet.
• Unread: a blue dot and a heavier subject on My tasks and Watching rows; screen readers hear “Unread”. Opening the memo clears it.
• Reading pane: off by default. A “Reading pane” switch in the My tasks toolbar (shown at 1400px and wider) turns it on; the choice is remembered per person. When on, the list and the selected memo sit side by side with the same decision buttons. Links from notifications, search and Home still open the full page; narrower screens and phones always use list → page.

## Build note

Use shadcn/ui components for everything: Button, Input, InputOTP, Select, Combobox, Tabs, Table, Dialog, Drawer, AlertDialog, DropdownMenu, Popover, Badge, Skeleton, Alert, Sonner, Sidebar. These boards are hand-written HTML only because the canvas cannot run React; do not copy their inline styles. The component for each pattern is listed on the Design rules page, section 1.

## Admin · User detail

- One page for a person: account status and the account actions from the Users list, then positions, roles and clearance (today the app shows only the last three).
- Account actions sit in the header and follow the same rules as the list menu; on phones they are in the ⋯ sheet.
- Clearance opens a dialog with the levels from least to most sensitive, instead of a select on the page.
- Give a role: “Where” is offered only for roles that can be limited to a unit; otherwise it says the role applies to the whole organisation (new copy).
- Every change asks for step-up (Confirm it is you) first, as the app does. Refusals (already holds it, last administrator, changed meanwhile) show inside the dialog.
- Needs nothing new from the API: `GET /admin/users/{id}`, its assignments and access, plus the existing lifecycle endpoints.
- New copy on the Users boards: “Can manage users, memo types, workflows and settings.” (invite checkbox hint), “Open profile and access”, “You cannot deactivate or sign out your own account here.”, “Try another name, email or status.”, “{n} shown”.
