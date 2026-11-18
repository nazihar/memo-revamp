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

## Behaviour notes

• Team tab appears only for people who oversee a unit (as today); everyone else sees five tabs.
• Organisation name and logo come from tenant branding; it is a label, not a switcher.
• Under the user's name we show the email: the user record has no job title.
• Row "⋯" menu: Claim, Release, Watch for that one task (existing endpoints).
• Show more loads the next batch by cursor; 50 per batch on desktop, 25 on phones.
• Forms validate in the app (TanStack Form), never with browser HTML validation: noValidate on every form, our own messages under the field, focus to the first error, submit stays enabled.
• New states the app does not handle yet: Page not found (unknown address), Connection lost (offline bar) and Signed out (session ended mid-task). See the Page states row.

## Build note

Use shadcn/ui components for everything: Button, Input, InputOTP, Select, Combobox, Tabs, Table, Dialog, Drawer, AlertDialog, DropdownMenu, Popover, Badge, Skeleton, Alert, Sonner, Sidebar. These boards are hand-written HTML only because the canvas cannot run React; do not copy their inline styles. The component for each pattern is listed on the Design rules page, section 1.
