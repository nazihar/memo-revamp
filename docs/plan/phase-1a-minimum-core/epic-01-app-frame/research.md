# E01 App frame and visual design — research

All sources were accessed on **2026-10-18**.

## R1 — Versions, licenses, maintenance

| Package | Current | In use | License | Maintenance | Decision |
|---|---|---|---|---|---|
| TypeScript | 7.0.2 (2026-07-08) | 6.0.3 | Apache-2.0 | Active | **Stay on 6.0.x.** `typescript-eslint` 8.71.2 (and `typescript-estree`) supports TypeScript `>=4.8.4 <6.1.0`; re-check every epic (Phase 0 retro item) |
| @tanstack/react-table | 9.3.1 (2026-10-12); 9.0.0 GA 2026-08-04 | — | MIT | Active; v8 frozen since 2025-04 | **Adopt 9.3.1**, pinned |
| @tanstack/react-form | 1.33.5 (2026-08-11) | — | MIT | Active; 2.0 in alpha | **Adopt 1.33.5**, pinned (forms arrive with the form template) |
| @fontsource/ibm-plex-sans, @fontsource/ibm-plex-sans-arabic | 5.3.0 (2026-07-19) | — | OFL-1.1 (fonts), MIT (package) | Active | **Adopt**: self-hosted woff2 (CSP `font-src 'self'`), Latin and Arabic subsets, weights 400/500/600 |
| eslint-plugin-jsx-a11y | 6.10.2 (2024-10-26) | — | MIT | No release for two years; peer range `eslint ^3…^9` | **Not adopted**: we run ESLint 10. Accessibility stays covered by axe in component tests and every e2e visit (Phase 0 retro item closed with this finding) |

Sources: npm registry metadata (`registry.npmjs.org/<package>`), https://github.com/jsx-eslint/eslint-plugin-jsx-a11y, https://github.com/typescript-eslint/typescript-eslint/releases

## R2 — Visual direction
Done with the owner (`ux-review.md`, round 1): two directions on a canvas; **Direction A (Ledger)** chosen on 2026-10-18. Reference points used: admin-heavy enterprise apps (sidebar navigation grouped by area, dense tables, breadcrumbs), board portals (OnBoard, Diligent: calm palettes, large reading type in director views) and e-office systems (inbox-first navigation). What we take: grouped sidebar, breadcrumbs, compact tables with real cursor pagination, two-column forms with a dirty-state warning. What we do better than typical admin UIs: visible labels and hints on every field, status as text plus icon, 40–44 px controls, logical CSS so the whole frame mirrors under RTL.

## R3 — Overlays under the strict CSP
Our CSP has no `'unsafe-inline'` for scripts or styles (`web/security-headers.json`). Findings:
- **Inline style elements are blocked; CSSOM is not.** CSP `style-src` governs `<style>` elements and `style="…"` attributes parsed from markup. Setting `element.style.x` from script (what React and Floating UI do for positioning) is allowed. So Radix popovers, menus, selects and tooltips work.
- **Radix modal primitives inject a `<style>` element** for scroll locking (`react-remove-scroll` → `react-style-singleton`), and hide siblings with `aria-hidden` (`aria-hidden` + focusable content triggered axe `aria-hidden-focus` in Phase 0). `react-style-singleton` takes a nonce from `get-nonce`.

| Option | Pros | Cons |
|---|---|---|
| A. Per-request style nonce: nginx `sub_filter` writes a nonce into `index.html` and into `style-src`; the app calls `setNonce()` | Radix/shadcn Dialog, Sheet and AlertDialog unchanged | `index.html` becomes per-request; nonce plumbing in nginx, the Vite dev server and any customer reverse proxy that serves the SPA; easy to break on-prem |
| **B. Native `<dialog>` for modal surfaces** (`showModal()`): browser makes the rest of the page inert, traps focus, handles Escape and the top layer | Static CSP stays the single source of truth; no injected styles; no `aria-hidden` on the app root; widely supported in current browsers | We own Dialog, Sheet and AlertDialog components (small); scroll locking via a CSS class on `<html>` |
| C. `style-src 'unsafe-inline'` | Trivial | Weakens CSP; rejected |

**Recommendation: B** (ADR-0021). Fewer moving parts for on-prem installs (AGENTS §7).

Sources: https://www.w3.org/TR/CSP3/#directive-style-src, https://html.spec.whatwg.org/multipage/interactive-elements.html#the-dialog-element, https://developer.mozilla.org/en-US/docs/Web/HTML/Element/dialog, `react-style-singleton` 2.2.3 source (`singleton.js`), https://www.radix-ui.com/primitives/docs/components/dialog

## R4 — Target size and density
WCAG 2.2 AA (2.5.8 Target Size, Minimum) requires 24 × 24 px; 44 px is the AAA level (2.5.5). Direction A uses 40 px controls and 44 px icon-only buttons in the working screens. Director mode (Phase 5) reuses the tokens at a larger scale (Direction B's 48–56 px). Source: https://www.w3.org/TR/WCAG22/#target-size-minimum
