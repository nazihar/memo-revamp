# E03 Web foundation — research

All sources accessed **2026-10-14**: the npm registry (versions, peer dependencies, licences, publish dates), plus the official docs of FormatJS, shadcn/ui, Playwright and axe-core.

## R10 — Lint/format: ESLint + Prettier vs Biome

| Criterion | ESLint 10 + typescript-eslint 8.71 + Prettier 3.9 | Biome 2.5 |
|---|---|---|
| Speed | slower (JS) | very fast (Rust) |
| **Externalised-string lint** (AGENTS §3.6: no hard-coded UI strings) | **`eslint-plugin-formatjs` 8.1** `no-literal-string-in-jsx`, `enforce-id`, `no-invalid-icu`, `enforce-placeholders` | no equivalent rule; we would need a custom plugin (GritQL) |
| Type-aware rules (floating promises, unsafe any) | yes (typescript-eslint, needs TS ≤ 6.0) | partial (own type inference) |
| TanStack Query rules | `@tanstack/eslint-plugin-query` 5.104 | no |
| Security rules (TESTING_STRATEGY §6, "eslint security rules") | `eslint-plugin-security` 4.2 | no |
| JSX a11y | `eslint-plugin-jsx-a11y` 6.10.2 is **stale** (last release Oct 2024, peers ESLint ≤ 9). The fork `eslint-plugin-jsx-a11y-x` 0.2.0 is pre-1.0 | some a11y rules |
| Tools to maintain | 2 (ESLint, Prettier) | 1 |

**Recommendation: ESLint 10 + Prettier** (ADR-0009). The formatjs i18n rules, the security rules and the TanStack rules are requirements in AGENTS.md and TESTING_STRATEGY.md, and only ESLint provides them. Static JSX a11y linting is skipped: the original plugin is stale and the fork is pre-1.0. Accessibility is enforced instead by axe in component tests and on every e2e page visit, as TESTING_STRATEGY §4 requires. Re-evaluate a static a11y plugin in Phase 1a.

## R12 — React i18n with ICU

| Criterion | **react-intl 12 (FormatJS)** | i18next 26 + ICU plugin | Lingui 6 |
|---|---|---|---|
| ICU MessageFormat completeness | native, full (plural, select, selectordinal, rich text, number and date skeletons) | via `i18next-icu`, a plugin with partial feature parity | native |
| Extraction / compile | `@formatjs/cli extract` / `compile` (AST precompile → smaller runtime) | i18next-parser | Lingui CLI with macros (needs a Babel or SWC plugin) |
| **Pseudo-localisation** | built in: `compile --pseudo-locale` (`xx-LS` = longer, `en-XA` = accented, `xx-HA`, `en-XB` = bidi) | plugin | built in |
| Lint integration | `eslint-plugin-formatjs` | `eslint-plugin-i18next` | partial |
| Translation consistency check | `formatjs verify --structural-equality --missing-keys` | — | — |
| Licence | BSD-3 / MIT | MIT | MIT |
| Build-time plugins needed | none (explicit ids) | none | macro transform |

**Recommendation: react-intl** (ADR-0010). It has full ICU support, ships pseudo-locales and verification, works with explicit message ids and needs no build plugin.

Decisions taken with it:
- **Explicit, namespaced ids** (`health.title`). Messages are defined next to the code with `defineMessages`.
- **The source locale is `en`.** `src/i18n/messages/en.json` is extracted from the source code and must not be edited by hand.
- **Translations** live in `ar.json` (the RTL demonstration locale; the pilot's languages are added in Phase 3) and are checked with `formatjs verify`.
- **Pseudo-locales** `xx-LS` (+30–40 % length, accented) and `en-XB` (bidi) are generated at build time and can be chosen in the dev and e2e builds. They are never offered in production.

## R11 — hey-api queryOptions and typed MSW
Done in E01: `@tanstack/react-query` plugin output `getHealthOptions()`; MSW handlers typed with the generated `Health` type. Handlers are now centralised in `src/test/handlers.ts`.

## shadcn/ui on Radix (with Tailwind 4)
- shadcn CLI 4.21 copies component sources into `src/components/ui`; the code is ours from then on.
- It depends on `radix-ui` 1.8, `class-variance-authority` 0.7.1, `clsx` 2.1, `tailwind-merge` 3.7, `tw-animate-css` 1.4 and `lucide-react` (icons, ISC).
- Tailwind 4.3 is configured CSS-first through `@tailwindcss/vite`. Design tokens are CSS custom properties (`--background`, `--foreground`, `--primary`, …) with a `.dark` variant. **Logical properties** (`ms-`, `me-`, `ps-`, `pe-`, `start-`, `end-`) are used instead of `ml/mr/left/right` so layouts mirror under `dir="rtl"`.
- No external fonts: a system font stack, so nothing is loaded from third-party hosts under a strict CSP.

## Playwright + axe
- `@playwright/test` 1.64 and `@axe-core/playwright` 4.13 (MPL-2.0, dev-only).
- A custom fixture runs axe after every navigation and fails the test on `serious` or `critical` violations. It uses the WCAG 2.0/2.1/2.2 A and AA tags.
- Visual baselines are OS-specific, so they are produced in the official Playwright Linux container. Nightly CI and local Docker use the same image.

## Lighthouse CI
`@lhci/cli` 0.15.1 (Apache-2.0, June 2025) is still the only maintained CLI for Lighthouse budgets. It runs nightly and is dev-only.

## Industry practice
- **Status as text, not colour alone** (GOV.UK and USWDS design systems) is already applied on the health page.
- The **reading experience for older users** (AGENTS §3.7): the base font size is 16 px, the minimum touch target is 44×44 px (WCAG 2.5.8 needs 24 px; we use the AAA-ish 44 px), focus rings are always visible, and there are no hover-only affordances.
