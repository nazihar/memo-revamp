# E04 Final rendition (PDF/A) — log

## 2026-11-15 — Plan written
- Research R1, R3, R8 and records practice by two research agents, checked by the lead (`research.md`).
- ADR-0052 (generated pages: one interface, PDFBox with Noto Sans for English) and ADR-0053 (one PDF/A-2u record per approved memo, validated by veraPDF, locked and re-hashed), both accepted under the delegated gate.
- Design, threat model, tasks T1–T7 and test plan written. No screen-flow review is needed for E04 (overview §10 Q1); the decisions taken under the delegation are listed in `ux-review.md` for the owner.
- **Changes against earlier plans:**
  - ADR-0048's "second fill" at approval is dropped. Re-filling would change the document the stamps bind (ADR-0051).
  - The overview's PDF/A-3 option for attachments that cannot be converted is replaced by placeholder pages naming the locked originals by hash.

## 2026-11-15 — T1 spike: ONLYOFFICE `pdfa` and veraPDF
- **Setup:** ONLYOFFICE Docs 10.0.0.1 in the local Compose stack (`full` profile), called through `OnlyOfficeConverter.toPdfA` (`outputtype: "pdfa"`). veraPDF 1.30.3 (Greenfield, `validation-model-jakarta`). Inputs built with POI in `PdfaConversionIT`: a Word memo (40 paragraphs, a table and a semi-transparent PNG), a spreadsheet and a three-slide presentation.
- **Results:**

  | Input | Conversion | Size | Part and level claimed | Valid as claimed | Valid as PDF/A-2u |
  |---|---|---|---|---|---|
  | Word memo | 2.1 s | 75 KB | 2a | No: 6.7.3.3-1 only (level A's tagged structure) | **Yes**, no failed rule |
  | Spreadsheet | 2.1 s | 22 KB | 2a | No: 6.7.3.3-1 only | **Yes** |
  | Presentation | 2.1 s | 21 KB | 2a | No: 6.7.3.3-1 only | **Yes** |

  veraPDF took 1.3 s on its first file (profile loading) and under 0.1 s afterwards.
- **Conclusions:**
  - ONLYOFFICE's `pdfa` output is PDF/A-2 with embedded fonts and Unicode mappings, and passes 2u. Office documents and attachments need no rasterising. The record re-declares itself as 2u, which is what it meets.
  - The forum report that `pdfa` output fails accessibility checks matches the single 6.7.3.3-1 failure (tagging), which only level A requires.
  - Rasterising stays as the fallback for uploaded PDFs that break a rule (non-embedded fonts, CMYK).
- **Changes against the design:**
  - PDFBox's jar has no sRGB profile; the output intent uses the JDK's own (`ICC_Profile.getInstance(CS_sRGB)`).
  - The record's metadata uses only predefined XMP properties. The number is `dc:identifier`; the hashes and versions are in `dc:description`. A custom `memo:` schema would need a PDF/A extension schema for no gain.
  - Noto Sans ships with Italic and Bold Italic too (notes can be italic), about 1.7 MB in all.
