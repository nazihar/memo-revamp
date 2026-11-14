# Memo types and numbering: publishing types, starting numbers, checking the register

**What they are:** a memo type says what a memo asks for (subject, classification and the type's own fields), which workflow it runs on, who may raise it, and how its memos are numbered. Each type has versions; one is **published** at a time, and every memo keeps the version it was submitted with (ADR-0043). Numbers come from a **series**; types that share a series share one register. A number is given once, at submit or at final approval as the type says, and never changes or repeats (ADR-0044).

**Who:**
- Makers hold `memo.types.manage`; checkers hold `memo.types.approve`. Tenant administrators hold both by default.
- A version is always reviewed by someone other than the person who submitted it.
- Publishing a version and setting a starting number need a fresh confirmation (step-up).

## Changing a memo type (organisation administrators, in the app)
1. Administration → **Memo types** → the type → **New draft** (a copy of the published version), or **New memo type** (name and code only).
2. Edit the tabs:
   - **Settings:** name, code, workflow, approvers, who may raise it, retention, closing and attachments.
   - **Form:** add, edit, reorder and remove fields; the preview shows the form as authors will see it.
   - **Numbering:** the parts of the number, the series, when it restarts, whether each unit counts on its own, and when the number is given. The sample shows the next number now and at the start of the next period.
3. **Save**, then **Submit for review**.
4. A second person opens the version. The **Changes** tab opens first and lists what changed. They choose **Approve and publish** (they confirm it is them) or **Send back** with a remark.
5. Memos submitted from then on use the new version. Drafts show a notice if fields they had were removed. Memos already submitted keep their version.

## Continuing a legacy series (starting number)
When a bank moves an existing paper or legacy register into memo, its numbers should continue rather than restart at 1.
1. Publish the type with the series and format the legacy register uses.
2. Administration → **Numbering** → the series → **Set a starting number**. Enter the period (for example `2026` or `FY2026-27`), the unit when each unit counts on its own, and the **first number** memo should give: one more than the last number used in the legacy register. Confirm with the authenticator code.
3. This works only for a period with no number issued yet. Once a memo has a number in that period, the starting number cannot change (`ALREADY_STARTED`). Set it before the first memo of the period is submitted.
4. It is recorded as `memo.numbering.started` in the audit log, and the register marks the counter as started by an administrator; the completeness check then starts from the first number given in memo.

## Checking the register
Administration → **Numbering** → the series → **Register**.
- Every number appears with its memo and status. Subjects show only to people who may read the memo.
- **Void** numbers belong to memos that were withdrawn, rejected or cancelled. Their numbers stay used; they are never given again.
- **Complete** means every number from the first to the last has its memo. A missing number should not happen; if one appears, report it as an incident with the series, period and number. Numbers are given inside the same database transaction as the submit or approval, so a failure gives no number at all.

## If something is wrong
- **A published version has a mistake:** publish a fixed version. There is no "unpublish". Memos already submitted keep the version they were submitted with, by design.
- **Two types would print the same numbers:** publishing is refused (`SERIES_OVERLAP`). Give one type a different series, or a different format.
- **A field a workflow rule uses was removed:** publishing is refused (`ATTRIBUTE_IN_USE`). Change the rule first, or keep the field.
- **Refusals:**
  - `SAME_PERSON`: ask another reviewer.
  - `DRAFT_EXISTS`: finish or send back the version in progress.
  - `WRONG_STATE`: only drafts change, and only versions in review are decided.
  - "Another memo type has this code": type codes are unique within the organisation.

**Not an operator task:** platform operators cannot see or change tenant memo types or registers (ADR-0031).
