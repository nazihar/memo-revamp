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
