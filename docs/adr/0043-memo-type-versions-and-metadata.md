---
status: accepted
date: 2026-11-14
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0043 — Memo-type versions, metadata fields and workflow attributes

## Context and problem statement
Phase 3 E01 turns the Phase 2 memo type (one mutable row) into the full configuration AGENTS §3.5 asks for: a metadata form, numbering, template, workflow, classification and retention per type, editable by tenant admins, versioned and protected by maker-checker. Today an edit to a type applies at once to every memo, including memos in review, and nobody reviews it. Memo data is only subject and classification, so DoA bands and workflow conditions cannot use an amount, which banks route on first (ADR-0039, ADR-0040 and Phase 3 research R10).

## Decision drivers
- A memo must never change meaning after submit because its type changed (the record is sacred, AGENTS §3.3).
- Sensitive configuration is reviewed by a second admin (AGENTS §3.5), as definitions and DoA rules already are.
- Rules that read a field must keep working: a field used by a published workflow or DoA rule cannot disappear or change type silently.
- No code in tenant configuration; no new dependency; expand → migrate → contract for the existing tables.
- Drafts may be incomplete; required fields matter at submit.

## Considered options
1. **A version table per memo type with the shared draft → in review → published → retired lifecycle; fields as typed JSON in the version; memos pinned at submit** (chosen).
2. Keep memo types mutable, add an audit diff and "effective from" dates. No review, and memos in review still change meaning.
3. A tenant-wide field library (Jira-style fields with contexts per type). More flexible, but a second admin surface and a second versioned entity; most banks' fields are per memo type. The tenant-wide rule is kept only where it matters, for fields used in rules (below).
4. Pin at create instead of submit. Drafts created before a change would submit under rules the admins have replaced.

## Decision outcome
Chosen option 1.

### Versions
- `memo_type` keeps the identity: id, key (built-ins), short `code` (used in numbers), `active`, and, for this release only, the old settings columns written alongside the published version (expand; dropped in a later contract migration once no running node reads them).
- `memo_type_version` holds everything else: name, default classification, workflow definition key, route policy, allowed approvers, DoA check, step-up on approve, numbering (ADR-0044), template file (filled by E03), retention (`{years, from: APPROVAL | CLOSURE}`, stored only; Phase 7 acts on it), who may raise it (units with or without their subtree, roles; empty means everyone with `memo.create`), attachment rules (enforced by E02), auto-close after N days (optional), and the field list.
- States DRAFT, IN_REVIEW, PUBLISHED, RETIRED. At most one PUBLISHED and one open (DRAFT or IN_REVIEW) version per type, by unique partial indexes. `reviewed_by <> submitted_by` by CHECK. A guarded `move(from, to, revision)` for every transition.
- Maker: `memo.types.manage`. Checker: the new `memo.types.approve`, with step-up on approve. Send-back needs a remark. A type is created as a draft version 1; it can be used once version 1 is published. Activating and deactivating a type stay direct (`memo.types.manage`, audited), because they change no memo's meaning.
- The transition rules (who may move what, same-person refusal, remark rules) live in a small, pure `common.review` helper. The memo module uses it; workflow definitions and DoA keep their own copies for now (no rework of merged code) and may adopt it later.

### Pinning
- A draft follows the current published version: the create and edit forms always show it, and its saved values are re-checked against it.
- **Submit pins the version** (`memo.memo_type_version_id`). From then on, the memo's workflow, numbering, fields and rules come from that version, including after a return and resubmission.
- Values for fields that the current version no longer has are kept on the draft and shown under "No longer on this form" until submit, where they are dropped. Submit writes a `memo.type_pinned` audit entry with the version and any dropped field keys.

### Fields
- A field is `{key, label, help?, type, required, rules, attribute, inbox}`. Types: TEXT, LONG_TEXT, NUMBER, AMOUNT, DATE, UNIT, PERSON, CHOICE, MULTI_CHOICE. Rules by type: lengths; min, max and decimals; allowed currencies (ISO 4217); choices with stable keys and editable labels. At most 40 fields per type. Keys match `^[a-z][a-zA-Z0-9]{0,39}$` and must not collide with built-in attribute names.
- The field list is validated by a JSON Schema (structure) and by Kotlin rules (meaning: unique keys, valid currencies, consistent bounds, choice keys unique).
- Values live in `memo.metadata jsonb`: AMOUNT as `{value: "1250000.00", currency: "BDT"}` (a decimal string, never a float), DATE as `YYYY-MM-DD`, UNIT and PERSON as ids, CHOICE as a key, MULTI_CHOICE as a list of keys. Saving a draft checks types and rules; submit also checks required fields. Errors come as RFC 9457 field problems at `metadata.<key>`.
- Labels are tenant content in the tenant's language (English only, Phase 3 Q3), not UI strings.

### Workflow attributes
- A field marked `attribute` is exposed to CEL conditions and DoA rules under its key. TEXT, CHOICE, UNIT and PERSON become `string`; NUMBER and AMOUNT become `double` (an amount in major units, plus `<key>Currency` as `string`); DATE becomes `timestamp` (start of day in the tenant's time zone). LONG_TEXT and MULTI_CHOICE cannot be attributes.
- Amounts as `double`: DoA bands are whole amounts (1,000,000; 5,000,000), which `double` represents exactly up to 2^53. The exact decimal stays on the memo; only comparisons use the `double`.
- The memo subject's attribute catalogue becomes **per tenant**: the built-ins plus the attribute fields of every published version of every active type, cached per tenant (`TenantCaches`, invalidated on publish and on activation changes, ADR-0020). `WorkflowSubject.attributes` takes the tenant.
- A key exposed as an attribute has **one type across the tenant**: publishing a version whose attribute field conflicts with another type's is refused (`ATTRIBUTE_CONFLICT`). Publishing a version that would remove the last declaration of an attribute declared by a published workflow definition or a published DoA version is refused (`ATTRIBUTE_IN_USE`).
- A memo of a type without the field gives `null`. A workflow condition then fails and takes `otherwise`, recorded with its inputs (ADR-0039); a DoA rule condition does not hold (ADR-0040). Admins scope rules by memo type when a field exists on only some types.

### Consequences
- Good: a memo's meaning is fixed at submit; every type change is reviewed and audited; DoA bands and conditions can use amounts and other fields; the builder, the simulator and the DoA screen pick up the new attributes with no change of their own.
- Bad: one more versioned entity and its screens; the per-tenant attribute catalogue adds a cache; the old `memo_type` columns are written twice until the contract migration.
- Neutral: the existing types are migrated to a published version 1 by Flyway with the same settings, and existing memos are pinned to it; the migration is recorded in the phase log, not the audit chain, because no person made the change.

## More information
- Research: `docs/plan/phase-3-memo-efile/epic-01-types-metadata-numbering/research.md` §1, §4, §5.
- Related: ADR-0036 (memo documents and step-up), ADR-0038 (definition lifecycle), ADR-0039 (CEL attributes), ADR-0040 (DoA), ADR-0044 (numbering).
