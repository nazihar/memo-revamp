# E01 App frame and visual design — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Tokens v2 and fonts:** Direction A palette (light/dark, status pairs), IBM Plex Sans + Arabic self-hosted, density; visual baselines updated | S | — |
| T2 | **Component kit:** Button, Input, Textarea, Select, Checkbox, Field, Badge/StatusBadge, Tabs, Breadcrumbs, Skeleton, Toast region | M | T1 |
| T3 | **Modal surfaces (ADR-0021):** Dialog, AlertDialog, Sheet on `<dialog>`; scroll-lock class | M | T2 |
| T4 | **App frame:** navigation model, sidebar with groups, top bar with breadcrumbs and slots, tenant identity placeholder, Sheet navigation below 1024 px | M | T2, T3 |
| T5 | **Page templates:** PageHeader, ListPage + DataTable (TanStack Table v9) with cursor pagination and states, DetailPage with URL tabs, Form/FormPage (TanStack Form) with Problem Details mapping and unsaved-changes guard, TreePanel | L | T2, T3 |
| T6 | **Patterns gallery** (dev/e2e only) and the e2e, axe, CSP and visual suites on it; README section for screen authors | M | T4, T5 |

## Acceptance criteria
- **T1:** Given light and dark themes, every text pair on the gallery passes axe contrast; fonts load from the app origin only (no third-party requests; CSP unchanged).
- **T3:** Given a closed Dialog, when its trigger is activated, then focus moves into the dialog, the rest of the page is inert, Escape closes it and focus returns to the trigger; no CSP violation is reported; axe passes with the dialog open.
- **T4:** Given a 1280 px window, the sidebar shows the enabled items with the current one marked `aria-current="page"`; given a 390 px window, a "Menu" button opens the navigation in a Sheet; in Arabic the sidebar is on the right.
- **T5:**
  - Given a list with more rows than a page, Next loads the next cursor page and Previous returns; there are no page numbers.
  - Given a sortable column, activating its header sorts and sets `aria-sort`.
  - Given a form with unsaved changes, navigating away asks for confirmation; given a server Problem Details response with field errors, the errors appear on those fields and focus moves to the first one.
  - Given the tree, arrow keys move and expand/collapse per the WAI-ARIA tree pattern.
- **T6:** The gallery route returns Not Found in a production build; Playwright visits every gallery section with axe and the CSP fixture, in light/dark and LTR/RTL screenshots.
