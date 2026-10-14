# E03 Web foundation — UX review

| Item | Status |
|---|---|
| Walkthrough of the shell with the product owner (themes, Arabic/RTL, keyboard only, 200 % zoom) | **Pending.** Needs the owner. Screenshots for the review: `web/e2e/__screenshots__/visual/shell.spec.ts/` |

## Self-review against AGENTS.md §3.7 (agent, 2026-10-14)
- Targets ≥ 44 px (buttons, menu items, nav links): yes (`min-h-11`, `min-w-11`).
- Focus is always visible: yes (global `:focus-visible` outline using the `ring` token).
- No hidden gestures, no hover-only affordances: yes.
- Status is shown as text plus an icon, never colour alone: yes (`StatusBadge`).
- Minimal chrome: header, one nav entry, content.

Findings from the owner session will be recorded here.
