# E03 Web foundation — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | ESLint + Prettier toolchain (ADR-0009); fix existing code | S | — |
| T2 | react-intl pipeline: extract, verify, pseudo; `ar` locale; locale and direction switching | M | T1 |
| T3 | Tailwind 4 + shadcn/ui; tokens; light/dark/system theme; app shell (skip link, header, nav, main); `StatusBadge`; health page restyled | M | T2 |
| T4 | Test infrastructure: `renderWithProviders`, central MSW handlers, axe helper; component tests for shell, theme and locale | S | T3 |
| T5 | Playwright: config and projects, axe-per-visit fixture, role fixture (anonymous), health smoke, storage hygiene check, visual spec (Linux baselines), pseudo-locale spec | M | T3 |
| T6 | CSP-compatible build + `security-headers.json` + `vite preview` headers + e2e header assertions | S | T5 |
| T7 | Lighthouse CI config and budgets | S | T5 |

## Acceptance criteria
- **T1:** `pnpm --filter web lint` passes; a hard-coded JSX string fails lint.
- **T2:**
  - `pnpm --filter web i18n:extract` is clean (no diff);
  - `i18n:verify` passes for `ar`;
  - choosing Arabic sets `<html lang="ar" dir="rtl">`;
  - `xx-LS` renders accented, longer strings.
- **T3:** the theme toggle persists across reloads; the layout mirrors under RTL; axe reports 0 serious or critical violations in light and dark.
- **T5:** `pnpm --filter web e2e` (smoke) passes against `pnpm dev`; any axe violation fails the test.
- **T6:** the production `index.html` contains no inline `<script>`; preview responses carry the CSP; the e2e run records no CSP violations.
