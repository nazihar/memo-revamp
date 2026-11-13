# E03 — UX review

The owner kept the per-epic gate delegation for Phase 2 (overview §10, Q1); only the E01 screen flows were reviewed by the owner before build. E03's screens follow E01's patterns (memo view, inbox, admin tables, dialogs with mandatory remarks, step-up). The flows below are checked against the R5/R11 benchmark and reviewed in the D1 demo session when it happens; findings are recorded here.

## Flows
1. **Author and publish (admin A, admin B):** Admin → Workflows → New (key, name, paste JSON) → validation errors listed by step → Submit for review. Admin B opens the review → sees the step list and the change against the published version → Approve (confirm code) or Send back with a remark.
2. **Choose it for a memo type:** Admin → Memo types → edit → Workflow: the published definitions; route policy Fixed / Initiator-defined / Both.
3. **Act on a task:** memo view → the step's buttons. Send back opens a dialog listing earlier steps of this round with their holders. Forward and Delegate open a person picker (eligible people only). Ask opens a person picker with the initiator first and a question field. Refer opens a multi-person picker and a "what to comment on" field.
4. **Answer and opinion:** the person asked or consulted sees the task in the inbox ("Clarify" or "Opinion"), opens the memo, writes the answer or opinion; the referrer sees opinions arrive in an Opinions panel.
5. **Recall:** the initiator sees Recall while nobody has acted yet; after recall the memo is editable again.
6. **Migration (admin B):** definition page → Running items on older versions → preview table (memo reference, current step, migratable / reason) → select → Migrate (confirm code).

## Findings
- (To be filled from the D1 demo session and the E03 review.)
