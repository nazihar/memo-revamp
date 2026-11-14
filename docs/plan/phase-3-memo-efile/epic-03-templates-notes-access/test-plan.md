# E03 Templates, note sheet and access — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. CI stages: PR (unit, property, integration, matrix, smoke E2E), nightly (PIT, browsers, visual, pseudo-locale, hostile templates), weekly (load).

## Scope and risks
- **Risks covered:**
  - hostile templates;
  - XSS through notes;
  - a list or search that forgets the access rule;
  - shares and copies that outlive their grant or lift classification;
  - unverifiable approvals.
- **Seed data:**
  - the demo tenant with a Credit facility letterhead template;
  - a distribution list "Branch managers";
  - `user@` (initiator, cleared Internal), `head.credit@` and `head.finance@` (approvers);
  - `auditor@` (unit reader for the Credit Division, `audit.read`);
  - a person not cleared for Internal.

## Tests by task
| Task | Layer | Test |
|---|---|---|
| T1 | Spike (recorded) | poi-tl 1.12.2 on POI 5.5.x and 6.0.0: split runs, headers, footers, loop row, timing on two letterheads |
| T1 | Unit | Sanitiser: one crafted file per refusal rule (macro, ActiveX, OLE, altChunk, external image, attachedTemplate, INCLUDE/LINK/DDE fields, .docm content type, zip bomb); tag inventory and malformed tags; hostile values stay text |
| T1 | Architecture | No SpEL use in template code |
| T2 | Integration | `MemoTemplatesIT`: upload → problems → preview → submit rules → publish → create from template (v1 content checked by text extraction); timeout path leaves the memo without a document; matrix entries |
| T3 | Unit and property | `noteDoc/v1` validator: every allowed shape accepted; generated invalid trees rejected; limits; text extraction golden files |
| T3 | Integration | `MemoNotesIT`: comments, decision remarks (note equals action text), corrections (own only, open memo only), mentions (notification, refusal), attachments bound on post, drafts private, legacy remarks merged, append-only grants |
| T4 | Property | SQL `MemoReadable` equals a Kotlin reference over random memos, participants, shares (active, revoked, expired), unit scopes and clearances |
| T4 | Integration | `MemoAccessIT`: each reader kind in get, lists, inbox, boxes and links; share rules (reason, expiry, clearance, revoke by whom); unit readers; ArchUnit rule against direct participant joins |
| T4 | Matrix | `p3-e03-access.yaml`: every endpoint × initiator, co-author, approver, copy, shared, revoked, expired, unit reader, under-cleared, other tenant |
| T5 | Integration | `MemoCopiesIT`: at submit and after approval; positions and lists resolved; one notification per person; not-cleared listed; receipts once with the version; additions after approval need `memo.copy.add`; rejected memo leaves copies unsent |
| T6 | Integration | `ApprovalStampsIT`: one row per decision with context and hashes; chain; Check detects a changed hash and a missing row; client fields for auditors only; recusal ordinal |
| T7 | Unit (web) | Renderer per node type (no raw HTML, link rel, restricted memoRef); editor toolbar; mention combobox (ARIA, keyboard); share-and-mention prompt; correction view; axe in English and Arabic |
| T7 | E2E (smoke) | `notes.spec`: write with a mention and a memo reference, attach, post, correct; the page records **no CSP violation** |
| T7, T8 | Visual (nightly) | Note sheet, stamps, share and copy-to dialogs, receipts, template block: light/dark, LTR/RTL, phone |
| T8 | Unit (web) | People with access, share dialog, copy-to dialog, receipts, copy recipient's view, template problems list |
| T9 | E2E (smoke) | `access-and-copies.spec`: copy after approval reaches a branch manager, the receipt appears after they open it; a share with expiry; the auditor as unit reader. `templates.spec`: an admin uploads a template, previews, publishes; a user creates from it |
| T9 | Performance | §9 budgets in `NotesAccessPerformanceIT`; recorded in the log |

## Special tests (`TESTING_STRATEGY §12`)
- **Hostile templates** (nightly): the T1 corpus plus large and deeply nested documents through validation and fill, with bounded time and memory.
- **Record integrity:** append-only on notes, mentions, note attachments, receipts, deliveries and approvals (trigger tests); the approval chain.

## Exit checklist
- [ ] Every test above green in its stage
- [ ] Matrix and audit coverage tests green with the new endpoints and actions
- [ ] Spike recorded in the log; ADR-0048 updated with the engine decision
- [ ] ASVS rows updated (V1, V3, V6, V8, V12, V14, V15, V16)
- [ ] Dependency register: Apache POI, poi-tl (or the fallback), Tiptap packages
- [ ] Owner's review of flows C and D recorded in `ux-review.md`
