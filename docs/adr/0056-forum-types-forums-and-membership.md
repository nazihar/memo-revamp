---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar
---

# 0056 — Forum types, forums and membership

## Context and problem statement
Phase 4 runs committees and boards on the platform. Every later epic (submissions, agendas, recusal, quorum, minutes, actions) needs to know three things: which committees exist, what policy they follow, and who sits on them in which role on a given date. Board portals model membership loosely: a role is often only a label, there is no voting flag, and terms are tracked only in the better tools. Bank charters, though, rely on roles, voting rights, independence and terms. Research: `phase-4-forums-meetings/epic-01-forum-types-forums/research.md §3.1, §3.2, §3.4`.

## Decision drivers
- Configuration over code (AGENTS §3.5). Policy that changes what counts as a valid decision is sensitive configuration, so it gets maker-checker.
- Membership drives access, so administrators never set permissions twice (research §3.1).
- History is kept: who was chair on 12 March must stay answerable.
- Fits the existing patterns: `common.review` versions (ADR-0043), relationship predicates in the owning module (ADR-0050), and the permission catalogue (ADR-0027).
- Friendly for company secretaries: presets, cloning, plain wording.

## Considered options
1. **Versioned forum types (policy) + unversioned forums + one membership row per term** (chosen).
2. Policy fields directly on each forum, with no types. This is simpler, but a bank with 20 committees edits the same quorum and outcomes 20 times, and nothing keeps them aligned.
3. Membership as authz role grants (a role "BRC member" scoped to a forum). This reuses ADR-0027, but grants have no term, voting, alternate or attribute, and the authz module would have to learn forums.
4. Maker-checker on membership changes. No portal or charter found requires it, because the board approves appointments outside the tool. It adds friction to the commonest task.

## Decision outcome
Chosen option 1.

### Forum types
- `forum_type` holds the identity: id, upper-case `code`, active, published version. `forum_type_version` holds the policy, in states DRAFT → IN_REVIEW → PUBLISHED → RETIRED through `common.review`.
  - Maker: `forum.types.manage`. Checker: `forum.types.approve`, with step-up on approve.
  - Same-person approval is refused. Send-back needs a remark. There is at most one open and one published version per type.
- **Fields in E01:**
  - name and description;
  - default classification, and retention `{years, from: MEETING}` (stored only; Phase 7 acts on it);
  - **member attributes**: up to 8 `{key, label}`, for example `independent`, `nonExecutive`, `management`;
  - the **quorum rule** (ADR-0057);
  - **outcomes**: a subset of the fixed kinds APPROVED, APPROVED_WITH_CONDITIONS, REJECTED, DEFERRED, RETURNED, REFERRED, NOTED, WITHDRAWN. Each has a tenant label (1–60 characters) and `resolution` (whether a decision of that kind is numbered, used by E06). What each kind does to a waiting memo is fixed in code (E02);
  - the **term policy**: default term length in months (optional), maximum consecutive terms (optional, a warning only), and reminder days (default `[42, 1]`, up to 3 values from 1 to 365).
- Later epics add their own fields to the version in expand-only migrations: submissions (E02), agenda template (E03), invitations (E05), minutes and resolution numbering (E06).
- **Presets** in code (Board, Board committee, Management committee) are offered only when a type is created. They are copied into draft version 1 and edited like anything else.
- Activate and deactivate are direct (`forum.types.manage`, audited). A deactivated type cannot be chosen for new forums; its forums keep working.

### Forums
- `forum` fields:
  - `key`, matching the workflow schema `^[a-z][a-z0-9-]{0,99}$` and unique per tenant;
  - `code` `^[A-Z0-9][A-Z0-9-]{0,9}$`, unique per tenant, for meeting and resolution numbers;
  - name and short name;
  - the forum type, followed at its **current published version** (E03 pins the version on each meeting);
  - an optional parent forum, one level deep or more but acyclic;
  - a classification, defaulting to the type's;
  - status ACTIVE or DISSOLVED, with the date and reason;
  - `version` for If-Match.
- Forums are operational records, not reviewed policy. Creating, editing, dissolving and cloning need `forums.manage`, and every change is audited with before and after. A dissolved forum keeps its history, takes no new members, submissions or meetings, and cannot be the target of a newly published workflow definition.
- **Clone:** copies the forum's settings into a new forum (new key, code and name) and, if asked, the members in office with their roles, attributes and alternates, starting on a chosen date.

### Membership
- `forum_member` has one row per **term of office**:
  - a person **or** a position (an ex-officio seat that follows its holder through `OrgDirectory.holders`);
  - a role: CHAIR, VICE_CHAIR, SECRETARY, MEMBER, STANDING_INVITEE or OBSERVER;
  - `voting`, defaulting to true for CHAIR, VICE_CHAIR and MEMBER and false for the others. It can be changed per row, because voting is not the same as the role (research §3.1);
  - the member attributes (keys from the type);
  - an optional alternate person;
  - `starts_on`, an optional `term_ends_on` and `ended_on` with an end reason;
  - `term_number`, and an optional `authority` (up to 500 characters, for example "Board resolution BOD/2026/031 of 12 October 2026").
- **In office on day d:** `starts_on ≤ d`, not ended before d (`ended_on IS NULL OR ended_on ≥ d`), and not past an unrenewed term (`term_ends_on IS NULL OR term_ends_on ≥ d`). A member past the term end is out of office and shown as "term ended, renew or end". Nothing is removed automatically.
- **Changes:**
  - Changing the role and renewing a term both end the row and start a new one (renewal: `term_number + 1`).
  - Voting, attributes, alternate, planned term end and authority are edited in place with If-Match, and audited with before and after. Decisions record their own snapshot (E05, E06), so later edits never change past decisions.
  - A row that has not started yet can be withdrawn. Any other row is ended, never deleted.
- **Invariants**, checked under a lock on the forum row:
  - at most one CHAIR in office on any day;
  - one person has at most one row in office per forum on any day;
  - a position seat is not duplicated;
  - an alternate is not the member themselves.
- **Who manages members:** holders of `forums.manage` manage any forum. A member in office as SECRETARY manages the members of that forum, except that they cannot add, change or end SECRETARY or CHAIR rows or their own row. The chair and secretaries are notified of every membership change, which is the detective control in place of maker-checker (option 4).
- **Term reminders:** a daily job per tenant notifies a forum's secretaries, or the holders of `forums.manage` when it has none, on each reminder day before a planned term end. A reminder table keyed by row and day keeps it idempotent.

### Access
- **New permissions:**
  - `forum.types.manage` and `forum.types.approve` (privileged);
  - `forums.manage` (privileged);
  - `forum.read`: see every forum and its membership up to one's clearance, for example internal audit. Granted by tenants only, like `memo.read.unit`.
- **Forum-relationship access** belongs to the forum module:
  - `ForumDirectory` (published API) answers members on a date, roles of a user, whether someone is secretary, and the forums of a user;
  - `ForumVisible` is a SQL predicate. A user sees a forum when they are or were a member, or hold `forums.manage` or `forum.read`, and in every case their clearance covers the forum's classification;
  - later modules build their checks on these.
- The roster is cached per tenant (`TenantCaches`, ADR-0020) and evicted on every membership or forum change.

### Workflow link
- The workflow module declares an `EscalationTargets` interface, which the forum module implements. Publishing a definition whose `escalate-to-forum` step names a key that is not an active forum is refused with `UNKNOWN_FORUM` at the step's JSON pointer.
- Definitions already published are not re-checked. E02's consumer handles a forum dissolved later.
- `GET /api/v1/admin/workflow/escalation-targets` (`workflow.definitions.manage`) lists active forums for the builder's picker.

### Wording
A new terminology term `forum` ("committee", "committees" in English) labels the screens, and tenants may rename it (ADR-0032).

### Consequences
- Good: one policy template for many similar committees; history by row; access follows membership; secretaries do their own work without central administrators; presets and cloning shorten set-up.
- Bad: a forum follows its type's published version, so a published change affects every forum of the type for future meetings. The review screen says so, and meetings pin their version (E03).
- Bad: position seats make membership depend on the org tree, so the roster cache is also evicted when the org changes (`OrgChanged`).
- Follow-ups: optional maker-checker on membership, as a tenant setting, if a customer asks; the standing register of interests (Phase 4 Q6); member self-service profile (Phase 5 director mode).

### Confirmation
- Matrix file `p4-e01-forums.yaml`, with the endpoint-coverage meta-test.
- The invariant tests under concurrency (two chairs added in parallel: one is refused).
- The cross-tenant tests for every new table.
- The audit coverage test.
- `ForumDirectory` property tests: in-office on a date, against a reference model.

## Pros and cons of the options
- **Option 1:** + matches charters and the review pattern; − two levels to explain, which the screens handle by showing the type's policy read-only on the forum page.
- **Option 2:** + one screen; − duplication and drift across committees; no review of policy.
- **Option 3:** + no new access code; − no terms, voting, alternates or history; leaks forum semantics into authz.
- **Option 4:** + four eyes on access; − friction on the commonest task, with no source asking for it; the board's own resolution already approves appointments.
