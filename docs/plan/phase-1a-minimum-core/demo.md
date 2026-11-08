# Phase 1a — demo script

A 30-minute walk through what Phase 1a delivers, on the dev environment (`https://dev.memo-revamp.naziharitsolution.com`) or locally (`pnpm dev`, then `http://localhost:5173`; the console at `http://admin.localhost:5173/platform`). Demo accounts and their password and TOTP secret are in `deploy/compose/.env` (`MEMO_DEMO_PASSWORD`, `MEMO_DEMO_TOTP_SECRET`); add the secret to an authenticator app first.

| # | Show | Where | What to point out |
|---|---|---|---|
| 1 | The app frame | Sign-in page, then the shell | Direction A (Ledger), light/dark, English/Arabic (RTL) from the top bar; keyboard navigation and the skip link |
| 2 | Operator console | `https://admin.<host>/platform`, `operator@demo.example` | Separate host and account; password then code; organisations list shows registry data only |
| 3 | Create an organisation | New organisation: name, address, language, time zone, first administrator (use an address you can read in Mailpit or your mailbox) | Step-up code; the organisation page says "No administrator yet"; the platform audit shows `tenant.created` and `tenant.first_admin.invited` |
| 4 | First administrator joins | Invitation email → link opens `https://<slug>.<host>/invitation` | Password policy, TOTP enrolment, recovery codes shown once; welcome page |
| 5 | Sessions and step-up | My account → My security | Two devices listed; sign one out; "New recovery codes" asks for a code when the confirmation window has passed |
| 6 | Organisation directory | Organisation (demo tenant, `orgadmin@demo.example`) | Tree, positions, assignments with history; a branch admin sees only their region |
| 7 | Roles and classification | Administration → Roles, Classification (`admin@demo.example`) | Built-in and custom roles, scoped grants, step-up on every change |
| 8 | Security settings | Administration → Security | Ranges shown and enforced; MFA for administrators can never be switched off |
| 9 | Branding and terms | Administration → Branding, Language and terms | Logo upload (scanned, encrypted); a colour with too little contrast is refused with its ratio; rename "unit" to "department" and see the organisation screen change; Arabic term with gender |
| 10 | Email | Administration → Email | Own relay or the platform's; delivery log |
| 11 | Notifications | Bell in the top bar; My account → Notifications | Security notices cannot be switched off |
| 12 | Files | Upload a PDF as an administrator (logo or later document features) | EICAR test file is refused; downloads stream with ranges |
| 13 | Audit | Administration → Audit | Filters; Export → ZIP with `VERIFY.md`; every change above is listed with who did it |
| 14 | Suspension | Back in the console: Suspend the new organisation | Its host shows the suspended notice at once and everyone is signed out; Resume |
| 15 | Operations | Status page; Grafana is not part of 1a | Health of database, storage, mail, malware scanner |

Exit criteria evidence (overview §5) is automated: the Playwright journey `web/e2e/smoke/platform.spec.ts` covers steps 2–5 and 14 on every pull request.
