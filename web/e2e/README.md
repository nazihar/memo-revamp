# Web end-to-end tests (Playwright)

The tests run against the **production build** (`vite build --mode e2e` + `vite preview`), with the real security headers and CSP. `/api` is proxied to the backend at `MEMO_API_ORIGIN` (default `http://localhost:8080`, which is what `pnpm dev` starts).

| Project             | What                                                                                                                                                           | Stage   | Command                                                    |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ---------------------------------------------------------- |
| `smoke`             | Status page, keyboard use of the shell, sign-in, invitation → MFA enrolment → sign-in (Mailpit), admin actions with step-up, security headers, storage hygiene | PR      | `pnpm --filter web e2e` (needs `pnpm dev` or the CI stack) |
| `firefox`, `webkit` | The same specs in other browsers                                                                                                                               | nightly | `pnpm --filter web e2e:full`                               |
| `pseudo`            | `en-XA` pseudo-locale: fails on any visible text that is not externalised                                                                                      | nightly | `pnpm --filter web e2e:full`                               |
| `visual`            | Shell + health page in light/dark × LTR/RTL, API mocked                                                                                                        | nightly | `pnpm --filter web e2e:visual`                             |

Every test uses the fixtures in `fixtures.ts`:

- **`app.visit(path)`** navigates, waits for the network to settle and fails on **serious or critical axe violations** (WCAG 2.2 A/AA tags). Call `app.checkA11y()` again after UI state changes (menus, themes, RTL).
- **CSP violations** reported by the browser fail the test automatically.
- **`role`** chooses the storage state from `roles.ts`: `anonymous`, `tenantAdmin` (admin@demo.example) or `user` (user@demo.example). The global setup (`global-setup.ts`) signs each demo user in through the real API, password and TOTP, once per run; there is no auth bypass. The password and TOTP secret come from the environment or from the git-ignored `deploy/compose/.env` that `pnpm stack:up` generates. Runs without a backend or without those credentials (visual, pseudo) skip it.
- **TOTP codes** are computed like an authenticator app does (`auth.ts`). A code works once per 30-second step, so the helpers fall back to the next step's code (still inside the server's ±1 window) when another sign-in just used the current one.

## Visual baselines

Screenshots differ between operating systems, so baselines are always rendered on Linux in the official Playwright image (the same image nightly CI uses):

```sh
pnpm --filter web build:e2e
pnpm --filter web preview --host 0.0.0.0     # keep running
pnpm --filter web e2e:visual:update          # docker: renders into e2e/__screenshots__
```

Review the images before committing them. They are part of the code review.
