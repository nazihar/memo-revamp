---
status: accepted
date: 2026-11-09
deciders: Product owner (Phase 2 overview Q5; gate delegated), Claude (agent)
---

# 0036 — Memo documents: version binding, lock during review, step-up on approve

## Context and problem statement
Every approval must be provably bound to the exact document it approved (MASTER_PLAN Phase 2 step 7), and the document must not change while a round is in review. Approvals are sensitive actions that need fresh MFA (AGENTS §3.4), but the owner asked that senior approvers are not prompted constantly (overview Q5).

## Decision drivers
- Non-repudiation: who approved which bytes
- No change during review, without trusting the UI
- Approver experience for senior management (Q5)
- Reuse the files service (ADR-0029) and step-up (ADR-0024)

## Considered options
For binding:
1. Store the document version id and SHA-256 on every action, and lock uploads in the memo's state
2. Store only the version id
3. Copy the document per round

For the step-up window:
1. One window for every sensitive action (today: 5 minutes, up to 30)
2. A separate approval window (default 30 minutes)

## Decision outcome
**Binding:** option 1. The memo's main document is one file in the files service (purpose `memo.document`: DOCX only, record storage, reads audited). Each `workflow_action` row carries a subject snapshot with the current version id and its SHA-256. The memo's `FilePurpose.mayUpload` answers false unless the memo is `DRAFT` or `RETURNED` and the actor is the initiator. The workflow's subject callback refuses submit and resubmit when the document has no version, so the lock is enforced in the files service's own transaction, not in the UI. A round records the version it started with; an approver always sees that version. The open task cannot point to a newer one, because no newer one can exist during review.

**Step-up:** option 2. `StepUpCheck.require(window)` gains a window parameter. A new tenant setting `security.stepup.approval.minutes` (default 30, range 1–120) is used for approve. A per-memo-type flag `stepUpOnApprove` defaults to true, and a tenant admin may turn it off. Security-setting changes keep the short window.

### Consequences
- Good: the history proves which bytes each approver saw; the hash also detects a version row that was swapped (the files service verifies content against its SHA-256 on read).
- Good: one MFA code covers a sitting of approvals; passkeys (Phase 1b) and mobile biometrics (Phase 6a) reduce it to one touch.
- Bad: a 30-minute window is weaker than 5 minutes for approvals. It is accepted by the owner and bounded by the session timeouts, and a tenant can shorten it.
- Follow-ups: Phase 3 widens the purpose (PDF main documents, attachments) and adds the side-by-side version comparison; E02 adds ONLYOFFICE saves through the same purpose and lock.

### Confirmation
Integration tests: upload refused in review (by API, regardless of UI); every action row carries the version and hash of the document at that moment; approve without a fresh factor → `401 step-up-required` with the approval window; approve within the approval window but outside the short window → allowed; flag off → no prompt.

## More information
ADR-0024 (step-up), ADR-0029 (purposes and record storage), research §"Step-up on approve".
