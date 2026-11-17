# E04 Final rendition (PDF/A) — research

> Step 1 of `AGENTS.md §5`. Overview research items **R3** (generated-page rendering) and **R8** (PDF/A for merged renditions), R1 for everything E04 adds, and how records systems produce and keep a final record. Gathered 2026-11-15 by two research agents and checked by the lead. All sources accessed **2026-11-15**. "Unconfirmed" marks anything not verified against a primary source.

## Lead's checks and conclusions
- **What we already have:**
  - PDFBox 3.0.8 is a backend dependency.
  - ONLYOFFICE Docs 10.0 converts Office files to PDF (`OnlyOfficeConverter`, ADR-0045).
  - The files service stores **record** purposes in the object-locked `records` bucket, encrypted per tenant. A provisional 7-day lock in the tenant's mode is extended to the final date after commit (ADR-0029).
  - SeaweedFS 4.48 is pinned; it has object lock since 3.94 and dedicated Object Lock IAM actions since 4.48.
  - So E04 needs no new storage. The rendition is a file of a new record purpose, `memo.rendition`.
- **Level: PDF/A-2u.**
  - PDF/A-2 allows transparency (ONLYOFFICE and Word output use it).
  - The "u" level guarantees Unicode for all text, which the text-match check needs.
  - NARA lists PDF/A-1 and PDF/A-2 as preferred for born-digital text.
  - **PDF/A-3** (any embedded file) is not used. Archives warn about arbitrary embedded content (Library of Congress, British Library). NARA's preference for PDF/A-3/4f covers "case files" whose embedded files are indexed.
  - Originals that cannot be converted stay as their own locked record files. The rendition lists each one with its SHA-256 on a placeholder page. The owner can still ask for PDF/A-3 later (ADR-0053 keeps the door open).
- **Documents and Office attachments: ONLYOFFICE `outputtype: "pdfa"`.**
  - The conversion tables list `pdfa` as an output for docx, xlsx, pptx and others.
  - Which part and level it produces is unconfirmed, so **E04 T1 starts with a spike**: convert a filled template, a Word memo with tables and images, a spreadsheet and a presentation, then validate each with veraPDF.
  - If ONLYOFFICE gives PDF/A-1b or 2b with embedded fonts, the assembler can carry its pages into the PDF/A-2u record.
  - If not, the fallback is to rasterise the offending pages with PDFBox `PDFRenderer`. That keeps the record valid but loses text on those pages, so they are flagged on the cover.
  - **Ghostscript is ruled out:** it is AGPL, and Artifex says AGPL reaches server-based use.
- **Generated pages: our own PDFBox code** behind a `PageRenderer` interface.
  - The pages are a cover, the note sheet by round, approval stamps and attachment placeholders.
  - Font: Noto Sans (OFL-1.1), TrueType builds, subset-embedded with `PDType0Font`. PDFBox cannot subset OTF/CFF.
  - A small layout helper does word wrapping, page breaks and simple tables.
  - Not chosen:
    - openhtmltopdf (LGPL, built on PDFBox, supports PDF/A): its release churn is about 70 versions in five months, and it has no Indic shaping.
    - Apache FOP: heavy, and its PDF/A-2 implementation level is not stated.
- **Complex scripts later** (§10 Q3, recorded only). Neither PDFBox nor FOP shapes Indic scripts; openhtmltopdf shapes Arabic only, through presentation forms. The two candidates behind the same interface:
  - ONLYOFFICE Document Builder → DOCX → ConvertService `pdfa`. It reuses a service we run; its availability in the Community edition is unconfirmed.
  - A HarfBuzz-based sidecar: Typst (Apache-2.0, all PDF/A levels since 0.14) or WeasyPrint (BSD-3, PDF/A since 56).
- **Validation: veraPDF 1.30.3** (Greenfield, `validation-model-jakarta`), under the **MPL-2.0** option of its GPL/MPL dual licence. MPL-2.0 is on our allowed list (AGENTS §3).
  - It runs in tests, which is the CI gate on every rendition the suite builds.
  - It also runs **in the rendition job**, so auditors see a real validation result: the profile, pass or fail, the failed rules, and the veraPDF version.
  - It runs on a bounded executor with a time limit.
  - 1.30.3 fixed four security issues (resource exhaustion, XXE, font parsing, stack overflow). 1.30.4 (2026-11-10) is younger than two weeks and is skipped until the Renovate window.
  - It brings Saxon-HE 12.8, Rhino and Jackson 2 onto the runtime classpath. These go in the dependency register, and the image size is measured in T2.
- **Fixity:**
  - SHA-256 of the plaintext in the file version (as today) and in the audit event.
  - A weekly re-hash job that compares stored and recomputed hashes and audits the result.
  - Object lock is enforced at the S3 layer only. SeaweedFS's filer, `weed shell` and volume endpoints can still remove chunks, and they are already confined to the internal network (ADR-0005). The re-hash is the real check.
  - PAdES B-LTA with an RFC 3161 timestamp is the upgrade path. It is recorded, not built.
- **Regeneration:**
  - The first rendition is the record of reference and is never replaced.
  - Regeneration is a deliberate act, with a reason. Examples: a converter defect, or a missing font.
  - It writes a new version of the same record file under the same lock rules, and audits `superseded` with both hashes and the renderer versions.
  - Version 1 stays locked and readable.

---

## R1: Components and versions

| Component | Latest stable (date) | Licence | Maintenance | Use in E04 |
|---|---|---|---|---|
| Apache PDFBox `pdfbox`, `xmpbox` | 3.0.8 (Maven 2026-07-08, announced 2026-07-11); 2.0.37; 4.0 "not released" | Apache-2.0 | Active | Merge, outline, page labels, XMP, OutputIntent, fonts, rasterising fallback, text check |
| PDFBox `preflight` | 3.0.8 | Apache-2.0 | Released, but validates PDF/A-1b only | Not used |
| veraPDF `validation-model-jakarta` | **1.30.3 (2026-10-08)**; 1.30.4 (2026-11-10), under two weeks old | GPLv3+ or MPLv2+ (dual); we use MPL-2.0 | Active; regular security fixes; 1.30.1 dropped the PDFBox model (Greenfield only) | Validation in tests and in the job |
| Noto Sans (latin-greek-cyrillic) | NotoSans-v2.015 (2024-11-20) | OFL-1.1 | Active project | Embedded font of generated pages (TTF builds) |
| ONLYOFFICE Docs conversion `outputtype: "pdfa"` | Docs 10.0.0.1 (ours) | AGPL-3.0, separate service (ADR-0047) | Vendor | Office documents and attachments to PDF/A |
| openhtmltopdf (`io.github.openhtmltopdf`) | 1.1.113 (2026-11-12, too young); 1.1.105 (2026-10-29) | LGPL-2.1+ | Very frequent releases; original repo dormant since 2024-06 | Not chosen |
| Apache FOP | 2.12 (2026-10-20) | Apache-2.0 | Active, slow | Not chosen |
| Ghostscript | 10.08.0 (2026-09-08) | AGPL-3.0 or commercial | Active | Ruled out (licence) |
| SeaweedFS | 4.48 pinned (2026-09-28); 4.51 (2026-10-25) newest past two weeks; 4.52, 4.53 too young | Apache-2.0 | Active | Object-locked `records` bucket (exists) |

Sources:
- PDFBox: https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/maven-metadata.xml, https://pdfbox.apache.org/, https://pdfbox.apache.org/3.0/migration.html, https://repo1.maven.org/maven2/org/apache/pdfbox/preflight/maven-metadata.xml
- veraPDF: https://repo1.maven.org/maven2/org/verapdf/validation-model/, https://repo1.maven.org/maven2/org/verapdf/validation-model-jakarta/, https://api.github.com/repos/veraPDF/veraPDF-library/releases. Licence: https://docs.verapdf.org/develop/ and https://repo1.maven.org/maven2/org/verapdf/validation-model/1.30.3/validation-model-1.30.3.pom. Transitive dependencies: https://repo1.maven.org/maven2/org/verapdf/core/1.30.3/core-1.30.3.pom
- Fonts: https://api.github.com/repos/notofonts/latin-greek-cyrillic/releases, https://api.github.com/repos/notofonts/latin-greek-cyrillic/license
- ONLYOFFICE: https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/conversion-tables/, https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/request/
- openhtmltopdf: https://github.com/openhtmltopdf/openhtmltopdf. FOP: https://xmlgraphics.apache.org/fop/2.12/pdfa.html
- Ghostscript: https://artifex.com/licensing, https://ghostscript.readthedocs.io/en/latest/VectorDevices.html
- SeaweedFS: https://github.com/seaweedfs/seaweedfs/releases

## R8: PDF/A for a merged record

**Levels**
- **PDF/A-2** (ISO 19005-2:2011, PDF 1.7):
  - adds transparency, JPEG 2000, object and XRef streams, and PAdES signatures;
  - embedded files must themselves be PDF/A;
  - conformance B covers visual appearance, U adds Unicode for all text, and A adds a full logical structure.
  - Source: https://www.loc.gov/preservation/digital/formats/fdd/fdd000319.shtml
- **PDF/A-3:**
  - allows embedded files of any format, as associated files: `AF`, `AFRelationship`, a MIME `Subtype`, and `F` plus `UF`;
  - the Library of Congress lists the risks: ingestion and management, no description beyond the MIME type, no quick way to see that files are embedded, and licensing of embedded content;
  - the British Library prefers PDF/A-1 over -2 and -3 for this reason.
  - Source: https://www.loc.gov/preservation/digital/formats/fdd/fdd000360.shtml
- **NARA transfer guidance** (Bulletin 2014-04 tables):
  - born-digital text: PDF/A-1 and PDF/A-2 preferred;
  - digitised paper: no PDF/A-3/4 attachments;
  - case files (PDF collections): PDF/A-4f and PDF/A-3 preferred, with an external index and no nested embedding.
  - Source: https://www.archives.gov/records-mgmt/policy/transfer-guidance-tables.html
- **UK TNA** accepts PDF/A-1a to 3u for transfer: https://www.nationalarchives.gov.uk/information-management/manage-information/digital-records-transfer/file-formats-transfer/
- **PDF Association 2022:** convert new files to PDF/A-2; use PDF/A-3/4f only where the nature of the embedded files is defined. Seen through search results only; the site answered 403, so this is unconfirmed. https://pdfa.org/wp-content/uploads/2022/04/1415-Choudhary.pdf

**Rules that block typical inputs** (veraPDF Parts 2 and 3 rules: https://github.com/veraPDF/veraPDF-validation-profiles/wiki/PDFA-Parts-2-and-3-rules)

| Rule | What it forbids | Our handling |
|---|---|---|
| 6.1.3-2 | Encryption | Already refused at upload for PDFs (ADR-0029). Inputs are re-checked; an encrypted part becomes a placeholder |
| 6.2.3-2 | Output intents with different ICC streams | Exactly one sRGB output intent; others removed after merge |
| 6.2.4.3 | Device colour without a matching output intent (CMYK under sRGB) | A part with CMYK is rasterised to sRGB, and that is flagged |
| 6.2.10-2 | Transparency without a page group colour space | A `Group` with `CS` set on such pages |
| 6.2.11.4.1 | Fonts not embedded | PDFBox cannot embed what is missing, so the part is rasterised and flagged |
| 6.5.1, 6.5.2 | JavaScript, Launch and other actions; `AA` | Stripped |
| 6.3.x | Annotations without an appearance `N`, without the Print flag, or of banned types | Flattened or removed |
| 6.6.4 | XMP without `pdfaid:part` and `pdfaid:conformance` | Our XMP: part 2, conformance U, Dublin Core, and our own schema |

**Merging with PDFBox** (`PDFMergerUtility`, legacy mode; https://raw.githubusercontent.com/apache/pdfbox/3.0/pdfbox/src/main/java/org/apache/pdfbox/multipdf/PDFMergerUtility.java)
- It merges outlines, page labels, the structure tree, AcroForm, names, output intents and metadata.
- It copies XMP only when the destination has none, so we set ours explicitly.
- It merges output intents by identifier, which is the 6.2.3-2 trap. After merging, the assembler rebuilds the outline and labels from known page indices and keeps one output intent.
- The PDF/A pattern for XMP and OutputIntent, including PDFBox's bundled `sRGB.icc`: https://raw.githubusercontent.com/apache/pdfbox/3.0/examples/src/main/java/org/apache/pdfbox/examples/pdmodel/CreatePDFA.java

**Bookmarks and page labels**
- `PDDocumentOutline` with `PDOutlineItem`s (`addLast`, `PDPageFitWidthDestination`) and `PageMode.USE_OUTLINES`: https://raw.githubusercontent.com/apache/pdfbox/3.0/examples/src/main/java/org/apache/pdfbox/examples/pdmodel/CreateBookmarks.java
- `PDPageLabels` with `PDPageLabelRange` (decimal or roman, with a prefix such as `A1-`), asserted with `getLabelsByPageIndices()`: https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.8/org/apache/pdfbox/pdmodel/common/PDPageLabels.html

**Text check** (https://pdfbox.apache.org/3.0/faq.html)
- `PDFTextStripper` with `setSortByPosition(true)`.
- Compare normalised text (NFKC, collapsed whitespace, headers and footers dropped).
- Assert that the key items appear in order: number, subject, the approved document's text, each note, each stamp.
- Rasterised pages are excluded from the comparison and flagged.
- The 2u flavour guarantees a Unicode mapping exists, not that it is right, so the content assertion stays.

**Validation in code** (https://docs.verapdf.org/develop/)
- Call `VeraGreenfieldFoundryProvider.initialise()` once.
- Then `Foundries.defaultInstance().createParser(in, PDFAFlavour.fromString("2u"))` and `createValidator(flavour, false).validate(parser).isCompliant()`.
- Pass the flavour explicitly, so a file that claims the wrong level fails.
- Jars: about 4 MB for veraPDF itself, plus Saxon-HE, Rhino, Jackson 2 and JAXB. Roughly 10–15 MB in all, which is unconfirmed and measured in T2.
- No official speed figure exists; T1 measures it.

## R3: Generated-page rendering

| Option | Complex-script shaping | PDF/A | Licence | Deployment | Verdict |
|---|---|---|---|---|---|
| **PDFBox 3.0.8 + our layout** | None. Partial GSUB, tested with Bengali only (PDFBOX-4189; PDFBOX-4220 open) | We add XMP and OutputIntent | Apache-2.0 | In process | **Chosen for English** |
| openhtmltopdf 1.1.x | Bidi through ICU4J; Arabic only, through ICU presentation forms; no Indic (danfickle#424, #112 open) | Yes | LGPL-2.1+ | In process (on PDFBox) | Not now: churn, no Indic |
| Apache FOP 2.12 | Arabic and Khmer full; Devanagari partial; Bengali and Tamil none | 1b detailed; 2/3 listed | Apache-2.0 | In process | No |
| WeasyPrint 70.0 (2026-09-08) | HarfBuzz through Pango | 1b to 4f, UA; "not guaranteed" valid | BSD-3 | Python sidecar | Later candidate |
| Typst 0.15.1 (2026-07-17) | rustybuzz (HarfBuzz port) and the ICU segmenter | All levels since 0.14.0 | Apache-2.0 | Rust CLI or sidecar | **Later candidate** |
| Chromium (Gotenberg 9.5.1) | HarfBuzz | "Remediate" to 2b; engine unconfirmed | MIT | Sidecar | No: every 9.x release is under two weeks old |
| ONLYOFFICE Document Builder (Docs 10.0) | The editors' text engine | Builder saves DOCX or PDF; DOCX → ConvertService `pdfa` | AGPL (separate service) | Already deployed | **Later candidate**; Community availability unconfirmed |

Sources:
- PDFBox: https://issues.apache.org/jira/browse/PDFBOX-4189
- openhtmltopdf: https://github.com/openhtmltopdf/openhtmltopdf
- FOP: https://xmlgraphics.apache.org/fop/2.10/complexscripts.html
- WeasyPrint: https://doc.courtbouillon.org/weasyprint/stable/changelog.html
- Typst: https://typst.app/docs/changelog/0.14.0/, https://typst.app/docs/reference/pdf/
- Gotenberg: https://github.com/gotenberg/gotenberg
- ONLYOFFICE Document Builder: https://api.onlyoffice.com/docs/docs-api/additional-api/document-builder-api/

**Fonts in PDFBox:**
- `PDType0Font.load(doc, stream, embedSubset = true)` embeds a subset of a TrueType font and writes a ToUnicode map.
- OTF/CFF with subsetting throws, so we use the TTF builds.
- Source: https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.8/org/apache/pdfbox/pdmodel/font/PDType0Font.html

## Industry practice: the final record

- **India eOffice (NIC):**
  - A file is closed (More → Close) with closing remarks and can be reopened.
  - Retention under CSMOP 2022 chapter 10 counts from the year of closing.
  - I found no official description of a single archive PDF (unconfirmed).
  - Sources: https://edudel.nic.in/edu/e_offince_dt_11122024/EOfficeFAQ.pdf, https://dor.gov.in/files/inline-documents/CSMOP.pdf
- **Bangladesh e-Nothi / d-Nothi:** no primary source on archiving (unconfirmed). https://nbr.gov.bd/uploads/form/d-Nothi_Short_User_Manual_2.pdf
- **ISO 15489-1:2016 §5.2.2:** a record must be authentic, reliable, complete and unaltered (integrity) and usable. MoReq2010: every entity keeps an event history. Sources: https://www.dpconline.org/digipres/implement-digipres/edrms-preservation-toolkit/edrms-toolkit-what-is-a-record, https://moreq.info/executive-summary.html
- **SEC 17a-4(f)(2)**, a well-known reference for non-rewriteable storage in finance. It requires:
  - a non-rewriteable, non-erasable format, or a time-stamped audit trail;
  - automatic verification;
  - export in usable formats with the audit trail;
  - a backup.
  - Source: https://www.law.cornell.edu/cfr/text/17/240.17a-4
- **S3 Object Lock:**
  - Locks apply per version and need versioning.
  - Compliance mode: nobody can shorten or remove the lock, root included.
  - Governance mode: can be bypassed with a permission.
  - Retention can be extended, never shortened.
  - Cohasset has assessed it for 17a-4.
  - Sources: https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html, https://github.com/seaweedfs/seaweedfs/wiki/S3-Object-Lock-and-Retention
- **MinIO:** the community repository is archived (last release RELEASE.2025-10-15), which confirms the earlier choice of SeaweedFS. https://docs.min.io/aistor/administration/object-locking-and-immutability/
- **What a final record contains** (synthesis of the above):
  - a cover with identifiers, title, classification, owner, the final approval's time and approver, and the hash algorithm;
  - the approved document;
  - the note sheet and approval history, with authentication;
  - the attachments: converted where possible, with the originals kept and referenced by hash;
  - bookmarks.
- **Fixity:**
  - a stored checksum, plus scheduled re-hashing (NDSA sets no frequency): https://www.digitalpreservation.gov/ndsa/working_groups/documents/NDSA-Fixity-Guidance-Report-final100214.pdf
  - later, PAdES B-LTA with RFC 3161 timestamps (ETSI EN 319 142-1).
- **Regeneration:**
  - keep the original and add a new representation, with process metadata (Archivematica; ISO 13008:2022): https://www.archivematica.org/en/docs/archivematica-1.17/user-manual/preservation/preservation-planning/, https://www.iso.org/standard/80730.html

**Adopt:**
- the record contents above;
- PDF/A-2u;
- a stored hash plus re-hashing;
- never overwriting;
- the converter and renderer versions on the cover and in the XMP.

**Do better than the products studied:**
- a validation result that auditors can see;
- placeholders that name each original by its hash;
- regeneration only with a reason, keeping both versions.
