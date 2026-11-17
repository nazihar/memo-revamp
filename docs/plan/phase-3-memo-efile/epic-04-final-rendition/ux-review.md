# E04 Final rendition (PDF/A) — UX review

> `AGENTS.md §5` Step 3. The owner kept the per-epic gate delegation and listed no E04 flow for review before build (overview §10 Q1), so this file is **for information**. Wireframes are structural; the look is the existing Ledger direction and components.

## Flow G — Final record box (memo page, approved and closed memos)

**G1. Being prepared** (polls every 10 seconds)
```
Final record
The final record is being prepared. It combines the approved document, the
approvals, the note sheet and the attachments in one PDF/A file.
```

**G2. Ready** (a reader cleared to it)
```
Final record
Version 1 · 62 pages · PDF/A                                  [ Download ]
Made 15 Nov 2026, 14:22
```

**G3. Ready, auditor's view**
```
Final record
Version 1 · 62 pages · PDF/A                                  [ Download ]
Made 15 Nov 2026, 14:22
SHA-256   3f9a1c07…e21b                                          [ Copy ]
✓ Valid PDF/A-2u (veraPDF 1.30.3)
  2 pages without text (rasterised) · 1 attachment kept as original
Made with memo 0.x, PDFBox 3.0.8, ONLYOFFICE 10.0.0.1, Noto Sans 2.015
```

**G4. Not cleared**
```
Final record
Version 1 · Classified Secret. You are not cleared to open it.
```

**G5. Failed** (holders of `memo.rendition.regenerate` see Retry)
```
Final record
The final record could not be made. Administrators have been told.   [ Retry ]
```

**G6. Regenerate** (holders of the permission; step-up follows)
```
Make a new version of the final record
The current version stays as it is and can still be opened. Say why a new
version is needed.
Reason *  [ The converter dropped a table on page 4.                 ]
                                              [ Cancel ]  [ Make version 2 ]
```

**History** (when there is more than one version)
```
Earlier versions
v1 · 15 Nov 2026 · replaced: "The converter dropped a table on page 4."  [ Download ]
```

## Decisions taken under the delegation (recorded for the owner)
1. **PDF/A-2u, with the originals beside it** rather than embedded (PDF/A-3). Archives prefer it for text; the originals stay locked and are named by hash on placeholder pages. Change to PDF/A-3 on request.
2. **The approved document is never re-filled.** The number and the approvals appear on the generated cover and approval pages. ADR-0048's second fill is dropped.
3. **One record per memo, classified at its highest part.** Readers below see that it exists.
4. **Regeneration needs a purpose-granted permission, a step-up and a reason,** and keeps every earlier version.
5. **A record that fails validation is still stored** and flagged, with an alert, rather than withheld.
