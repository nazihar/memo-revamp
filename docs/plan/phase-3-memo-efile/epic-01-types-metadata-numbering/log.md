# E01 Memo types, metadata and numbering — log

## 2026-11-14
- **Plan written** under the delegated gate (Phase 3 Q1): research, ADR-0043 (memo-type versions, metadata fields, attributes), ADR-0044 (numbering), design, threat model, tasks T1–T8, test plan, and UX review.
- **Deviation from the overview:** field validation has **no admin-defined patterns** (regular expressions), only lengths, bounds and choices. Patterns from tenant data are a ReDoS risk (threat model D3), and no use case needs them yet.
- **Owner review needed before T6 and T7:** the screen flows in `ux-review.md` (memo-type admin, create form) and three questions. Backend tasks T1–T5 proceed meanwhile.

## 2026-11-14 — T1 type versions and review
- **Built:**
  - `memo_type_version` (`memo/V6`), with codes and the published version number on `memo_type`;
  - `common.review` (`ReviewState`, `ReviewRules`);
  - `MemoTypeVersionStore`, `MemoTypeLifecycle` and `MemoTypeChecks`;
  - the version endpoints under `/api/v1/admin/memo-types/{typeId}/versions`;
  - the `memo.types.approve` permission (tenant admins hold it, as every permission);
  - matrix `p3-e01-memo-types.yaml`.
- **Transitional, until T6:** the Phase 2 admin screen keeps its direct create and edit (`POST`/`PUT /api/v1/admin/memo-types`). Each direct change is recorded as a new published version (`createdBy` the admin, no reviewer), so the history stays complete and memos can be pinned to it from T2. T6 removes the direct path with the new screens, and creating a type then starts from a draft.
- **Finding, row-level security in migrations:** FORCE ROW LEVEL SECURITY binds the schema owner too. A migration sets no tenant, so an UPDATE on a forced table changes nothing, silently.
  - V6 lifts FORCE for its own transaction and puts it back. `MemoTypeBackfillIT` proves the data and the restored FORCE.
  - The Phase 2 migration `workflow/V3` has two such UPDATEs (`workflow_round.route`, `workflow_step.route_index`). On dev they changed no row, so rounds and steps created before E03 keep the defaults (empty route, no route index). Those are E01-era demo and test memos. Not checked yet: T8 queries dev for any still open and corrects them.
  - New guard: `MigrationDmlRlsTest` fails any new migration that changes data in a forced table without lifting FORCE or setting a tenant. `workflow/V3` is its one recorded exception.
- **Builds:** the VS Code Java extension locked `.build-cli/memo/backend/build/test-results/test`, so CLI builds used `MEMO_BUILD_ROOT=.build-cli/alt` in this session.

## 2026-11-14 — T2 metadata fields
- **Built:**
  - `memo/V7`: field list and raise scope on the version, `memo.metadata` and `memo.memo_type_version_id`, the `memo_pinned_guard` trigger, and the backfill that pins submitted memos to version 1;
  - the field model and `MemoFieldChecks`;
  - `MemoMetadata` (type checks on save, required fields at submit);
  - pinning at submit with `memo.type_pinned`;
  - `MemoTypes.forMemo`: resubmit, routes, step-up and the memo view now read the pinned version;
  - the raise scope, enforced on create and on the type list;
  - `GET /api/v1/memo-types/{id}/form`;
  - the origin unit, set at create;
  - details, stale details, type version and origin unit in the memo view;
  - `unit` kind in the directory search.
- **Decided while building:**
  - A memo's **type** can now change only in DRAFT, like its classification. Before, it could change while RETURNED, which would break pinning.
  - The field list is checked through typed request objects, not a JSON Schema (ADR-0043 updated).
  - Stale values sent back unchanged by the form are accepted. Only new unknown keys are refused.
- **Deferred to T3:** the Caffeine cache for published type versions (design §8). T3 builds the per-tenant attribute catalogue cache, and the type list will share it. Until then, the type list reads one published version per type, a handful of rows.
- **Web:** generated types gained required `details`, `staleDetails`, `hasFields` and `units`, so the typed fixtures were updated. One run of the web suite started while the backend build was starting and had 5 timeouts; the rerun passed 72 of 72 (the known flake under load, Phase 2 retrospective).
- **Tests:**
  - The full backend suite passed apart from two tests. `MemoTaskActionsIT` was a real fixture fault: it switched a type's workflow by SQL on `memo_type` only, and submit now reads the published version, so the fixture now updates the version too. `FilesMaintenanceIT` got one S3 400 from the test storage and passed when re-run alone.
  - After the detekt refactors (`MemoTypeAccess`, `MetadataValues`, `OrgUnitDirectory`), every memo test and the cross-cutting checks passed again: 1,233 tests in 24 suites, plus `ArchitectureRulesTest`.

## 2026-11-14 — T3 fields as workflow attributes
- **Built:**
  - `MemoAttributes`: the per-tenant catalogue (built-ins plus the attribute fields of every type's published version), cached as `memo.attributes` and invalidated when a version is published; memo values as conditions read them (amount as `double` plus `<key>Currency`, a date as a timestamp at the start of the day in the tenant's zone).
  - `MemoAttributeChecks`: `ATTRIBUTE_CONFLICT` (one type per key across the tenant; an amount's currency name kept free) and `ATTRIBUTE_IN_USE`.
  - `WorkflowDefinitions.attributesInUse(subjectType)`: attributes declared by published definitions and fragments, with their types, plus names read by published DoA rule conditions.
  - `DefinitionChecks` and `DoaChecks` read subject attributes per call. Before, they captured them once at start-up, which would have hidden every field published later.
- **Decided while building:**
  - The catalogue includes **inactive** types' published versions, so switching a type off cannot silently break a rule (ADR-0043 updated).
  - `ATTRIBUTE_IN_USE` only concerns attributes memos offer now. A name declared for another subject (the test subject in workflow tests) is not the memo's to keep.
- **Tests:** `MemoAttributesIT` covers the catalogue and the builder catalogue, a DoA band on the amount (6,000,000 needs the director; 4,000,000 and no amount match no rule), dropping or un-marking an attribute the rule reads, and key conflicts.

## 2026-11-14 — T4 numbering
- **Built:**
  - `memo/V8`:
    - numbering on the version (default: the Phase 2 format in the series `general`);
    - nullable `memo.reference` with a length check;
    - number parts on the memo with a unique index per counter;
    - `memo_number_counter`;
    - the `memo_number_guard` trigger;
    - the backfill turning existing `M-YYYY-NNNNN` references into `general` numbers and copying `memo_reference_counter`.
  - The numbering model and `NumberFormats`: periods, rendering, skeletons, checks.
  - `MemoNumberingChecks`: `SERIES_OVERLAP` and `SERIES_MISMATCH`.
  - `MemoNumbers`: numbering at submit (after pinning, before the workflow starts) or at final approval (in the approval's listener, same transaction), and `NO_ORIGIN_UNIT`.
  - The register, completeness check, starting number and preview: `MemoNumberingAdmin` and `/api/v1/admin/memo-numbering`.
  - The numbering moment on the type list, form and memo view.
  - On the web, "Unnumbered" wherever a reference is shown (`referenceOf`).
- **Changed from the plan:**
  - The number is taken just before the workflow starts, not as the very last statement. That way the first task, its notification and its email already carry the number. The lock still covers only one counter (ADR-0044 updated).
  - The starting number is `POST … /start` with an idempotency key, the actions' convention, rather than `PUT`.
  - Discarded Phase 2 drafts with references count as void numbers too.
  - The register shows the void flag and status. The reason stays in the memo's history rather than being copied.
- **Rollout note:** while a node on the previous release runs beside a new one, the old node still numbers drafts from `memo_reference_counter`, and the new one numbers `general` from `memo_number_counter`. The unique reference index turns a clash into a refused request, never a duplicate. Deploys to dev and production replace all nodes, so the overlap lasts seconds.
- **Bug caught by the tests:** `select min(...)` read with `Long::class.java` (primitive) turned "no number" into 0, so `ALREADY_STARTED` fired on an unused counter. It now uses the boxed type.
- **Tests:**
  - `NumberFormatTest`: examples, plus properties (distinct sequences give distinct numbers; a financial year turns over exactly on its first day).
  - `NumberingIT`: submit and approval moments, resubmission, `NO_ORIGIN_UNIT`, register, void numbers, completeness, starting number, overlap and mismatch.
  - `NumberingConcurrencyIT`: 40 parallel transactions get 1..40, and a rollback gives its number back.
  - `MemoTypeBackfillIT`: extended for V8.
  - `MemoDraftIT`: drafts are unnumbered.

## 2026-11-14 — T5 lifecycle
- **Built:**
  - `memo/V9`: the `CLOSED` status with `closed_at`; `approved_at`, set when the workflow approves and backfilled for approved memos; `memo_link`, where removal is recorded and nothing is deleted; and retention, attachment rules and auto-close days on the version.
  - `MemoClosing`: closing by the initiator (`POST /memos/{id}/close`), plus the hourly `memo-auto-close-sweep`, which runs one task per tenant as the system and closes approved memos after their pinned version's days. It is idempotent.
  - `MemoLinks`: add by number (`LINK_TARGET_UNREADABLE` for numbers the person cannot open, `LINK_EXISTS`, `LINK_LIMIT`, `SELF`); the creator removes a link while the memo is a draft or returned. Links and "linked from" are shown only to people who may open both memos.
  - `MemoTypePolicies` for the checks. The view has `links`, `linkedFrom`, `canLink` and `closedAt`, and `CLOSE` is offered to the initiator of an approved memo.
  - Web: the "Closed" status label and tone (the button comes with T7).
- **Changed from the plan:**
  - Only the **initiator** closes by hand. A tenant administrator is not a participant and cannot open the memo, so closing for them goes through auto-close. The design's "tenant admin" path is dropped.
  - "Link" is a `canLink` flag on the view, not an action in `allowedActions`: links are not workflow steps, and the action list stays as the workflow defines it.
  - The closing remark is not stored, only recorded as given or not. Audit entries never carry remarks.
- **Fixed in passing:**
  - Since the semgrep fix, `MemoTypeBackfillIT` seeds in one transaction, so seeded types shared one `now()` and duplicate codes were numbered in random order. Seeding now uses `clock_timestamp()`.
  - Semgrep in CI had blocked T1 for the session-level `set_config` in that test; it now sets the tenant transaction-locally.
  - SpotBugs in CI had blocked T2 for re-read nullable rule properties.
  - From now on, SpotBugs and the semgrep rules are checked locally before each push.
- **Tests:**
  - `MemoLifecycleIT` (5): closing by the initiator only and only when approved; nothing after closing; auto-close with a test clock, as the system, idempotent; links by number both ways, unreadable and duplicate targets, link visibility, recorded removal; retention and attachment rules kept and checked.
  - `MemoTypeBackfillIT` extended for V9.

## 2026-11-14 — T8 (first part): representative demo types
- **Built:** `RepresentativeTypes`, seeded by `MemoSeeding.representativeTypes` from the demo workflow seeder. Each is published through the reviewed lifecycle: `admin@` drafts and submits, `orgadmin@` approves. The seed only fills gaps.
  - **Credit proposal (CP):** customer, facility amount (attribute), purpose (attribute) and tenor; `CR/2026-27/0001`, restarting each financial year (July); retention 10 years from closure; step-up on approve.
  - **Expense approval (EXP):** amount (attribute), cost centre (unit, attribute) and category; `EXP-2026-00001`, yearly; auto-close after 30 days; retention 7 years from approval.
  - **Procurement (PRC):** estimated value (attribute), vendor and method (attribute); `PRC/2026-27/0001`, **numbered on final approval**; at least one attachment required (E02 enforces it); retention 10 years.
  - **Policy approval (POL):** effective date and policy areas (multiple choice); `POL-2026-001`; auto-close after 90 days; retention 25 years.
- **The Phase 2 demo types are left as they were** ("Credit facility", "Credit under authority"): the existing E2E journeys create memos of them without details, and required fields would break those journeys.
- `DemoWorkflowsIT` checks that each representative type is published once (version 2) with its fields and series, including after a second seed run.
- **Open in T8:** the E2E journeys (they need the T6 and T7 screens), `EXPLAIN` for the register and link lookups, ASVS rows, and the dev check of the rows `workflow/V3` did not backfill.

## 2026-11-14 — T6 admin screens
- **Owner review:** "ok, keep working", recorded as acceptance of the screen flows with the defaults built for the three questions (`ux-review.md`).
- **Backend:**
  - `POST /admin/memo-types` now takes `{name, code}` and starts the type as draft version 1, so nobody can raise it until it is published.
  - The Phase 2 direct edit (`PUT /admin/memo-types/{id}`) is gone; `PUT /admin/memo-types/{id}/active` switches a type on or off.
  - The list carries each type's open version (`pending`), and version responses name the scope's units.
  - `Numbering.needsUnit` is no longer written to JSON.
  - `MemoTypes.create` publishes at once, for seeders and the built-in type only.
- **Web:**
  - `features/memo-types/`: the list with a New memo type dialog; the type page with version selector and the Settings, Form, Numbering and Changes tabs; save, submit, approve with step-up, send back, new draft and switch on/off; the numbering page with series, counters, the register with void numbers and completeness, and a starting number with step-up.
  - `features/memo/MetadataFields.tsx`: the form controls per field type, used by the preview now and by the create form in T7.
  - The people picker searches units too.
  - New catalogue part `memoAdmin` (Arabic 3.5 KiB).
  - `builder.spec` follows the new flow: draft, submit, the second admin approves with TOTP.
- **Changed from the flows:** the field editor is a dialog, not a side sheet (the Sheet component is the navigation drawer).
- **Tests:** `memo-types.test.tsx` (5): the list, editing a draft with a new field and saving with If-Match, the numbering sample, review and approve, and the register; axe on each. The web suite passed apart from one platform test, which timed out under load and passed alone (the known flake).

## 2026-11-14 — T7 create form and memo view
- **New memo** (`/memos/new`):
  - The type comes first. Six or fewer types are radio choices, each saying when it is numbered and whether it asks for details. More types become a list labelled "Memo type".
  - The type's fields follow under "{type} details", built by `MetadataFields` from `GET /memo-types/{id}/form`.
  - A draft saves with required fields still empty; the server checks them at submit and names each field as `metadata.<key>`, shown under the field.
  - Choosing another type clears the details.
- **Edit dialog:** while the memo is a draft, it shows the type's fields filled from the memo's details (units and people keep their names) and sends them back. Another type starts its details afresh.
- **Memo view:**
  - A **Details** section shows each value as people read it: amounts with their currency code, dates in the reader's locale, and names for units, people and choices.
  - A draft whose type dropped fields shows a notice and lists those values under "No longer on this form".
  - The header reads "Unnumbered — numbered when submitted" or "… when finally approved" until the memo has its number.
  - A **Linked memos** section lists links and the memos linking here. Links are added by number with a kind (refers to, supersedes, follows up), and a refusal shows under the number. Whoever added a link may remove it.
  - A **Close** action and dialog, with an optional remark.
- **E2E:** `e2e/memo-type.ts` `chooseMemoType` picks a type whichever way the page offers it. The four journeys that create memos use it.
- **Tests:** `memo-create.test.tsx` (8):
  - the type first, then details sent with the draft (axe);
  - a field error from the server;
  - details formatted, the numbering note and linked-from (axe);
  - Arabic (axe);
  - the stale-field notice;
  - editing a draft's details;
  - linking by number and a refused number;
  - closing.

  Long UI tests take the repo's 20-second timeout; under the full suite on this machine they ran just past 5 seconds.

## 2026-11-14 — T8 (rest): journeys, volume, ASVS, the V3 rows on dev
- **E2E journeys:**
  - `memo-types.spec` (built with T7): an admin builds a type with a required amount and a `TA-` format, a second admin publishes it, a user raises a memo, and the memo is numbered `TA-<year>-00001` on submit.
  - `authority.spec` gains the amount band. A credit proposal under BDT 5 million goes from the desk to the chief financial officer alone; at 8 million the managing director decides first, and the chief financial officer has no task until then.
- **Demo seed:**
  - "Credit proposal" now runs on the credit-authority workflow (`FIXED`).
  - The DoA gets two rules ahead of the E04 rule for every memo: credit proposals of BDT 5 million or more (`item.amount >= 5000000.0`) ask the managing director, then the chief financial officer; smaller ones ask the chief financial officer only.
  - On a fresh tenant the DoA is created with all three rules. A tenant seeded earlier (dev) gets the bands in a new DoA version taking effect today, and the credit proposal type gets a new version on the authority workflow; nothing is done while a DoA version is being written or reviewed. This upgrade path has no test of its own (the IT seeds a fresh tenant), so it is checked on dev after deploy; a seeding failure is logged and never stops start-up.
  - `DemoWorkflowsIT` checks the type's workflow and the order of the rules, after two seed runs.
  - `WorkflowSeeding.submitDoa` now takes rules with a memo type and a condition, placed ahead of the rules in force.
  - `MemoSeeding.representativeTypes` takes the workflow for a type code and returns the type ids.
  - The bands compare the amount whatever its currency; the demo's credit proposals are in BDT. A bank's real bands would add `item.amountCurrency == "BDT"` or one rule per currency.
- **Volume and `EXPLAIN`** (`NumberingPerformanceIT`, 100,000 numbers in one period, every 50th void):
  - Register page with the completeness check: p95 81 ms locally (budget 300 ms).
  - Lookup by number: p95 4 ms (budget 50 ms).
  - Plans:
    ```
    Limit (actual rows=51)
      -> Index Scan using memo_number_uk on memo m (actual time=0.042..0.051 rows=51)
           Index Cond: tenant_id, number_series, number_period, number_scope, number_sequence > 73000
           Buffers: shared hit=8
    Execution Time: 0.072 ms

    Index Scan using memo_tenant_id_reference_key on memo (actual time=0.034..0.035 rows=1)
      Index Cond: tenant_id, reference = 'P-2026-073000'
      Buffers: shared hit=4
    Execution Time: 0.047 ms
    ```
  - Most of the register's time is the completeness check (`generate_series` with an index probe per number); it stays inside the budget at this volume.
- **The rows `workflow/V3` did not backfill**, checked on dev (read-only, as the database owner):
  - 16 rounds still had an empty route, 6 of them open;
  - 17 route steps had no route index, 7 of them active or pending;
  - 7 open instances were affected.

  An active route step without its index cannot find the next entry, so those memos would have ended their route early. New migration `workflow/V15__route_backfill.sql` repeats both backfills, lifting FORCE for the transaction, only for rounds whose steps all still carry the pre-E03 defaults. `RouteBackfillIT` reproduces the dev state: V3 changes nothing, V15 fixes the old round, leaves a round started since E03 alone, and FORCE is back on. `MigrationDmlRlsTest`'s note on V3 points to V15. After deploy, check on dev that the three counts are zero.
- **ASVS:** seven rows for E01 in `docs/security/asvs-checklist.md`: maker-checker and step-up for types, pinned versions and values, numbering integrity, bounded forms, link and register visibility, audit, and labels rendered as text.
- **PIT:** `NumberFormats` joins the engine in the mutation-testing targets (threshold 70 %). Locally: numbering 84.7 % (72 of 85 mutants killed), engine 89.3 %.
- **Visual baselines (nightly):** the memo view now shows Details and Linked memos, so its six images were re-rendered. New `visual/memo-types.spec`: the type page's Form tab with the preview (light/dark × LTR/RTL) and the new-memo form with details (LTR, RTL, phone). Rendered in the Playwright container and reviewed.
- **Required marks:** choice fields were marked required with "*" while other fields carried no mark. They now follow the app's convention (optional fields say so; required ones carry no mark), and the details lead text says "every field not marked optional".
- **Runbook:** `docs/runbooks/memo-types-and-numbering.md` covers publishing a type, continuing a legacy series with a starting number, checking the register, and the refusals.
- **Dependency register:** unchanged; no dependency changes since master.
