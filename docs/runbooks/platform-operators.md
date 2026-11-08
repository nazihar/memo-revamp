# Runbook — platform operators and the operator console

For the people who run a memo installation: creating organisations, suspending them, and managing operator accounts. Background: ADR-0031 (operator path), ADR-0033 (audit export), `docs/plan/phase-1a-minimum-core/epic-07-tenant-admin/design.md`.

## Where the console is
| Setting | Default | Notes |
|---|---|---|
| `MEMO_PLATFORM_HOST` | `admin.<MEMO_TENANCY_BASE_DOMAIN>` in multi-tenant mode; none in single-tenant mode | DNS and TLS must cover it. No organisation may use the slug `admin` (also refused: `www`, `api`, `mail`, `static`) |
| `MEMO_PLATFORM_ENABLED` | `true` | `false` removes the console everywhere |
| `MEMO_PLATFORM_PUBLIC_URL` | `https://<host>` | Only for the enrolment link the bootstrap command prints |

The console lives at `https://<host>/platform`. Its API (`/api/v1/platform/**`) answers only on that host; organisation pages and APIs answer 404 there, and the console answers 404 on organisation hosts.

Proxies: forward the `Host` header unchanged (our nginx and Caddy do). Kubernetes: set `platform.host` in the Helm values; the ingress gets a rule for it. Caddy (dev): the edge serves `admin.<dev host>` next to the dev host.

## The first operator (and resetting one)
Operators are not organisation users. The first one is created on the server with the backend image's command mode:

```sh
# Compose (on-prem or dev VM), from the install directory:
docker compose run --rm backend operator-invite owner@example.com "Owner Name"
# Kubernetes:
kubectl run memo-operator-invite --rm -it --restart=Never --image=<backend image> --env-from=... -- operator-invite owner@example.com "Owner Name"
```

The command prints a one-time link (24 hours) to its own output, never to the log. Open it, choose a password (at least 15 characters), then set up an authenticator app; two-step sign-in is always required for operators.

Running the command again for an existing operator **resets** them: password and authenticator are cleared, their sessions end, and a new link is printed. This is also the recovery path for a lost authenticator (operators have no recovery codes in Phase 1a).

Non-production installs seed a demo operator `operator@demo.example` with the demo password and TOTP secret from `.env`.

## Sign-in rules
- Password, then a TOTP code. Five wrong passwords in a row lock the operator for 30 minutes; rate limits also apply per address.
- Sessions end after 15 minutes without activity and 8 hours at most. The cookie `MEMO_OPERATOR` is sent only to the console's host.
- Creating an organisation, suspending, resuming and inviting a first administrator ask for a fresh code (valid 5 minutes).

## Organisations
- **Create:** name, address (slug), default language, time zone, and the first administrator's name and email. The administrator gets an invitation email (the platform relay sends it; configure `MEMO_SMTP_*`).
- **Invite the first administrator again:** possible only while the organisation has no active administrator (for example the invitation expired after 7 days). Once someone has accepted, the platform can no longer place accounts in the organisation.
- **Suspend:** every request to the organisation's host answers 403 and shows "This organisation is suspended"; everyone is signed out at once; its background work (mail, scans) waits and resumes on its own an hour after **Resume**, at the latest.
- **Rename:** changes the display name only. The address cannot change in Phase 1a.

What operators see: the registry entry (name, address, status, dates, whether an administrator exists). Operators cannot read organisation data; the database refuses it (row-level security in platform scope).

## Audit
- Every operator action is in the **platform audit** (console → Platform audit). Actions that concern an organisation (created, renamed, suspended, resumed, first administrator invited) are also in that organisation's own audit, with the actor `Platform operator`.
- Organisations export their audit as a verifiable ZIP (Admin → Audit → Export). The file contains `VERIFY.md` with the steps; anchors live in the `records` bucket under `audit-anchors/<tenant id>/`.

## Troubleshooting
| Symptom | Cause | Fix |
|---|---|---|
| Console shows "not available at this address" | Host is not `MEMO_PLATFORM_HOST`, or the console is off | Check the variable and DNS; restart the backend after changing it |
| Enrolment link says it is no longer valid | Used, older than 24 hours, or a newer link was printed | Run `operator-invite` again |
| Operator locked | Five wrong passwords | Wait 30 minutes, or reset with `operator-invite` |
| Invitation mail never arrives | No platform relay | Set `MEMO_SMTP_*`; check the organisation's Admin → Email delivery log once it has an administrator |
| Organisation still reachable right after suspension | Another node cached the host for up to a minute while the database notification was delayed | Wait a minute; check the backend log for the cache listener |
