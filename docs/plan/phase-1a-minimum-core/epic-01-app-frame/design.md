# E01 App frame and visual design — design

Visual direction: **A (Ledger)**, approved by the owner on 2026-10-18 (`ux-review.md`). Canvas with the reference screens: https://claude.ai/artifact/Qb8eHhT5rzdq2EXbw9A5Rx (private). Decisions: [ADR-0021](../../../adr/0021-modal-dialogs-native-dialog.md) (modals on `<dialog>`), ADR-0009 (lint), ADR-0010 (i18n).

E01 is web-only. It builds the frame and the page templates every later screen uses; the feature screens come with their epics (E03 sign-in, E04 org, E07 admin).

## 1. Design tokens v2 (`web/src/styles/tokens.css`)

Names stay as in Phase 0 (shadcn convention) so components need no renames; values change, and status pairs are added.

| Token | Light | Dark | Use |
|---|---|---|---|
| `--background` | `#F6F7F9` | `#10151D` | Page ground |
| `--card` | `#FFFFFF` | `#171E29` | Surfaces: sidebar, top bar, tables, forms |
| `--foreground` | `#1B2333` | `#E8ECF2` | Body text |
| `--muted-foreground` | `#5A6475` | `#A3ADBD` | Secondary text, labels of groups (≥ 4.5:1 on card and background) |
| `--border` / `--input` | `#DDE1E7` / `#C7CDD6` | `#2A3341` / `#3A4556` | Dividers / control borders |
| `--primary` | `#1F4E8C` | `#8DB2EA` | Primary actions, active navigation |
| `--primary-foreground` | `#FFFFFF` | `#0D1726` | Text on primary |
| `--accent` / `--accent-foreground` | `#E8EEF7` / `#163A69` | `#1E2B40` / `#C9DAF4` | Active nav item, selected row |
| `--ring` | `#2F6BC0` | `#8DB2EA` | Focus outline |
| `--destructive` | `#B42318` | `#F28B82` | Destructive actions, errors |
| `--status-{ok,warn,danger,neutral}-bg/-fg` | `#E6F3EA/#1E6B3A`, `#FDF1DC/#7A4B00`, `#FBE7E7/#9B1C1C`, `#EEF0F3/#4B5563` | dark equivalents ≥ 4.5:1 | Status badges (always text + icon) |

- Typography: **IBM Plex Sans** and **IBM Plex Sans Arabic** (OFL, self-hosted woff2, weights 400/500/600, Latin and Arabic subsets); system fallbacks after them. Base size 14 px for working screens; headings 22 px / 600.
- Radius 6 px for controls, 8 px for surfaces. Spacing on a 4 px grid.
- Density: controls 40 px high, icon-only buttons 44 × 44 px (WCAG 2.2 AA asks 24 px; research §R4).
- Contrast: every text pair ≥ 4.5:1, checked by axe in light and dark on every e2e visit.

## 2. App frame

```
┌───────────────┬───────────────────────────────────────────────────────────┐
│ [logo] Tenant │ Breadcrumbs ............................ [lang] [theme] [·]│  top bar (sticky)
│ ─────────────  ├───────────────────────────────────────────────────────────┤
│ WORK          │  Page header: title, description, actions                 │
│  Home         │                                                           │
│  …            │  Page content (template)                                  │
│ ADMINISTRATION│                                                           │
│  …            │                                                           │
└───────────────┴───────────────────────────────────────────────────────────┘
```

- **Sidebar** (inline-start, 232 px, `nav` landmark "Main navigation"): tenant identity at the top (logo slot + name), grouped sections. Items come from a typed **navigation model** (`web/src/app/navigation.ts`): label message, route, icon, group, and an `enabled` flag, so screens join the menu as their epic ships (E01: only Home). `aria-current="page"` on the active item.
- **Small screens (< 1024 px):** the sidebar becomes an off-canvas **Sheet** (`<dialog>`, ADR-0021) opened by a "Menu" button in the top bar.
- **Top bar** (`header` landmark): breadcrumbs from route `staticData`, then language menu, theme menu, and two slots filled later: notifications (E06) and the account menu (E03).
- **Tenant identity**: `useTenantBranding()` returns the product defaults ("memo", no logo) until E03 resolves the tenant from the host and E07 adds branding.
- **Skip link** to `#main`; `main` landmark focusable for the skip link.
- Everything uses logical properties, so the frame mirrors in Arabic.

## 3. Page templates (`web/src/components/page/`)

| Template | Parts | Behaviour |
|---|---|---|
| `PageHeader` | title (h1), description, actions slot | One h1 per page; actions wrap on narrow screens |
| `ListPage` | filter bar (search + selects + "Clear filters"), `DataTable`, cursor pagination footer | `DataTable` on TanStack Table v9: column definitions, optional row selection with an accessible "select all", sortable headers announce the sort (`aria-sort`), empty / loading / error states, horizontal scroll inside its own box. Pagination is cursor-based: Previous/Next only, no page numbers (AGENTS §4) |
| `DetailPage` | header with status badge, tabs (Radix Tabs; CSSOM only), content | Tab state in the URL search params so links and Back work |
| `FormPage` / `Form` | two-column grid of `Field`s, actions bar | TanStack Form; `Field` wires label, hint, error (`aria-describedby`, `aria-invalid`); server Problem Details (`errors[].field`) map onto fields; dirty-state banner and a confirmation on leaving with unsaved changes (router blocker + `AlertDialog`); submit disabled while pending |
| `TreePanel` | searchable tree (WAI-ARIA tree pattern: arrow keys, Home/End, type-ahead) | Used by the org editor (E04) |
| States | `EmptyState`, `LoadingState` (skeleton), `ErrorState` (Problem Details title + correlation id + retry) | Never show raw errors |

## 4. Component kit additions (`web/src/components/ui/`)
`Button` (variants primary, secondary, ghost, destructive; sizes default 40 px, icon 44 px), `Input`, `Textarea`, `Select` (native `<select>`, styled), `Checkbox` (native), `Field`, `Badge`/`StatusBadge` (tokens v2), `Tabs`, `Breadcrumbs`, `Dialog`, `AlertDialog`, `Sheet` (ADR-0021), `Toast` (an `aria-live="polite"` region we own; no style injection), `Skeleton`.

## 5. Patterns gallery (`/patterns`)
A route that renders every template and component with synthetic data. It exists in development and in the `e2e` build mode only (`import.meta.env.VITE_PATTERNS`); in production builds the route returns Not Found. It is the target of component-level e2e, axe and visual tests until feature screens exist.

## 6. i18n
All new strings in `en.json` (and `ar.json`), extracted by `pnpm i18n`; pseudo-locales cover the templates. Dates and numbers through `Intl` via react-intl.

## 7. Performance budget
- First-route JS stays ≤ 250 KB gzip (NFR P5; 157 KB in Phase 0). TanStack Table/Form and the gallery are route-split (lazy).
- Fonts: 2 families × 3 weights × 2 subsets, `font-display: swap`; only the Latin subset preloads.
- LCP ≤ 2.5 s, CLS ≤ 0.1 on the home route (Lighthouse CI).

## 8. What E01 does not do
No authentication, no tenant resolution, no real data screens. Notifications and account menu slots stay empty until E06 and E03.
