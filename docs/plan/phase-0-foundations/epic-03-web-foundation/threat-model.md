# E03 Web foundation — threat model (STRIDE)

## Data flows
1. Browser ↔ static web server (SPA assets).
2. Browser → backend `/api` (same origin).
3. Browser storage (preferences only).

| # | Threat | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|
| T1 | Script injection runs because of a permissive CSP | Strict CSP: `script-src 'self'`, no inline scripts, `object-src 'none'`, `base-uri 'self'` | V3.4.3 (CSP) | e2e `security-headers.spec.ts`; build check that `index.html` has no inline script |
| T2 | Clickjacking | `frame-ancestors 'none'` | V3.4.6 | e2e header assertion |
| I1 | Tokens or sensitive data persisted in Web Storage and readable by XSS | Only allow-listed preference keys (`memo.theme`, `memo.locale`); session in an HttpOnly cookie (Phase 1a) | V3.2.x / V14.3.x | e2e `storage-hygiene.spec.ts` |
| I2 | Third-party resource loading leaks user IPs or breaks on-prem installs | No external fonts, CDNs or analytics; CSP `default-src 'self'` | V15 (dependency isolation) | CSP + e2e console check for blocked requests |
| T3 | Translation files inject markup | react-intl renders text nodes; rich text uses explicit tag components only; ICU validity checked | V3.2.2 (safe rendering) | `i18n:verify`, `formatjs/no-invalid-icu` |
| D1 | — | — | — | — |

Residual risk: `style-src 'self'` without `'unsafe-inline'` blocks `<style>` elements and `style="…"` attributes written as markup or via `setAttribute`. It does **not** block CSSOM writes (`element.style.setProperty`), which is how React and Radix apply dynamic styles. The e2e CSP-violation check proves that the components we use stay within this. If a future component needs inline style markup, we decide on `style-src-attr` explicitly, in an ADR.
