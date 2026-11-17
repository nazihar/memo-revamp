# E01 Memo types, metadata and numbering — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows; ASVS 5.0 IDs as in `docs/security/asvs-checklist.md` (chapter-level where the checklist groups them).

## Data flows
1. Tenant admin (maker) → memo-type draft (settings, fields, numbering) → submit.
2. Second admin (checker) → approve with step-up → published version → caches (types, attributes) → workflow catalogue, DoA checks.
3. Initiator → create or edit draft with metadata → submit → version pinned, number taken → workflow attributes read by the engine.
4. Workflow final approval → number taken (ON_APPROVAL).
5. Admin with approve → starting number for a series period.
6. Readers → memo view (metadata, links, linked from); admins and auditors → numbering register.
7. Scheduler → auto-close per tenant.

## STRIDE

| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | A maker approves their own type change by holding both permissions | 2 | `reviewed_by <> submitted_by` CHECK in SQL plus `SAME_PERSON` in the service; step-up on approve | V8 (authorisation), V2 business logic (sequential steps) |
| S2 | A stolen session publishes a type or sets a starting number | 2, 5 | Step-up (fresh MFA) on approve and start; both audited with acting user and IP | V6 / V7 (authentication, session), V16 |
| T1 | A memo's meaning is changed after submit by editing its type | 1–3 | Version pinned at submit; versions immutable once out of DRAFT; guarded moves with revision | V2 business logic, V15 (secure architecture) |
| T2 | Metadata tampered after submit through the API | 3 | `PUT /memos/{id}` refuses metadata outside DRAFT; database trigger refuses changes to `metadata`, `reference`, `memo_type_version_id` after they are set on a non-draft row | V2, V8 |
| T3 | A number reused, skipped or changed (fraud, hiding a memo) | 3, 4 | Counter row in the business transaction; unique index; number columns write-once (trigger); register with completeness check; voids derived from status and shown | V2 business logic, V16 audit |
| T4 | A starting number set after numbers exist, to create duplicates or gaps | 5 | Refused when the counter has issued numbers (`ALREADY_STARTED`); unique index as last guard; audited | V2, V8 |
| T5 | Two series rendering identical numbers | 1, 2 | `SERIES_OVERLAP` at submit and approve; unique index; 409 without commit | V2 |
| R1 | An admin denies having changed a type or a rule input | 1, 2, 5 | Every transition audited in the hash chain with before and after; review remark stored | V16 |
| R2 | An initiator denies the values they submitted | 3 | `memo.created`/`memo.updated` carry metadata keys; pinned version and values immutable after submit; `memo.type_pinned` | V16 |
| I1 | Metadata values (amounts, people) leak to users who cannot read the memo | 6 | Memo read policy unchanged and server-side; the register shows subjects only to readers; links to unreadable memos are hidden in both directions; link lookup answers "not found" for unreadable targets (no existence oracle beyond what the number itself implies) | V8 |
| I2 | Sensitive values in logs, emails or URLs | 3, 6 | Metadata never logged or put in URLs or email; audit stores LONG_TEXT as "changed" only; link lookup by number goes in the body, not the query string | V14 (data protection), V16 |
| I3 | Cross-tenant read of versions, counters or links | all | RLS ENABLE + FORCE, composite foreign keys, cross-tenant tests | V8, tenancy rules (AGENTS §3.2) |
| D1 | Huge field lists, choice lists, metadata or link counts slow every create | 1, 3 | At most 40 fields, 100 choices, 64 KiB fields JSON, 64 KiB metadata (SQL CHECK and request limits), 50 links | V2 (anti-automation), V13 |
| D2 | Contention on a counter blocks submits | 3 | Number as the last statement, short lock; per series, period and scope; lock timeout answers 503 with retry | V2 |
| D3 | A field pattern rule causes ReDoS | 1, 3 | No admin-defined regular expressions: text rules are lengths only; choice keys are fixed tokens | V1 (encoding), V2 |
| E1 | An attribute field is used to escalate routing (for example an initiator types a low amount to avoid a DoA band) | 3 | Not a technical bypass: values are the initiator's declaration, as in paper memos; approvers see them in the memo view; DoA re-checks at submit. Recorded as a business risk; Phase 3 E03 note sheet lets approvers challenge values | V2 |
| E2 | A user raises a memo type they are not allowed to | 3 | Raise scope checked server-side at create and submit; `GET /memo-types` only lists allowed types | V8 |
| E3 | An attribute removal silently breaks a published rule, making it always false (letting memos skip a DoA band) | 2 | `ATTRIBUTE_IN_USE` refusal at publish; conflicting types refused; tests for both | V2, V15 |
| E4 | Stored XSS through labels, help text or choice labels shown to every initiator | 1, 3 | Plain text only, rendered by React as text; length limits; no HTML or Markdown in labels | V1 (encoding), V3 (web frontend) |

## Residual risks
- **E1:** a dishonest initiator can enter a value that avoids a band. This is a business control (approver review, audit), not a software one.
- **Auditors:** they see register entries (number, status) for memos they cannot read. Numbers and statuses are not confidential in themselves; subjects stay hidden.
