---
status: accepted
date: 2026-11-14
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0044 — Memo numbering: formats, series, gapless counters and voids

## Context and problem statement
Memos are numbered `M-YYYY-NNNNN` from one counter per tenant and UTC year, taken when the draft is created. Organisations want their own formats (prefixes, unit and type codes, calendar or financial year, padding), resets per period, numbers taken at submit or at final approval, a register that proves no number is missing, and their legacy sequence continued at go-live (Phase 3 research R2). The master plan requires gapless numbers from a counter row locked in the business transaction, never a PostgreSQL sequence, and voids recorded and never reused.

## Decision drivers
- Gapless and unique under concurrency, with rollbacks leaving no gap.
- Configuration, not code; reviewed like the rest of the memo type (ADR-0043).
- Short lock times: numbering must not serialise more than it has to.
- Existing memos keep their references; today's sequence continues.

## Considered options
1. **The format in the memo-type version; counters per series, period and scope; the number taken late in the submit or final-approval transaction** (chosen).
2. A separate numbering-scheme entity with its own versions and review. Cleaner sharing, but a second reviewed entity and screen for what is, in practice, one setting of a memo type. Sharing is covered by the series name.
3. A PostgreSQL sequence per series. Not gapless.
4. Numbering after commit in a background job. Gapless, but the approver of an on-submit type would see an unnumbered memo, and the job adds a failure mode.

## Decision outcome
Chosen option 1.

### Format
- A list of parts: TEXT (literal, including separators), TYPE_CODE, UNIT_CODE (the memo's origin unit), YEAR (2 or 4 digits), FINANCIAL_YEAR (styles `2026-27` or `FY27`, from a start month set on the format), SEQUENCE (minimum digits 1–9, required exactly once). At most 12 parts; a rendered number is at most 100 characters.
- **Series:** a name (`^[a-z][a-z0-9-]{0,49}$`) shared by every type that should share one register. **Reset:** NEVER, YEARLY or FINANCIAL_YEAR. **Count per:** SERIES or UNIT (a sequence per origin unit; requires UNIT_CODE in the format).
- **Moment:** ON_SUBMIT (the first submit; a resubmission keeps the number) or ON_APPROVAL (the final approval). Drafts have no number and show as "Unnumbered".
- The period is computed in the tenant's time zone at the moment of numbering. The unit code is the origin unit's code at that moment and is stored as part of the number, which never changes afterwards.
- Publishing a version is refused if another series could render the same numbers: two series whose formats have the same literal parts in the same order and no type code that tells them apart (`SERIES_OVERLAP`). The unique index on `(tenant_id, reference)` remains the last guard; a collision there answers 409 and nothing is committed.

### Counters
- `memo_number_counter (tenant_id, series, period, scope, last_number)` with the primary key on the first four columns. `period` is `ALL`, `2026` or `FY2026-27`; `scope` is empty or the unit id.
- Taken with `INSERT … ON CONFLICT DO UPDATE SET last_number = last_number + 1 RETURNING last_number` in the business transaction, late: on submit after the memo is pinned and just before the workflow starts (so the first task and its notification already carry the number), on approval in the approval's own transaction. The row lock is held from there to commit, and only on one series, period and scope. A rollback undoes the counter and the memo together.
- **Starting number:** an admin with `memo.types.approve` and a fresh step-up may set the next number of a series, period and scope **once, before its first number is taken** (for continuing a legacy series). It is audited; afterwards it is refused (`ALREADY_STARTED`).

### Voids and the register
- A number, once taken, belongs to its memo for ever. Numbers whose memo was later withdrawn or cancelled (or, for Phase 2 references, discarded as a draft) are shown as **void** in the register; they are never reused. The reason is in the memo's history (its withdrawal or cancellation remark).
- While a memo of an on-approval type is in review it has no number: screens say "Unnumbered" and emails show a dash.
- The register lists every number of a series and period in order, with memo status and void reason, readable by memo-type admins and auditors (memo ids and numbers only, no subject for memos the reader cannot see). A check counts the numbers in each period against the counter and reports any difference (a nightly test on generated data, and on demand in the register).

### Existing data
- `memo.reference` becomes nullable (drafts) and its format CHECK becomes a length check; the unique index stays. Existing references are kept.
- Every existing type's version 1 gets the format `M-{YEAR:4}-{SEQUENCE:5}`, series `general`, yearly reset, ON_SUBMIT, and `memo_reference_counter` rows are copied to `general` counters so the sequence continues. The old table stays until the contract migration.
- `memo.origin_unit_id` is set at create from the initiator's primary assignment (Phase 1b adds a choice between assignments). A format with UNIT_CODE refuses submit with `NO_ORIGIN_UNIT` when there is none.

### Consequences
- Good: numbers are gapless, unique and configurable without code; the register proves completeness; drafts no longer use up numbers; the lock is short and scoped to one series and period.
- Bad: submits of one series serialise for the end of their transaction; acceptable at expected volumes (a bank's memo rate is far below one per second per series), and checked in the E05 load test.
- Neutral: on-create numbering is no longer offered. The legacy series stays on existing memos.

## More information
- Research: `docs/plan/phase-3-memo-efile/epic-01-types-metadata-numbering/research.md` §3.
- Related: ADR-0043 (memo-type versions), ADR-0035 (guarded transitions), ADR-0017 (tenant context).
