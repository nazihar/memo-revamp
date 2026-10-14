---
status: accepted
date: 2026-10-14
deciders: product owner (delegated), agent
---

# 0009 — Web lint and format: ESLint + Prettier

## Context and problem statement
AGENTS.md §4 requires a choice between ESLint + Prettier and Biome, recorded in an ADR. The lint must also enforce externalised UI strings and valid ICU messages (§3.6), apply security rules (TESTING_STRATEGY §6), and catch TanStack Query misuse. Research: `epic-03-web-foundation/research.md §R10`.

## Considered options
1. ESLint 10 + typescript-eslint + Prettier
2. Biome 2.5

## Decision outcome
Chosen option: **1**, because only ESLint has the required rule sets: `eslint-plugin-formatjs` (no literal strings in JSX, ids, ICU validity), `eslint-plugin-security` and `@tanstack/eslint-plugin-query`. Prettier formats; `eslint-config-prettier` turns off conflicting stylistic rules.

Configuration (flat config, `web/eslint.config.js`):
- `@eslint/js` recommended
- typescript-eslint `strictTypeChecked` + `stylisticTypeChecked`
- react-hooks
- react-refresh
- TanStack Query
- formatjs:
  - `no-literal-string-in-jsx`: error
  - `enforce-id`: explicit
  - `no-invalid-icu`
  - `enforce-placeholders`
- security
- Generated code (`src/api/generated`, `routeTree.gen.ts`) is ignored.

Static JSX a11y linting is **not** included: `eslint-plugin-jsx-a11y` is stale and does not support ESLint 10, and its fork is pre-1.0. Accessibility is enforced by axe in component and e2e tests. Revisit in Phase 1a.

### Consequences
- Good: requirements are enforced mechanically.
- Bad: slower than Biome; two tools to keep updated.
- Bad: typescript-eslint pins TypeScript to < 6.1 (ADR-0002).

### Confirmation
`pnpm --filter web lint` runs in the PR stage (E04).
