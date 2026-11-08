---
status: accepted
date: 2026-11-08
deciders: product owner (delegated), agent
---

# 0032 — Tenant terminology overrides as term arguments

## Context and problem statement
Tenants call the same things by different names ("unit" or "department", "memo" or "note"). Overrides must reach UI messages and emails, in every language the tenant uses, without breaking grammar: Arabic nouns have grammatical gender that adjectives and verbs agree with, and six plural categories (overview E07, R17). Research: `phase-1a-minimum-core/epic-07-tenant-admin/research.md §R17`.

## Decision drivers
- Grammar stays in translators' hands; tenant admins type words, not ICU syntax.
- A message change must not silently break a tenant's customisation.
- Same mechanism for web messages and email templates.

## Considered options
1. Per-tenant message overlays (override whole messages by id).
2. Search-and-replace on rendered text.
3. **Term arguments**: messages take terms as arguments; tenants override the term's forms.

## Decision outcome
Chosen option: **3**.

- **Catalogue (code):** `TermKey` = `unit`, `position`, `memo`. Each term has forms `one` and `other` and, per language that needs it, `gender` (`masculine | feminine`). Defaults per locale are messages in the catalogue: `term.unit.one`, `term.unit.other`, `term.unit.gender`, translated like any message.
- **Override (tenant):** table `tenancy_term` (`tenant_id`, `term`, `locale`, `one`, `other`, `gender`, `version`); 1–40 characters per form, no `{`, `}`, `<`, `>`, `#` or control characters. A missing override for a locale falls back to that locale's defaults, never to another locale's override.
- **Messages:** a message that mentions a term takes the arguments `{unit}`, `{units}`, `{Unit}`, `{Units}` (capitalised forms derived with the locale's upper-casing of the first letter; identical in Arabic) and `{unitGender}` for agreement through ICU `select`. Messages never combine a count with an overridable term; counted phrases use neutral wording.
- **Web:** `GET /api/v1/terms?locale=` (signed-in and public, cached by version) gives the resolved forms; a `useTerms()` hook returns the argument map and `formatMessage` calls for term messages pass it. A lint check (`i18n:verify`) fails if a message uses a term argument that `useTerms` does not supply, or names a term literally where the catalogue says it must be an argument (word list for the org screens).
- **Email:** `MailTexts` resolves `{term.unit}` and friends from the same tenant terms for the outbox locale.
- **1a scope:** org screens use `unit` and `position`; `memo` is in the catalogue and the settings screen for Phase 2.

### Consequences
- Good: admins only type nouns; translators keep agreement correct; messages can change freely because overrides hold no message text.
- Bad: messages with terms are slightly harder to write (arguments and `select`); count-plus-term phrases are not available; a term's article (Arabic definite article "ال") is part of the message, so admins enter the bare noun.
- Follow-ups: grammatical case attributes when a language that needs them is added; term use in Phase 2 memo screens and emails.

### Confirmation
- Web unit tests: overridden forms render; Arabic `select` picks the feminine and masculine forms; fallback to defaults per locale.
- `i18n:verify` checks term arguments.
- `TerminologyIT`: validation, version check, audit, cache invalidation; `MailTemplatesTest` with a term in a test template.

## Pros and cons of the options
### Message overlays
- Good, because any grammar is possible.
- Bad, because each tenant must override every affected message in every language, overlays silently go stale when a message changes, and admins edit ICU syntax.

### Search-and-replace
- Good, because no message changes are needed.
- Bad, because it breaks inflection, capitalisation, compounds and every language other than the one it was written for.

### Term arguments (chosen)
- Good, because forms and agreement are explicit and translator-owned.
- Bad, because message authors must use the arguments consistently (lint check).

## More information
- https://projectfluent.org/fluent/guide/terms.html (accessed 2026-11-08)
- https://formatjs.github.io/docs/core-concepts/icu-syntax/#select-format (accessed 2026-11-08)
- https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html (accessed 2026-11-08)
