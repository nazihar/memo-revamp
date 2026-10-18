# E01 App frame and visual design — log

## 2026-10-18
- Round 1 visual review: two directions on a canvas; the owner chose **Direction A (Ledger)** (`ux-review.md`).
- Plan written (research, ADR-0021, design, threat model, tasks, test plan). Gate delegated (overview Q1).
- Findings: TypeScript 7.0 is out but `typescript-eslint` supports `<6.1`, so we stay on 6.0.x; `eslint-plugin-jsx-a11y` does not support ESLint 10 and is unmaintained, so it is not adopted (axe covers accessibility).
- Implemented T1–T6 on `feat/1a-e01-app-frame`: Direction A tokens (light/dark, status pairs) and self-hosted IBM Plex Sans + Arabic; component kit; `Dialog`/`AlertDialog`/`Sheet` on native `<dialog>` (ADR-0021); app frame with navigation model, grouped sidebar, breadcrumbs and a Sheet menu below 1024 px; page templates (PageHeader, DataTable on TanStack Table v9 with cursor pagination, form helpers with Problem Details mapping and an unsaved-changes guard, TreePanel, states); patterns gallery with e2e, axe, CSP and visual suites.
- Findings:
  - **Vite inlined small font subsets as `data:` URIs**, which `font-src 'self'` blocks; the e2e CSP fixture caught it. Fonts are now never inlined (`build.assetsInlineLimit`).
  - **The visual-regression gate was too lenient since Phase 0**: with `maxDiffPixelRatio: 0.01` and the default per-pixel threshold 0.2, a completely new shell matched the Phase 0 baselines. Tightened to `0.002` / `0.1` and verified that the old baseline now fails (3 % of pixels differ) while fresh baselines pass.
  - **TanStack Table v9** changed the API (`useTable` with `features: tableFeatures({...})`; `getAllCells` without the column-visibility feature); fast-refresh lint needs non-component exports in their own files.
  - The e2e axe fixture waited for all animations; skeleton pulses never end, so infinite animations are now ignored.
  - The optional-field label lacked a space before "(optional)", so screen readers read "Description(optional)"; fixed and covered by a test.
- Results: Vitest 28/28; Playwright smoke 12/12 (6 new on the gallery); visual 9/9 with new Linux baselines; first-route JS 161 KB gzip (budget 250 KB).
