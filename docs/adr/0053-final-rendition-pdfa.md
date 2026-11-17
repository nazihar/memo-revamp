---
status: accepted
date: 2026-11-15
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0053 — Final rendition: one PDF/A-2u record per approved memo, validated, locked and re-hashed

## Context and problem statement
When a memo is finally approved, the bank needs one file that stands for it on its own: the approved document, who approved it and how, the notes, and the attachments. That file must be readable for decades, provably unchanged, and kept for the memo type's retention period.

We already have:
- PDF renditions per file version, as previews (ADR-0045);
- object-locked record storage (ADR-0005, ADR-0029);
- hash-chained approvals (ADR-0051).

What should the record be, how is it built, and how is it kept?

## Decision drivers
- An archival standard that a validator checks, not "a PDF".
- What approvers approved is never changed. Their stamps bind a document version and its hash (ADR-0051).
- Fixity that can be proved later, not just asserted.
- No GPL or AGPL code linked into the backend (AGENTS §3).
- Classification: an attachment classified above the memo (ADR-0046) must not leak through the record.

## Considered options
1. **PDF/A-2u**, with attachments converted and originals that cannot be converted kept as locked sibling files, named on placeholder pages.
2. **PDF/A-3u**, embedding every original as an associated file.
3. **A plain PDF** plus a manifest.
4. **A ZIP "package"** (BagIt-style) with the PDF and the originals.

## Decision outcome
**Option 1.**

- **Contents, in order, with bookmarks and page labels:**
  1. **Cover:**
     - memo number, subject, type, classification and the rendition's own classification, initiator and unit, created and approved times, and the final approver;
     - the approved document's version and SHA-256, and the record's version;
     - the renderer and converter versions;
     - "Pages without text" when a part was rasterised.
  2. **The approved document:**
     - It is the version that the final stamps bind, converted with ONLYOFFICE `outputtype: "pdfa"`.
     - It is never edited or re-filled. **ADR-0048's "second fill" is dropped:** filling again would change what was approved. The number and the approvals appear on the cover and the approval pages instead.
  3. **Approval history:** every round's decisions as stamps, with the authentication factors and their age, the document hash, and each record's own hash and its link in the chain.
  4. **Note sheet:** comments, remarks and corrections by round, oldest first, with authors and times. Links are printed as text with their URL. Mentions read `@Name`. A memo reference appears as the memo's number, or "restricted" when the memo is not readable by every reader of this memo.
  5. **Attachments**, each with its title, type, size, SHA-256 and classification:
     - PDFs are normalised;
     - Office files are converted to PDF/A by ONLYOFFICE;
     - images are placed on pages;
     - anything else (ZIP, unknown types, encrypted or broken files) gets a **placeholder page**, and the original stays a locked record file of its own, named by its hash.
- **Normalising inputs:**
  - One sRGB output intent.
  - Our XMP: `pdfaid` part 2 and conformance U, Dublin Core, and a `memo:` schema holding the number, the document hash and the renderer versions.
  - Actions, JavaScript, `AA`, forms and banned annotations are removed.
  - Transparency groups get a colour space.
  - A part that still breaks a rule (fonts not embedded, CMYK, encryption) is **rasterised** at 150 dpi as sRGB and flagged on the cover. Its text is excluded from the text check.
  - Ghostscript is not used (AGPL).
- **Classification:**
  - The record is classified at the highest level among the memo and every attachment it includes.
  - A reader cleared below that level sees that a final record exists and its classification, but cannot open it.
  - There is one record per memo, not one per clearance level. A per-level variant is carried until a customer asks for it.
- **Building:**
  - A tenant background job starts when the memo reaches APPROVED. The db-scheduler instance id is the memo id plus the record version, so a retry builds the same thing.
  - Inputs are read from the memo's state at approval: document version, stamps, notes, and the attachment versions bound to the final round.
  - The job assembles the file in a spool and validates it with **veraPDF 1.30.3** (Greenfield, MPL-2.0 option, flavour `2u`) on a bounded executor with a time limit.
  - It then stores the file as purpose **`memo.rendition`** (record storage, encrypted per tenant, object-locked).
  - It audits `memo.rendition.created` with the SHA-256, the size, the page count and the validation result.
  - A record that fails validation is still stored. It is marked **not compliant**, with the failed rules, and an alert is raised. Withholding it would lose the record.
- **Retention:**
  - The lock runs until approval + the memo type's retention years when the retention counts from approval, else until approval + the tenant's default.
  - It is extended to closure + years when the memo closes.
  - Locks are only ever extended, in the tenant's mode (GOVERNANCE or COMPLIANCE, ADR-0029).
- **Fixity:**
  - The SHA-256 is kept on the file version and in the audit event.
  - A weekly job re-reads every record (later, a rolling sample per tenant when volume demands) and compares the hashes. It audits `memo.rendition.verified` or `memo.rendition.mismatch`; a mismatch is a security incident (runbook).
- **Regeneration:**
  - Only with `memo.rendition.regenerate` (granted on purpose, like `memo.read.unit`), a step-up and a reason.
  - It writes a new version of the same file and audits `memo.rendition.superseded` with both hashes and the renderer versions.
  - The earlier version stays locked and readable, and the memo shows which version is current.
- **Not now:**
  - PAdES B-LTA sealing with an RFC 3161 timestamp (the upgrade path for signatures);
  - PDF/A-3 embedding;
  - per-clearance variants;
  - a stamped page number on the document's first page.

### Consequences
- Good: one validated file per approved memo, readable without our software, with its fixity provable from the audit chain and the lock.
- Good: the approved document and its hash are untouched, so Check on the stamps (ADR-0051) and the record agree.
- Bad: originals that cannot be converted are outside the PDF. They are kept and named by hash, but a reader needs both. PDF/A-3 stays possible if the owner wants originals inside.
- Bad: veraPDF brings Saxon-HE, Rhino and Jackson 2 onto the runtime classpath. They are recorded in the register, run on a bounded executor, and their image size is measured.
- Bad: rasterised parts lose their text. They are flagged on the cover and excluded from the text check; counting them is part of T1's spike.
- Neutral: the record's classification can be higher than the memo's.

### Confirmation
- CI: every rendition built by the integration and E2E suites is validated by veraPDF as 2u, and the text check finds the number, the subject, the document's text, each note and each stamp in order.
- Golden tests for the generated pages (ADR-0052).
- Integration tests:
  - inputs with JavaScript, missing fonts, CMYK, encryption and a ZIP give a compliant record with flagged or placeholder parts;
  - classification above the reader's clearance is refused;
  - regeneration keeps both versions;
  - a tampered stored object is reported by the fixity job;
  - deleting or shortening the lock is refused (existing object-lock tests extended to the purpose).

## Pros and cons of the options
### Option 1 — PDF/A-2u, originals beside it
- Good: preferred by NARA, LoC and BL for text; nothing arbitrary inside the PDF.
- Bad: the originals that cannot be converted live outside it.
### Option 2 — PDF/A-3u with embedded originals
- Good: one self-contained file; NARA prefers it for case files.
- Bad: arbitrary content inside an archival PDF; harder to inspect; archives warn against it.
### Option 3 — Plain PDF and a manifest
- Good: simplest.
- Bad: no standard to validate against; fonts and colour not guaranteed.
### Option 4 — A package
- Good: originals and renditions together.
- Bad: not one readable file; needs our tools or BagIt knowledge to read.

## More information
- Research: `docs/plan/phase-3-memo-efile/epic-04-final-rendition/research.md` (R1, R8, industry practice).
- Library of Congress, PDF/A-2 and PDF/A-3: https://www.loc.gov/preservation/digital/formats/fdd/fdd000319.shtml, https://www.loc.gov/preservation/digital/formats/fdd/fdd000360.shtml (accessed 2026-11-15).
- NARA transfer guidance: https://www.archives.gov/records-mgmt/policy/transfer-guidance-tables.html (accessed 2026-11-15).
- veraPDF: https://docs.verapdf.org/develop/ (accessed 2026-11-15).
- Related: ADR-0005 and ADR-0029 (record storage), ADR-0045 (renditions), ADR-0046 (attachment classification), ADR-0048 (templates: second fill dropped), ADR-0051 (stamps), ADR-0052 (generated pages).
