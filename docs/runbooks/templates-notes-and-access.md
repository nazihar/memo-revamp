# Memo templates, the note sheet and who can read a memo

**What they are:**
- **Templates (ADR-0048):** a memo type's version can carry a Word template with tags such as `{{memo.subject}}`. A new memo of the type starts its document from the template, filled with the memo's details. The template is checked on upload, and previewed with sample values before the version goes for review.
- **The note sheet (ADR-0049):** the memo's running record. It holds comments, decision remarks and corrections, all append-only, with mentions and attached files. Each decision is shown as an **authenticated approval** stamp: how the person signed in, the document version and its hash.
- **Access (ADR-0050):** a memo is read by its initiator, co-authors, approvers, the people notified, its **copy** recipients and people it is **shared** with, plus holders of `memo.read.unit` for the unit it comes from. Classification still applies on top.

**Who:**
- **Templates:** memo-type makers (`memo.types.manage`) upload them, and checkers (`memo.types.approve`) see them during review.
- **Shares:** the initiator, co-authors and approvers can share a memo. Each share needs a reason and can have an end date.
- **Copies:** the initiator and co-authors choose copies while the memo is a draft or returned. Holders of `memo.copy.add` can add copies after approval.
- **Distribution lists:** kept by holders of `memo.distribution.manage`.
- **`memo.read.unit`:** given by the tenant's administrators on purpose, for a unit subtree. It is not part of any built-in role.

## What must be running
| Component | Why | Without it |
|---|---|---|
| The malware scanner and object storage (files service) | Template uploads, previews, filled documents and note attachments are stored files | Uploads are refused; a memo of a type with a template is created without its document, and the initiator can retry with "Start from the template" |
| ONLYOFFICE Docs (`documents` profile) | The PDF of a template preview | The preview cannot be shown; the version cannot be submitted while its template is not previewed |
| The scheduler (`MEMO_JOBS_ENABLED=true`) | `memo-share-expiry-sweep` records lapsed shares | Nothing breaks for readers (the read rule checks the end time itself); only the "expired" audit entry and the share list's state wait |

## Settings
- **Tenant:** `memo.copy.comments` (`true` or `false`, default `false`) says whether copy recipients may write notes. The owner's decision (2026-11-15) is no by default.
- **Defaults to know:**
  - copies default to after approval;
  - a share lasts at most a year;
  - a note is at most 4,000 characters of text, with up to five files.

## Templates: what the check refuses
- **Refused at upload, with the rule's code:**
  - macro-enabled files (`.docm`, `vbaProject.bin`);
  - `.dotx`;
  - external relationships (linked images or templates, `attachedTemplate`);
  - `INCLUDE` fields.
- **Listed as problems with part, paragraph and a suggestion:**
  - unknown tags (for example `{{memo.numbr}}`);
  - unsupported or malformed tags;
  - unbalanced sections;
  - an approvals list outside a table row.
- **Blocks review:** a version with problems, or a template not previewed since its last upload, cannot be submitted.
- **What is not checked:** layout. Admins check the PDF preview.

## Monitoring
| Signal | Meaning | Action |
|---|---|---|
| Audit `memo.type.template_uploaded`, `template_previewed`, `template_removed` | Template changes on draft versions | Normal maker activity |
| Problem `TEMPLATE_FILL_FAILED` on create or "Start from the template" | Filling a template failed or took too long | Check the backend log for the template's file; preview the version again; the memo can still take an uploaded document |
| Audit `memo.share.granted`, `memo.share.revoked`, `memo.share.expired` | Who opened a memo to whom, and why | Review with the auditor; shares are listed on the memo with their reason |
| Audit `memo.copy.sent` with recipients not sent | A copy reached people not cleared for the memo, or inactive | Expected; they are listed as not sent in the receipts |
| Audit `memo.approval.checked` with problems | A stamp no longer matches what was approved | Treat as an incident: the approval record is append-only and hash-chained, so a mismatch means a change outside the application |

## Answering questions
- **"Who could read this memo?"** Open People with access on the memo. It lists approvers, copies (with who has read them), and shares (with who gave them, why, and until when). Unit readers are not listed per memo; list the grants of `memo.read.unit` under Access.
- **"Did they see it?"** Open the receipts from the copies count. It shows when each copy recipient and approver first opened the memo, and which document version they saw.
- **"Was this approval really theirs?"** Use Check on the stamp. It re-computes the record's hash, its link to the previous approval, and the document hash.

## Known limits
- **Copy sending:** copies sent after approval go out in the approval's own transaction, not in a background job (design §9 planned a job). It was measured within budget for the demo lists; very large lists (thousands of people) will slow down that approval.
- **Session hash:** the approval record holds a SHA-256 of the session, not an HMAC. The Check action is not rate-limited.
- **Unused files are not deleted yet:** previews and note files that were never posted are kept until a files deletion API exists.
- **Memo references in notes:** inserting a reference with `#` waits for search (E05). References already render, as "Memo (restricted)" for readers who cannot open the memo.
- **Mention suggestions** are not yet sorted to put the memo's readers first.
