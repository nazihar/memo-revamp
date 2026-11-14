# E01 Memo types, metadata and numbering — research

> Step 1 of `AGENTS.md §5`. Accessed 2026-11-14 unless stated. Phase research items: R1 (versions), R2 (numbering), R10 (industry practice, memo-type configuration).

## 1. Starting point (code, 2026-11-14)
- **Memo types** are one mutable row each (`memo_type`): name, default classification, workflow definition key, route policy, allowed approvers, DoA check, step-up, active. Edits take effect at once for every memo, including memos already in review, because a memo stores only `memo_type_id` and submit re-reads the type. No versions, no maker-checker, no cache.
- **References** are `M-YYYY-NNNNN`, taken **when the draft is created** from `memo_reference_counter` (tenant + UTC year) with `INSERT … ON CONFLICT DO UPDATE … RETURNING`, inside the create transaction. Drafts that are later discarded keep their number, so the visible series has numbers that never reached anyone. A CHECK on `memo.reference` enforces the format; nothing else parses it (backend and web treat it as an opaque string; the web isolates it for right-to-left text).
- **`memo.origin_unit_id`** exists but is always null.
- **Workflow attributes** for CEL conditions and DoA rules are fixed in code per subject type: the memo declares `memoTypeId` and `classificationRank`. `AttributeType` is `string | int | double | bool | timestamp`. There is no amount anywhere; ADR-0039 and ADR-0040 name "an amount, from Phase 3" as the expected next attribute. A DoA rule condition that cannot be evaluated does not hold (`DoaMatcher.holds`); a workflow condition that fails at runtime takes `otherwise` and records the inputs (ADR-0039).
- **Maker-checker** exists twice, by copy: workflow definitions (`DefinitionLifecycle`) and DoA rules (`DoaLifecycle`), each with draft → in review → published → retired, `reviewed_by <> submitted_by` in SQL, step-up on approve, a required remark on send-back.
- **Statuses:** draft, in review, returned, approved, rejected, withdrawn, discarded, cancelled. "Closed" is only an inbox box; there is no closed or archived state and no links between memos.
- **Web:** the memo-types admin is one dialog; the create page has subject, type and classification. There are no amount, multi-choice or unit-picker components; the people picker is an ARIA 1.2 combobox over the directory search. Ordering uses up/down buttons, never drag and drop (`RouteEditor`). The admin catalogue part is 8.0 KiB (en) and 9.5 KiB (ar) gzip against a 15 KiB limit per part.

## 2. Versions (R1)
E01 adds **no new dependency**. It uses what Phase 2 pinned:

| Library | Pinned | Latest seen | Note |
|---|---|---|---|
| `com.networknt:json-schema-validator` | 3.0.8 | 3.0.9 (2026-11-03) | 3.0.9 is under two weeks old; Renovate proposes it after the waiting period. Used to validate the field-definition JSON |
| `dev.cel:cel` | 0.15.0 | 0.15.0 (27 Oct) | Conditions over the new metadata attributes |

Money is handled with the JDK (`BigDecimal`, `java.util.Currency` for ISO 4217 codes) and, in the browser, `Intl.NumberFormat`. No money library is needed.

Sources: <https://github.com/networknt/json-schema-validator/releases>, <https://github.com/google/cel-java/releases>.

## 3. Numbering (R2)

### 3.1 Practice
- **Structured references** are common in records offices: a documentation-centre scheme encodes office, recipient, sequence and year, for example `10/1/1234/2021` for "the 1,234th document of 2021" (Ajman University documentation centre). Government e-office systems generate the file number from administrator-defined "file heads" (department, section, subject) rather than letting users type it (eOffice user manuals, MP Forest; NIC district pages publish "standard file heads" per section).
- **Reset rules:** systems either restart the sequence each financial year (from the financial-year start set in settings, not necessarily January) or put the year in the format and never reset (Qoyod, Cairo ERP, Refrens, Manager.io forum). Resetting by hand is warned against because it produces duplicates; the safe pattern is an automatic reset per period with the period inside the number.
- **Continuing a legacy series** at go-live is a normal request: set the next number once, before the first document of the period is numbered (Powered Now, Manager.io forum).
- **When numbers are taken:** e-office systems number a file when it is created, but correspondence (outgoing letters, approved notes) is numbered when issued. For approvals, numbering at submit gives approvers a number to cite; numbering at final approval gives a register with only approved items. Both are needed, per memo type (master plan).

### 3.2 Gapless under concurrency
- A PostgreSQL sequence is not gapless: a value taken by a transaction that rolls back is never reused (pgsql-general, "guaranteeing that a sequence never skips"; "Best approach for a gap-less sequence").
- The standard pattern is a counter row updated **in the same transaction** as the business row: `UPDATE … SET last = last + 1 RETURNING last` (or `INSERT … ON CONFLICT DO UPDATE … RETURNING`) takes a row lock that serialises other writers until commit; a rollback undoes both, so no gap. The cost is that number-taking transactions for the same counter run one at a time, so they must be short and the counter scoped narrowly (per series and period, not per tenant).
- Our existing `MemoStore.nextNumber` already uses this pattern. E01 keeps it and **takes the number as the last statement of the submit or approval transaction**, so the lock is held for the shortest time.

Sources:
- <https://it.ajman.ac.ae/docs/referencing-system-by-the-documentation-center>
- <https://mpforest.gov.in/img/files/eOffice_User_Manual.pdf>
- <https://umaria.nic.in/en/eoffice/>
- <https://www.qoyod.com/en/knowledge-base/document-numbering-using-the-financial-year/>
- <https://help.cairo.pl/books/cairoerp/page/how-to-reset-document-numbering-at-year-end>
- <https://help.refrens.com/en/article/how-to-reset-document-series-and-set-financial-year-for-you-business-oawqnn/>
- <https://forum.manager.io/t/closing-period-restarting-reference-numbers/50903>
- <https://support.powerednow.com/en/knowledge/how-to-edit-your-document-number-at-the-end-of-the-financial-year>
- <https://postgrespro.com/list/thread-id/1403668>
- <https://postgrespro.com/list/thread-id/1436372>

## 4. Per-type fields (R10)
- **ServiceNow** service catalogue items carry "variables" of over 20 types (single-line and multi-line text, select box, multiple choice, reference to a record, date, attachment, and more), configured per item, with type-specific attributes; only some types are indexed for search. Lesson: a small set of well-made types beats a long list; references (person, unit) must be real lookups, not text.
- **Jira** separates the field (a tenant-wide definition with a type) from its context (which projects and issue types use it, with defaults and options per context). Lesson: a field key used in rules should mean the same thing everywhere. E01 makes a field key that is exposed to workflows **tenant-wide with one type**: two memo types may both have `amount`, but it must be an amount in both.
- **Approval products** (Phase 2 R11 benchmark: SAP flexible workflow, ServiceNow approvals) drive routing from item attributes such as amount and cost centre. This is what DoA bands need from E01.

**Adopt:** a typed field list per memo type, versioned with the type; required checked on submit (drafts may be incomplete); a live preview; amount + currency as one field; person and unit as lookups; choice lists with stable keys (labels can change without breaking rules).
**Do better:** every field change is reviewed (maker-checker) and pinned per memo, so a rule or a memo never silently changes meaning; fields used by published workflows or DoA rules cannot be removed or retyped.
**Not adopted:** scripted fields and client-side scripts (ServiceNow catalogue client scripts): no code in tenant configuration (AGENTS §3.5, ADR-0039).

Sources:
- <https://www.servicenow.com/docs/r/servicenow-platform/service-catalog/c_ServiceCatalogVariables.html>
- <https://www.servicenow.com/community/itsm-blog/defining-service-catalog-variable-question-choices/ba-p/2294367>
- <https://confluence.atlassian.com/adminjiraserver/configuring-a-custom-field-938847235.html>

## 5. Recommendations
1. **Versioned memo types with maker-checker** (ADR-0043): one version table holding every setting and the field list; drafts follow the current published version and are pinned at submit; one small shared review helper rather than a third copy of the lifecycle code.
2. **Fields** as a typed JSON list validated by JSON Schema (structure) and by Kotlin rules (meaning); values on the memo as `jsonb`, checked by type on save and for required on submit (ADR-0043).
3. **Workflow attributes** from fields marked "use in rules"; amounts exposed in major units as `double` plus a currency string; the catalogue becomes per tenant and cached (ADR-0043).
4. **Numbering** defined in the memo-type version as a list of parts, counted per **series** (shared by types that should share a register), period and optional unit scope, taken at submit or final approval as the last statement of the transaction (ADR-0044).
5. **Existing data:** every type gets a published version 1 with its current settings and the format `M-{year}-{sequence:5}` on series `general`, continuing today's counter; existing memos keep their references.
