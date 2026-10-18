# E01 App frame and visual design — UX review

## Round 1: visual direction (sent 2026-10-18)
Two directions, each shown on three screens that cover the page types every later epic uses: the app frame with a **list page** (users), a **tree + detail + form page** (org unit) and the **unauthenticated pages** (sign-in and MFA). Canvas: https://claude.ai/artifact/Qb8eHhT5rzdq2EXbw9A5Rx (private to the owner).

| | Direction A — Ledger | Direction B — Clear |
|---|---|---|
| Feel | Compact, institutional, data-dense | Spacious, calm, large type |
| Navigation | Sidebar on the inline-start side, grouped (Work, Administration); breadcrumbs in a top bar | Top bar with main sections; second-level tabs for the active section |
| Type | IBM Plex Sans + IBM Plex Sans Arabic (OFL), 14 px base | Atkinson Hyperlegible Next (OFL, designed for low vision) + Noto Sans Arabic, 16–17 px base |
| Colour | Navy `#1F4E8C` on cool grey | Teal `#0B5F69` on white |
| Density | 40–44 px rows and controls | 48–56 px rows and controls |
| Forms | Two-column grid, tabs (Details / Positions / History) | Single column, grouped sections, history as a link |
| Best for | Secretaries and admins working through many records | Occasional users and directors; older, non-technical readers |

Shared in both: 44 px minimum targets, text + icon status badges (never colour alone), visible labels on every field, cursor pagination (Previous/Next, no page numbers), a dirty-state warning on forms, logical CSS properties (mirrors under RTL), tenant logo and name in the frame, no tenant switcher (Q3).

Agent recommendation: **A for the working screens, with B's type size and control height in director mode** (Phase 5), which reuses the same tokens at a larger scale.

**Decision:** waiting for the owner.
