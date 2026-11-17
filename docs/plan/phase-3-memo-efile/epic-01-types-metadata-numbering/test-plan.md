# E01 Memo types, metadata and numbering — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. CI stages: PR (unit, integration, matrix, smoke E2E), nightly (PIT, browsers, visual, pseudo-locale), weekly (load).

## Scope and risks
- **Risks covered:**
  - wrong or duplicate numbers (special test: gapless numbering under concurrency, `TESTING_STRATEGY §12`);
  - memos changing meaning after submit;
  - maker-checker bypass;
  - attribute removal breaking rules;
  - metadata leaks.
- **Seed data:** the demo tenant's representative types (T8), `admin@` (maker), `reviewer@` (checker), `auditor@`, `user@` (initiator), the approvers, and an org unit with a code for each initiator.

## Tests per slice

| Task | Layer | Tests |
|---|---|---|
| T1 | Unit | `ReviewLifecycleTest`: every allowed and every forbidden transition, same-person, remark rules |
| T1 | Integration | `MemoTypeVersionsIT`: create → draft 1 → submit → approve publishes; new draft from published; `DRAFT_EXISTS`; If-Match revision conflict (412); send-back needs a remark; approve needs step-up; `SAME_PERSON`; the old columns are mirrored; audit for each action |
| T1 | Migration | `MemoTypeBackfillIT`: on a database holding Phase 2 data at memo V5, V6 gives one published version per type with identical settings and unique codes, per tenant, and FORCE RLS is restored (T2 and T4 extend it: pinned memos, counters copied, references unchanged); Flyway applies on empty and seeded databases (CI gate) |
| T1 | Persistence/RLS | `RlsMetaTest` picks up the new tables; `MemoTypeCrossTenantIT`: versions, counters and links of tenant B invisible and unmodifiable from tenant A |
| T2 | Unit + property | `FieldListValidatorTest` (each rule and code); `MetadataValidatorTest` (each type: valid, invalid, missing, extra keys); `MetadataProperties` (any value accepted on save round-trips through JSON unchanged; any value valid for a field stays valid after an unrelated field is added) |
| T2 | Integration | `MemoMetadataIT`: save incomplete draft; submit missing required → 422 at `metadata.<key>`; pin at submit; stale fields kept on draft then dropped at submit with `memo.type_pinned`; metadata refused after submit (API 409/422 and the trigger at SQL level); raise scope (`NOT_ALLOWED_TO_RAISE`, list filtering); unit search kind |
| T3 | Integration | `MemoAttributesIT`: catalogue per tenant from published versions; cache invalidated on publish; `ATTRIBUTE_CONFLICT`; `ATTRIBUTE_IN_USE` for a definition and for a DoA version; DoA band on amount matches and does not match; CEL condition on amount routes; a memo of a type without the field takes `otherwise` with inputs recorded |
| T4 | Unit + property | `NumberFormatTest` (each part, styles, 2/4-digit years, financial years across the start month, padding, 100-character limit); `NumberFormatProperties` (rendering is deterministic; distinct sequences render distinct numbers; period keys change exactly at the reset boundary in the tenant's zone, including across DST); `SeriesOverlapTest` |
| T4 | Integration | `NumberingIT`: ON_SUBMIT and ON_APPROVAL; resubmission keeps the number; old-style drafts keep `M-` references; `NO_ORIGIN_UNIT`; per-unit scope; starting number once (`ALREADY_STARTED` afterwards, step-up, audit); register order, voids with reasons, subject hidden from non-readers; completeness check |
| T4 | Concurrency | `NumberingConcurrencyIT`: 50 parallel submits on one series → 1..50, no gaps or duplicates; a forced rollback after numbering leaves no gap; parallel submits on two series do not wait on each other (timing bound); a double click with one idempotency key numbers once |
| T4 | Mutation | PIT on `memo.internal.numbering` nightly, score ≥ 70 % |
| T4 | Fuzz | Jazzer on the format parser and renderer (nightly, time-boxed) |
| T5 | Integration | `MemoCloseIT` (close from APPROVED only, by initiator or admin; refused for others; nothing allowed after; auto-close per tenant with the test clock, idempotent re-run, `system` actor); `MemoLinksIT` (add by number, unreadable target → not found, limit 50, remove only by creator in DRAFT/RETURNED, removal recorded, linked from filtered by readability, cross-tenant) |
| T6 | Unit (web) | memo-types list; memo-type page tabs; field editor (each type's rules); reorder with buttons; live preview matches the create form; numbering sample updates; changes view; review dialog with step-up; Problem Details mapping (`SAME_PERSON`, `ATTRIBUTE_IN_USE`, `SERIES_OVERLAP`, 412); register and starting number; axe on each |
| T7 | Unit (web) | type chooser (cards ≤ 6, select above); each field component (amount formatting and decimal string, currency choice, unit picker combobox keyboard, date, radios vs select, checkbox group); required errors from the server focus the field; stale-field notice; Details section; links and linked from; close dialog; axe on each, en and ar |
| T8 | E2E (smoke, PR) | `memo-types.spec.ts`: `admin@` creates "Travel advance" with an amount field (attribute) and format `TA-{YEAR}-{SEQ:4}`, submits; `reviewer@` approves with step-up; `user@` creates and submits a memo; it is numbered `TA-2026-0001`; the Details section shows the amount. `memo.spec.ts` updated for "Unnumbered" drafts |
| T8 | E2E (smoke, PR) | `authority.spec.ts` extended: a credit memo above the amount band needs the director; below it does not |
| T8 | Visual (nightly) | memo-type page, create form with fields, memo view Details: light/dark, LTR/RTL, phone |
| T8 | Pseudo-locale (nightly) | the new screens in `en-XA` and `xx-LS` |
| T8 | Performance | register query and link lookup `EXPLAIN` at 100,000 numbers (generator); budget table in `design.md §8` |

## Authorisation matrix tests
`authz/p3-e01-memo-types.yaml` lists every new endpoint with its rule. Each one comes from `design.md §4`:
- `tenantAdmin` for the manage endpoints;
- a new rule `tenantApproverStepUp` for approve and start;
- `auditorOrTypeAdmin` for the register;
- `signedIn` for the raisable-type list and its form;
- memo-level rules through the existing memo fixtures.

`AuthorizationMatrixTest` generates allow and deny cases. `EndpointCoverageTest` fails on any endpoint left out.

## State-machine tests
- **Memo-type version:** every arrow in `design.md §1` allowed, plus these forbidden:
  - DRAFT → PUBLISHED;
  - IN_REVIEW → IN_REVIEW;
  - PUBLISHED → DRAFT;
  - RETIRED → anything;
  - submit of a non-draft;
  - approve by the submitter.
- **Memo:** APPROVED → CLOSED allowed. Forbidden:
  - CLOSED → any;
  - close from DRAFT, IN_REVIEW, RETURNED, REJECTED, WITHDRAWN, CANCELLED;
  - number or version change after set.
- **Property:** the workflow invariant suite gains "a numbered memo's reference never changes" and "every non-draft memo has a pinned version".

## Security tests
- Tampering:
  - metadata, reference or version changed by direct SQL as the application role is refused by the trigger;
  - a starting number after use is refused;
  - self-approval is refused.
- XSS strings in labels, help and choice labels are rendered as text (web unit test and E2E).
- Size limits answer 413/422 without storing anything.
- ZAP baseline covers the new pages (nightly).

## Accessibility and i18n
- axe on every new screen in en and ar.
- The form builder and field components work by keyboard (no drag and drop; reorder with buttons).
- The unit picker is an ARIA 1.2 combobox.
- Amount formatting follows the locale, but the stored value stays a decimal string.
- Numbers are isolated with FSI/PDI in RTL text.
- The new catalogue part stays under 15 KiB gzip.

## Exploratory charters
- Change a type while drafts, in-review memos and returned memos exist. Check what each initiator and approver sees.
- Try to make two types produce the same number.
- Cross a financial-year boundary with the test clock and look at the register.

## Acceptance mapping
The `tasks.md` acceptance criteria map to `MemoTypeVersionsIT` (T1), `MemoMetadataIT` (T2), `MemoAttributesIT` (T3), `NumberingIT` and `NumberingConcurrencyIT` (T4), `MemoLifecycleIT` (T5), and the web unit tests and `memo-types.spec.ts` (T6–T8).

## Exit checklist
- [x] Every test above green in its stage; PIT ≥ 70 % on numbering (84.7 %, T8)
- [x] Matrix and audit coverage tests green with the new endpoints and actions
- [x] `EXPLAIN` recorded in the log (T8, `NumberingPerformanceIT`)
- [x] Owner's screen review recorded in `ux-review.md` before T6 and T7
- [x] ASVS rows updated (V2 business logic, V8, V16, V3)
