# D1 pilot demo — sequential memo approval

A 20-minute walk through the first slice on the dev environment (`https://dev.memo-revamp.naziharitsolution.com`) or locally (`pnpm dev`, then `http://localhost:5173`). Every demo account uses the password and TOTP secret in `deploy/compose/.env` (`MEMO_DEMO_PASSWORD`, `MEMO_DEMO_TOTP_SECRET`); add the secret to an authenticator app first. Use two browser windows (one private) to switch people quickly.

| Account | Role in the demo |
|---|---|
| `user@demo.example` | Initiator: writes and submits memos |
| `head.credit@demo.example` | First approver (Demo Head of Credit) |
| `head.finance@demo.example` | Second approver (Demo Head of Finance) |
| `director@demo.example` | Last approver (Demo Director) |
| `admin@demo.example` | Reassigns a task whose approver left |

The demo tenant already holds memos in every state (design §15): a draft, two in review, one returned, one resubmitted as round 2, one approved, one rejected, one withdrawn and one waiting for reassignment.

| # | Show | Who, where | What to point out |
|---|---|---|---|
| 1 | Inbox | `user@` → Inbox | Tabs My tasks, Drafts, Sent (who holds each memo now), Closed; the same on a phone |
| 2 | Create a memo | New memo | Subject, type, classification (only levels up to my clearance); the draft gets a reference such as `M-2026-00012` |
| 3 | Document and route | The new draft | Upload a Word file (a macro file renamed `.docx` is refused); add approvers Head of Credit, Director with the people picker, reorder with ↑ ↓; adding yourself is refused with the reason |
| 4 | Submit | Submit | The memo is locked: no edits, no new versions; the Head of Credit gets an in-app notice and an email with the reference only, never the subject |
| 5 | Return | `head.credit@` → Inbox → the memo → Return | A remark is required; the history shows round 1 with the remark |
| 6 | Version 2 and resubmit | `user@` → the memo | Upload a new version (allowed again while returned), Resubmit: round 2 starts with the Head of Credit; round 1 is marked "its approvals no longer count" |
| 7 | Approve with step-up | `head.credit@`, then `director@` → Approve | The first approval asks for the authenticator code (step-up, valid 30 minutes for approvals); the second approver is not asked again within the window; the memo is Approved and the author is told |
| 8 | The note sheet | The approved memo → History | Who did what, when, with which document version (each action is bound to the version's SHA-256) |
| 9 | Reassignment | `admin@` → Administration → Open tasks → Waiting for reassignment | "Replacement of the branch generator": its approver left; the title is hidden unless the admin is cleared; Reassign to the Head of Finance with a reason (step-up); both approvers are told |
| 10 | Audit | `admin@` → Administration → Audit, filter `workflow.` | Every submit, approval, return and reassignment, with the actor and the document hash |

Automated evidence: the Playwright journey `web/e2e/smoke/memo.spec.ts` (create → upload → route → submit → return → version 2 → resubmit → round 2 → approve → Approved, and the admin reassignment) runs on every pull request; the backend journeys are `MemoReviewIT`, `MemoReassignIT`, `MemoNotificationIT` and `DemoMemosIT`.
