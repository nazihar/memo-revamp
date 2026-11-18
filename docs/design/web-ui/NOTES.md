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

## Admin · Roles and Classification

- Users, Roles and Classification share a row of section links under the top bar. The sidebar's Users entry opens the section until the full, grouped Administration menu exists.
- Roles: open a role to see its permissions; built-in roles are read-only. New and Edit list every permission in five groups (Organisation, People and access, Memos, Workflows, Records and settings) with an "Admin" tag (administrator permission: always needs two-step sign-in) and a "Per unit" tag (can be limited to a unit). Remove only works for roles nobody holds.
- Editing a role that people hold says that changes apply to them at once (new copy).
- Classification: the up and down buttons move a level one place, and the arrows point where the row goes. In the app today the arrow icons point the other way: ArrowUp makes a level more sensitive, which moves it down the list.
- A new level goes in as the most sensitive, as the API does, and the Add level dialog says so (new copy).
- Refusals show inside the dialog: name taken, still in use (with what to do first, new copy), last level, at most 20 levels, changed meanwhile.
- Web catalogue gap: `tenant.settings.manage` has no label in `access-messages.ts`; the boards call it "Manage organisation settings".
- New copy: "Choose at least one permission." (the app greys out Save instead), the permission group names, the custom role descriptions, "Least sensitive" / "Most sensitive" tags, the level descriptions, and the footer notes.

## Admin · Memos (Memo types, editor, Numbering, Distribution lists)

- Memo types, Numbering and Distribution lists share a row of section links. The sidebar's Memo types entry opens the section.
- Memo types list: "In use / Switched off" tabs and a filter by name or code, both done in the browser (the list is small). "Switch off" asks first and says that memos already written keep the type (new copy). The app switches a type off without asking.
- Editor: one page per type, with a version picker and the four tabs from the app (Settings, Form, Numbering, Changes). The `state` tweak shows a draft that was sent back (Save, Submit for review), a version in review (opens on Changes; remark, Send back, Approve and publish) and a published version (read-only, New draft). The actions sit in a bar that stays at the bottom. Only a draft can change; publishing needs a second administrator and step-up.
- Settings are grouped in four cards: Basics, Approval, Who may raise it, Records. In read-only states the controls are greyed, and the unit Remove and "Add a unit" buttons are hidden.
- Form tab: fields in order (move, edit, remove; on phones one ⋯ menu per field), fields added in this version are marked, the template with its tags, and a preview of the form as the author sees it. Edit field is a dialog.
- Numbering tab: number parts, series, restart, count, when the number is given, and a sample of the next numbers.
- Numbering page: series on the left; the chosen counter's register on the right, with the completeness check, void numbers and "Not visible to you". "Set a starting number" asks for a Unit only for a series counted per unit, with a unit picker instead of the app's "Unit id" text field.
- Distribution lists: members are people or positions, and a position reaches whoever holds it when a copy is sent. The app's single "A name and at least one member are required." becomes two field errors: Name "Required." and "Add at least one member." (new copy). Switch off sits in the edit dialog and the row menu.
- API: nothing new.
- New copy: the switch-off confirmation, the "New memo type" lead, "Added in this version", the template tag hint, the sample note, the read-only note for a version in review, the field help text and the list dialog lead.

## Admin · Workflows (definitions, builder, delegation of authority, queues, substitutions, business calendar)

- The five pages share a row of section links. The sidebar's Workflows entry opens the section; Open tasks keeps its own sidebar entry.
- Workflows list: All / Workflows / Sub-workflows / Changes in progress tabs and a filter by name or key, done in the browser. "Built in" and "Sub-workflow" badges. The New workflow dialog has Name, Key, Description (optional) and "Meant to be used inside other workflows".
- Builder: a version picker and the draft / in review / published states, as on the memo type editor.
  - Views: Diagram, Steps (the keyboard and screen-reader path), JSON, and Changes while a version is in review.
  - The side panel shows the selected step (Goes to, Decided when, What the holder can do with "needs a remark", deadline and escalation, Leads to, Remove) or Simulate.
  - Steps are marked Added, Changed, Reached or "1 problem" in words as well as colour.
  - Check runs the validator; Submit is refused with "Fix the problems first." while there are problems.
  - Approve, Send back and Retire are dialogs with a remark; the remark is required only to send back ("Say what needs to change.").
  - A published version shows "Memos on older versions", with the ones that can move ticked.
- Phones: no diagram ("The diagram needs a wider screen."). Steps is the default view; a step and Simulate open as sheets.
- Delegation of authority: the versions table, then the selected version (Takes effect on, rules with approval groups) beside "Who approves this?". In a draft one rule is open for editing; the others are read-only summaries with Change. "Another version takes effect on that date." shows under the date.
- Queues: like distribution lists, plus a Key that cannot change once made. Key and Name are each required on their own (the app checks them together).
- Substitutions: All / In force / Planned / Ended tabs, "Move open tasks" on in-force rows, and End today or Cancel it in the row menu. The Add substitution dialog shows the overlap refusal inside it.
- Business calendar: weekly hours (Friday and Saturday not worked), the month view of holidays and special hours with a list underneath, and "When is it due?". On phones "Add hours" is a + button.
- API: nothing new.
- New copy: the New workflow dialog lead, the send-back lead, the "Cancel it" action for planned substitutions, the substitutions footer, the calendar legend and the due-date example line.

## Admin · Organisation-wide settings (Security, Email, Branding, Language and terms)

- The four pages share a row of section links. The sidebar's Administration › Security entry opens them.
- Security, Branding: forms on the page with a Save bar that stays at the bottom and says when there are unsaved changes. Saving asks for step-up, as the app does. "Someone changed these settings meanwhile…" shows in the bar.
- Security: each number shows the range the server allows (`SecurityLimit`: idle 5–480 minutes, longest session 1–24 hours, confirmation 1–30 minutes, password 12–128, waiting after 3–10, lock after 5–50, lock 5–1440 minutes).
  - Errors sit under the field: "Between 5 and 480." (new copy) and the server's "Lock after more failures than the waiting starts."
  - "What people will notice" sums the settings up in plain words (new copy).
  - The longer confirmation for approving memos (`security.stepup.approval.minutes`, 5–240) is not on the app's page, and not on this board either. Add it to the page if administrators should be able to change it.
- Email: the mail server is a summary card. Change opens a dialog with the server, port, encryption, sender, user name and password, where an empty password keeps the stored one.
  - "Send a test email to me" shows a failure on the card. "Use the platform's server" asks first (new copy).
  - The delivery log has All / Failed tabs (new; client-side over the page of entries) and "Failed emails are tried again up to five times." (new copy; check it against the retry policy).
- Branding: the logo on light and dark, and both colours with the contrast checks from `lib/contrast.ts` (4.5:1 on the page, 3:1 on panels) shown as "Readable" or "Too little contrast" with the ratio. A preview of the top bar and sign-in in both themes. The error example uses #5b9bd5, which fails (2.96:1).
- Language and terms: Defaults (language, time zone) and Your terms each save on their own, as in the app. Terms per language; Arabic adds grammatical gender and right-to-left fields. An example sentence shows the English terms in use (new copy).
- API: nothing new.
