# Workflow tasks waiting for reassignment

**When:** an approver can no longer act on a memo task: they were deactivated, or their clearance was lowered below the memo's classification after the memo was submitted. The task is created **blocked** (design E01 §2, invariant 12) instead of reaching them, and the memo waits.

**Who is told:** the memo's initiator and every holder of `workflow.tasks.reassign` (tenant administrators by default) get the in-app notice and the email "A memo is waiting for reassignment". The email carries the memo reference only.

**What to do** (organisation administrator, in the app):
1. Administration → **Open tasks** → filter **Waiting for reassignment**. The memo's title shows only if you are cleared for it; the reference always shows.
2. **Reassign**: pick someone active and cleared for the memo who is not already on its route and is not the author, give a reason, and confirm with your code (step-up).
3. Both the previous and the new approver are told; the action appears in the memo's history and in the audit log (`workflow.task.reassigned`).

**If reassignment is refused:** `ASSIGNEE_NOT_ELIGIBLE` (the person is inactive or not cleared: raise their clearance first, or choose someone else), `ASSIGNEE_IN_ROUND` (already on this route), `INITIATOR_AS_APPROVER` (the author), `REMARK_REQUIRED` (give a reason).

**Not an operator task:** platform operators cannot see or move tenant tasks (ADR-0031). If no one in the organisation holds `workflow.tasks.reassign`, a tenant administrator grants it (Administration → Roles).
