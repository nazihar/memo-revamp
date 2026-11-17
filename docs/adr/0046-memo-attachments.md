---
status: accepted
date: 2026-11-14
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0046 — Memo attachments: typed by the tenant, stricter classification only, bound to each submission

## Context and problem statement
Bank memos carry supporting papers: statements, quotations, valuations, spreadsheets. Phase 2 memos have one main document. E02 adds attachments with the master-plan rules: any type the tenant allows, a preview, a classification that may only be stricter than the memo's, the memo type's attachment rules (E01: required, at least N), and a lock during review like the document. What is an attachment, who may see it, and how does the record show what the approvers saw?

## Decision drivers
- The record is sacred (`AGENTS.md §3.3`): an approval must say which attachment versions, with which hashes, it covered; nothing is deleted.
- Classification is enforced on the server and never lowers what the memo already is.
- One upload path: scanning, encryption, record retention and audit come from the files service (ADR-0029).
- A reader must not learn the name of an attachment they are not cleared to open.

## Considered options
1. **An attachment row per file in the memo module, files of a new purpose `memo.attachment`, an optional stricter classification, and a per-round binding of attachment versions written at submit.**
2. Attachments as extra versions or parts of the main document file.
3. Attachments as workflow-level objects in the `workflow` module.

## Decision outcome
Chosen option 1.
- **`memo_attachment`:** memo, file (purpose `memo.attachment`, record storage, its versions are the attachment's versions), title (the file name by default), optional `classification_id` with its rank, which must be strictly above the memo's rank when set (null: the memo's own level), position, added by and at, removed by and at. Rows are never deleted; removing sets `removed_at` and is allowed only while the memo is editable.
- **Who changes them:** the initiator and the memo's co-authors (ADR-0047), only while the memo is `DRAFT` or `RETURNED` (the document's rule). At most 50 attachments per memo; each file at most 50 MiB (the platform ceiling and the tenant setting can only lower it).
- **Types:** the purpose allows the safe list (PDF, images, text and CSV, OOXML and ODF documents, ZIP); the tenant setting `memo.attachments.types` narrows it. Macro-enabled and legacy OLE2 formats stay refused (ADR-0029).
- **Who sees them:** readers of the memo. An attachment with a stricter level is listed to someone not cleared for it as "Restricted attachment" without its title, type or size, and its content, renditions and text answer 404. The effective level of an attachment is the higher of its own and the memo's, so raising the memo's classification in a draft raises them with it.
- **Binding:** submit and resubmit write `memo_submission_attachment` (memo, round, attachment, file version, SHA-256) for every attachment then present. The history shows the bound list for each round; the final rendition (E04) uses it. A round's binding never changes.
- **Rules:** the type version's `attachmentRules` are checked at submit (`ATTACHMENTS_REQUIRED` with the number needed).
- **Preview and text:** both through ADR-0045 renditions, under the attachment's effective classification.

### Consequences
- Good: approvals cite exactly what was attached; removed attachments stay in the record of the rounds that had them.
- Good: no new file pipeline; attachments get scanning, encryption, retention and audit for free.
- Bad: one more table to filter by clearance in every list that shows attachments; covered by a single store method and the matrix tests.
- Bad: a reader cleared for the memo but not for an attachment learns that a restricted attachment exists. Accepted: it tells them to ask, and hides everything else.

### Confirmation
`MemoAttachmentsIT` (stricter-only, lock during review, rules at submit, per-round binding, restricted listing, removal kept), matrix `p3-e02-attachments.yaml`, and the E2E attachment journey.

## Pros and cons of the options
### Option 1 — attachment rows, a new purpose, a per-round binding
- Good: fits the files service and the memo's ownership of its content; clear audit.
- Bad: a second table for submissions.
### Option 2 — part of the main document file
- Good: no new table.
- Bad: versions of one file would mean different documents; classification per attachment impossible.
### Option 3 — workflow-level objects
- Good: reusable by other subjects.
- Bad: the workflow module knows nothing of files or classification; it would invert module boundaries.

## More information
Design: `docs/plan/phase-3-memo-efile/epic-02-documents/design.md` §1–§3. Related: ADR-0029 (uploads), ADR-0043 (attachment rules on the type), ADR-0045 (renditions), ADR-0047 (co-authors).
