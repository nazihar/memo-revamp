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
- **`role`** chooses the storage state from `roles.ts`: `anonymous`, `tenantAdmin` (admin@demo.example) or `user` (user@demo.example). A demo user signs in through the real API, password and TOTP, when a test first needs the role; there is no auth bypass. The password and TOTP secret come from the environment or from the git-ignored `deploy/compose/.env` that `pnpm stack:up` generates. Visual and pseudo runs mock the API and sign nobody in.
- **One session per project, worker slot and role** (`e2e/.auth/<project>/<role>.<parallelIndex>.json`). A step-up moves the session to a new id (ASVS V7.2.4), so tests running at the same time must not share one: the first asked to confirm would sign the others out. Browsers do not share them either: WebKit saves the cookie as SameSite=None without Secure, which Chromium drops. The session is checked before each test and signed in again only if it has gone, and a context saves its session back after a sign-in or step-up call. A second person in a journey opens with `pageAs(browser, role)`, which keeps its session the same way.
- **Step-ups** are asked once a session's last code is older than the organisation's window (5 minutes for administration), so a short run rarely sees one and a long one (nightly, two browsers) does. Give `confirmIfAsked` what the page shows once the action is done: it waits for that or for the "Confirm it is you" dialog, so it never looks too early, and it types the code into the dialog's own field.
- **TOTP codes** are computed like an authenticator app does (`auth.ts`). The server takes each 30-second step once per user, so the helpers fall back to the next step's code (still inside the server's ±1 window) when another sign-in or step-up just used the current one, and wait for the following step when both are used.

## Against dev

`E2E_BASE_URL` points the journeys at a running deployment instead of a local preview (no web server is started). For the dev VM, with the demo accounts' password and TOTP secret read from its `/opt/memo/dev/.env` (never printed or written to disk):

```sh
export E2E_BASE_URL=https://dev.memo-revamp.naziharitsolution.com
eval "$(ssh ubuntu@123.176.58.175 "sudo grep -E '^MEMO_DEMO_(PASSWORD|TOTP_SECRET)=' /opt/memo/dev/.env" | sed 's/^/export /')"
pnpm --filter web exec playwright test --project=smoke e2e/smoke/committee-cycle.spec.ts   # or any journey
```

Dev keeps its data between deployments and the demo seeders only fill gaps, so a journey that reads seeded data can find dev older than a fresh seed (the phase logs say what stays partial there). Each run adds its own test memos and meetings (titles and subjects carry a time stamp, most start with `E2E`).

## Visual baselines

Screenshots differ between operating systems, so baselines are always rendered on Linux in the official Playwright image (the same image nightly CI uses):

```sh
pnpm --filter web build:e2e
pnpm --filter web preview --host 0.0.0.0     # keep running
pnpm --filter web e2e:visual:update          # docker: renders into e2e/__screenshots__
```

Review the images before committing them. They are part of the code review.
