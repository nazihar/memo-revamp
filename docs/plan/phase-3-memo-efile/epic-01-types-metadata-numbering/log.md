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
