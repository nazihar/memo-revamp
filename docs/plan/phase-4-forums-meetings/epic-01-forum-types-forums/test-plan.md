# E01 Forum types and forums — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. CI stages: PR (unit, integration, matrix, smoke E2E), nightly (PIT, Jazzer, browsers, visual, pseudo-locale), weekly (load).

## Scope and risks
- **Risks covered:**
  - policy changed without review;
  - two chairs, or overlapping terms;
  - a secretary escalating their own rights;
  - rosters leaking to non-members;
  - wrong quorum arithmetic;
  - reminders sent twice;
  - workflows escalating to a forum that does not exist.
- **Seed data:**
  - the demo tenant's types and forums (T6);
  - `admin@` (maker), `reviewer@` (checker), `secretary@` (BRC and MCC secretary), `director@` (BRC member), `head.credit@` (MCC chair);
  - `auditor@` (granted `forum.read` in tests), `user@` (no forum).

## Tests per slice

| Task | Layer | Tests |
|---|---|---|
| T1 | Unit | `ForumTypeRulesTest`: each field rule and its problem code (attributes, outcomes, term policy, caps); `ForumTypeLifecycleTest`: every allowed and forbidden transition through `common.review` |
| T1 | Unit + property | `QuorumTest`: each building block with charter examples (HSBC two including the chair, Barclays two independents, Exim two-thirds including the chair, JPMorgan one-third rounded up, Catalyst two and two, the Charity Commission steps through MAJORITY); `QuorumProperties`: adding an attendee never turns quorate into not quorate; the needed number is monotonic in n; MAJORITY is `floor(n/2)+1`; the FRACTION result matches exact rational arithmetic; the result is independent of attendance order |
| T1 | Integration | `ForumTypesIT`: create from each preset → draft 1; save with If-Match (412 on a stale revision); submit; approve needs step-up; `SAME_PERSON`; send-back needs a remark; new draft from published; `DRAFT_EXISTS`; changes view; activate and deactivate; audit for each |
| T1 | Persistence/RLS | `RlsMetaTest` picks up the tables; `ForumCrossTenantIT` (types part) |
| T1 | Mutation and fuzz | PIT on `forum.internal.quorum` nightly (≥ 70 %); Jazzer on reading the rule JSON (nightly, time-boxed) |
| T2 | Integration | `ForumsIT`: create (key and code rules, `KEY_TAKEN`, `CODE_TAKEN`, `TYPE_INACTIVE`, `PARENT_CYCLE`); edit with If-Match; dissolve (members cannot be added afterwards, rows can still be ended); clone with and without members |
| T2 | Integration | `ForumMembersIT`: add a person and a position; voting default per role; unknown attribute refused; `termEndsOn` defaulted from `termMonths`; renew (new row, `termNumber + 1`); change role (old row ended the day before, new row from the date); end; withdraw an upcoming row only; `ALREADY_MEMBER`; `ONE_CHAIR`; `SECRETARY_LIMIT` (secretary on CHAIR, SECRETARY, own row); history by row; notifications to the chair, secretaries and the member |
| T2 | Concurrency | `ForumMembersConcurrencyIT`: two parallel "add chair" requests → exactly one succeeds; two parallel adds of the same person → one succeeds; the same idempotency key twice → one row |
| T2 | Unit + property | `InOfficeProperties`: `membersOn(d)` equals a reference model over random rows (starts, term ends, ends, renewals); a position seat resolves to the holders on d; a vacant seat gives no member |
| T2 | Integration | `ForumDirectoryIT`: cache eviction on a membership change and on `OrgChanged` (holder changes); `rolesOf`, `isSecretary`, `forumsOf` |
| T2 | Authorisation matrix | `p4-e01-forums.yaml`: every endpoint × `admin@`, `reviewer@`, a `forums.manage`-only user, `secretary@` (own forum and another forum), a member, a former member, `auditor@` with `forum.read` (cleared and not cleared), `user@`; allowed and denied cells, 404 for invisible forums |
| T2 | Persistence/RLS | `ForumCrossTenantIT`: tenant A cannot read, update or end tenant B's forums or members, including through `ForumVisible` and `/me/forums`; composite foreign keys refuse a cross-tenant type, parent or user |
| T2 | Audit | `AuditActionCoverageTest` includes the `forum.*` actions; each IT asserts the entry with before and after |
| T3 | Integration | `TermRemindersIT` (test clock): 42 days and 1 day before → one notification each to the secretaries; no secretary → `forums.manage` holders; a re-run the same day sends nothing; an ended or renewed row gets nothing; tenant context set per tenant (fail-closed check) |
| T3 | Integration | `EscalationTargetsIT`: publish with an unknown or dissolved forum → 422 `UNKNOWN_FORUM` at the step pointer; an active forum passes; already-published definitions are untouched; the list endpoint needs `workflow.definitions.manage`; `ApplicationModules.verify()` |
| T3 | Web unit | Builder `StepDetails`: the forum picker lists targets; an unknown key in an imported definition shows a problem |
| T4 | Web unit | Quorum editor ↔ rule round-trip; the sentence for each building block (ICU, en and ar); outcome editor; the term policy editor; type page states and toolbars by role |
| T4 | E2E | `committee-types.spec`: create from a preset, edit the quorum with the tester, submit as `admin@`, approve as `reviewer@` with TOTP |
| T5 | Web unit | Add-member form rules (role → voting default, attributes from the type, alternate not self); roster on a date; history; the actions available by role (secretary vs `forums.manage`) |
| T5 | E2E | `committees.spec`: `admin@` creates the BRC, appoints the chair and secretary; `secretary@` adds members, renews one, ends one; `director@` sees the BRC under "My committees"; `user@` gets "not found" for its address |
| T5 | Accessibility | axe on every new page in en and ar (CI); keyboard-only path through the add-member sheet and the quorum tester; manual screen-reader pass recorded in `ux-review.md` |
| T5 | Visual | Baselines for the type page, the committee page and the add-member sheet (nightly, en and ar) |
| T6 | Performance | `ForumListPerformanceIT`: 50 forums × 300 rows; `EXPLAIN` without sequential scans on the list, members and `/me/forums` queries; budgets of design §8 |
| T6 | E2E (dev) | The journeys against dev after deploy |

## Authorisation matrix tests
Derived from design §4. Every cell is a test, and the meta-test fails on any endpoint missing from `p4-e01-forums.yaml`.

## State-machine tests
- **Forum-type versions:** every allowed transition (DRAFT → IN_REVIEW → PUBLISHED → RETIRED, IN_REVIEW → DRAFT) and every forbidden one (approve a draft, submit a published version, edit a version in review, approve one's own).
- **Membership rows:** the derived states of design §1. Add (upcoming or in office), withdraw only upcoming, end from in office or term ended, renew and change role from in office or term ended, nothing from ended.

## Security tests
- `SECRETARY_LIMIT` cases.
- The 404-not-403 rule for invisible forums.
- Rule caps and rate limit on the preview.
- No forum names in notification subjects (template check).
- ZAP baseline picks up the new endpoints.

## Performance
Design §8 budgets, in `ForumListPerformanceIT` (PR stage, small) and `EXPLAIN` checks.

## Accessibility and i18n
- All strings in the catalogue (en, ar).
- The `forum` term argument used wherever "committee" appears (`i18n:verify`).
- Quorum sentences use ICU plural and select.
- The pseudo-locale run nightly.

## Exploratory charters
- Set up a full bank committee structure from presets in under 15 minutes. Note every confusing label.
- Try to gain a role you should not have as a secretary.

## Acceptance mapping
The acceptance criteria in `tasks.md` map one-to-one to the ITs and E2E specs above.

## Exit checklist
- [ ] All tests above green in CI.
- [ ] PIT ≥ 70 % on quorum.
- [ ] Matrix complete.
- [ ] axe clean.
- [ ] Journeys pass on dev.
- [ ] ASVS rows.
- [ ] Runbook.
- [ ] Owner's screen review recorded.
