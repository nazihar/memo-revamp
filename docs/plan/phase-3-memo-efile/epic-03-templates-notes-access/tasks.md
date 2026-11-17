# E03 Templates, note sheet and access — tasks

> Step 5 of `AGENTS.md §5`. Vertical slices; each task is its own PR, stacked until the owner merges. The web tasks for the note sheet and copy-to (T7, T8) start only after the owner's review of `ux-review.md` flows C and D.

| Task | Goal | Touches | Size | Depends |
|---|---|---|---|---|
| T1 | **Template engine spike, then sanitiser and validation:** poi-tl 1.12.2 on POI 5.5.x and 6.0.0 (split runs, headers, footers, the loop row, timing) decides the engine (ADR-0048). Then: purpose `memo.template`; the sanitiser with every refusal rule; tag inventory and problems; the `TemplateFiller` interface; ArchUnit ban on SpEL | files, memo, build (POI, poi-tl), dependency register | L | — |
| T2 | **Templates on memo types and create from template:** template on the type-version draft (`memo/V12`); preview fill and its PDF; maker-checker rules (no submit with problems or without a preview); fill at create as document v1; "Fill from template" retry; admin API | memo, files, docedit (conversion) | M | T1 |
| T3 | **Note sheet backend:** `memo/V13`; `noteDoc/v1` validator and tree; decision remarks as notes (action endpoints accept `remarkBody`); comments; corrections; mentions (refusal and notification); attachments on a note; private drafts; legacy remarks merged into the list | memo, workflow (derived text only), notification, files | L | — |
| T4 | **Access model:** `memo/V14` participant kinds and revocation; `MemoReadable` used by every memo query (get, lists, inbox, boxes, links, dashboards so far); `memo.read.unit` (scopable); shares with reason and expiry; the expiry sweep; ArchUnit rule; property test | memo, authz, workflow queries | L | — |
| T5 | **Copy-to and receipts:** copies (people, positions, lists), `send_when`, sending at submit and at approval (job), deliveries, receipts on first open; distribution lists admin API; `memo.copy.add` after approval | memo, workflow (event), notification, org (positions) | M | T4 |
| T6 | **Authenticated approvals:** identity's `AuthenticationContext`; `memo/V15` approvals with context and hash chain; stamps API; Check; the recusal ordinal follow-up | memo, identity, workflow | M | — |
| T7 | **Web: note sheet and stamps:** the note sheet replacing History; Tiptap editor (lazy, `injectCSS: false`, our CSS); mentions combobox with ARIA; memo references; attachments on a note; corrections; drafts; stamps and Check; the editor in the approve, return and reject dialogs; Arabic strings; no-CSP-violation E2E | web | L | T3, T6, owner review C |
| T8 | **Web: access, copy-to, templates:** People with access box; share dialog; copy-to dialog and receipts; copy recipient's view; distribution lists admin; template block on the memo-type page with tag list, problems and preview; create page note | web | L | T2, T4, T5, owner review D |
| T9 | **Close:** E2E journeys; demo seed (a letterhead template on Credit facility, a distribution list, notes with mentions and a correction, a share, copies with receipts, the auditor as unit reader for the Credit Division); performance of §9; ASVS rows; runbooks (templates, note sheet and access); STATUS and log | all | M | all |

**Order:** T1 → T2; T3, T4 and T6 in any order (T4 first is preferred, since T5 and T8 need it); T5 after T4; T7 and T8 after the owner's review; T9 last.

## Acceptance criteria (selection; the rest in `test-plan.md`)
- **T1:**
  - Given a .docm, or a .docx with `vbaProject.bin`, an external image relationship, an `INCLUDE` field or `attachedTemplate`, then the upload is refused with the rule's code and nothing is stored.
  - Given `{{memo.numbr}}`, then the problem list names the part, the paragraph and a suggestion.
  - Given a placeholder split across three runs by Word, then it is recognised.
- **T2:**
  - Given a published type version with a template, when a user creates a memo, then document v1 is the filled template with the subject and field values, and the memo records the template version.
  - Given a template with problems, then the version cannot be submitted for review.
- **T3:**
  - Given a note with a `javascript:` link, an unknown node or an unknown key, then it is refused with 422 and the path of the problem.
  - Given a mention of a user who cannot read the memo, then the post is refused with `MENTION_NO_ACCESS`.
  - Given an approve with `remarkBody`, then one note and one action exist, and the action's remark equals the note's text.
  - UPDATE and DELETE on `memo_note` are refused for the application role.
- **T4:**
  - Given a share that expired a minute ago, then the memo is 404 for that person in get, lists and the inbox.
  - Given a unit reader for the Credit Division, then they see memos raised in its subtree, only up to their clearance.
- **T5:**
  - Given copies "after approval" and a final approve, then each resolved person is a `COPY` participant, notified once even when reached twice, and the receipt list shows them unread.
  - Given a recipient not cleared, then they are listed as not sent.
- **T6:**
  - Given an approve after a step-up 2 minutes earlier, then the approval row records the factor times, the hashes and a record hash chained to the previous approval.
  - Given a changed hash in the database (test), then Check reports it.
