# E01 App frame and visual design — threat model (STRIDE)

E01 adds no endpoint and handles no data. The relevant threats are in the browser.

| # | Threat | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|
| T1 | A UI library injects inline styles or scripts, forcing a weaker CSP later | Modals on native `<dialog>` (ADR-0021); CSP unchanged; any violation fails e2e | V3.4 (browser security headers) | CSP fixture on every e2e test, all modals opened in `patterns.spec.ts` |
| I1 | Fonts or icons loaded from third-party origins leak user activity | Self-hosted fonts; CSP `font-src 'self'`; existing "no third-party requests" e2e | V3.6 | existing `security.spec.ts` |
| I2 | The patterns gallery (synthetic data, all components) reachable in production | Route enabled only in development and `e2e` builds; Not Found in production | V13 (configuration) | production-build check (T6) |
| S1 | Error states leak internals | `ErrorState` shows the Problem Details title and correlation id only | V16.5 | `ErrorState` component test |
| E1 | Dialogs that don't make the background inert let users act on hidden controls | `showModal()` makes the rest of the document inert | — | dialog component tests + axe |
