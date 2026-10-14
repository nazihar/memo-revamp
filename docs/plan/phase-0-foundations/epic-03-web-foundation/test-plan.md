# Test plan — E03 Web foundation

## Scope and risks
This epic adds the shell, theming, i18n/RTL, the lint gates, the e2e and accessibility infrastructure, and CSP. Risks:
- hard-coded strings;
- RTL breakage;
- a11y regressions;
- CSP regressions.

## Tests per slice
| Slice | Layer | Test (behaviour) | Data / fixtures | CI stage |
|---|---|---|---|---|
| T1 | Lint | ESLint (formatjs no-literal-string, security, TanStack) | — | PR |
| T2 | i18n | `i18n:extract` drift; `i18n:verify` (missing keys, ICU structure) | messages | PR |
| T2 | Component | `LocaleSwitcher.test.tsx`: Arabic → `lang=ar`, `dir=rtl`, Arabic text rendered; persisted choice is restored | — | PR |
| T3 | Component | `ThemeToggle.test.tsx`: dark → `.dark` on `<html>`, persisted; system follows `prefers-color-scheme` | matchMedia stub | PR |
| T3/T4 | Component + a11y | `AppShell.test.tsx`: landmarks, skip link moves focus to main, axe clean | MSW health | PR |
| T3 | Component | `HealthPage.test.tsx` (E01) via `renderWithProviders`, plus axe | MSW | PR |
| T5 | E2E smoke + a11y | `health.spec.ts`: page renders UP from the real stack; axe after every visit | Compose stack | PR (E04) |
| T5 | E2E | `storage-hygiene.spec.ts`: only allow-listed keys in localStorage; sessionStorage empty | — | PR |
| T5 | Visual | `visual.spec.ts`: shell in light/dark × LTR/RTL | Linux container baselines | Nightly |
| T5 | i18n | `pseudo.spec.ts`: with `xx-LS`, every visible text node is pseudo-localised (catches hard-coded strings) | — | Nightly |
| T6 | Security | `security-headers.spec.ts`: CSP and friends on `vite preview`; no console CSP violations; built `index.html` has no inline scripts | `vite preview` | PR |
| T7 | Performance | Lighthouse CI budgets | `vite preview` | Nightly |

## Accessibility and i18n
- axe (WCAG 2.2 A and AA tags): component and e2e; zero serious or critical violations.
- Manual keyboard checklist (Phase 0): Tab order is skip link → header controls → nav → main; menus open with Enter/Space and close with Esc; focus is always visible.
- Languages: en (source), ar (RTL), xx-LS and en-XB (pseudo).

## Exploratory charters
1. Use the shell with the keyboard only, in RTL and at 200 % zoom (30 min).
2. Try to make the app load anything from a third-party origin (30 min).

## Exit checklist
- [x] All listed tests implemented and green in their stage (Vitest 12; Playwright smoke 6, pseudo 1, visual 4 with Linux baselines; Lighthouse config ready for nightly; firefox and webkit projects defined, first run in nightly CI)
- [x] No new quarantined tests
- [x] ASVS checklist updated (V3 rows)
