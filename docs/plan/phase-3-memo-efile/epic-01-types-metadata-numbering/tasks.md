# E01 Memo types, metadata and numbering — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **Type versions and review:** `common.review` (`ReviewState`, `ReviewRules`); `memo/V6` (versions, code, published version, backfill); `MemoTypeVersions` store with guarded moves; admin API for versions (create type, new draft, save with If-Match, submit, approve with step-up, send back); `memo.types.approve` permission and role template; dual write of the old columns; type cache; audit; matrix `p3-e01-memo-types.yaml` | L | — |
| T2 | **Metadata fields:** field-list JSON Schema and Kotlin rules; `memo.metadata` on create and edit (type checks), submit (required) with version pinning, stale fields, `memo.type_pinned`; write-once trigger; `GET /memo-types/{id}/form`; memo view with labelled values; raise scope; unit kind in directory search | L | T1 |
| T3 | **Fields as workflow attributes:** `WorkflowSubject.attributes(tenantId)`; memo catalogue from published versions, cached; `WorkflowDefinitions.declaredAttributes`; `ATTRIBUTE_CONFLICT` and `ATTRIBUTE_IN_USE` at submit and approve; amount and currency values; builder catalogue and DoA pick them up | M | T2 |
| T4 | **Numbering:** format model and renderer (property-tested); origin unit at create; counters taken last in the submit transaction (ON_SUBMIT) and in the approval listener (ON_APPROVAL); old-node drafts recognised; `SERIES_OVERLAP`; preview; numbering overview, register with voids and completeness check; starting number; audit | L | T1, T2 |
| T5 | **Lifecycle:** CLOSED with close action and the auto-close tenant task; links (add by number, remove in DRAFT/RETURNED, linked from, readability filter, limit); retention and attachment rules stored and shown | M | T1 |
| T6 | **Web, admin** (after the owner's screen review): new catalogue part `memoAdmin`; memo-types list; memo-type page (settings, form builder with side-sheet field editor and live preview, numbering with live sample, versions, changes view, review dialog with step-up); numbering overview and register with starting number; en/ar | L | T1–T4 |
| T7 | **Web, memo** (after the owner's screen review): type chooser; create and edit form rendered from the schema with new field components (amount and currency, unit picker, date, choice as radios or select, multi-choice as checkbox group, person); memo view Details, number or "Unnumbered", stale-field notice, links and linked from, close; en/ar | L | T2, T4, T5 |
| T8 | **Close:** representative bank memo types in the demo seed (General, Credit facility with amount and DoA bands, Expense, Procurement, Policy approval), each with its numbering; E2E journeys; volume and `EXPLAIN` for register and lookup; ASVS rows; dependency register unchanged check; runbook note on starting numbers; STATUS and log | M | all |

**Migrations** come one per task, each with the tables and columns that task uses: T1 `memo/V6` (type versions and codes), T2 the memo metadata and pinning columns, T4 numbering, T5 closing and links.

**Order:**
- T1 first.
- After T1: T2 and T5 in either order.
- After T2: T3 and T4.
- T6 and T7 wait for the owner's screen review (`ux-review.md`) and their backend tasks.
- Each task merges as its own PR when CI is green, stacked on the previous one until the owner merges.

## Acceptance criteria (selection; full list in test-plan.md)
- **T1:**
  - Given `admin@` submitted version 2 of "Expense", when `admin@` approves it, then the answer is 409 with reason `SAME_PERSON` and nothing changes.
  - When `reviewer@` approves with a fresh MFA, version 2 is published, version 1 is retired, and both audit entries exist.
- **T2:**
  - Given "Expense" has a required amount field, a draft saves without it.
  - Submit answers 422 with `metadata.amount` `FIELD_REQUIRED`.
  - After the amount is filled, submit pins the memo to the published version, and later versions of "Expense" do not change what the memo view shows.
- **T3:**
  - Given "Credit facility" publishes `amount` as an attribute, a DoA rule `item.amount >= 5000000.0` matches a memo of 6,000,000 BDT and not one of 4,000,000.
  - A version that removes `amount` while that rule is published is refused with `ATTRIBUTE_IN_USE`.
- **T4:**
  - Given the format `CR/{UNIT}/{FY}/{SEQ:4}`, a July financial-year start, unit HO-CR and Asia/Dhaka, a memo submitted on 2026-11-14 is numbered `CR/HO-CR/2026-27/0001`.
  - 50 parallel submits on that series give 0001–0050 with no gap.
  - A rolled-back submit leaves the counter unchanged.
- **T4 (register):** a memo numbered and then withdrawn appears in the register as void with the withdrawal remark; its number is never issued again.
- **T5:**
  - Given an approved memo, the initiator closes it, after which every action and link addition is refused.
  - Given `auto_close_days = 30`, a memo approved 31 days ago is closed by the system.
- **T7:**
  - With the keyboard only, an initiator creates a memo of a type with an amount, a unit, a date and a choice field, saves it, and submits it.
  - axe finds no serious issue in English and Arabic.
