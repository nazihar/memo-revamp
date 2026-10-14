# E03 Web foundation — log

## 2026-10-14
- Plan written (research, ADR-0009 lint, ADR-0010 i18n, design, threat model, tasks, test plan). Gate delegated.
- Implemented T1–T7 on branch `phase-0/e03-web-foundation`.
  - ESLint 10 + Prettier with formatjs, security and TanStack rules.
  - react-intl with extract, verify and pseudo-locales; `en` and `ar`.
  - Tailwind 4 + shadcn/ui primitives; tokens; light/dark/system theme; RTL shell; `StatusBadge`.
  - Vitest helpers (providers, real route tree, axe).
  - Playwright projects with axe on every visit and a CSP-violation fixture, role fixture, smoke/security/pseudo/visual specs; Linux visual baselines rendered in the Playwright container.
  - Lighthouse budgets.
- Verified locally:
  - lint, typecheck and format clean;
  - Vitest 12/12;
  - Playwright smoke 6/6 and pseudo 1/1 against the real `pnpm dev` stack;
  - visual 4/4 against the committed baselines;
  - i18n extract, pseudo and verify clean;
  - main bundle 157 KB gzip.
- The test infrastructure caught two real defects:
  - modal Radix menus inject inline styles (CSP) and hide the app root from assistive technology. Fixed with non-modal menus.
- Negative check: injecting a hard-coded string makes the pseudo-locale e2e fail.
- Open: owner UX walkthrough (`ux-review.md`).
