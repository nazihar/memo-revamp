---
status: accepted
date: 2026-11-14
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0047 — ONLYOFFICE 10: co-editing by named co-authors with session-bound keys, spreadsheets, hardening; desktop Word not adopted

## Context and problem statement
Phase 2 (ADR-0037) edits the memo document in ONLYOFFICE 9.4, one author at a time, with the document key derived from the file version. E02 completes the integration: several people drafting together, spreadsheet attachments, the master plan's hardening list, conversion (ADR-0045), and a research-gated "edit in desktop Word". Research found that 10.0.0.1 fixes more than 40 security issues in the conversion and macro paths E02 widens, that our key rule splits a co-editing session after a force-save, and that we switch off ONLYOFFICE's private-address filter today.

## Decision drivers
- Fixed security issues in the paths we are about to use more (conversion of more formats, macros).
- Co-editing must give one shared session, never two diverging copies.
- The document server reaches nothing but our internal connector; no hosted AI (`AGENTS.md §3.1`).
- Credentials never land in places that persist outside our control; nothing sensitive in URLs (`§3.4`).
- The adapter boundary stays: memos know nothing of ONLYOFFICE.

## Considered options
- A. **Upgrade to 10.0.0.1 now** vs stay on 9.4 until 10.0.x.
- B. **Keys bound to the editing session** vs keys from the file version (Phase 2).
- C. **Desktop Word through WebDAV and `ms-word:` with a token in the URL path** vs not adopted.

## Decision outcome
- **A — 10.0.0.1 first** (one month old, past the bake period; there is no patched 9.4). Conversion tokens carry `operation: "converter"`; the internal download sends an exact `Content-Length` (10.0 checks it).
- **B — session-bound keys.**
  - A file has at most one open **edit room**. Opening for editing joins the room if one is open (same key, same base version) and otherwise starts one with key HMAC(tenant, file, base version, room id).
  - Force-saves (status 6) store new versions and keep the key; anyone opening later joins the same room. Status 2 or 4 closes the room; the next opening starts a new one.
  - A save is stored as the user ONLYOFFICE names in the callback, who must be a member of the room. The audit lists every member who connected.
  - Fast co-editing with `coEditing.change: false`, so nobody can switch to strict mode alone.
- **Co-authors:** the initiator names co-authors on a draft (at most ten, people who may create memos). Co-authors may edit the document and the attachments and read the memo while it is a draft or returned; they cannot submit, discard, withdraw or change the route. They are memo participants (`COAUTHOR`), so the memo's read rules include them; they stay readers after submit.
- **Spreadsheets:** `.xlsx` attachments open in the spreadsheet editor under the same rules. The reference-data events are not declared, so external links stay hidden. The main document stays `.docx`.
- **Submit waits** while any edit room is open on the document or an attachment (`DOCUMENT_BEING_EDITED`).
- **Hardening**, point by point (research §1.7):
  - JWT on configs, browser requests, inbox and outbox, with the token purpose checked.
  - Callbacks only on the internal connector.
  - `ALLOW_PRIVATE_IP_ADDRESS` removed; instead `externalRequest.directIfIn.allowList` = our internal connector and `externalRequest.action.allow: false`.
  - Macros, plugins and AI off, in the server configuration and in every editor config (`aiSettings.enable: false`).
  - DocBuilder off. The browser-facing `/office/` path blocks the converter, command, DocBuilder, info and example paths.
  - Cache on a size-capped tmpfs with files kept one hour.
  - CPU and memory limits.
  - Sessions at most 12 hours absolute and one hour idle.
- **Scaling:** the Community Edition keeps its state in memory and runs as one instance. A restart loses edits since the last force-save; the editor's own auto-assembly checkpoints every five minutes are turned on (not versions). High availability means the commercial edition behind the same adapter, recorded as an option, not built.
- **C — desktop Word not adopted.**
  - Word gets no browser cookies. Microsoft 365 blocks Basic prompts by default. Office's forms-login keeps our session in a cookie store we do not control, and is Windows-only.
  - The only workable option, a token in the URL path, cannot be short-lived for a desktop session. It persists in Office's recent-files registry, Windows Recent items and the Office cache, and would sit in proxy logs.
  - Step-up cannot be enforced inside Word.
  - People who need desktop Word download, edit and upload a new version (audited, scanned, versioned). Revisit only by a new ADR with an SSO-integrated customer that accepts the endpoint risk.

### Consequences
- Good: one shared session per file; no lost or split edits after a force-save.
- Good: the document server can no longer be used to reach other addresses, and the AI proxy, macros and plugins are closed.
- Bad: tokens in 10.0 are stricter; our signing needs the purpose claim, and a misconfiguration shows up as error -8 in conversions.
- Bad: co-authors widen who holds a draft; the initiator alone still decides when it is submitted.
- Bad: no desktop Word; some users will download and upload.

### Confirmation
`EditRoomIT` (join, force-save, close, membership of the saver), `CoAuthorsIT`, the T1 spikes (10.0 editor flow, conversion token, outbound request refused, `/ai-proxy` 403, tmpfs cache), the hardening checks in `security.spec` and the deploy lint, and the E2E co-editing journey.

## Pros and cons of the options
### A — upgrade now vs wait
- Upgrade: fixes the conversion and macro issues before E02 widens those paths; the breaking token change is small.
- Wait: avoids a fresh major, but leaves known issues in the conversion path.
### B — session-bound keys vs version keys
- Session-bound: correct co-editing; a little more state (`docedit_room`).
- Version keys: simpler; splits sessions after a force-save.
### C — desktop Word
- Adopt: familiar to users.
- Not adopt: keeps credentials out of persistent client stores; costs convenience.

## More information
Research: `docs/plan/phase-3-memo-efile/epic-02-documents/research.md` §0, §1, §6; the licence brief `docs/legal/onlyoffice-licence-brief.md`. Supersedes the key rule of ADR-0037; the rest of ADR-0037 stands.
