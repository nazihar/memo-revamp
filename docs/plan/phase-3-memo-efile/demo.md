# Phase 3 demo — memo and e-file management

A walk through Phase 3 (milestone M1) on the dev environment (`https://dev.memo-revamp.naziharitsolution.com`) or locally (`pnpm dev`, then `http://localhost:5173`). About 60 minutes; each epic's section stands on its own. It assumes the Phase 2 demo (`phase-2-workflow-engine/demo.md`) has been seen.

Every demo account uses the password and TOTP secret in `deploy/compose/.env` (`MEMO_DEMO_PASSWORD`, `MEMO_DEMO_TOTP_SECRET`). Use two or three private browser windows to switch people quickly. Previews, document editing, text search of documents and PDF/A records need the **documents stack** (ONLYOFFICE and the Tika server): on dev it runs; locally start it with the `documents` profile.

| Account | Role in the demo |
|---|---|
| `admin@demo.example` | Tenant administrator; "Records keeper" (E04) and "Legacy importer" (E06), cleared to Confidential |
| `reviewer@demo.example`, `orgadmin@demo.example` | The second administrator for maker-checker |
| `user@demo.example` (Demo User) | Initiator, cleared to Internal |
| `head.credit@demo.example`, `head.finance@demo.example`, `director@demo.example` | Approvers; the heads see their teams |
| `auditor@demo.example` | Auditor; reads the Credit Division's memos as a unit reader |

The demo data is seeded at start-up and only fills gaps, so an environment seeded before an epic may lack that epic's newer items (each epic's `log.md` says what its seed adds).

## 1. Memo types, metadata and numbering (E01)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 1 | Representative types | `admin@` → Administration → Memo types | Credit proposal (CP), Expense approval (EXP), Procurement (PRC), Policy approval (POL): fields, numbering formats and retention set up as data, each version maker-checked |
| 2 | A memo with details | `user@` → New memo → Expense approval | Required fields (amount with currency, cost centre, category) checked as typed; the number (`EXP-<year>-00001` form) is given at submit, gapless |
| 3 | Authority by amount | `user@` → a Credit proposal of BDT 8 million, then of 3 million | At 5 million or more the managing director decides first; below it the chief financial officer alone (the DoA bands) |
| 4 | Numbering | `admin@` → Memo numbering | The register per series and period, with its completeness check |

## 2. Documents (E02)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 5 | Edit in the browser | `user@` → the draft "Laptop refresh for the credit team" → Edit document | ONLYOFFICE in the page; saving makes a new version |
| 6 | Attachments | Same draft | `laptop-quotation.pdf` previews in the page; an attachment can be classified above the memo, and approvers not cleared for it see "Restricted attachment" |
| 7 | Compare versions | "Office lease extension" (resubmitted with version 2) | The changes between versions, marked |

## 3. Templates, the note sheet and access (E03)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 8 | A template | `user@` → New memo → Credit facility | The letterhead is filled at create: number, date, subject, initiator, classification |
| 9 | The note sheet | "Credit line renewal for Meghna Agro" | A mention, a note and its correction (the original stays); approvals as authenticated, hash-chained entries |
| 10 | Shares and copies | Same memo | A 30-day share with the branch administrator, with its reason; copies to the "Credit committee" list sent at approval; the head of finance's receipt |
| 11 | Unit reader | `auditor@` | Reads the Credit Division's memos through "Unit reader", within clearance |

## 4. The final record (E04)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 12 | PDF/A record | `user@` → an approved memo, e.g. "Staff welfare fund contribution" → Final record | One PDF/A-2u file: cover, approved document, approval history with hashes, note sheet, attachments; validated by veraPDF; locked and re-hashed weekly |
| 13 | A new version | `admin@` as "Records keeper" on a memo shared with them | Version 2 with a reason and a step-up; version 1 stays downloadable |

## 5. Inbox, search and dashboards (E05)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 14 | Search | `user@` → the box in the top bar, "lease" | Results with where the words were found; the words never appear in a URL; the saved search "Lease and rent" |
| 15 | Who cannot see | `head.finance@`, the same words on a memo they take no part in | Nothing found: the read rule applies at query time |
| 16 | Following | `user@` → Inbox → Watching | Memos Demo User started are followed; news in the app, digests by email |
| 17 | Team view and dashboard | `head.credit@` → Inbox → Team; Dashboard | Open work of the Credit Division's people; reassign with a reason and a step-up |

## 6. Legacy import (E06)
The demo tenant holds an imported batch seeded from the synthetic legacy sample (`SyntheticLegacy`, `DemoLegacySeeder`):

| | |
|---|---|
| Batch | **Legacy DMS sample, 2019–2021**, source code `LEGACY-DMS`, time zone Asia/Dhaka, state **COMPLETED** with its report |
| Workbook | Memos, Files and Approvals sheets, in the 1904 date system (old Mac Excel), read in its own system |
| Imported | HO/2019/0012 "Generator purchase for the Khulna branch" (Approved, Internal; initiator Demo User linked by email; approvers Demo Head of Credit, linked, and "M. Karim", by name only; `memo.docx` and `quotation.pdf`) |
| | HO/2019/0047 "Renewal of the Gulshan branch lease" (Closed, Internal; initiator Demo Leaver, the deactivated demo leaver, linked by email; `memo.doc` and `rent-schedule.xls`, the latter a Confidential attachment, stricter than the memo) |
| | HO/2020/0003 "Sponsorship of the regional cricket league" (Rejected, Internal; initiator "Rafiq Hossain", by name only, who left before the move; a 3-page `scan.tif`, download-only, and `annexes.zip`) |
| | HO/2020/0118 "Revision of the cash-in-transit insurance cover" (Approved, Confidential; initiator "Nasreen Akter", whose email nobody in the tenant has, confirmed as not linked; its Files row names `Memo.PDF`, the folder holds `memo.pdf`: `PATH_CASE_MATCH`) |
| Rejected | HO/2021/0007 twice (`NUMBER_DUPLICATE_IN_BATCH`); HO/2021/0009 raised on "31/02/2021" (`DATE_INVALID`); HO/2021/0015 "Pending with GM", mapped to NOT_IMPORTED (`IN_FLIGHT_NOT_IMPORTED`) |
| Folder notes | `2021/HO-2021-0007/Memo.pdf` and `memo.pdf` (`PATH_CASE_ONLY`, `FILE_UNREFERENCED`); a `Thumbs.db`, skipped |

On a stack without the Tika server the `.doc` and `.xls` originals cannot be typed, so HO/2019/0047 is rejected `FILE_NOT_RECEIVED`; the rest is the same.

### The seeded batch
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 18 | The batches | `admin@` → Administration → Import | Only holders of `memo.import` see the entry ("Legacy importer", a custom role: no built-in role carries it) |
| 19 | Metadata and originals | Open **Legacy DMS sample, 2019–2021** → Metadata, Originals | The workbook used, with its SHA-256; originals by state, the skipped system file, refusals with their reason |
| 20 | Mappings | → Mappings, a tab per kind | Legacy statuses ("Pending with GM" → Not imported), types, units, classification with the **legacy order** (Up and Down) and the rule that a stricter marking never maps lower; people linked by email only, a name never links |
| 21 | Dry run problems | → Dry run; filter by code (`NUMBER_DUPLICATE_IN_BATCH`, `DATE_INVALID`, `PATH_CASE_MATCH`) | Each problem names the sheet row and column; errors reject only their row; the dry run cannot write (read-only transactions) |
| 22 | The import | → Import | Who started it and when, the rows imported, skipped, rejected; "Waiting for previews to catch up" when the previews fall behind. The seeder started this batch without a session, so it has no step-up time; the live import below shows the step-up |
| 23 | The report | → Report | Balanced, complete and valid; the balance lines; the parts: summary PDF, detail workbook (formulas made harmless), `manifest-sha256.txt` and the workbook used, all kept as records. Download the summary |

### Imported memos
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 24 | An imported memo | `user@` → search "Generator purchase", open HO/2019/0012 | The banner ("Imported from LEGACY-DMS … not verified by this system"), the legacy details as given, the **legacy approval history** labelled as imported and not verified, the originals with their previews, "No final record: for an imported memo the original documents are the record". No route, note sheet or actions |
| 25 | Origin filter | `admin@` → Search → Origin: Imported | Only imported memos, each with the "Imported" badge; the date filter uses the raised-on date. The administrator reads them as a tenant-wide unit reader cleared to Confidential; `user@` finds only HO/2019/0012, where Demo User is the linked initiator |
| 26 | People who left | `admin@` → search "Rafiq Hossain" | Found by the legacy initiator's name, though he never had an account here |
| 27 | A stricter original | `admin@` → HO/2019/0047 | `rent-schedule.xls` is Confidential, above the memo; a reader cleared below Confidential sees it only as a restricted original. Demo User is not a participant of this memo and does not read it |

### A live mini-import (as the journey does)
Prepare beforehand: a small workbook from the template (two memos, each with a MAIN original and one approval) and a folder holding the two originals. Use numbers no earlier import used.

| # | Show | Who, where | What to point out |
|---|---|---|---|
| 28 | New batch | `admin@` → Import → New batch | Name, source code, the time zone the legacy dates are in |
| 29 | Metadata and folder | Metadata: upload the workbook; Originals: Choose folder | Files are sent three at a time with progress; pause and resume; a reload resumes by choosing the folder again |
| 30 | Confirm and dry run | Mappings: Confirm all for each kind; Dry run: Run | Every mapping must be confirmed; any change sends the batch back to "needs a new dry run" |
| 31 | Start | Import → Start | The confirmation lists what will **not** happen (no approvals, tasks, notifications or final records), then the step-up code |
| 32 | Report and close | Report → download the summary; Close with a reason | Staged copies are purged; the report and the imported memos stay |
| 33 | A correction | Open an imported memo → Correct a legacy detail (or Raise classification) | A reason and a step-up; the legacy record stays as given and the correction is added with the reason and name |
| 34 | Nothing else happened | `user@` → Inbox, Notifications | No tasks and no notifications from the import; Audit (`admin@`, filter `import.` and `memo.imported`) shows every step, each memo leading to the person who started the import |

## 7. Evidence
**Automated journeys** (Playwright, smoke stage on every pull request; those that need the documents stack run nightly):

| Epic | Spec |
|---|---|
| E01 | `memo-types.spec.ts`, `authority.spec.ts` |
| E02 | `attachments.spec.ts`, `document-editing.spec.ts` (documents stack) |
| E03 | `templates.spec.ts`, `notes.spec.ts`, `access-and-copies.spec.ts` |
| E04 | `final-record.spec.ts` |
| E05 | `search.spec.ts` (its attachment-words test on the documents stack), `inbox-team.spec.ts`, `dashboard.spec.ts` |
| E06 | `legacy-import.spec.ts` (the legacy `.doc` part on the documents stack) |

**Volume:** the weekly `load` workflow (inbox, search, team view and dashboards at volume V2; search at V1); the import volume test `ImportVolumeIT` (10,000 memos with 30,000 originals, recorded in the E06 log). Runbooks: `docs/runbooks/` (memo types and numbering, attachments and previews, document editing, templates, notes and access, final records, search, legacy import).
