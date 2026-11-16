# E06 Legacy import — UX review

> `AGENTS.md §5` Step 3. The owner reviews only the screen flows of E01, E03 and E05 before build (overview §10 Q1), so **E06 has no owner gate**: this file is **for information**. The questions at the end were decided under the delegation, each as recommended, and are recorded here for the owner. Wireframes are structural; the look is the existing Ledger direction and components.

## Flow L — Batches and intake (holders of `memo.import`)

**L1. Administration → Import**
```
Import legacy memos                                              [ New batch ]
Bring memos from an earlier system: their originals and a spreadsheet of their
facts. Nothing is imported until you have checked a dry run.

Name                      Source        State        Memos   Updated
Head office 2015–2019     LEGACY-DMS    Completed    9,812   16 Nov 2026
Branch archive (rehearsal) LEGACY-DMS   Dry run done 1,204   15 Nov 2026
```
- Rows open the batch. States read as words, never colour alone.
- People without `memo.import` do not see the menu item; the server answers 403 anyway.

**L2. New batch** (dialog)
```
New import batch
Name *              [ Head office 2015–2019                   ]
Source code *       [ LEGACY-DMS ]   Capital letters, digits and "-". The same
                                     source code keeps re-runs from duplicating memos.
Time zone of dates  [ Asia/Dhaka ▾ ]
Default classification for blank cells  [ None: blank is an error ▾ ]
Number prefix       [            ]   Optional. Added before every legacy number,
                                     for example "LEG-", if they clash with ours.
                                                         [ Cancel ] [ Create ]
```

**L3. Batch page, step 1 — Metadata**
```
Head office 2015–2019 · LEGACY-DMS                       State: Open
① Metadata ✓   ② Originals   ③ Mappings   ④ Dry run   ⑤ Import   ⑥ Report

Metadata
Use our template, or your own file with the same columns.  [ Download template ]
( ● ) One workbook (.xlsx) with sheets Memos, Files, Approvals
(   ) CSV files, UTF-8, one per table

legacy-export.xlsx · 4.2 MB · uploaded 16 Nov 2026 10:02        [ Replace ]
Tip: never open a legacy CSV export in Excel before uploading it. Excel removes
leading zeros from numbers and changes dates.
```

**L4. Step 2 — Originals** (folder upload)
```
Originals
Unpack the archive on your computer, then choose the folder. Each file is sent
and checked on its own.                                     [ Choose folder ]

Folder: HO-archive · 31,204 files · 1.6 GB
Skipped: 12 system files (Thumbs.db, .DS_Store)  ·  Too large (over 100 MB): 1 [ View ]

Sending   ████████████░░░░░░░░  18,450 of 31,204 files · 0.9 of 1.6 GB
About 25 minutes left · 3 at a time                           [ Pause ]

Refused (7)                                                      [ View all ]
  2016/CR-0042/annex.doc        Contains macros
  2017/HO-FIN-117/scan.msg      This type is not allowed
```
- **Resume:** after a reload or on another day: "18,450 files are already here. Choose the same folder to send the rest." Only paths missing (or changed in size) are sent.
- Paths show left to right even in Arabic.
- **Phones:** the step says "Use a computer to send a folder"; the rest of the batch stays readable.

## Flow M — Mappings and the dry run

**M1. Step 3 — Mappings** (tabs: Statuses · Types · Units · Classification · People)
```
Statuses                                              [ Confirm all proposals ]
Legacy value             Memos   Ours                        Confirmed
Sanctioned               6,102   [ Approved ▾ ]               ✓
Approved & filed         2,880   [ Closed ▾ ]                 ✓
Declined                   611   [ Rejected ▾ ]               ☐
Pending with GM             42   [ Not imported (in flight) ▾ ] ☐
```
- **Classification** adds an order column: "Order the legacy markings from least to most strict. A stricter marking can never map to a lower level than a less strict one." Rows move with Up and Down buttons (keyboard), and the rule's error names the pair.
- **People:** one row per email or legacy id with its memo count; an email that equals a user's is proposed; a legacy id is linked through the people picker. People given only by name are listed as "Kept as text (no identifier)" with a count, and cannot be linked.
- Saving any tab says: "Run the dry run again before importing."

**M2. Step 4 — Dry run**
```
Dry run                                                    [ Run dry run ]
Last run 16 Nov 2026 11:40 · nothing was imported

9,812 memos ready   ·   147 rejected   ·   1,903 with warnings   ·   0 already imported
31,204 originals: 30,964 used by rows · 233 not used by any row · 7 refused

Problems   Level [ Errors ▾ ]  Code [ Any ▾ ]  Sheet [ Any ▾ ]   [ Download (XLSX) ]
Sheet   Row   Legacy number   Problem
Memos    214  CR/2016/0042     A memo with this number already exists.
Memos    880  HO-FIN-2019-07   Status "Pending with GM" is in flight: finish it in the old system.
Files   1,322 CR/2017/0108     File "2017/CR-0108/main.pdf" was not received.
Memos   3,051 0042/2018        "0042/2018" could be made by the series "credit"; set its starting number past it or use a prefix.
                                                                         [ Load more ]
```
- Problems never show subjects; the sheet row number lets the admin find the row in their file.
- Rejected rows can go into a later batch once fixed; the source code keeps that safe.

## Flow N — Import and report

**N1. Step 5 — Start** (dialog)
```
Import 9,812 memos?
• 9,812 memos and 30,950 originals will be imported. 147 rejected rows will not.
• 1,903 memos have warnings (for example 1,644 people kept as text).
• Imported memos are final. They get no approvals, tasks, notifications or
  final record. Their legacy approvals show as history, not verified by this system.
• This cannot be undone. Corrections are added later, never by deleting.
                                        [ Cancel ]  [ Confirm and verify identity ]
```
The step-up follows (as for approvals).

**N2. Progress**
```
Importing · 4,120 of 9,812 memos · about 40 minutes left                 [ Stop ]
Waiting for previews to catch up (210 waiting). The import resumes by itself.
Imported 4,102 · Skipped 0 · Rejected 12 · Failed 6
```
- Stop finishes the current chunk. A stopped batch shows "Resume" (step-up again) or "Finish without the rest".

**N3. Step 6 — Report**
```
Reconciliation report                                    ✓ Balanced · Complete · Valid
Rows read 9,959 = imported 9,794 + rejected 159 + skipped 0 + failed 6 + not started 0
Originals received 31,204 = imported 30,880 + held back 84 + not used 233 + refused 7
Previews: PDF 30,402 done · 478 not possible · 0 failed      Text: 29,960 done · 920 not possible
Fixity: 30,880 originals re-checked · 0 mismatches
[ Summary (PDF) ]  [ Details (XLSX) ]  [ Manifest (SHA-256) ]
Classified Confidential: only people cleared to it can open these files.
                                                                   [ Close batch ]
```
- Until the previews settle: "The report is made when previews are done (about 2 hours)."
- Close says: "Staged copies of the originals are deleted. The report and imported memos stay."

## Flow O — Imported memos (readers)

**O1. Memo page**
```
CR/2016/0108 · Working capital line for Meghna Agro                 [Imported]
Imported from LEGACY-DMS on 16 Nov 2026 ("Head office 2015–2019").
Created and decided in an earlier system; not verified by this system.

Details   Credit proposal · Confidential · Credit Division (legacy: "CR-HO")
          Raised 3 Mar 2016 · Decided 21 Mar 2016 · Status Approved (legacy: "Sanctioned")
          Initiator: R. K. Bansal (as given; not linked to a user)

Legacy approval history (imported, not verified by this system)
1  A. Rahman (linked: Demo Head of Credit)   Head of Credit   Recommended   14 Mar 2016
2  S. Karim                                   GM Credit        Sanctioned    21 Mar 2016  "Subject to collateral"

Original documents
Main     proposal.doc · Word 97–2003 · 412 KB          [ Preview ] [ Download ]
Annex    valuation.pdf · 2.1 MB                         [ Preview ] [ Download ]
Annex    Restricted original

Final record
No final record: for an imported memo the original documents are the record.

Corrections (1)
21 Nov 2026 · Raised on: 2016-03-13 → 2016-03-03 · "Typo in the legacy export" · Demo Tenant Admin
```
- Holders of `memo.import` who can read the memo see "Correct a legacy detail" and "Raise classification" (each asks for a reason, then the step-up).
- No route, workflow box, note sheet or action buttons appear.

**O2. Search**
- Filters gain **Origin**: ☐ Created here ☐ Imported, with a chip "Imported".
- Results show an "Imported" badge after the number.

## As built (T9)
The screens follow flows L to O and their wording; where they depart:
- **L1:** the list has no "Memos" column (the batch summary carries no counts); the template is a plain download link on the list and on step 1.
- **L3:** the stepper is a list of links; the step shown is not a link to itself but text with `aria-current="step"` (TanStack Router marks an active link `aria-current="page"` whatever it is given). The step is in the address (`?step=`). "Needs a new dry run" is a note on every step while the batch is OPEN after a dry run; settings are a dialog ("Settings").
- **L4:** files over 100 MB are listed under "Too large" and neither registered nor sent; paths the server would refuse (and a second file with the same NFC path) are counted apart. Pause lets the files in flight finish. A refused file is not sent again at the same size (it would be refused again); one changed in size is. The upload lives with the page, not the step: moving between steps keeps it, a reload ends it ("… files are already here. Choose the same folder to send the rest."). Phones see "Use a computer to send a folder." with the counts and refusals.
- **M1:** each tab saves its own kind ("Save mappings"); "Confirm all proposals" confirms and saves at once. Types, units and levels are offered from the batch's mapping targets (the API now names them, design §10 As built T9), so a holder of `memo.import` alone can map. People: a legacy id or email is linked through the people picker and unlinked with "Not linked: keep the name as text"; people given only by name have no row (the API lists none), so the tab says so in words without a count.
- **M2:** the problem list has a Column column instead of "Legacy number" (a problem carries its sheet row, not the number); the legacy number is on the Rows tab, with the subject or "Restricted" as the API returns it. There is no "Download (XLSX)": no endpoint gives the dry run's problems as a file (the report's workbook has the Problems sheet).
- **N1:** the dialog states the READY rows, the originals received (not the originals those rows use: no count gives it) and the warnings as problems of level WARNING, with the people kept as text from `PERSON_NOT_LINKED`. Refusals are said in the dialog. N2's time left is the rows left over the rate.
- **N3:** each balance line is shown; the classification note names the level of the batch's `max_rank`.
- **O1:** the batch's name in the banner shows only to holders of `memo.import` (the batches API needs it); other readers see the source code and the date. The memo's metadata fields, links to it, sharing and following stay as for any memo.
- **O2:** Origin is one choice (All memos, Created here, Imported), in the address as `origin=` and kept in saved searches as `origins`; the chip reads "Origin: Imported"; the badge follows the number and subject.

## Questions and recommended answers (decided under the delegation)
1. **In-flight legacy memos** (draft, pending, returned)?
   - **Recommended and decided:** refused, with the reason in the dry run. The bank finishes them in the old system or raises them anew.
   - Alternative: import them as drafts (two numbers per memo, and a route the legacy approvers never saw).
2. **People who left or are unknown?**
   - **Recommended and decided:** kept as the text given; linked to a user only by an email equal to theirs or a legacy id the admin maps. No placeholder accounts; never the importing admin.
3. **Legacy approvals?**
   - **Recommended and decided:** shown as history, labelled "imported, not verified by this system". No workflow actions, stamps or notifications.
4. **A final record (PDF/A) for imported memos?**
   - **Recommended and decided:** none. The originals are the record, with previews, search and weekly fixity.
5. **Old Office files (.doc, .xls, .ppt)?**
   - **Recommended and decided:** accepted for the legacy purposes only, scanned for macros, typed by the isolated Tika server, previewed through ONLYOFFICE. Macro-enabled, encrypted and Outlook `.msg` files are refused.
6. **Archives?**
   - **Recommended and decided:** unpacked on the desktop; the folder is sent file by file. The server never unpacks a ZIP. A command-line uploader is the path for very large volumes (not built).
7. **TIFF scans?**
   - **Recommended and decided:** accepted; previewed if ONLYOFFICE converts them (the T1 spike decides), otherwise download only. PDFs are preferred, as the instructions say.
8. **Who may import, and is there a second person?**
   - **Recommended and decided:** a purpose-granted `memo.import` and a step-up to start; no maker-checker. The dry run, the report and the audit per memo carry the assurance, with a rehearsal on a non-production tenant first.
9. **Corrections after import?**
   - **Recommended and decided:** appended with a reason and a step-up, by readers holding `memo.import`; classification only raised.
10. **Legacy numbers?**
    - **Recommended and decided:** kept as the memo's number, outside our gapless series; an optional batch prefix when they clash with ours.

## Owner's review
Not required for E06 (overview §10 Q1). The owner may revisit any of the decisions above; none blocks the build.
