# E03 Web foundation — UX review

| Item | Status |
|---|---|
| Walkthrough of the shell with the product owner (themes, Arabic/RTL, keyboard only, 200 % zoom) | **Done** 2026-10-15 (owner, local `pnpm dev`). Screenshots for the review: `web/e2e/__screenshots__/visual/shell.spec.ts/` |

## Self-review against AGENTS.md §3.7 (agent, 2026-10-14)
- Targets ≥ 44 px (buttons, menu items, nav links): yes (`min-h-11`, `min-w-11`).
- Focus is always visible: yes (global `:focus-visible` outline using the `ring` token).
- No hidden gestures, no hover-only affordances: yes.
- Status is shown as text plus an icon, never colour alone: yes (`StatusBadge`).
- Minimal chrome: header, one nav entry, content.

## Owner session (2026-10-15)
- **Result:** the UI looks OK; no defects reported.
- **Finding:** the layout is very simple.
  - **Decision:** not fixed in Phase 0, because the shell has a single page and nothing to lay out yet.
  - **Carried into Phase 1a** as its first epic, before the feature screens:
    - wireframes of the app frame (navigation, user menu, tenant switcher, notifications) and the standard page types (list, detail, form);
    - a visual direction (colours, typography, density);
    - owner approval, then build.
  - **Input wanted from the owner:** brand assets (logo, colours, fonts) and 2–3 reference apps.
