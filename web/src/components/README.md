# Building screens (Phase 1a E01)

Every screen is built from these parts; the patterns gallery (`/patterns`, development and e2e builds only) shows each one
with synthetic data. Design: `docs/plan/phase-1a-minimum-core/epic-01-app-frame/design.md`.

- **Frame:** routes render inside `AppShell`. Add a screen to the menu in `src/app/navigation.ts` (`enabled: true` when its
  epic ships) and give its route a breadcrumb with `staticData: { crumb: messages.x }`.
- **Page header:** `page/PageHeader` (one h1, description, actions).
- **Lists:** `page/DataTable` with `DataTableColumn<T>` columns, server-side sorting (`sorting` / `onSortingChange`), optional
  selection (`rowLabel`), cursor pagination (`paging`: Previous/Next only) and the `state` prop for loading and errors.
- **Forms:** TanStack Form + `ui/field` (`Field` wires label, hint and error); `page/FormPage` for `FormGrid`, `FormActions`,
  `UnsavedBanner` and `UnsavedChangesGuard`; map server Problem Details with `page/problem-details` `applyFieldErrors`.
- **Trees:** `page/TreePanel` (WAI-ARIA tree keyboard pattern).
- **Modals:** `ui/dialog` (`Dialog`, `AlertDialog`, `Sheet`) on the native `<dialog>` element (ADR-0021). Never use Radix
  modal primitives or anything that injects `<style>`: the CSP blocks it and the e2e CSP fixture fails.
- **Feedback:** `useToast()` for short confirmations; errors belong on the fields or in `ErrorState`.
- **Rules:** every string through react-intl (with Arabic in `ar.json`); logical CSS properties only (RTL); status always as
  text + icon (`Badge`); controls 40 px, icon buttons 44 px.
