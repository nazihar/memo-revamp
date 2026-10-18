---
status: accepted
date: 2026-10-18
deciders: product owner (delegated), agent
---

# 0021 — Modal surfaces on the native `<dialog>` element, no style nonce

## Context and problem statement
The SPA's CSP forbids inline styles and scripts (`web/security-headers.json`, E03 Phase 0). Radix's modal primitives (Dialog, AlertDialog, Sheet in shadcn/ui) inject a `<style>` element for scroll locking, which the CSP blocks, and set `aria-hidden` on the app root, which axe reported as `aria-hidden-focus`. Phase 0 avoided modals and asked for an ADR before the first one. Research: `phase-1a-minimum-core/epic-01-app-frame/research.md §R3`.

## Decision drivers
- Keep one static CSP that every install (nginx image, customer reverse proxies, Kubernetes ingress) can serve.
- Accessible modals: focus moves in and is trapped, Escape closes, focus returns, the background is inert.
- Few moving parts for on-prem (AGENTS §7).

## Considered options
1. Per-request style nonce (nginx `sub_filter` + `setNonce()`)
2. **Native `<dialog>` with `showModal()` for every modal surface**
3. `style-src 'unsafe-inline'`

## Decision outcome
Chosen option: **2**.
- Our `Dialog`, `AlertDialog` (confirmations) and `Sheet` (off-canvas navigation on small screens) components wrap `<dialog>` and `showModal()`. The browser provides the inert background, focus containment, Escape handling and the top layer.
- Scroll lock: a CSS class on `<html>` while a modal is open (stylesheet rule, no inline style).
- Radix stays for **non-modal** primitives (menus, popovers, selects, tooltips): they position through the CSSOM, which CSP allows. Menus stay non-modal (Phase 0).
- No nonce anywhere; the CSP is unchanged.

### Consequences
- Good: the CSP stays static and strict; no `aria-hidden` on the app root; no nonce plumbing in nginx, Vite or customer proxies.
- Bad: we own three small components instead of copying shadcn's. Their behaviour is covered by component tests and axe.
- Follow-ups: if a future library needs injected styles, it is either replaced or brings a new ADR.

### Confirmation
- The e2e CSP-violation fixture (fails on any CSP report) runs on a page that opens every modal surface.
- Component tests: focus moves into the dialog and back to the trigger; Escape closes; the confirm button of an `AlertDialog` is not the default focus for destructive actions.
- axe on the open-dialog state: zero serious or critical violations.

## More information
Sources (accessed 2026-10-18): https://html.spec.whatwg.org/multipage/interactive-elements.html#the-dialog-element, https://www.w3.org/TR/CSP3/#directive-style-src
