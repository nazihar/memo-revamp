# Test plan — E01 App frame and visual design

## Scope and risks
New: tokens, fonts, component kit, modal surfaces, app frame, page templates, patterns gallery. Highest risks: inaccessible custom widgets (dialogs, tree, table), CSP regressions from UI libraries, layouts that break in RTL or with long (pseudo-localised) strings. See [design.md](design.md), [threat-model.md](threat-model.md).

## Tests per slice
| Slice | Layer | Test (behaviour) | Data / fixtures | CI stage |
|---|---|---|---|---|
| T2 | Component (Vitest + Testing Library) | `Field`: label/hint/error wiring (`aria-describedby`, `aria-invalid`); `Button` disabled/pending; `StatusBadge` always has text | — | PR |
| T3 | Component | `Dialog`/`AlertDialog`/`Sheet`: opens with `showModal`, focus in, Escape closes, focus returns; AlertDialog default focus is Cancel | jsdom `<dialog>` polyfill in test setup | PR |
| T4 | Component | `AppShell`: navigation model renders enabled items only; active item `aria-current`; Menu button opens the Sheet below the breakpoint | router test harness | PR |
| T5 | Component | `DataTable`: sort with `aria-sort`; select all; empty/loading/error states. `CursorPagination`: Previous disabled on first page. `Form`: Problem Details → field errors, focus first error; unsaved-changes guard. `TreePanel`: keyboard pattern | synthetic rows | PR |
| T6 | E2E (Playwright, Chromium) | `patterns.spec.ts`: each gallery section with axe; open every modal (CSP fixture + axe); keyboard-only run through the frame | gallery | PR (smoke), nightly (Firefox, WebKit) |
| T6 | Visual | Gallery and frame in light/dark × LTR/RTL; Sheet navigation at 390 px | gallery | Nightly |
| T6 | i18n | Pseudo-locales `xx-LS`, `en-XA` on the gallery (no truncation, no hard-coded text) | gallery | Nightly |
| T6 | Build | `/patterns` returns Not Found in a production build (`image-smoke` / e2e against the production image) | production image | PR |
| — | Performance | Lighthouse CI: first-route JS ≤ 250 KB gzip, LCP ≤ 2.5 s, CLS ≤ 0.1 | home route | Nightly |

## Authorization matrix tests
None (no endpoints).

## State-machine tests
Dialog open/closed; form pristine → dirty → submitting → saved/error (component tests).

## Security tests
CSP fixture on every e2e test (including all modals open); no third-party requests (existing e2e); no new browser storage keys (existing e2e).

## Accessibility and i18n
axe on every gallery section and open dialog; manual keyboard and screen-reader pass (NVDA + Firefox) over the frame, list, form, dialog and tree recorded in `ux-review.md`; Arabic RTL screenshots.

## Exploratory charters
1. Keyboard only, 200 % zoom: reach every control in the frame and templates; nothing hidden or trapped (30 min).
2. Pseudo-locale and Arabic: long labels, mirrored icons and layout (30 min).

## Acceptance mapping
T1 → axe contrast in `patterns.spec.ts`; T3 → `Dialog.test.tsx`, `patterns.spec.ts`; T4 → `AppShell.test.tsx`, visual; T5 → `DataTable.test.tsx`, `Form.test.tsx`, `TreePanel.test.tsx`; T6 → `patterns.spec.ts`, production-build check.

## Exit checklist
- [x] All listed tests implemented and green in their stage (manual screen-reader pass pending, see ux-review.md)
- [x] No new quarantined tests
- [ ] Manual accessibility pass recorded
