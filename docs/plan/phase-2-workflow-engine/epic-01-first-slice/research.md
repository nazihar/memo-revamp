# E01 First slice: sequential memo approval — research

All sources were accessed on **2026-11-09**.

## R1 — Versions, licences, maintenance

E01 adds **no new library**. Everything it needs is already in the build (jOOQ, Spring Modulith, db-scheduler, the files service, TanStack Query/Table/Form, react-intl). The candidates looked at:

| Component | Current | Licence | Decision |
|---|---|---|---|
| `@dnd-kit/core` / `@dnd-kit/sortable` (route ordering) | 6.3.1 (2024-12-05) / 10.0.0 (2024-12-04) | MIT | **Not adopted in E01.** No release for almost two years. Its successor `@dnd-kit/react` is 0.5.3 (2026-10-31), pre-1.0. The route is a short ordered list (typically 2–8 approvers); move-up / move-down buttons are the keyboard path WCAG 2.2 needs anyway (2.5.7 Dragging Movements), so drag-and-drop adds a dependency for no capability. Phase 4's agenda builder re-evaluates (AGENTS §2 names dnd-kit there) |
| Flowable (`flowable-engine`) | 8.0.0 (2026-02-26), Spring Boot 4 and Spring Framework 7 | Apache-2.0 | **Not adopted** (R2) |
| Operaton (Camunda 7 CE fork) | 2.2.1 (2026-11-07) | Apache-2.0 | **Not adopted** (R2) |
| Spring Boot | 4.1.x in the build; 4.2 GA still a STATUS follow-up | Apache-2.0 | Unchanged; E01 takes 4.2 as an ordinary Renovate PR if it lands |

Sources: `npm view @dnd-kit/core`, `npm view @dnd-kit/sortable`, `npm view @dnd-kit/react` (registry.npmjs.org), https://documentation.flowable.com/latest/admin/release-notes/latest, https://forum.flowable.org/t/flowable-8-0-0-release/12548, https://github.com/operaton/operaton/releases, https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html

## R2 — Engine approach: our own vs embedded

The master plan expects our own engine "unless research shows clear advantage". Requirements that decide it: data-driven JSON definitions edited by tenant admins (E03, E06); rounds within one instance; every table under forced RLS with `tenant_id` first in every index (AGENTS §3.2); audit in the same transaction through `AuditLog` (§3.3); guarded transitions and idempotency keys; tenant context carried into every timer (ADR-0017); on-prem with few moving parts (§7).

| Option | Fit | Cost |
|---|---|---|
| **Own engine on PostgreSQL** (state machine in plain Kotlin, jOOQ tables in the `workflow` module) | Exactly our model: rounds, document binding, clearance re-checks, "who holds it now", acting-for in the actor. Tables follow our RLS and index rules; audit and events through the existing kernel; timers on db-scheduler with tenant payloads (ADR-0018). Unit- and property-testable without Spring (TESTING_STRATEGY §2) | We build what an engine gives for free: parallel joins, timers, sub-workflows, definition versioning (E03–E05). Scoped by the master plan, and each is a bounded piece |
| **Flowable 8 (Apache-2.0)** | Mature BPMN/CMMN engine, Spring Boot 4 support since 8.0.0. Rich step semantics | About 80 tables of its own (`ACT_*`, `FLW_*`) with no `tenant_id`-first indexes and no RLS; its own multi-tenancy is a `TENANT_ID_` column filtered in Java, which AGENTS §3.2 does not accept. Its job executor would run beside db-scheduler (two schedulers, two tenant-propagation stories). BPMN XML is not what tenant admins edit (E06 needs a simpler model and would translate). Audit lives in its history tables, outside our hash chain. Upgrades and schema migrations are Flowable's, not expand → contract under our control |
| **Camunda 7 CE → Operaton / CIB seven (Apache-2.0)** | Same engine family as above | Camunda 7 Community Edition reached end of life with 7.24 in October 2025; the community forks are young (Operaton 2.x, monthly releases). The same RLS, scheduler and audit mismatches as Flowable, plus a younger maintainer base on the security path |

**Recommendation: our own engine** (ADR-0034). An embedded engine gives the features of E03–E05 earlier but violates three non-negotiables (forced RLS on every tenant table, audit in our chain in the same transaction, one tenant-aware scheduler), and wrapping it to comply would cost more than the bounded features it saves. The engine's core stays small: a pure-Kotlin transition function over instance state, which E03 widens with step types.

Sources: https://documentation.flowable.com/latest/admin/release-notes/latest, https://www.flowable.com/open-source/docs/bpmn/ch05a-Spring-Boot, https://camunda.com/platform-7/, https://forum.camunda.io/t/camunda-7-community-edition/58607, https://github.com/operaton/operaton/releases, https://wz-it.com/en/blog/camunda-7-end-of-life-operaton-cibseven-fluxnova-compared/

## R3 — Double actions: guarded updates and idempotency keys

Three ways the same action can happen twice: a double click (same browser, same key or none), a retry after a network failure (same key), and two people or two devices acting at once (different keys).

| Mechanism | Stops | Notes |
|---|---|---|
| **Idempotency key** (client-generated UUID per user intent) | Retries and double clicks with the same key | Replays the first result instead of failing. The existing `system.Idempotency` claims the key row first in the business transaction, so a concurrent repeat blocks on the unique index until the first commits, then reads its stored result. A reused key with another body → 422 |
| **Guarded update** (`UPDATE … WHERE id = ? AND state = ? AND version = ?`, 0 rows → 409) | Two different actions on the same task or instance | No lock held across the request; the loser gets `409 conflict` with the current state. AGENTS §4 (database patterns) |
| `SELECT … FOR UPDATE` on the instance | Same as guarded update | Serialises at the row; useful where several rows change together (the task, its step, the instance). Used inside the transaction after the guard, never across user think time |

The IETF draft "The Idempotency-Key HTTP Header Field" is at revision 08 (2026-10-22), an active working-group draft, not yet an RFC. It recommends: a UUID key per request; a payload fingerprint; replaying the original result for a completed request; **409** for a retry while the original is still in flight; **422** for the same key with a different payload; **400** when a required key is missing; keys scoped to the client so they cannot be guessed. Our implementation already matches except the in-flight case, where we block and replay instead of answering 409 (the client sees one consistent answer); the key is scoped per tenant and operation and stored with a request hash. One gap: the key is not scoped to the user. E01 adds the actor to the stored fingerprint (a key reused by another user is a 422, never a replay of someone else's result).

**Recommendation:** every workflow and memo action takes a required `Idempotency-Key` (400 without), runs inside `Idempotency.once`, and inside it performs a guarded update on the task and the instance (ADR-0035). The web client creates the key when the user opens the action dialog, so a double submit of the same dialog replays, and a second dialog on another device conflicts.

Sources: https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/, https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-07.html

## R5 — How products handle return, resubmission and rounds (first-slice part)

| Product family | What happens on return / rejection and resubmission | What we take |
|---|---|---|
| Salesforce approval processes | A submitted record is locked; the submitter (if allowed) or an admin may **recall** it. Every submission creates a **new process instance**; the history lists instances one after another | Lock during review; recall (E03). Do better: one instance with numbered rounds, so "the third attempt at this memo" is one record with one history, not separate instances that auditors must stitch together |
| Microsoft Power Automate approvals | Sequential approvals in one action; rejection ends the flow. "Resubmit" re-runs the flow, creating a brand-new approval; built-in history has limited retention, so guides recommend copying outcomes to a list | Do better: history is part of the record (append-only, hash-chained audit, no retention gap), and a resubmission continues the same instance |
| ServiceNow approvals | Approvals are records against the requested item; a rejection or a change can **reset** approvals so they are requested again | Earlier approvals are not deleted when the round restarts; they stay visible as "round 1" and never count toward round 2 |
| Government e-office (NIC e-Office and similar) | The file carries an append-only **note sheet** ("green note" is the permanent record; "yellow note" is a draft remark that can be revised); file **movement history** shows every hop; a sent item can be pulled back until the receiver opens it | Note sheet as an append-only record of every action, with the remark, the round and the document version; drafts of remarks are not in the record until the action is taken. The movement view becomes our "history by round". Pull-back before the first action is E03's recall |

What auditors need from the history (from the above and AGENTS §3.3): who acted, in what capacity (acting for whom), what they saw (the document version and its hash), when, with what remark, and in which round. Approvals from an earlier round must be visible but clearly marked as not counting.

**Labels in the memo view:** "Round 1", "Round 2" with the action that ended each round ("Returned by X on D: remark"); the current round on top and expanded, earlier rounds collapsed. Status words: Draft, In review (round N), Returned, Approved, Rejected, Withdrawn.

Sources: https://developer.salesforce.com/docs/atlas.en-us.api_action.meta/api_action/actions_obj_recall_approval_submission.htm, https://blog.cloudanalogy.com/salesforce-approval-process/, https://learn.microsoft.com/power-automate/approve-reject-requests, https://matthewdevaney.com/easiest-power-automate-sequential-approval-flow-pattern, https://www.servicenow.com/docs/r/HGDW_sLOfs0QXhKu64QAng/m2ze82MocJuY3PF3EoJJqg, https://mpforest.gov.in/img/files/eOffice_User_Manual.pdf, https://edudel.nic.in/edu/e_offince_dt_11122024/EOfficeFAQ.pdf, https://tgtransco.com/it_uploads/file_creation.pdf

## Main document type
The first slice uploads `.docx` (master plan). The files service already recognises DOCX from content and refuses DOCM and legacy OLE2 (ADR-0029). E01's memo document purpose allows **DOCX only**; PDF as a main document and attachments of other types are Phase 3. Retention: memo documents are records (object-locked `records` bucket) with the tenant's record retention.

## Step-up on approve (Q5)
The step-up window today is one tenant setting (`security.stepup.minutes`, default 5, 1–30) used for every sensitive action (ADR-0024). Q5 asks for a separate, longer window for approvals so a sitting of approvals needs one code. Design: a second setting `security.stepup.approval.minutes` (default 30, range 1–120); `StepUpCheck.require(window)` takes the window, and the memo's approve action passes the approval window. The longer window only relaxes approvals; security-setting changes keep the short one.
