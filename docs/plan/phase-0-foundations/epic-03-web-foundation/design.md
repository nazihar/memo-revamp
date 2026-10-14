# E03 Web foundation — design

Decisions: [ADR-0009](../../../adr/0009-web-lint-and-format.md) (lint), [ADR-0010](../../../adr/0010-web-i18n.md) (i18n).

## 1. Application shell (wireframe)

```
┌──────────────────────────────────────────────────────────────────────┐
│ [Skip to content]                                                     │
│ ▣ Memo & Meeting Management            [Language ▾] [Theme: ◐ ▾]      │  ← header (banner)
├───────────────┬──────────────────────────────────────────────────────┤
│ Navigation    │  <h1>Platform status</h1>                             │
│  • Status     │  ...page content (main, id="main")...                │
│               │                                                       │
│ (collapses to │                                                       │
│  a menu button│                                                       │
│  < 768 px)    │                                                       │
└───────────────┴──────────────────────────────────────────────────────┘
```
- Landmarks: `header` (banner), `nav` (aria-label), `main` (`id="main"`, the skip-link target).
- The navigation sits on the *inline-start* side, so it moves to the right under RTL. All spacing uses logical properties.
- **Theme:** light, dark or system. The choice is stored in `localStorage["memo.theme"]`; this is a UI preference, not a credential, so AGENTS §2 is respected. It is applied with the `.dark` class on `<html>`. An external theme bootstrap script prevents a flash of the wrong theme without needing an inline script (CSP).
- **Language:** English and العربية, plus the pseudo-locales in non-production builds. The choice is stored in `localStorage["memo.locale"]` and sets `<html lang dir>`.
- Touch targets are ≥ 44 px. Focus is visible (a `ring` token). Base font size is 16 px with a system font stack.

## 2. Design tokens
CSS custom properties in `src/styles/tokens.css`, consumed by Tailwind 4 `@theme inline`:
- colours: `--background`, `--foreground`, `--card`, `--muted`, `--primary`, `--primary-foreground`, `--border`, `--ring`, `--destructive`;
- status colours: `--status-up`, `--status-degraded`, `--status-down`;
- `--radius`.

Light and dark values are checked for **WCAG AA contrast (4.5:1 for text)** by axe in tests.

## 3. Status component
`StatusBadge` shows a text label plus a colour and icon. Colour is never the only signal (WCAG 1.4.1). It is used by the health page.

## 4. Security
- The production build contains **no inline scripts**. Vite emits external module scripts; the theme bootstrap is an external file in `public/`.
- One source of truth for headers is `web/security-headers.json`:
  - CSP: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'`
  - `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`
- `vite preview` serves these headers. The E04 web image (static server) uses the same file.
- **No tokens or sensitive data in Web Storage.** An e2e check asserts that `localStorage` holds only allow-listed preference keys and that `sessionStorage` is empty.

## 5. Testing infrastructure
- **Vitest + Testing Library + MSW.** `renderWithProviders()` sets up the query client, intl and router. Shared typed handlers live in `src/test/handlers.ts`. An `expectNoA11yViolations(container)` helper runs axe-core.
- **Playwright** (`web/e2e`). The `page` fixture is wrapped: every `goto` runs axe (serious and critical violations fail the test) and records console CSP violations. Projects:
  - `smoke` (Chromium; PR);
  - `full` (Chromium, Firefox, WebKit; nightly);
  - `visual` (light/dark × LTR/RTL screenshots; nightly, Linux container baselines);
  - `pseudo` (`xx-LS`; nightly).
- **Role fixtures** (Phase 0 Q5): `e2e/fixtures/roles.ts` maps role → storage state. Only `anonymous` exists now. Phase 1a adds initiator, approver, secretary, director and tenant-admin by performing real logins in global setup. There is no auth bypass.
- **Lighthouse CI** config with budgets (script ≤ 250 KB gzip on the first route, LCP ≤ 2.5 s, CLS ≤ 0.1); runs nightly from E04.

## 6. Audit, tenancy, API
No changes. The web app still calls only `GET /api/v1/health`.

## 7. UX review
The product owner gets a walkthrough of the shell (themes, RTL, keyboard navigation) at the end of E03. Findings go into `ux-review.md`.

## 8. Implementation notes (differences from the plan above)
- **Pseudo-locales.** FormatJS `xx-LS` only lengthens strings. The *accented and bracketed* locale is `en-XA`, so the build ships three pseudo-locales: `en-XA` (used by the nightly hard-coded-string check), `xx-LS` (length) and `en-XB` (bidi).
- **Menus are non-modal** (`modal={false}`), following the WAI-ARIA menu-button pattern. The e2e CSP fixture showed that modal Radix menus inject an inline `<style>` for their scroll lock, which our CSP blocks, and they set `aria-hidden` on the app root (axe `aria-hidden-focus`). Modal dialogs in Phase 1a need a CSP nonce strategy; that gets an ADR when the first dialog is built.
- **Typed messages.** `defineMessages<{…}>` declares placeholder value types, so a missing or misspelled ICU argument fails `tsc`. Values are primitives; dates are pre-formatted with `intl.formatDate`.
- **Static JSX a11y lint is not used** (ADR-0009). `security/detect-object-injection` is off: it flags every typed `obj[key]`.
- **`vite preview` allows the host `host.docker.internal`** so the Linux Playwright container can render visual baselines.
