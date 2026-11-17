# E03 Templates, note sheet and access — log

## 2026-11-15 — Plan
- **Research:** R4 (templating) and R9 (rich text) plus industry practice (D-Nothi, NIC e-Office, Microsoft) by two research agents, checked by the lead. The lead confirmed poi-tl's release state and its POI 5.2.2 dependency on Maven Central, so T1 starts with a spike.
- **ADRs (proposed until the owner's review of the screen flows):**
  - ADR-0048 templates;
  - ADR-0049 note sheet;
  - ADR-0050 access model and copy-to;
  - ADR-0051 authenticated approvals.
- **Plan:** design, threat model, tasks T1–T9 and test plan written.
- **Waiting for the owner:** flows C (note sheet) and D (sharing and copy-to) in `ux-review.md`, with six questions and recommended answers. The backend tasks T1–T6 do not depend on the answers except for defaults (copy timing, comments by copies), which are settings.

## 2026-11-15 — Owner review of flows C and D
- **Owner:** "approved as recommended". Recorded in `ux-review.md` (Owner feedback).
- **ADRs:** 0049, 0050 and 0051 accepted. ADR-0048 stays proposed until the T1 spike chooses the engine.
- **Build starts:** T1 (template engine spike) first.

## 2026-11-15 — T1 template engine spike, scanner and fill
- **Spike** (a scratch project with two generated letterheads; tags split across runs with different formatting, a header, a footer, the approval loop row, and a hostile variant):

  | | POI 5.5.1 | POI 6.0.0 |
  |---|---|---|
  | Split runs, header, footer, loop row | pass | pass |
  | SpEL-looking tag `{{T(java.lang.Runtime)…}}` | left as text | left as text |
  | Fill p50 / p95 (200 fills, dev machine) | 13 / 19 ms | 11 / 17 ms |
  | Without Batik | — | pass |

  **Decision:** poi-tl 1.12.2 on POI 6.0.0 (ADR-0048 accepted).
- **Findings that shaped the code:**
  - inside a `{{?x}}` block poi-tl makes the block's value the root, and an empty string still shows the block;
  - so the filler renames section tags to `?x` (both ends, so poi-tl still pairs them) and answers them with a flag from our own lookup, which also replaces poi-tl's dot-path engine;
  - a `{{tag}}` inside the loop's template row breaks the fill, so the check reports `TAG_IN_LOOP_ROW`;
  - `{{@…}}` with a text value would be read as a picture source, so `@ # * +` are refused at fill as well as at upload.
- **Built:**
  - a new Modulith module `templating`: the scanner (commons-compress and hardened StAX, no POI); refusal rules with part names; the tag inventory with part, paragraph, table row and excerpt; the vocabulary check with suggestions; `PoiTlFiller` (2 threads, queue 8, 5 s, POI zip limits lowered);
  - the files hook `FilePurpose.inspectContent` and the reason `CONTENT_REFUSED` with `refusals` in the problem and the audit;
  - the purpose `memo.template` (.docx, 10 MiB, records; uploads only for a draft type version by `memo.types.manage`);
  - `MemoTemplateTags` (the published tags);
  - ArchUnit rules (poi-tl only in `templating`; no SpEL);
  - `com.deepoove.poi` logging at WARN.
- **Deviations, recorded in ADR-0048:**
  - `.dotx` is refused by the existing type check for now; carried;
  - a package that passes every rule is stored as uploaded rather than re-serialised; fills are POI-written.

## 2026-11-15 — T2 templates on memo types and create from template
- **Built:**
  - `memo/V12`: on the type version, the template file and exact file version, the cached scan of that version, and the version last previewed; on the memo, the template version it was filled from;
  - `MemoTemplateListener`: each upload for a draft version points the version at it (vetoed once the version left draft) and resets the preview;
  - admin API `GET|DELETE …/versions/{n}/template` (problems with part, paragraph, excerpt and suggestion, plus the tags the version may use) and `POST …/template/preview` (sample values into a `memo.template.preview` file with a PDF rendition);
  - `MemoTemplateChecks` in submit-for-review: refused with `TEMPLATE_NOT_PREVIEWED` or `TEMPLATE_PROBLEMS`;
  - a new draft copies the template, scan and preview of the version it starts from.
- **Create from template:**
  - `POST /memos` fills the published version's template after the memo is committed, as the initiator, through the full upload pipeline, into document v1, and records the template version (audit `memo.document.filled`);
  - values: `MemoTemplateValues` (dates `d MMM yyyy`; amounts with currency and grouping; the page's display text for people, units and choices; "(number on submit)"; an empty approval table);
  - a failed fill leaves the memo without a document, and the new memo action `FILL_FROM_TEMPLATE` (`POST /memos/{id}/document/from-template`) tries again.
- **Choices:**
  - the scan is cached per template version, so the check against the version's fields (which can change) needs no file read, and submit stays inside its transaction;
  - the check runs again at each `GET`, preview and submit.
- **Carried:**
  - deleting previews after 24 hours: the files module has no deletion yet, so previews are ordinary objects kept for now;
  - `.dotx` (T1).

## 2026-11-15 — T4 access model and shares (built before T3, as the plan prefers)
- **Read rule** (`MemoReadable`): classification at most the reader's clearance, and either an active participant row (not revoked, a share not past its expiry) or a `memo.read.unit` grant whose scope covers the memo's origin unit (`Policy.condition` over `memo.origin_unit_id`). It is used by the memo get (and so every endpoint behind it), the inbox's visible-among filter, links and the numbering register. An ArchUnit rule keeps `memo_participant` in `MemoReadable` and the three stores that write it.
- **`memo/V13`:**
  - participant kinds `COPY` and `SHARED`, with `granted_by`, `expires_at` (shares only) and `revoked_at`;
  - a partial index on active rows, and `memo (tenant_id, origin_unit_id)`;
  - `memo_share`: only its revocation columns may change; never deleted.
- **One row per reader:** a stronger way in (initiator > co-author > approver > notified > copy > share) takes over a weaker or revoked row. A revoked share therefore never takes away an approver's access; a test checks this.
- **Shares:**
  - `GET|POST /memos/{id}/shares`, `DELETE …/{shareId}`;
  - given by the initiator, co-authors and approvers, to an active person cleared for the memo, with a reason (1–500) and an optional end within a year;
  - revoked by the giver, the initiator or `memo.share.manage`;
  - a daily job records expiries (the rule already ignores them);
  - audited `memo.share.granted|revoked|expired`.
- **Permissions:**
  - `memo.read.unit` (scopable) and `memo.share.manage`, both privileged;
  - the built-in administrator role no longer carries the permissions that open other people's memos (`Permission.GRANTED_ON_PURPOSE`);
  - web labels in English and Arabic.
- **Tests:**
  - `MemoAccessIT`: shares; validation; a lapsed share; approver over a share; a unit reader with TOTP (the grant ends their sessions) up to their clearance; a tenant admin sees nothing;
  - `MemoReadableIT`: the SQL rule equals a Kotlin reference over random memos, ways in and readers, 3 seeds;
  - ArchUnit, PolicyIT, the matrix.
- **Not yet:**
  - the `MemoAccessChanged` event (no consumer before E05);
  - the "people with access" summary, which comes with copy-to in T5;
  - the memo list performance at 100,000 memos, which is in T9.

## 2026-11-15 — T5 copy-to, receipts and distribution lists
- **`memo/V14`:** `memo_copy` (person, position or list; at submit or after approval; only `sent_at` and the removal of an unsent copy change); `memo_copy_delivery` and `memo_receipt` (append-only records); `memo_distribution_list` and members.
- **Copies:**
  - `GET|PUT /memos/{id}/copies`: the initiator or a co-author, while the memo is a draft or returned, with If-Match on the memo; sent copies stay;
  - `POST …/copies/additions`: after approval, for `memo.copy.add`; sent at once;
  - each copy is resolved when sent: a position to its holder today, a list to its people and position holders;
  - each person gets one delivery (`SENT`, `NOT_CLEARED`, `INACTIVE`); sent people become `COPY` participants;
  - each person is notified once (`MEMO_COPIED`), however many copies reach them, and not at all if they could already read the memo.
- **When copies go:**
  - at submit (and resubmit), the "at submit" copies go in the submit's transaction; anyone they would reach who is not cleared refuses the submit, named, like an approver;
  - at final approval, the "after approval" copies go in the approval's transaction;
  - a rejected or withdrawn memo sends none, and shows them as not sent.
- **Receipts:**
  - written by `GET /memos/{id}` for copy recipients and approvers on first open, with the document version;
  - `GET …/receipts` lists them, for the initiator, co-authors and `audit.read`.
- **Distribution lists:** `/distribution-lists`; everyone reads them, `memo.distribution.manage` keeps them (If-Match); a delete only switches a list off; names are unique among active lists (409).
- **Permissions:** `memo.copy.add` (granted on purpose) and `memo.distribution.manage` (in the administrator role).
- **Memo view:** `readingAs` (the reader's participant kind; null for a unit reader) and the actions `EDIT_COPIES` and `ADD_COPIES`, for T8's screens.
- **Deviation:** "after approval" copies are sent in the approval's transaction, not in a job. The writes are batched per person and idempotent through `sent_at`; the 500-recipient timing is measured in T9 (design §9 target: under 5 s).

## 2026-11-15 — T3 note sheet backend
- **`memo/V15`:**
  - `memo_note` (REMARK, COMMENT or CORRECTION; body `noteDoc/v1` with its plain text; a remark linked to its action by sequence; a correction by `corrects_id`);
  - `memo_note_mention` and `memo_note_attachment`, which, with `memo_note`, are append-only by grants;
  - `memo_note_draft` (private, mutable, one per person and memo).
- **`NoteDoc`:** the validator and tree for `noteDoc/v1`:
  - paragraphs, nested lists (at most 3), bold, italic, `https:` links, hard breaks, mentions and memo references;
  - anything else is refused with its JSON path;
  - limits on size, nodes, text, and on mentions, links and memo references;
  - the canonical stored JSON, and the plain text.
- **Endpoints:** `GET|POST /memos/{id}/notes`, and `GET|PUT|DELETE …/notes/draft` (If-Match once the draft exists; 204 when there is none).
- **Who writes:**
  - readers comment while the memo is not closed;
  - copy recipients only with the tenant setting `memo.copy.comments` (off by default, owner's answer 2); unit readers never;
  - authors (or the person they acted for) correct their own entries, with corrections nested at most 10 deep.
- **Mentions:**
  - the person must be an active participant cleared for the memo, or the post is refused with `MENTION_NO_ACCESS` per person (`mentions.<id>`), so the web can offer "Share and mention";
  - those mentioned are notified (`MEMO_MENTIONED`, a new mail template in English and Arabic).
- **Decision remarks:**
  - submit, resubmit, withdraw, approve, return and reject accept `remarkBody` as well as `remark`; a plain remark becomes one paragraph;
  - the note is checked first, the workflow receives its plain text, then the entry is written linked to the action, all in one transaction;
  - remarks without an entry (before E03, or recall and hand-ons) are merged into the list from the workflow's actions, as `action-<seq>`.
- **Files on a note:**
  - purpose `memo.note.attachment` (the attachment types and size), owned by the memo, uploaded by someone who may write;
  - bound to one entry when it is posted, at most five per entry.
- **Audit:** `memo.note.posted`, `memo.note.corrected` and `memo.mentioned` (entry id and user ids, never the body).
- **Carried:**
  - removing unbound uploads after 24 hours, which needs files deletion, as for previews;
  - a stricter classification per note file;
  - search over notes (E05).

## 2026-11-15 — T6 authenticated approvals
- **Identity:** `AuthenticationContext.current()` gives the session's factors, when the password and the second factor were last proven, and the SHA-256 of the session id.
- **`memo/V16`:** `memo_approval`, append-only. One row per approve, recommend, return and reject, written in the action's transaction by `MemoTasks`, under the memo's row lock. It holds:
  - the round, the action's sequence, the decision, the actor, who they acted for, and their position;
  - authentication: factors, the password and second-factor times, whether the step-up rule applied, the session hash;
  - the client (IP and user agent);
  - the content: the document version id, number and SHA-256, and each attachment bound to the round with its SHA-256;
  - `record_hash`: SHA-256 of a canonical JSON (keys ordered, timestamps in microseconds) plus the previous record's hash, so the chain runs per memo.
- **API:**
  - `GET /memos/{id}/approvals` returns the stamps: name, position, decision, time, factors, the second-factor age, the short document hash; client fields only for `audit.read`;
  - `POST …/approvals/check` recomputes the record hashes, the chain and the content hashes from storage, names what differs (`RECORD_CHANGED`, `CHAIN_BROKEN`, `DOCUMENT_CHANGED`, `DOCUMENT_MISSING`, `ATTACHMENT_CHANGED`), and is audited (`memo.approval.checked`).
- **Wording:** `ApprovalWordingTest` fails if the web catalogues or the mail texts say "digital signature" or "e-signature", in English or Arabic.
- **Phase 2 follow-up, recusal:**
  - a route entry whose approver stands aside now restarts with the rest of the route rebuilt behind it, as send back and staff changes already did;
  - the step that replaces it therefore keeps its place, and later approvers can send back to it;
  - `SendBackAndRecuseTest` checks the order and the send back.
- **Deviations:**
  - the session id is hashed with plain SHA-256, not an HMAC with an install key: session ids are long random values, so the hash cannot be reversed, and no new secret is needed;
  - the check is not rate-limited yet (carried; it reads only the memo's own rows);
  - the deep check that re-reads content (`deep=true`) waits for E04's renditions.

## 2026-11-15 — T7 web: note sheet, editor and stamps
- **Dependencies:** Tiptap 3.32.2, MIT packages only. Versions released in the last two weeks were skipped; research had named 3.34.0.
- **Editor (`NoteEditor`, lazy):**
  - StarterKit cut down to the format's nodes, plus Mention;
  - `injectCSS: false`, with Tiptap's core ProseMirror rules in our stylesheet;
  - `https:` links only;
  - mentions picked from the directory in an ARIA listbox (arrow keys, Enter, Escape);
  - a text count against 4,000.
- **`toNoteDoc`:** drops what Tiptap adds (a link's `target`, `rel` and `class`, a mention's `mentionSuggestionChar`, empty texts), so the server's strict check sees only the format.
- **`NoteRender`:** our own React walk, no HTML from the server. Links get `rel="noopener noreferrer nofollow"`; a memo reference is a link only when the reader may open the memo, otherwise "Memo (restricted)".
- **`NoteSheet` replaces History:**
  - rounds newest first, with the earlier-round notice;
  - comments, remarks and corrections; a corrected entry is greyed and struck through, with "Corrected ↓";
  - decisions are boxed stamps carrying their remark (or the plain remark of older actions), the authentication line, the short document hash and Check;
  - the other workflow events are small grey lines;
  - client fields appear when the server sends them (auditors).
- **`WriteNoteDialog`:**
  - the private draft is saved 3 seconds after a change, with If-Match;
  - up to five files;
  - corrections start from the corrected text;
  - when someone mentioned cannot see the memo, it offers "Share and mention" with a reason, which shares the memo, then posts.
- **Decision dialogs:** submit, resubmit, withdraw, approve, recommend, return and reject use the editor and send `remarkBody`; the other actions keep plain text.
- **Tests:**
  - unit: the format, rendering, rounds, and the stamp with its check;
  - `memo.test` updated (the editor in the return dialog);
  - jsdom shims for ProseMirror's layout calls;
  - smoke E2E `notes.spec`: a bold note, a mention, share and mention, the person mentioned reads it, with the fixture failing on any CSP violation;
  - visual baselines for the memo page re-rendered with mode `all` (view in light and dark, LTR and RTL, and the phone approve sheet, which now waits for the lazy editor) and checked.
- **Carried:**
  - inserting a memo reference with `#` needs a lookup by number, which comes with E05 search; references already render;
  - the mention list does not yet sort the people who can read the memo first.
