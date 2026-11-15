---
status: accepted
date: 2026-11-15
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0051 — Authenticated approvals (e-signature v1): the authentication context of each decision, shown as a stamp

## Context and problem statement
Approvers decide on a memo after a fresh second factor (step-up, ADR-0036), and each action records the document version and hash (ADR-0036, ADR-0046). What the record does not keep is **how** the person was authenticated when they decided:
- the method;
- when the second factor was last proven;
- the session;
- the network address and device.

The memo view also does not show decisions the way a paper file does, as a stamp under the remark. The master plan asks for "electronic signature v1": each approval recorded with its authentication context and rendered as a stamp, in the memo view now and in the PDF/A from E04.

What does a decision record, what does the stamp claim, and how is it checked?

## Decision drivers
- Evidence that the decision was made by that person, at that time, on that exact content.
- Honest naming. Without a key under the signer's sole control this is not an advanced or qualified signature (eIDAS), nor an ICT Act 2006 digital signature by a certificate subscriber (research §B.5).
- No new secrets to manage in v1; an upgrade path to organisation sealing (E04) and CA e-Sign later.

## Considered options
1. **An approval record per decision**, written with the action, holding the authentication context and the hashes of the content decided on, plus a server-computed record hash; shown as a stamp.
2. Sign each decision with a per-tenant server key now.
3. Integrate a licensed CA's remote e-Sign now.

## Decision outcome
**Option 1, named "authenticated approval" in the product.**

- **What is recorded** (`memo_approval`, append-only, one row per approve, return or reject, written in the same transaction as the action):
  - action id, actor, acting-for, the position held, and the time;
  - **Authentication:** the method of the sign-in (password + TOTP), the time of the last second-factor proof, whether a step-up was required and its age, and a hash of the session id (never the id itself);
  - **Client:** IP address and user agent, as the trusted proxy reports them;
  - **Content:** the document version id and SHA-256, and for each attachment bound to the round, the version id and SHA-256 (ADR-0046);
  - **Record hash:** the SHA-256 of a canonical JSON of all the fields above, plus the previous approval's record hash for the memo (a per-memo chain).
- **The stamp** (memo view, and the PDF from E04) shows:
  - name, position, the decision and the time (tenant time zone);
  - "Authenticated with password and authenticator, verified 2 min before" (the second-factor age);
  - the short document hash (first 8 hex characters);
  - a **Check** action. The server recomputes the content hashes from storage and the chain from the rows, and answers "matches" or names what differs (audited).

  The client fields (IP, device) are shown to auditors only.
- **Wording:** the UI and documents say "authenticated approval", never "digital signature". The runbook states what it is and is not, citing research §B.5.
- **Upgrade path:**
  - E04 seals the final PDF/A with a tenant certificate and an RFC 3161 timestamp;
  - a later phase may add a licensed CA's e-Sign as an optional per-step requirement. Neither changes these rows.
- **Phase 2 follow-up included:** after a recusal, the next step's ordinal is shown correctly in the stamp and history (Phase 2 retrospective, "Carried").

### Consequences
- Good: each decision carries evidence a reviewer or auditor can check, and the chain shows if a row was altered or removed.
- Good: no key management in v1.
- Bad: a database owner could rewrite both rows and hashes. The chain detects partial edits, not a full rewrite. E04's sealed PDF/A (object-locked) is the external anchor.
- Bad: IP and user agent are personal data; they are shown to auditors only, and retention follows the memo.

### Confirmation
- Integration tests:
  - every approve, return and reject writes exactly one row, with the right context;
  - the chain verifies; editing a field (as the owner role, in the test) makes Check report it;
  - step-up age is recorded;
  - the auditor sees client fields and others do not.
- An ArchUnit or text check that UI strings never say "digital signature".
- The PDF stamp is rendered by E04 from the same row.

## Pros and cons of the options
### Option 1 — authentication context with a hash chain
- Good: strong evidence, simple, honest.
- Bad: not tamper-proof against a database owner; E04 anchors it.
### Option 2 — server-key signatures now
- Good: harder to rewrite.
- Bad: key management, rotation and HSM questions for every on-prem install, for little gain before E04's seal.
### Option 3 — licensed CA e-Sign
- Bad: per-signature cost, e-KYC, connectivity; customer-specific; later and optional.

## More information
Research §B.3–§B.5, ADR-0024 (MFA and step-up), ADR-0036 (document binding and step-up on approve), ADR-0046 (attachment binding), MASTER_PLAN (digital signatures in a later phase).
