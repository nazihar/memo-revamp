# E06 Minutes and resolutions — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows; ASVS 5.0 IDs as in `docs/security/asvs-checklist.md` (chapter-level where the checklist groups them). Builds on the E03, E04 and E05 threat models, ADR-0048's template controls, ADR-0051's authenticated approvals and ADR-0053's record controls.

## Data flows
1. During and after the meeting, the secretary records a decision per item; the quorum snapshot is taken in the same transaction; the secretary saves notes per item.
2. When the meeting is marked as held, the `minutes-release` job numbers resolutions from the committee's counter and sends decisions to their submissions and waiting memos (`ForumDecisions` → workflow); later decisions are numbered and released in their own transactions.
3. The secretary generates the draft from the committee type's template (uploaded by a type administrator, reviewed by a second); the job fills it and wraps each item in a content control.
4. Secretaries edit the draft in ONLYOFFICE; the chair reviews in review mode; every save is a file version, checked for structure.
5. Copies for each redaction set are built (controls replaced, converted to PDF by ONLYOFFICE, stamped DRAFT) and served to readers; members comment.
6. The next meeting's decision or the end of circulation moves the minutes to the chair, who confirms with a step-up; the approval row is written; the meeting is frozen.
7. The record job builds the PDF/A record and approved copies, validates them and stores them object-locked; the fixity job re-hashes them weekly.
8. Corrections after approval are proposed and approved, producing a new record version; secretaries issue resolution extracts.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Someone confirms minutes as the chair (a shared session, a secretary at the chair's desk) | 6 | Only the confirmer named by the rule may confirm; a fresh step-up (MFA) per the forum's window; the approval row records the authentication context and a session hash (ADR-0051); nobody confirms on the chair's behalf | V6, V7, V8 |
| S2 | A forged or altered resolution extract is used to get a loan disbursed | 8 | Extracts are issued only by secretaries with a step-up, stored locked with their SHA-256, audited; the extract names its number, and the resolution page in memo is the source of truth; the runbook tells operations to check the number and hash in memo before acting | V6, V16 |
| S3 | A secretary records the chair's agreement to circulate that was never given | 4 | "On behalf" needs how and a note, is recorded with both names, audited, and the chair is told at once; the chair still confirms the final minutes personally | V8, V16 |
| T1 | A decision is changed after the meeting to suit someone (outcome, dissent removed, a director named as approving) | 1 | Entries are append-only with the actor, time and a reason for every correction; the history is visible to officers and `forum.read`; once released the outcome is fixed; after approval nothing changes; the minutes' chair review and circulation show the decision; the leak and matrix tests cover who may correct | V16, V2 |
| T2 | A resolution number is reused, skipped or assigned twice | 2 | One counter row per committee and period locked to commit; unique keys on sequence and number; voids recorded, never freed; triggers forbid changing a number; the register count check; `ResolutionNumberingConcurrencyIT` | V2, V16 |
| T3 | Approved minutes or their record are altered | 6, 7 | Approved minutes have no editable file (the purpose refuses edits after APPROVED); the record and copies are in object-locked storage; SHA-256 in the audit chain; weekly fixity with `minutes.record.mismatch` as a security incident; the approval row's record hash chained per committee; corrections are new versions | V16, V12 |
| T4 | Someone rewrites history in the draft (removes a director's dissent) | 4, 5 | The decision record holds the dissent independently of the document; the template prints it; the chair reviews; members comment; every saved version is kept and its SHA-256 audited; the confirmation binds the exact version | V16 |
| T5 | A malicious template (macros, external links, field codes, OLE) runs code or calls out when filled or opened | 3, 4 | ADR-0048's sanitising scan refuses it on upload; the type is reviewed by a second administrator; ONLYOFFICE runs without outbound network, macros and plugins disabled (ADR-0047); poi-tl without SpEL | V5, V14 |
| T6 | Injection through decision text, notes, comments or names into the .docx (XML) or PDF | 1, 3 | Values are set as text runs by poi-tl and POI (escaped XML); control characters stripped; lengths capped; the structure check and veraPDF validation; Jazzer on the template filler's inputs nightly | V1, V5 |
| T7 | Two secretaries record or correct the same decision, or a double click records twice | 1 | If-Match on the decision's version, `SELECT … FOR UPDATE`, idempotency keys; a second first-record answers 412 or replays | V2 |
| T8 | A release runs twice and resumes a memo twice, or a correction races a release | 2 | Release is guarded on `released_at` under the decision's lock; the submission's state guard (QUEUED or ACCEPTED) refuses a second decide; corrections take the same lock, so a correction waits for a release and then sees `RELEASED` | V2 |
| R1 | A chair denies having approved the minutes | 6 | Authenticated approval with second-factor age, document hash, client and a per-committee hash chain; the stamp's Check recomputes it (ADR-0051) | V16 |
| R2 | A member denies having been sent, or having commented on, the draft | 5 | Circulation, notifications, comments and responses are recorded and audited; copy opens are audited at document-open granularity | V16 |
| R3 | A secretary denies issuing an extract | 8 | Step-up, certification fields, audit | V16 |
| I1 | A recused director reads the discussion of their item through a copy (a missing content control, text typed outside the item's block, the PDF's text layer, metadata or comments in the .docx) | 4, 5, 7 | Copies are made from the redaction set by rule; the structure check blocks every step while a control is missing or duplicated; tracked changes and comments are resolved or removed before a copy is built (the copy job accepts nothing: it refuses a document with unresolved changes, telling the secretary); document properties are reset in copies; the secretary previews any reader's copy; canary strings in notes, decisions' hidden parts and papers are checked in every copy's text layer by the leak suite; text between item blocks is the meeting's general business by design, and the template and runbook say so | V8, V14 |
| I2 | A recused officer edits or reviews the full draft | 4 | `mayEdit` and the review mode refuse anyone recused from any minuted item (403 `RECUSED`); the reviewer and confirmer rules pass to the vice-chair | V8 |
| I3 | Notes, decisions or minutes reach guests, item attendees, former members or someone above whose clearance an item sits | 1, 5, 6 | `MinutesAccess` on `PaperAccess` and recusals; guests and attendees never read minutes; copies redact items above clearance or restricted; former members lose access; the purposes' `mayRead` is the same rule, so the generic files endpoints serve nothing else | V8 |
| I4 | Notification emails or subjects leak an item title, outcome or resolution text | 2, 5, 6 | Subjects take no parameters; bodies name the committee and the meeting's date and link to the app; no decision text in any email; canaries checked in every notification | V14 |
| I5 | The memo's note sheet or final record exposes the vote, dissent or other items' content to the memo's readers | 2 | The remark carries the outcome, number, text and conditions of the memo's own item only; no vote or names; the memo's readers were already entitled to the memo | V8, V14 |
| I6 | People asked for comments (executives) read items they were not asked about | 5 | Their redaction set is every item not named in the request | V8 |
| I7 | The audit log or logs carry decision text, notes or comments | all | Only SHA-256 of free text in audit details; no content in logs (AGENTS §4) | V14, V16 |
| D1 | Many redaction sets, large documents or repeated regeneration exhaust ONLYOFFICE and the PDF pool | 3, 5, 7 | At most 50 copies per minutes; copies debounced; jobs on the existing bounded rendition pool with time limits; POI file-backed mode with ADR-0048's size limits; draft regeneration rate-limited per meeting (one running at a time by the instance id) | V2 |
| D2 | A failed release blocks every memo of the meeting | 2 | One transaction per decision; failures are retried and reported per decision; the others proceed | V2 |
| E1 | A member records or corrects a decision, or a secretary of another committee does | 1 | Deny-by-default; matrix tests over every endpoint with every column, including recused and other-committee secretaries | V8 |
| E2 | A type administrator changes the numbering or template to affect a meeting already held | 3 | The policy is pinned per meeting and copied onto the minutes row; type changes are reviewed by a second administrator (maker-checker) | V8 |

## Residual risks
- A secretary can type an item's discussion outside its block (for example in "Any other business"); the recused reader would then see it. Contained by the template's structure, the preview, the chair's review and the runbook; not fully preventable in a free document.
- Extracts are PDFs and can be forged outside memo; operations must check them against memo (runbook).
- ONLYOFFICE keeping content controls is proved by the T1 spike for 10.0.0.1; an ONLYOFFICE upgrade reruns the spike's test (`MinutesControlsRoundTripIT`, nightly with the stack).

## ASVS rows to add (closed in E08)
- V8 (authorisation): decisions, notes, minutes stages and copies, register, extracts, with the recused column.
- V16 (logging): every event in §5 of the design, free text hashed.
- V5 and V1 (files and input): templates, copies, the structure check.
- V6 and V7 (authentication, session): step-up on confirmation, corrections, extracts and the starting number.
- V12 and V14 (data protection): records and copies locked, notifications without content.

## Reviewed at phase close
Reviewed at phase close on 2026-11-28 (Phase 4 E08 T11): **every mitigation holds as built**. The fail-closed leak check (I1) worked as designed: it refused copies rather than serve them.
- **Found and fixed:**
  - I1, I3 (T3): a recused alternate not named on the member's restricted item got the recused view of its decision. The recused view now needs the item to be unrestricted or restricted to the viewer.
  - E1 (T3): a ruling or withdrawal on a decided item answered `FROZEN` before the 403, which was an oracle for someone who may not act. The decided check now follows E04's access checks.
  - I1 (T6): the meeting-level actions table printed every action's title, so the leak check refused the copies of recused and restricted readers. An item's actions are now printed inside its block only, and the meeting table holds only actions agreed on no item.
  - D1 (#255): ONLYOFFICE keeps a conversion's answer by its key, failures included. One transient error (-3 during a restart) failed a copy or a record for good. Each conversion is now sent under the caller's key plus a fresh nonce, so a retry is a new conversion (`OnlyOfficeConverterKeyTest`, `ConversionRetryIT`). Nothing is served from a kept answer.
- **Changed by the build:**
  - I5: the memo's note sheet carries the outcome and the resolution number only (`ForumEscalationIT`). E07 adds the follow-up memos, which are read by the memo's own rule.
  - The register (E08) reads decisions through definer functions, under the same rule (`DecisionRegisterEquivalenceProperties`).
  - The demo seed records decisions, approves minutes and certifies an extract without a step-up through `DecisionSeeding` and `MinutesSeeding`. Only `com.memo.demo` may call them (`ArchitectureRulesTest`). The seed binds the demo tenant only, and the demo tenant is refused under `prod` (`DemoTenantSeedIT`).
- **ASVS rows planned above:** written in E06 T12. V7 is part of the V6 row (the approval keeps the session's hash), and V12's "records and copies locked" is part of the V14 rows.
- **Evidence:** the seven E06 rows of `docs/security/asvs-checklist.md` (V8, V14 twice, V6, V2, V5, V16), with their tests. Also: S2 the extract's check and runbook; S3 `onBehalfBy` with how it was given (`MinutesCirculationIT`); T4 the structure check on every saved version (`StructureScanTest`); I6 `MinutesCirculationIT`; I7 the hashes (`AuditActionCoverageTest`); D2 one transaction per decision, retried and reported (`DecisionReleaseIT`); E2 the pinned type version (`ForumTypeMinutesPolicyIT`).
- **Residual risks:** all three confirmed.
  - Text typed outside an item's block cannot be redacted by control. The leak check refuses a copy that contains a redacted item's recorded text, and the runbook covers the rest.
  - Extracts must be checked against memo.
  - An ONLYOFFICE upgrade reruns `MinutesControlsRoundTripIT`.
- **Pen test:** area 3 (copies per reader), area 4 (extracts) and area 7 (step-up) of `docs/security/pen-test-scope.md`.
