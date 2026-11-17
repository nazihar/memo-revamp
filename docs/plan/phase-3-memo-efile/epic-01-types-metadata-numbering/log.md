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
