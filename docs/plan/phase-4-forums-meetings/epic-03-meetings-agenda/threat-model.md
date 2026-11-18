# E03 Meetings and the agenda builder — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows; ASVS 5.0 IDs as in `docs/security/asvs-checklist.md` (chapter-level where the checklist groups them).

## Data flows
1. A secretary creates, schedules, reschedules, starts, ends or cancels a meeting, and adds guests. The audience is recomputed.
2. A secretary builds the agenda: starts it from a template or an earlier meeting, adds sections, items and papers, places submissions from the tray, moves rows, and sets restrictions and classifications. The forum records placements.
3. The chair agrees the agenda or a late paper, or the secretary records the agreement on their behalf.
4. A secretary publishes a revision. The audience is notified after commit, each person about what they may see.
5. Members, guests and item attendees read the published agenda and open papers (the PDF rendition, streamed through the meeting module). Officers also download originals.
6. A member asks to discuss a consent item, or withdraws the request.
7. A submission changes in the forum module (returned, declined, referred, deferred, withdrawn), and the meeting module removes the item from the draft.
8. The hourly sweep reminds secretaries about unpublished agendas and meetings not marked as held.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Someone other than the forum's secretary builds or publishes an agenda, or a member impersonates the chair to agree a late paper | 2, 3, 4 | Officer checks on every builder endpoint, and the chair's actions for the chair (or vice-chair) in office only. Agreement on the chair's behalf is recorded as such, with the secretary as actor and `onBehalfBy`. Matrix tests | V8 |
| S2 | A former member keeps reading papers with an old session | 5 | Audience rows carry `until`, compared with today in the rule; sessions follow ADR-0023 | V8, V7 |
| T1 | A published agenda or the record of what was read is altered afterwards | 4, 5 | Revisions are append-only (no UPDATE or DELETE for the app role); papers pinned to file versions in object-locked record storage; audit hash chain | V16, V2 |
| T2 | Two secretaries' edits interleave and lose a change, or a submission is placed twice | 2 | The agenda's `version` as If-Match under the row lock; unique placement index; idempotency keys on creates | V2 |
| T3 | A late paper bypasses the committee's late rule | 2, 4 | The late mode is checked server-side at placement and at publication; AWAITING_CHAIR items are left out of revisions; tests per mode | V2 business logic |
| T4 | A crafted join link (javascript:, a phishing host with credentials) is shown to directors | 1 | `https` only, no user-info part, at most 2,000 characters; rendered as a plain link with `rel="noopener noreferrer"`; never fetched by the server | V1, V5 |
| R1 | A secretary denies publishing a change, or a chair denies agreeing | 3, 4 | Audit with actor, acting-for, note and revision; the revision list shows who published and why | V16 |
| R2 | A director denies having had a paper available, or opening it | 5 | Revisions record availability per version; `meeting.paper.opened` per document open | V16 |
| I1 | A restricted or members-only paper reaches someone outside its restriction through a list, a count, a notification, the preview, the generic files endpoint or an export | 4, 5 | One rule (ADR-0061) on every surface; restricted papers absent, not greyed; the `meeting.paper` purpose's `mayRead` is the rule; `FileServing` callers pinned by an architecture test; per-recipient notification content; leak-suite skeleton per surface | V8, V14 |
| I2 | An item title reveals a confidential matter (a counterparty, a dismissal) to those outside it, or in email | 4, 5 | "Hide the title" per item; email subjects without parameters and bodies without item titles (ADR-0030); in-app texts per the rule | V14 |
| I3 | Copying an earlier meeting carries a restriction naming people who are no longer entitled, or drops one silently | 2 | Restrictions narrowed to the new audience, the dropped names listed to the secretary; restrictions only narrow access | V8 |
| I4 | Members read the whole memo file, not just the pack | 5 | Members are not memo readers; they read pinned papers only (ADR-0061) | V8 |
| I5 | The join link or location reaches people outside the audience | 1, 4 | Shown only to the audience and officers; `forum.read` sees no link; never in email subjects | V8, V14 |
| D1 | A huge agenda or a flood of moves slows the builder | 2 | Caps: 30 sections, 150 items, 20 papers per item, 5 presenters, 100 restricted names; rate limit on builder writes per user | V2 |
| D2 | A burst of revisions floods inboxes | 4 | Notify off for minor corrections; in-app items grouped per meeting; email only when something the person sees changed | V2 |
| E1 | A guest or item attendee escalates to members-only items or other items' papers | 5 | Source-specific clauses in the rule; tests per audience source | V8 |
| E2 | A conflicted officer reads what they are recused from | 5 | The E04 hook applies to officers too, and the rule is written so | V8 |

## Residual risks
- Officers (secretaries, chair) see every draft and every paper. This matches the role. E04 narrows it for recusals.
- Directors may print or photograph papers. Watermarking and the secure viewer are Phase 5.
