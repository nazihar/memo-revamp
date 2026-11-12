# Workflow definitions: publishing, retiring and moving memos

**What they are:** a workflow definition says how a memo moves from step to step (reviews, approvals, branches on the memo's classification or type, parallel approvals, notifications). Memo types choose one. Each definition has versions; one is **published** at a time, and every memo keeps the version it started on (ADR-0038).

**Who:** authors hold `workflow.definitions.manage`; reviewers hold `workflow.definitions.approve`. Tenant administrators hold both by default. A version is always reviewed by someone other than the person who submitted it.

## Changing a workflow (organisation administrators, in the app)
1. Administration → **Workflows** → the workflow → **New draft** (a copy of the published version), or **New workflow**.
2. Edit the definition (JSON, schema version 2) or **Import a file**, then **Save draft**. **Check** lists every problem with where it is (a JSON pointer such as `/steps/2/next`).
3. **Submit for review**. Submitting needs a saved draft without problems.
4. A second person opens the version in review and chooses **Approve and publish** (they confirm it is them) or **Send back** with a remark. Publishing retires the version that was published before.
5. To use a new workflow for a memo type: Administration → **Memo types** → the type → **Workflow**. "The workflow names every step" (`FIXED`) means its memos have no route; the other two choices need a definition with a `route` step.

## Memos already running
Running memos stay on their version. To move them to the published version: the workflow's page → **Memos on older versions** → **Find memos** → select → **Move selected** (step-up, at most 100 at a time). A memo moves only if every step it is at still exists in the new version with the same type; otherwise the list says why (`STEP_MISSING`, `STEP_CHANGED`) and it stays where it is. Returned memos always move: their next round starts on the new version. Each move is a `MIGRATE` entry in the memo's history and `workflow.instance.migrated` in the audit log.

## If something is wrong
- **A published version has a mistake:** write a fixed version (New draft), have it reviewed and published, then move the running memos to it. There is no "unpublish"; retiring stops new memos only.
- **A memo is stuck at a step nobody can act on:** reassign the task (see `workflow-reassignment.md`) or, as a last resort, cancel the memo's workflow with a reason (Administration API `POST /api/v1/admin/workflow/instances/{id}/cancel`, `workflow.instances.cancel`, step-up). The author can then start a new memo.
- **Refusals:** `SAME_PERSON` (ask another reviewer), `IN_USE` on retire (move the memo types to another workflow first), `DRAFT_EXISTS` (finish or send back the version in progress), `DEFINITION_INVALID` (fix the listed problems), `BUILT_IN` (the built-in sequential approval cannot change).

**Not an operator task:** platform operators cannot see or change tenant workflows (ADR-0031).
