# E03 Templates, note sheet and access — research

> Step 1 of `AGENTS.md §5`. Overview research items **R4** (server-side .docx templating) and **R9** (rich text in an append-only record), plus how established e-filing products handle the note sheet, copy-to, access and approval stamps. Gathered 2026-11-15 by two research agents and checked by the lead. All sources accessed **2026-11-15**. "Unconfirmed" marks anything not verified against a primary source.

## Lead's checks and conclusions
- **poi-tl on current POI:** Maven Central lists poi-tl `1.12.2` as the last release (`1.12.3-beta1` updated 2024-03-13). Its POM depends on `poi-ooxml` **5.2.2**, while POI's latest is **6.0.0** (2026-10-22) (https://repo1.maven.org/maven2/com/deepoove/poi-tl/maven-metadata.xml, https://repo1.maven.org/maven2/com/deepoove/poi-tl/1.12.2/poi-tl-1.12.2.pom, https://repo1.maven.org/maven2/org/apache/poi/poi-ooxml/maven-metadata.xml). So **E03 T1 starts with a one-day spike**: poi-tl 1.12.2 on POI 5.5.x and 6.0.0, measured on two real-looking letterheads. If it fails or needs patches, the fallback is a small engine of our own on POI XWPF that keeps poi-tl's tag syntax (so admins see no difference), with docx4j as the second option. ADR-0048 records this.
- **POI is not a backend dependency yet;** PDFBox 3.0.8 is. Templates bring POI (Apache-2.0) and its transitive set; batik is excluded.
- **Tiptap 3 under our CSP:** the default `injectCSS` adds a `<style>` element that `style-src 'self'` blocks; `injectCSS: false` plus our own CSS is required. ProseMirror sets `style.cssText` in a few fallbacks, which the CSP may block; T7 adds an E2E check that fails on any CSP violation on the note sheet (research §A.2).
- **Naming:** v1 approvals are an **"authenticated approval"**, not a "digital signature" (research §B.5). The Bangladesh ICT Act 2006 builds advanced-signature conditions into "electronic signature", and our approval is bound by the server, not by a key under the signer's sole control. The PDF/A seal (E04) and optional CA e-Sign per step are the upgrade path.
- **Industry practice adopted** (research §B.4): remarks freeze once the memo moves on; a private, auto-saved draft remark; corrections as new entries rather than recall-and-edit; main versus copy recipients, copies read-only; read = the recipient opened the memo; stamps record the authentication event, not a picture. **Do better:** mentions (none of the studied products have them), receipts shown to the sender, shares with a reason and an expiry, and a server-side check of the document hash under each stamp.

---

## R4: Server-side .docx templating

All sources accessed 2026-11-15. "Unconfirmed" means I could not check the claim against a primary source. Version dates come from the Maven Central directory listings (`repo1.maven.org/maven2/<group>/<artifact>/`) and GitHub's REST API (`api.github.com/repos/<owner>/<repo>`, `/releases`, `/commits`, `/contributors`).

### Recommendation in one paragraph

Use **poi-tl** (Apache-2.0, on Apache POI) with its **default expression engine and never SpEL**. Admins type `{{memo.number}}`-style tags in Word. The approval block is a table row with `{{approvals}}` above it and `[approver]`, `[decision]`, `[decidedAt]` in the row. Before poi-tl sees an upload, a scanner of our own (POI-based) sanitises it, lists its tags and checks them. Feed poi-tl only a `Map<String, Any>` of strings, plus lists of maps for the row loop, and never domain objects. Keep ONLYOFFICE for DOCX→PDF(/A) conversion only, not for templating. Make docx4j plain `${}` replacement or an in-house POI engine the fallback. Pin the poi-tl version, and budget for a fork, because the last poi-tl release is from 2024.

### Comparison table

| Option | Latest stable (date) | Licence | Health | Admin syntax in Word | Split runs | Repeating table rows | Code-executing EL | Fit |
|---|---|---|---|---|---|---|---|---|
| **poi-tl** | 1.12.2 (2024-01-25); 1.12.3-beta1 (2024-03-13) | Apache-2.0 | One maintainer; commits up to 2026-10-13; no release for 20 months | `{{name}}`, `{{@img}}`, `{{?section}}…{{/section}}` | Handled (`RunningRunBody` merges runs) | `LoopRowTableRenderPolicy`, `[field]` in the row | Default: dot-path only. SpEL is opt-in (`useSpringEL()`) | Good. Java 8 bytecode; POI 5.2.2+ |
| **docx4j** (plain `${}` / MERGEFIELD / OpenDoPE) | 17.3.6 (2026-11-08); 11.5.x line also maintained | Apache-2.0 | Very active (weekly releases); one main maintainer | `${name}`, MERGEFIELD, or content controls bound by XPath | `${}`: only after `VariablePrepare`, "simple cases only". Content controls: not affected | OpenDoPE repeat content controls | XPath only; no Java EL | Good. Java 11 bytecode; JAXB (Jakarta) |
| **Apache POI XWPF, direct** | 6.0.0 (2026-10-19 on Central); 5.5.1 (2025-11-26) | Apache-2.0 | Apache PMC; commits 2026-11-13 | Whatever we implement | We implement it | We implement it | None | Good. POI 6 needs Java 17+ |
| **docx-stamper** (original) | 1.4.0 (2019-09-20) | MIT | Inactive (last commit 2023-08-22) | `${spel}` | — | — | Full SpEL | Not viable |
| **office-stamper** (maintained fork) | 3.8 (2026-11-13) | MIT | Active; one main maintainer | `${spel}`; repeats written as **Word comments** (`repeatTableRow(x)`) | Handled (preprocessor turns them into smart tags) | Comment `repeatTableRow(list)` | SpEL. "Restricted" mode by default, but instance methods still allowed | Java 25 bytecode; docx4j-based |
| **XDocReport** | 2.2.0 (2026-02-05) | MIT | Moderate; 362 open issues | MERGEFIELD containing Freemarker/Velocity (`$x`, `[#list]`) | Recommends MERGEFIELD to avoid it | `FieldsMetadata.addFieldAsList` | Freemarker/Velocity. Needs heavy lock-down | Java 17 bytecode |
| **ONLYOFFICE Document Builder** (`/docbuilder` of our Docs 10.0) | Docs 10.0.0 (GitHub release 2026-10-12) | AGPL-3.0 (separate service: allowed) | Vendor-maintained | We define it (text search or content controls) | `SearchAndReplace` acts on document text (behaviour across runs unconfirmed) | `ApiTable.AddRow` | Our own JS script; the template itself is data | Async HTTP; whether Community includes it is unconfirmed |

Sources for the table:
- Maven listings: [poi-tl](https://repo1.maven.org/maven2/com/deepoove/poi-tl/), [docx4j-core](https://repo1.maven.org/maven2/org/docx4j/docx4j-core/), [poi-ooxml](https://repo1.maven.org/maven2/org/apache/poi/poi-ooxml/), [docx-stamper](https://repo1.maven.org/maven2/org/wickedsource/docx-stamper/docx-stamper/), [office-stamper engine](https://repo1.maven.org/maven2/pro/verron/office-stamper/engine/), [XDocReport docx](https://repo1.maven.org/maven2/fr/opensagres/xdocreport/fr.opensagres.xdocreport.document.docx/).
- GitHub repos: [Sayi/poi-tl](https://github.com/Sayi/poi-tl), [plutext/docx4j](https://github.com/plutext/docx4j), [apache/poi](https://github.com/apache/poi), [thombergs/docx-stamper](https://github.com/thombergs/docx-stamper), [verronpro/office-stamper](https://github.com/verronpro/office-stamper), [opensagres/xdocreport](https://github.com/opensagres/xdocreport), [ONLYOFFICE/DocumentServer](https://github.com/ONLYOFFICE/DocumentServer/releases).
- Bytecode levels: I read them from the class-file headers of the downloaded jars: docx4j-core 17.3.6 is major 55 (Java 11), office-stamper engine 3.8 is major 69 (Java 25), and XDocReport 2.2.0 is major 61 (Java 17). The poi-tl Java 8 / POI 5.2.2 requirement is from the [poi-tl docs](https://deepoove.com/poi-tl/).

---

### 1. Versions, licences, maintenance

- **poi-tl**
  - The last stable release is 1.12.2, on Maven Central since 2024-01-25. The GitHub release [v1.12.2](https://github.com/Sayi/poi-tl/releases) is dated the same day.
  - 1.12.3-beta1 followed on 2024-03-13 ([Maven](https://repo1.maven.org/maven2/com/deepoove/poi-tl/)).
  - The licence is Apache-2.0 ([POM](https://repo1.maven.org/maven2/com/deepoove/poi-tl/1.12.2/poi-tl-1.12.2.pom)).
  - The master branch is `1.13.0-SNAPSHOT` on POI 5.4.0, with commits through 2026-10-13. They cover run-level content controls, a markdown fix that includes path-traversal hardening, and a SpEL blank-tag fix ([commits](https://github.com/Sayi/poi-tl/commits/master), [pom.xml](https://github.com/Sayi/poi-tl/blob/master/pom.xml)).
  - It is effectively one maintainer: Sayi has 776 commits, and the next contributor has 4 ([contributors](https://github.com/Sayi/poi-tl/graphs/contributors)). The repo has about 5.2k stars and 64 open issues.
  - Risks:
    - There has been no release for 20 months.
    - Whether 1.12.2 works with POI 5.4, 5.5 or 6.0 is **unconfirmed**. It was built against 5.2.2.
- **docx4j**
  - 17.3.6 was released 2026-11-08, and there are 10 releases between 2026-09-21 and 2026-11-08 ([Maven](https://repo1.maven.org/maven2/org/docx4j/docx4j-core/), [CHANGELOG](https://github.com/plutext/docx4j/blob/VERSION_17_3_7/CHANGELOG.md)).
  - The licence is Apache-2.0 ([POM](https://repo1.maven.org/maven2/org/docx4j/docx4j-core/17.3.6/docx4j-core-17.3.6.pom)).
  - 17.0.0 (2026-06-29) changed the API: `getContent` replaces `getEGBlockLevelElts`, and more ([CHANGELOG](https://github.com/plutext/docx4j/blob/VERSION_17_3_7/CHANGELOG.md)).
  - Jason Harrop (plutext) has about 5,700 commits. Recent changelog entries also list AI co-contributors.
  - The transitive dependencies need a licence audit: JAXB runtime, xalan, antlr 2.7.7, stringtemplate 3.2.1, mbassador ([POM](https://repo1.maven.org/maven2/org/docx4j/docx4j-core/17.3.6/docx4j-core-17.3.6.pom)).
- **Apache POI**
  - 6.0.0 has been on Maven Central since 2026-10-19. The changes page gives 2026-10-22 and says "Minimum Java version is now JDK 17" ([changes](https://poi.apache.org/changes.html)).
  - The licence is Apache-2.0.
  - It is maintained by the Apache PMC, with commits on 2026-11-13 ([GitHub](https://github.com/apache/poi)).
- **docx-stamper / office-stamper**
  - The original [docx-stamper](https://github.com/thombergs/docx-stamper) last released 1.4.0 in 2019 and last committed on 2023-08-22.
  - The fork, [office-stamper](https://github.com/verronpro/office-stamper), released 3.8 on 2026-11-13 (GitHub release v3.8.0 on 2026-11-14). Its licence is MIT ([POM](https://repo1.maven.org/maven2/pro/verron/office-stamper/3.8/office-stamper-3.8.pom)).
  - It is effectively one maintainer: caring-coder has 1,558 commits. It has 39 stars.
  - It depends on docx4j-core and `spring-expression` 7.0.8 ([engine POM](https://repo1.maven.org/maven2/pro/verron/office-stamper/engine/3.8/engine-3.8.pom)).
- **XDocReport**
  - 2.2.0 was released 2026-02-05; the previous release, 2.1.0, was 2024-06-17 ([Maven](https://repo1.maven.org/maven2/fr/opensagres/xdocreport/fr.opensagres.xdocreport.document.docx/)).
  - The licence is MIT ([xdocreport-parent POM](https://repo1.maven.org/maven2/fr/opensagres/xdocreport/xdocreport-parent/2.2.0/xdocreport-parent-2.2.0.pom)).
  - It has two main maintainers (angelozerr, pascalleclercq) and 362 open issues ([GitHub](https://github.com/opensagres/xdocreport)).
- **ONLYOFFICE Document Builder**
  - The `/docbuilder` endpoint is documented for Docs ([Document Builder API](https://api.onlyoffice.com/docs/docs-api/additional-api/document-builder-api/)).
  - From 10.0 it can be switched off with `services.CoAuthoring.server.docbuilderEnable`.
  - Community is AGPL-3.0 ([README editions table](https://github.com/ONLYOFFICE/DocumentServer/blob/master/Readme.md)).
  - Whether the **Community** package includes the docbuilder service is **unconfirmed**. Third-party mirrors of older edition tables disagree, and a forum answer says the built-in builder accepts POST requests. Check it empirically on our 10.0.0.1 instance.
- **Others considered**
  - [YARG](https://github.com/cuba-platform/yarg) (Apache-2.0, on docx4j): last release 2.2.22 on 2025-01-24, and pulls in a reporting framework. Not a serious candidate.
  - [docxtemplater](https://github.com/open-xml-templating/docxtemplater): JavaScript, so it would mean a Node sidecar, and several of its modules are paid. Included below for its syntax only.
  - Carbone was not evaluated, because its licence is not on our list (unconfirmed).

### 2. Placeholder syntax an admin can type, and the split-run problem

Word splits typed text into several `<w:r>` runs when spell-check, revision IDs (rsid) or formatting changes land mid-word. As a result, `{{memo.number}}` is often stored as `{{memo.` + `number}}`.

- **poi-tl**
  - Tags are plain typed text: `{{var}}` (text), `{{@var}}` (image), `{{#var}}` (table), `{{*var}}` (list), `{{?var}}…{{/var}}` (section), `{{+var}}` (nested) ([docs](https://deepoove.com/poi-tl/)).
  - Split runs are handled: `RunningRunBody` "finds the tag boundaries, merges the runs that hold one tag into a single run" ([source](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/resolver/RunningRunBody.java)). This is applied to paragraphs and to content inside content controls ([TemplateResolver](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/resolver/TemplateResolver.java)).
  - In default mode a tag's content must match `((#)?[\w一-龥]+(\.[\w一-龥]+)*)?`, i.e. names and dots only ([Configure.java](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/config/Configure.java)).
  - Repeating rows: `{{approvals}}` goes in the row above, and the template row uses `[name]`. The bracket delimiters can be changed through `LoopRowTableRenderPolicy(prefix, suffix)` ([docs](https://deepoove.com/poi-tl/), [source](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/policy/LoopRowTableRenderPolicy.java)).
- **docx4j**
  - Plain `${KEY}` needs `VariablePrepare`. Its own Javadoc says it "works in simple cases only", "won't work if your KEY is split across separate runs … (which often happens)", and cannot add table rows. It recommends XPath-bound content controls instead ([source](https://github.com/plutext/docx4j/blob/VERSION_17_3_7/docx4j-core/src/main/java/org/docx4j/model/datastorage/migration/VariablePrepare.java)).
  - MERGEFIELD merge exists (`MailMerger`).
  - OpenDoPE uses content controls with `w:dataBinding` XPaths, plus repeat and condition controls ([CHANGELOG](https://github.com/plutext/docx4j/blob/VERSION_17_3_7/CHANGELOG.md)).
- **office-stamper**
  - `${expression}` is typed text, and a preprocessor converts it into smart-tag runs, which removes the split-run problem ([how-it-works](https://github.com/verronpro/office-stamper/blob/main/engine/src/site/asciidoc/how-does-it-works.adoc)).
  - Loops and conditions go in **Word comments**, for example `repeatTableRow(employees)` ([advanced features](https://github.com/verronpro/office-stamper/blob/main/engine/src/site/asciidoc/advanced-features.adoc)).
- **XDocReport**
  - Its docs advise inserting a MERGEFIELD (Ctrl+F9, then edit the field, then MergeField, then `$project.Name`). Typed text "can work, but you can have trouble if you style the field" ([DocxDesignReport](https://github.com/opensagres/xdocreport/wiki/DocxDesignReport)).
  - Lists are declared in code with `addFieldAsList` ([wiki](https://github.com/opensagres/xdocreport/wiki/DocxReportingJavaMainListFieldInTable)).
- **POI direct**: we would write the run-merging ourselves, about the job `RunningRunBody` does.
- **ONLYOFFICE**: the script uses `ApiDocument.SearchAndReplace`, `GetContentControlsByTag` and `ApiTable.AddRow` ([office-js-api examples](https://github.com/ONLYOFFICE/office-js-api/tree/master/Examples/Word)). How it treats text split across runs is unconfirmed.
- **Content controls for admins**
  - Word hides the Developer tab by default ([Microsoft](https://support.microsoft.com/en-us/office/show-the-developer-tab-e1192344-5e56-4d45-931b-e5fd9bea2d45)). Content controls, including Repeating Section, live there ([Create a fillable form](https://support.microsoft.com/en-us/office/create-a-fillable-form-39a58412-107e-426b-a10b-ac44937e3a9f)).
  - A Microsoft Q&A volunteer says repeating-section controls cannot be created on Mac without programming ([Q&A](https://learn.microsoft.com/en-us/answers/questions/2168784/how-to-create-a-repeating-table-in-a-word-form); not official).
  - Binding a control to an XPath needs the XML Mapping pane or an add-in. In my judgement this is not realistic for bank administrators; it is unconfirmed by any study.

### 3. Fidelity with real letterheads

- **poi-tl**
  - Tags "can appear anywhere, including headers, footers, table cells, text boxes" ([docs](https://deepoove.com/poi-tl/)). The resolver walks the body, headers, footers and footnotes ([TemplateResolver](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/resolver/TemplateResolver.java)).
  - Untouched content is kept as is, because POI edits the XML in place. A logo that is already in the template is unaffected.
  - The inserted text takes the tag run's formatting (unconfirmed from the docs; that is how the merged run works in the source).
  - Fields such as PAGE and NUMPAGES are left alone, and the converter computes them.
- **docx4j**: JAXB keeps unknown content. 11.5/17.x include many fixes for "read as Word reads it", e.g. `mc:AlternateContent` and repair modes ([CHANGELOG](https://github.com/plutext/docx4j/blob/VERSION_17_3_7/CHANGELOG.md)).
- **office-stamper**: docx4j-based, so fidelity is similar.
- **XDocReport**: it preprocesses the XML into a Freemarker/Velocity template and then merges. Fidelity is good for simple layouts; a measured comparison is unconfirmed.
- **ONLYOFFICE**: the document is opened and re-saved by the ONLYOFFICE engine, so the whole file is re-serialised. This is the same engine that does our PDF conversion, which keeps output consistent, but it may normalise Word-specific constructs (unconfirmed).
- **Right-to-left and complex scripts**
  - Text inserted into an existing run keeps that run's `w:rtl` and `w:cs` properties, in any library that edits runs in place.
  - POI's high-level API exposes little bidi support, so any new paragraphs we create would need low-level CT* access (unconfirmed).
  - poi-tl 1.12.2 has no RTL documentation. Out of scope for now; record it as a test case for later.

### 4. Security with a partly trusted template author

- **Expression languages**
  - **poi-tl**
    - The default `DefaultELRenderDataCompute` evaluates `a.b.c` only. It reads a `Map` key, a JavaBean getter, or a declared field by reflection ([Dot.java](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/expression/Dot.java), [Configure.java](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/config/Configure.java)).
    - SpEL runs only after `useSpringEL()`. It then uses a `StandardEvaluationContext` with no restrictions, so `T(...)`, constructors and bean references all work. SpEL mode also widens the tag regex to any characters ([SpELRenderDataCompute](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/render/compute/SpELRenderDataCompute.java), [ConfigureBuilder](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/config/ConfigureBuilder.java)).
    - Hardening:
      - Never call `useSpringEL`, and add an ArchUnit/detekt rule that bans it.
      - Do not put `spring-expression` near the template code. It is on the classpath anyway through Spring, so the ban has to be in code review and static checks.
      - Pass only `Map<String, Any?>` of `String` and `List<Map>`, so getter and field reflection cannot reach domain objects.
  - **office-stamper**
    - The default is the deprecated `restrictedFactory`: no type locator and no constructors. However, it keeps `DataBindingMethodResolver.forInstanceMethodInvocation()` and `DataBindingPropertyAccessor.forReadWriteAccess()`, so templates can call instance methods and write properties on the context objects.
    - The system property `officestamper.spel.mode=permissive` switches to the permissive mode ([EvaluationContextFactories](https://github.com/verronpro/office-stamper/blob/main/engine/src/main/java/pro/verron/officestamper/api/EvaluationContextFactories.java), [OfficeStamperConfigurations](https://github.com/verronpro/office-stamper/blob/main/engine/src/main/java/pro/verron/officestamper/preset/OfficeStamperConfigurations.java)).
    - The original docx-stamper advertises "the full feature set of SpEL" ([README](https://github.com/thombergs/docx-stamper)).
  - **XDocReport / Freemarker**: Freemarker's FAQ says to let users upload templates only if they are "highly trusted personnel". Otherwise use `TemplateClassResolver.ALLOWS_NOTHING_RESOLVER` and a `WhitelistMemberAccessPolicy`. It warns that `SAFER_RESOLVER` "is not restrictive enough" ([Freemarker FAQ #23](https://freemarker.apache.org/docs/app_faq.html)). Velocity has the same class of problem (unconfirmed for its current defaults).
  - **docx4j OpenDoPE**: uses XPath over our own XML data, with no Java method calls. An XPath 2/3 engine (Saxon) is optional ([CHANGELOG](https://github.com/plutext/docx4j/blob/VERSION_17_3_7/CHANGELOG.md)).
  - **ONLYOFFICE**: the script is ours and the template is data. The document is still parsed by the C++ converter, which had "40+ security fixes in 10.0" ([README](https://github.com/ONLYOFFICE/DocumentServer/blob/master/Readme.md)).
- **XXE**
  - POI's XMLBeans options set `setDisallowDocTypeDeclaration(true)` and `setEntityExpansionLimit(1)` ([POIXMLTypeLoader](https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/ooxml/POIXMLTypeLoader.java)).
  - Its JAXP and StAX factories set secure processing, empty `ACCESS_EXTERNAL_*` and no external entities ([XMLHelper](https://github.com/apache/poi/blob/trunk/poi/src/main/java/org/apache/poi/util/XMLHelper.java)).
  - docx4j changelogs record XXE fixes and a refactored StAX configuration, "central to secure operation" (11.5.6) ([CHANGELOG](https://github.com/plutext/docx4j/blob/VERSION_17_3_7/CHANGELOG.md)).
- **Zip bombs**
  - POI's `ZipSecureFile` defaults:
    - minimum inflate ratio 1%;
    - maximum entry size 4 GB;
    - maximum file count 1000;
    - maximum text size 10 M characters.
    - Sources: [javadoc](https://poi.apache.org/apidocs/dev/org/apache/poi/openxml4j/util/ZipSecureFile.html), [source](https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipSecureFile.java).
  - These limits are **JVM-global statics**, so lower the entry size at startup. POI 6.0.0 adds further zip-bomb hardening ([changes](https://poi.apache.org/changes.html)).
  - docx4j defaults to 50 MB per part and 50 MB total, and a ratio of 500. The ratio is checked only when loading from a `File` ([CHANGELOG 11.5.6](https://github.com/plutext/docx4j/blob/VERSION_17_3_7/CHANGELOG.md)).
- **External relationships**
  - OOXML allows `TargetMode="External"` on:
    - image links (`r:link`);
    - `attachedTemplate` in `settings.xml.rels` (remote template injection, [MITRE ATT&CK T1221](https://attack.mitre.org/techniques/T1221/));
    - hyperlinks, OLE links, `subDocument` and frames.
  - Fields such as `INCLUDEPICTURE`, `INCLUDETEXT`, `LINK` and `DDEAUTO` also reference outside resources.
  - None of the libraries removes these by default (unconfirmed for each; I found no such option in the docs).
  - Whether ONLYOFFICE's converter fetches external images during conversion is **unconfirmed**.
  - Hardening: our sanitiser refuses or strips every external relationship except `hyperlink`, refuses those field codes, and refuses `altChunk`, `oleObject`, ActiveX and embedded packages.
- **Macros**
  - Refuse any package whose main part content type is `…document.macroEnabled.main+xml`, or that contains `vbaProject.bin`, `vbaData.xml` or ActiveX parts.
  - Accept only the `.docx` and `.dotx` main content types. For `.dotx`, rewrite the content type to `.docx` (unconfirmed whether POI does this; easy with OPC).

### 5. Validating a template on upload

In this order:

1. **Package checks** with POI OPC:
   - size limit of about 10 MB;
   - `ZipSecureFile` limits;
   - allowlist of content types;
   - the macro, external-relationship and field-code refusals from section 4.

   Save the **re-serialised, sanitised** package as the stored template.
2. **Tag inventory**:
   - Call poi-tl `XWPFTemplate.compile(...)`, then `getElementTemplates()`, which returns `MetaTemplate`s covering the body, headers, footers and footnotes ([XWPFTemplate](https://github.com/Sayi/poi-tl/blob/master/poi-tl/src/main/java/com/deepoove/poi/XWPFTemplate.java)).
   - Check each tag name against the memo type's field allowlist, refuse unknown tags, and refuse any tag sign except plain text, `?` and `@` for the logo.
   - The `[field]` tags inside a loop row are resolved only at render time, so the scanner must read the row after a loop tag itself (unconfirmed; verify in a spike).
3. **Broken tags**:
   - Separately, join the `w:t` text of each paragraph across runs, then regex-search it for `{{` without a matching `}}`, for `{{…}}` that does not match the grammar (poi-tl leaves those as literal text), and for stray `[`/`]` in loop rows.
   - Report each problem with its location: the part (header1, body), the paragraph number and a text excerpt.
4. **Test render** with synthetic data: fill every allowlisted field with a sentinel value, render, convert to PDF through ONLYOFFICE, and show the admin a preview before activating the template.

### 6. Performance and memory

- I found no published benchmark for any option, so all of the following is **unconfirmed** and needs a spike measurement.
- POI and docx4j load the whole package into memory as an object model. For a 2–5 page letterhead under 1 MB, I expect tens of milliseconds per fill once warm, and a few tens of MB of short-lived heap. docx4j's JAXB context costs a one-off startup time.
- ONLYOFFICE docbuilder is an asynchronous HTTP job that has to fetch the template and the script by URL ([API](https://api.onlyoffice.com/docs/docs-api/additional-api/document-builder-api/)). Expect seconds rather than milliseconds (unconfirmed), and it takes capacity from the editing and conversion work on Community's single-process server ([README](https://github.com/ONLYOFFICE/DocumentServer/blob/master/Readme.md)).
- The repo memory notes a 2 GiB test heap. Template filling should run on a bounded executor with a timeout.

### 7. Kotlin/JVM fit and post-processing

- poi-tl, docx4j, POI and XDocReport are plain Java and easy to call from Kotlin.
- office-stamper is compiled for Java 25, which matches our runtime.
- Post-processing is needed in every case:
  - remove leftover empty paragraphs from blank optional fields (poi-tl `{{?x}}` sections help);
  - keep fields dirty, or rely on the converter for PAGE and NUMPAGES;
  - then convert to PDF/A through ONLYOFFICE. PDF/A validation is outside R4.
- The archival re-fill uses the same stored sanitised template and the frozen memo data. It is deterministic if the data map is snapshot-based.
- POI is not yet a backend dependency. pdfbox 3.0.8 already is (`gradle/libs.versions.toml`). poi-tl pulls POI and batik-transcoder ([POM](https://repo1.maven.org/maven2/com/deepoove/poi-tl/1.12.2/poi-tl-1.12.2.pom)), so exclude batik unless SVG images are needed.

### How comparable tools expose placeholders

- **Word mail merge (MERGEFIELD)**: Microsoft says to pick the data source before inserting merge fields, through Mailings > Insert Merge Field ([Insert mail merge fields](https://support.microsoft.com/en-us/word/insert-mail-merge-fields)). That is familiar to office staff but awkward without a data file. We could ship a sample CSV, but poi-tl does not read MERGEFIELDs.
- **Content controls**: need the Developer tab, and the repeating section is Windows-only in practice (see section 2).
- **docxtemplater**: typed `{tag}` with `{#loop}…{/loop}`, where the loop markers sit in the first and last cell of a table row to repeat it ([docs](https://docxtemplater.com/docs/tag-types/)).
- **Easiest for admins**: typed tags copied from an in-app list. This is my judgement, unconfirmed by any study. A "copy tag" button in our template admin page beside each field removes typing errors.

### Recommendation

- **Library**: poi-tl 1.12.2 on POI 5.x, pinned. Run a 1-day spike to check 1.12.2 against POI 5.5.1 and 6.0.0, and keep a fork-ready vendored build. Fallback: a thin POI XWPF engine of our own, about 300–500 lines, reusing the run-merge approach. docx4j is the second choice if poi-tl turns out incompatible with current POI.
- **Syntax to publish to admins**:
  - `{{memo.number}}`, `{{memo.date}}`, `{{initiator.name}}`, `{{meta.<fieldKey>}}`;
  - `{{?meta.x}}…{{/meta.x}}` for optional blocks;
  - `{{@tenant.logo}}` only if the logo is data rather than part of the letterhead;
  - the approval table: `{{approvals}}` in a row above the template row, and `[name]`, `[role]`, `[decision]`, `[decidedAt]`, `[comment]` in the template row.
  - Use English ASCII keys only.
- **Hardening checklist**:
  1. Never `useSpringEL()`; enforce it with an ArchUnit test.
  2. Data is maps of pre-formatted strings only.
  3. Configure `useDefaultEL(true)` and `AbortHandler`, so a missing or invalid tag fails the render instead of silently printing blank.
  4. Set `ZipSecureFile` limits at startup: inflate ratio 1%, entry size about 20 MB, file count about 500.
  5. Sanitise on upload: no macros, ActiveX, OLE or altChunk, no external relationships except hyperlinks, no `attachedTemplate`, no INCLUDE/LINK/DDE fields.
  6. Store and render only the sanitised copy.
  7. Validate the tag allowlist per memo type, and run a test render plus PDF preview before activation.
  8. Render on a bounded executor with a timeout.
  9. Allowlist dependency licences in CI.
- **Do not use**:
  - office-stamper, XDocReport or docx-stamper, because their expression languages can call code;
  - ONLYOFFICE docbuilder for filling, because of the unconfirmed Community availability, the latency, and the extra AGPL-service coupling;
  - OpenDoPE content controls as the admin-facing syntax.

### Could not verify

- Whether poi-tl 1.12.2 works with POI 5.4, 5.5 or 6.0.
- Whether poi-tl's `getElementTemplates()` includes loop-row `[…]` tags.
- Whether Community 10.0 ships `/docbuilder`.
- Whether ONLYOFFICE's converter fetches external images.
- All performance and memory figures.
- RTL behaviour.
- How admins actually prefer to insert placeholders.

---

## R9: rich text in an append-only note sheet, and industry practice

All web sources accessed 2026-11-15. "Measured locally" means I installed the packages in a scratch directory and inspected or bundled them myself (method in A.4). "Unconfirmed" means no primary source was read.

### Summary of recommendations

- **Editor:** Tiptap 3 (3.34.0), using only MIT packages. Set `injectCSS: false`. Lazy-load the editor on the compose screen only.
- **Storage:** ProseMirror/Tiptap JSON against a strict, versioned schema (`noteDoc/v1`), validated in Kotlin and stored as `jsonb`. Next to it, store server-derived plain text and the mention ids.
- **Rendering:**
  - Web: our own React renderer walks the JSON tree. No `dangerouslySetInnerHTML`, and the reader does not load the editor bundle.
  - PDF/A: the same tree walk, driving PDFBox.
- **Not HTML + OWASP sanitizer:** sanitiser bypasses keep being found (four GHSAs in November 2026 alone), and the server would have to parse HTML for the PDF anyway.
- **Not Markdown:** the JS and Java parsers would differ, there is no standard mention syntax, and a WYSIWYG round trip loses information.

---

## Part A: the editor and the storage format

### A.1 Candidates: version, licence, health

| | Tiptap 3 | ProseMirror (plain) | Lexical | Slate (for reference) | Quill (for reference) |
|---|---|---|---|---|---|
| Latest stable | 3.34.0, 2026-11-13 [1] | model 1.25.13 (2026-11-08), view 1.42.9 (2026-10-31) [2] | 0.53.0, 2026-10-28 [3] | 0.147.4 (2026-10-26), slate-react 0.147.6 [4] | 2.0.3, 2024-11-30 [5] |
| Licence | MIT for every package we need (core, react, pm, starter-kit, extension-list, extension-link, extension-mention, suggestion, extensions) [1] | MIT [2] | MIT [3] | MIT [4] | BSD-3-Clause [5] |
| Release cadence | 6 releases from 2026-10-28 to 2026-11-13 [1] | view: 6 patch releases from 2026-09-18 to 2026-10-31 [2] | about monthly: 0.48–0.53 from 2026-07 to 2026-10 [3] | active | last release Nov 2024 [5] |
| Repository | github.com/ueberdosis/tiptap: 39k stars, 937 open issues, last push 2026-11-13 [6] | Forgejo at code.haverbeke.berlin. The GitHub mirrors are **archived** (last push 2026-04-01) [7][8] | github.com/meta/lexical (moved from facebook/lexical): 24k stars, 522 open issues, last push 2026-11-14 [9] | 31.7k stars; README says "Slate is currently in beta" [10] | 663 open issues, last push 2025-07-25 [6q] |
| npm downloads, week to 2026-11-13 | @tiptap/core 36.7M [11] | prosemirror-view 40.0M [11] | lexical 8.9M [11] | – | – |
| Stability | 3.x semver | 1.x semver | **0.x**: the 0.53.0 changelog has a "Breaking changes" section (measured locally, `lexical/CHANGELOG.md`) | 0.x, beta | effectively unmaintained |

**Paid versus free in Tiptap**
- The pricing page says "The Tiptap Editor is open source (MIT) and free" and "Only platform features and cloud documents are priced". [12]
- The paid features are collaboration cloud, Comments, AI, DOCX/PDF import and export, document history and compare, page layouts, and the REST APIs. [12]
- None of those are needed for R9.
- Mention, Link, lists, Bold, Italic, Placeholder and UndoRedo all ship in the MIT npm packages listed above. [1]
- I could not open per-extension plan badges: the extensions overview page gave no plan labels when fetched. [13]
- **Rule:** do not add any package from Tiptap's private registry. Allow only `@tiptap/*` from the public npm registry and check licences in CI.

Slate (beta, 0.x) and Quill (no release for two years) are **not serious contenders**, and they are not analysed further.

### A.2 Behaviour under our CSP (`style-src 'self'`, no `'unsafe-inline'`)

**What the browser blocks**

According to MDN, without `'unsafe-inline'` these are blocked: [14]
- `<style>` elements;
- `style="..."` attributes in markup;
- `el.setAttribute("style", …)`;
- `el.style.cssText = …`.

Setting a single property, `el.style.display = "none"`, is **not** blocked. [14]

Two points are unconfirmed:
- whether every browser blocks `cssText` (MDN says yes; I did not test it);
- whether parsing pasted HTML into an inert document raises violations.

The test below covers both.

### Tiptap 3

- **Injects a `<style>` element.**
  - `@tiptap/core` has `createStyleTag()`, which runs `document.createElement("style")` and appends it to `<head>` when `injectCSS: true`. That is the default (measured locally, `@tiptap/core/dist/index.js` lines 4150–4158 and 6610). The docs confirm `injectCSS` can turn this off. `injectNonce` exists only for nonce-based CSPs. [15]
  - **Action:** pass `injectCSS: false`. Then copy Tiptap's ~40 lines of core CSS (`.ProseMirror { position: relative; white-space: pre-wrap; … }`, `img.ProseMirror-separator`, `.ProseMirror-gapcursor`) into our Tailwind stylesheet.
- **Never writes a `style` attribute** in any `@tiptap/*` package (measured locally: no `setAttribute("style"…)` outside `createStyleTag`).
- **Inherits ProseMirror's `cssText` uses** (next section).

### ProseMirror

- **Never injects a `<style>` element.** The project expects you to include `prosemirror-view/style/prosemirror.css` yourself.
- **Uses `style.cssText` in four places** in `prosemirror-view/dist/index.js` (measured locally):
  1. Applying decorations that carry a `style` attribute (lines 1766–1769). Avoid this by using class-only decorations.
  2. An iOS Safari workaround for list markers (lines 2174–2177).
  3. Clipboard fallbacks `captureCopy` and `capturePaste` (lines 3732, 3782). These run only when the Clipboard API path is unavailable.
  4. Node attributes from a schema's `toDOM` go through `setAttribute` (prosemirror-model line 3449). A `style` attribute would be blocked, so **our schema must never emit `style`**. Tiptap's text-style, colour and alignment extensions do; we do not use them.
- **Effect:** under our feature set the only expected hits are the iOS workaround and the old clipboard paths. They would degrade silently rather than break editing. This is unconfirmed until tested.

### Lexical

- **Never injects a `<style>` element** and never writes a `style` attribute.
- **Uses CSSOM only:** `style.setProperty(...)` for indentation (line 5650) and `setDOMStyleFromCSS()` for text styles (measured locally, `lexical/dist/Lexical.dev.js`). Both are allowed.
- **Theme:** we supply class names and CSS.

### What we ship ourselves, whichever editor
- Editor base CSS (above).
- Classes for lists, links, mention chips and the placeholder. Tiptap's Placeholder uses `data-placeholder` with a `::before { content: attr(data-placeholder) }` rule in our CSS.
- The mention popup, positioned with `el.style.left/top` property sets (allowed).

**Test to add:** a Playwright spec that registers a `securitypolicyviolation` listener. It then types, formats, pastes (HTML from Word or a browser), inserts mentions and links, and undoes. It asserts zero violations in Chromium, Firefox and WebKit.

### A.3 Accessibility and RTL

**Tiptap 3**
- **Keyboard:** ProseMirror's base keymap, plus list keymaps (`ListKeymap`) and undo/redo. Mark shortcuts (Mod-B, Mod-I) are built in. [16]
- **Screen readers:** `role="textbox"` is set by default (measured locally, `getEditorViewAttributes`). [16]
- **ARIA:**
  - We must add `aria-multiline`, `aria-label` and `aria-describedby` through `editorProps.attributes`.
  - The suggestion plugin has **no ARIA** (measured locally: no `aria-` strings in `@tiptap/suggestion`). We build the combobox and listbox: `aria-expanded`, `aria-controls` and `aria-activedescendant` on the editor, `role="option"` and `aria-selected` on items.
  - Tiptap's guide says the editor is headless and accessibility of the UI is ours, and recommends `role="toolbar"` for the toolbar. [16]
- **RTL:** a `textDirection` editor option ('ltr', 'rtl', 'auto') and a TextDirection extension (`setTextDirection`). [15] (also measured locally, core lines 2773, 6279)

**ProseMirror**
- **Keyboard:** same keymaps, which we wire ourselves.
- **Screen readers and ARIA:** native contenteditable semantics only. Everything else is ours.
- **RTL:** native bidi; we set the `dir` attribute through view or node attributes ourselves.

**Lexical**
- **Keyboard:** rich-text plugin keymap.
- **Screen readers:** `ContentEditable` accepts the full aria-* prop set (aria-label, aria-multiline, aria-activedescendant, aria-expanded and more). It also has `useLexicalAriaLiveRegion`.
- **ARIA:** `LexicalTypeaheadMenuPlugin` sets `role="option"`, `aria-selected`, `aria-controls` and `aria-activedescendant` (measured locally, `@lexical/react/dist`).
- **RTL:** direction is detected per block (`__dir`), serialised as `direction`.

Docs:
- Lexical's intro claims "accessibility" without detail. [17]
- Tiptap's guide is generic: "make sure…". [16]

Neither project publishes a WCAG conformance statement (unconfirmed for Lexical beyond the intro page).

**Net:** Lexical gives more ARIA wiring out of the box. With Tiptap we build the mention combobox's ARIA ourselves, about one component.

### A.4 Bundle size (measured locally)

**Method**
- esbuild, minified ESM.
- `react` and `react-dom` external, since we already ship them.
- `NODE_ENV=production`, with conditions `module, browser, production`, as Vite uses.
- gzip level 9.
- Feature set: paragraphs, bold, italic, hard break, bulleted and numbered lists, link, mention with suggestion, undo/redo, placeholder.

| Setup | min | gzip | brotli |
|---|---|---|---|
| Tiptap 3.34: individual extensions + `@tiptap/extension-list` + Link + Mention + `@tiptap/extensions` (UndoRedo, Placeholder) + `@tiptap/react` | 418.8 KiB | **135.2 KiB** | 115.9 KiB |
| Tiptap 3.34: StarterKit + Mention (more than we need) | 433.7 KiB | 139.0 KiB | 118.8 KiB |
| Plain ProseMirror: model, state, view, schema-list, keymap, history, commands, inputrules. No mention UI or link logic, which we would write | 211.6 KiB | **65.8 KiB** | 57.6 KiB |
| Lexical 0.53: composer, rich-text, list, link, history, typeahead menu | 282.5 KiB | **92.5 KiB** | 79.1 KiB |

Where Tiptap's size goes (min):
- prosemirror-view 96 KiB;
- @tiptap/core 91 KiB;
- prosemirror-model 44 KiB;
- prosemirror-transform 32 KiB;
- extension-list 22 KiB;
- linkifyjs 20 KiB (pulled in by extension-link).

**Mitigation:** load the editor as a lazy route chunk, used only when someone writes a remark. The read view never imports it (A.7). The 135 KiB is then paid once, by authors, and is cached.

### A.5 React 19

| | React 19 |
|---|---|
| `@tiptap/react` 3.34.0 | peer `react: ^17 \|\| ^18 \|\| ^19` [1] |
| `@lexical/react` 0.53.0 | peer `react: >=18.x` [3] |
| ProseMirror | framework-free. We would write the React binding, or use a community one (not evaluated). |

### A.6 Mentions: support and how they are stored

**Tiptap**
- `@tiptap/extension-mention` is an inline atom node with attributes `id`, `label` and `mentionSuggestionChar` (measured locally, `addAttributes`).
- It renders as `<span data-type="mention" data-id=…>`.
- Docs cover `renderText`, `renderHTML`, `deleteTriggerWithBackspace` and multiple triggers. [18]
- **Stored JSON:**
  ```json
  {"type":"mention","attrs":{"id":"<user uuid>","label":"Rahim Uddin","mentionSuggestionChar":"@"}}
  ```

**ProseMirror**
- No mention package. We define an atom node and a suggestion plugin. That is the same JSON shape if we copy Tiptap's.

**Lexical**
- No official package: `@lexical/mention` returns 404 on npm (measured locally).
- The playground's `MentionNode` extends `TextNode` and stores `mentionName` (a display name, not an id). [19] We would write our own DecoratorNode that carries a user id.

**Rule for us:**
- The id is authoritative.
- The label is a display snapshot taken at the time of writing. It is kept because the archive must show the name as it was.
- The server resolves the ids for notifications.

### A.7 Storage format decision

| Criterion | PM/Tiptap JSON with a strict schema | Restricted HTML + OWASP sanitizer | Markdown (CommonMark) |
|---|---|---|---|
| XSS when rendering in React | Our renderer maps known node types to React elements. Text is escaped by React. The only sink is `href`, re-checked on render. Safe by construction. | Needs `dangerouslySetInnerHTML` (plus DOMPurify as a second layer) or an HTML-to-React parse. The sanitiser is the only barrier. | Needs a Markdown renderer with raw HTML disabled, plus link checks. |
| Server validation | Typed tree walk: allow-list, attributes, limits. Exact and simple. | Sanitiser policy. It is active (Apache-2.0, 16 releases in 2026, last 20261110.1 [20][21]), but **four security advisories were fixed in November 2026** (GHSA-76cc-622x-jwm4, -x759-2xw8-gp4m, -ppfr-7xq6-p6c2, -643j-x48g-g86p) [21]. This shows how large the HTML attack surface is. | Parse with commonmark-java 0.32.0 (BSD-2-Clause) [22]. The editor (JS) and server (Java) parsers can disagree. |
| Mentions | A typed node carrying an id | A `<span data-user-id>` convention, which the sanitiser must allow and the server must parse | No standard syntax. A custom extension is needed in both parsers. |
| Plain text for full-text search | Concatenate text nodes, `@label` for mentions, newlines between blocks | Needs an HTML parse | Needs a parse, or noisy raw text |
| PDF/A with PDFBox | Same tree walk: runs of (text, bold, italic, link) inside paragraphs and list items | Parse HTML into a tree first (jsoup or similar), then the same as JSON | Parse into an AST, then the same |
| Schema evolution over years | An explicit `schemaVersion`. Old versions are never rewritten (append-only) and renderers handle every version. Small, closed set of types. | HTML has no version, and the allowed subset drifts with sanitiser releases | CommonMark spec versions and extensions drift |
| Editor round trip | Native (`editor.getJSON()` / `setContent`) | Native (`getHTML`), but the parse is lossy at the edges | Lossy. Needs a Markdown serialiser. |

**Decision:** JSON. HTML is never stored and never rendered from stored data.

### A.8 Recommended schema `noteDoc/v1` and the server rules (Kotlin)

### Envelope stored per note entry
- `body jsonb`: the document.
- `body_schema int`: 1.
- `body_text text`: derived by the server, never accepted from the client. Feeds `tsvector` and the existing `remark` length check (currently 1–4000 characters in `workflow/V1__engine.sql`).
- **`note_mention(entry_id, user_id)` rows:** derived by the server; they drive notifications.

### Allow-list (anything else is rejected, not stripped, so the author sees an error)

| Type | Allowed content | Attributes |
|---|---|---|
| `doc` | 1..200 blocks: `paragraph`, `bulletList`, `orderedList` | none |
| `paragraph` | inline*: `text`, `hardBreak`, `mention`, `memoRef` | none (or `textDirection` ∈ {null, "ltr", "rtl", "auto"} if we enable it) |
| `bulletList` | 1..100 × `listItem` | none |
| `orderedList` | 1..100 × `listItem` | `start` int 1..9999. `type` must be null. |
| `listItem` | `paragraph`, then optional nested lists | none |
| `text` | `text`: string, 1..4000 chars, NFC-normalised, no C0/C1 controls (`\n` included), no lone surrogates | `marks` ⊆ {bold, italic, link}, no duplicates |
| `hardBreak` | none | none |
| `mention` | atom | `id`: UUID of a user **in the same tenant** (checked with RLS on). `label`: 1..200 chars. `mentionSuggestionChar` = "@". |
| `memoRef` (internal memo link, as an atom node rather than a link mark) | atom | `memoId`: UUID. `label`: the snapshot reference number, ≤ 100 chars. |
| `link` mark | | `href`: must parse as an absolute `https:` URI, no userinfo, ≤ 2048 chars. `target`, `rel` and `class` are dropped, and the renderer adds `rel="noopener noreferrer nofollow"`. |

### Limits
- 64 KiB serialised JSON.
- Nesting depth ≤ 8 JSON levels (list nesting ≤ 3).
- ≤ 2,000 nodes in total.
- ≤ 4,000 characters of derived plain text (matches today's limit).
- ≤ 20 distinct mentions, ≤ 20 memoRefs, ≤ 20 links.
- **Unknown JSON keys rejected**, as in Jackson `FAIL_ON_UNKNOWN_PROPERTIES` on a sealed-class model.
- **Jackson read constraints:** set `StreamReadConstraints` nesting and length limits on this endpoint.
- **Empty content rejected:** no empty text nodes, and the document must contain some text.
- Parse into a Kotlin sealed-class AST (`NoteNode`). The same AST feeds plain-text extraction, mention extraction and the PDF renderer.
- The repository's networknt `json-schema-validator` 3.0.8 could check the structure, but limits and cross-checks are clearer in code.

### Semantic checks
- A mention of a user who cannot read the memo is **rejected**, with a "share first?" prompt. A mention never grants access implicitly.
- A `memoRef` to a memo the author cannot read is rejected.
- Rendering also hides the details of a memoRef from readers without access: show "Memo (restricted)".

### Rendering
- **Web:** a ~100-line React component switching on `type`. No `dangerouslySetInnerHTML`. The client re-checks that `href` starts with `https:`. `memoRef` renders as a router `<Link>`.
- **PDF/A:** walk the AST and lay out runs with regular, bold and italic embedded fonts (PDF/A requires embedded fonts). Bullets and numbers are drawn. A mention prints as `@label`. A memoRef prints its reference number. A link prints its text followed by the URL in a footnote or in brackets. PDFBox 3.0.8 is already in the repo.

### Evolution
- `body_schema` is set per entry and never migrated in place, because entries are append-only.
- Adding a node type means v2. The v1 validator and renderers stay forever.
- Add golden-file tests per version (JSON → plain text, JSON → PDF text extraction).

### A.9 Editor recommendation

**Tiptap 3 (3.34.x), MIT packages only:**
- `@tiptap/react`, `@tiptap/pm`;
- Document, Paragraph, Text, Bold, Italic, HardBreak;
- `@tiptap/extension-list` (Bullet, Ordered, ListItem, ListKeymap);
- `@tiptap/extension-link` with `autolink: false`, `protocols` limited, and `isAllowedUri` set to https-only;
- `@tiptap/extension-mention` with `@tiptap/suggestion`;
- a custom `memoRef` node;
- `@tiptap/extensions` (UndoRedo, Placeholder).

**Configuration:** `injectCSS: false`, `enableInputRules`/`enablePasteRules` scoped to our marks, and `editorProps.attributes` for ARIA.

**Why Tiptap over the alternatives**
- **Versus plain ProseMirror:** the same document model and JSON, with maintained mention, suggestion, link and list keymaps and a React 19 binding. It costs about 70 KiB gzip more, which lazy loading absorbs.
- **Versus Lexical:**
  - Lexical is 1.x-stable in neither API nor serialised format (0.x).
  - It has no first-party mention node; the playground's example stores a name, not an id.
  - Its JSON carries editor-internal fields (`format` bitmask, `style` string, `detail`, `mode`, `indent`, `version`), which are worse for a decades-long archive.
  - Lexical's advantages are smaller size (92 vs 135 KiB gzip), better built-in ARIA, and CSSOM-only styling. They are real, but the archive argument wins.

**Fallback:** if Tiptap ever moves a needed piece behind a paid plan, drop to plain ProseMirror with the same JSON, so no data migration is needed.

---

## Part B: how established products handle these features

Delegated web research. Some primary sites (nothi.gov.bd release notes, the D-Nothi vendor guide, docs.eoffice.gov.in, EUR-Lex, the CCA e-Sign manual) refused connections or were unreadable. Claims taken from search snippets are marked unconfirmed.

### B.1 e-Nothi / D-Nothi (Bangladesh, a2i)

Sources: D-Nothi FAQ pages on office sub-domains [B1][B2].

- **Note sheet (নোটাংশ / অনুচ্ছেদ)**
  - A nothi has a note part and a letter part (পত্রাংশ). A letter can be cloned into a new note. [B1]
  - **Attachments:** a wrong attachment can only be changed before sending; "once sent, there is no edit or delete option". [B1]
  - **Corrections:** done by *recall* ("নথি ফেরত আনুন"), and only until midnight the same day and only if the recipient has not opened the file. After recall the note can be edited. [B2]
  - Draft auto-save in newer releases: unconfirmed. Rich text and @mentions: not documented (unconfirmed).
- **Copy-to (অনুলিপি) and issuing letters (পত্রজারি)**
  - Main and copy recipients are kept apart. Others "can only view and archive", and only copy recipients can archive an incoming letter. [B1]
  - An approver must approve the draft letter before it is issued (unconfirmed, snippet).
  - **What counts as "read":** opening the letter detail. After that, recall is blocked. There is a sending-status ("প্রেরণ অবস্থা") view. [B1][B2]
- **Access**
  - Granted per nothi to *designations* (posts), not to people, via "অনুমতি সংশোধন" (edit permission). A new holder of a post inherits its pending work. [B1]
  - Security classification: unconfirmed.
- **Signature and stamp**
  - The signature is an uploaded image (150×50 px), with name and designation printed under it. Signature order follows designation level. [B1]
  - The CCA e-Sign manual has a section on using remote CA e-Sign in D-Nothi, after e-KYC (unconfirmed, snippet [B3]).

### B.2 India NIC e-Office (eFile)

Sources: eFile manual (CAG) [B4], CAG course material [B5], e-Office FAQ [B6].

- **Note sheet**
  - **Green note** is the noting itself. **Yellow note** is a draft note for getting a reporting officer's approval before the green note is final. [B5]
  - **Yellow note actions:** Edit, Discard or Confirm. Confirming converts it to green. [B4]
  - A yellow note "cannot be digitally signed", and its "history … is discarded once it has been converted into Green note". [B6 FAQ 2.18, 2.52]
  - "The Green Note cannot be changed after the file has been moved". [B6 FAQ 2.31]
  - New notes appear "just after the signature stamp of the last comments". [B6 FAQ 2.19]
  - **Rich text and attachments:** a rich-text editor with tables and hyperlinks (pasted links are kept as plain text), auto-save with a last-saved time, and PDF attachments on a note up to 25 MB. [B5][B6 FAQ 2.30–2.35]
  - No mentions.
- **Copy-to**
  - One "To" recipient. CC copies are numbered x(1), x(2), for example 2345(1)/2015-Admin. [B6 FAQ 1.24–1.49]
  - Approved drafts (DFA) are dispatched after approval, by self or through the Central Registry Unit (CRU). [B4][B5]
  - **What counts as "read":** unread items are bold. "Pull back" works only until the recipient opens the item; "pull up" takes it back forcibly. [B6 FAQ 2.2, 9.5]
- **Access**
  - Incoming dak (mail) is graded Normal, Confidential, Secret or Top Secret. [B4]
  - Search across the hierarchy "downline". Superiors see subordinates' inboxes. [B6 FAQ 8.4–9.6]
  - Only the file's creator can close it. [B4]
  - Login is with an OTP as the second factor. [B5]
- **Signature**
  - A DSC token enables "Sign and Send". A signed note shows a green tick on the name stamp. [B4]
  - FAQ: "Does the DSC get verified at the server end? No." [B6 FAQ 2.34]
  - Aadhaar eSign uses an OTP. [B5]

### B.3 Microsoft Power Automate approvals and SharePoint eSignature

- **Remarks**
  - Each response records responder (id, name, UPN), response, comments, and request and response dates (UTC). The creator is always shown "to prevent spoofing". [B7]
  - Markdown works in request *details* only, with uneven client support. [B8]
  - No CC concept. [B7]
- **Access:** Purview sensitivity labels classify content, can encrypt it to named users or groups, add watermarks, and require a justification to lower a label. [B9]
- **Signature**
  - SharePoint eSignature says it "uses simple electronic signatures as defined under… eIDAS". [B10]
  - After signing, an audit trail is appended to the PDF, which is then "digitally signed by a Microsoft certificate". [B11]
  - Viewed, signed and downloaded events go to the Purview audit log. [B10]

### B.4 Feature-by-feature: adopt and do better

| Feature | Adopt | Do better |
|---|---|---|
| **1. Note sheet** | Freeze a remark once the memo moves (e-Office). Append below the last stamp. A draft remark like the yellow note, auto-saved with a last-saved time. PDF attachments on a remark. | Keep remark drafts as a private, audited record (e-Office discards them). Corrections as new entries that reference the original, instead of recall-and-edit (D-Nothi). Sanitised rich text via the JSON schema. Add @mentions, which none of them have. |
| **2. Copy-to** | Main versus copy recipients, with copies limited to view and archive (D-Nothi). Numbered copies (e-Office). Issue after approval (potrojari, DFA). | Choose per copy recipient: "at submit, for information" or "after final approval". Name the list source (a saved distribution list or individuals). **Read = the recipient opened the memo body view**, recorded per recipient with time. Show it to the sender, not only use it to block recall. |
| **3. Access** | Grants bound to posts or positions, so successors inherit (D-Nothi). A classification level per memo (e-Office, Purview). | Deny by default: route participants plus copy recipients plus explicit grants. No hierarchy-wide "downline" reading (e-Office) unless the tenant configures unit-scope readers. Shares are logged, need a reason, can be revoked and can expire. Require a justification to lower a classification. |
| **4. Stamps and e-signature** | The stamp shows name, designation, time and a "verified" marker (e-Office green tick). An audit trail appended to the final PDF and sealed with a platform certificate (SharePoint). Optional CA e-Sign per step later. | Record the authentication event, not just a picture (D-Nothi): user, position, acting-for, time, auth method and MFA freshness (step-up), session id, IP, user agent, and SHA-256 of the document version and attachments. Verify on the server and show the result (e-Office does not). |

### B.5 Legal angle: what the sources say (not legal advice)

**Bangladesh ICT Act 2006 (Act 39 of 2006)** [B12]
- **s.2(1) definition** (Bangla text, which prevails under s.90): an electronic signature must be
  - uniquely linked to the signatory;
  - capable of identifying the signatory;
  - created by means under the signatory's control;
  - linked to the data so that later changes are detectable.
- **s.5:** a *subscriber*, meaning the holder of a certificate, may authenticate a record with an electronic signature.
- **s.6:** an electronic record satisfies a requirement for writing.
- **s.7:** where a law requires a signature, it may be electronic "in the prescribed manner".
- **ss.16–17:** a "secure" electronic signature. Under s.17(2) it is treated as invalid if the record is altered.
- **ss.18–22, 36:** the Controller (CCA) and licensed certifying authorities. CCA lists, among others, Bangladesh Bank, BCC and Dohatec as licensed CAs. [B13]
- **Amendments:** the 2009 and 2013 amendment footnotes were not mapped to the signature sections (unconfirmed). No evidential presumption clause was found in ss.5–17 (unconfirmed).

**eIDAS (Reg. 910/2014, as amended by 2024/1183)** [B14]
- Art. 3(10): simple electronic signature.
- Art. 3(11) with Art. 26: advanced, meaning uniquely linked, identifying, created with data under the signatory's sole control, and linked so changes are detectable.
- Art. 3(12): qualified, meaning advanced plus a QSCD (qualified signature creation device) and a qualified certificate.
- Art. 25(1): a signature is not denied legal effect solely because it is electronic or not qualified. Art. 25(2): a qualified signature equals a handwritten one.
- The article wording is from the Commission FAQ and known text; EUR-Lex was blocked (unconfirmed as fetched).

**What an "e-signature v1" (an MFA-authenticated approval, logged with a hash, no PKI) can and cannot claim.** This is interpretation, not legal advice.
- **It can claim:**
  - a *simple electronic signature* in eIDAS terms, which is what Microsoft claims for a comparable design [B10];
  - an authenticated, time-stamped approval record with an integrity hash, kept under ICT Act s.6 (writing) and s.9 (retention).
- **It cannot claim:**
  - an eIDAS *advanced* or *qualified* signature, because the server, not the signer, controls the binding;
  - to be an ICT Act s.5 or s.7 electronic signature by a "subscriber" with a certificate from a CCA-licensed CA. Because the Bangladesh definition builds advanced-style conditions into "electronic signature" itself, the product should call it an **"authenticated approval"**, not a "digital signature".
- **Upgrade path:**
  - seal the archived PDF/A with an organisation certificate and an RFC 3161 timestamp;
  - later, offer optional remote e-Sign from a CCA-licensed CA per route step.

---

### Sources (R9 and industry)

[1] npm registry, @tiptap/core, react, starter-kit, extension-mention, extension-link, extension-list, extensions, suggestion, pm: https://registry.npmjs.org/@tiptap/core (and the same path for each package); https://www.npmjs.com/package/@tiptap/react
[2] https://registry.npmjs.org/prosemirror-model, https://registry.npmjs.org/prosemirror-view
[3] https://registry.npmjs.org/lexical, https://registry.npmjs.org/@lexical/react; release v0.53.0: https://github.com/facebook/lexical/releases
[4] https://registry.npmjs.org/slate, https://registry.npmjs.org/slate-react
[5] https://registry.npmjs.org/quill
[6] https://api.github.com/repos/ueberdosis/tiptap ; [6q] https://api.github.com/repos/slab/quill
[7] https://prosemirror.net/ ("being developed on Forgejo", code link to https://code.haverbeke.berlin/prosemirror)
[8] https://api.github.com/repos/ProseMirror/prosemirror (archived: true), https://api.github.com/repos/ProseMirror/prosemirror-view (archived: true)
[9] https://api.github.com/repos/facebook/lexical (redirects to meta/lexical)
[10] https://github.com/ianstormtaylor/slate/blob/main/Readme.md
[11] https://api.npmjs.org/downloads/point/last-week/lexical (and the same for prosemirror-view, @tiptap/core)
[12] https://tiptap.dev/pricing
[13] https://tiptap.dev/docs/editor/extensions/overview
[14] https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/style-src
[15] https://tiptap.dev/docs/editor/api/editor (injectCSS, injectNonce, textDirection)
[16] https://tiptap.dev/docs/guides/accessibility
[17] https://lexical.dev/docs/intro
[18] https://tiptap.dev/docs/editor/extensions/nodes/mention
[19] https://github.com/facebook/lexical/blob/main/packages/lexical-playground/src/nodes/MentionNode.ts
[20] https://repo1.maven.org/maven2/com/googlecode/owasp-java-html-sanitizer/owasp-java-html-sanitizer/maven-metadata.xml ; licence https://github.com/OWASP/java-html-sanitizer
[21] https://github.com/OWASP/java-html-sanitizer/releases
[22] https://repo1.maven.org/maven2/org/commonmark/commonmark/0.32.0/commonmark-0.32.0.pom
[B1] https://blri.nothi.gov.bd/FAQ
[B2] https://dnc.nothi.gov.bd/FAQ
[B3] https://cca.portal.gov.bd/sites/default/files/files/cca.portal.gov.bd/policies/5d41e2a0_011d_4898_a3ce_1c1639665714/2024-04-02-04-35-29f3dbcd007952dc8acbf1ed08db6610.pdf (unconfirmed: TLS error, snippet only)
[B4] https://cag.gov.in/uploads/media/eofficeManual-063919e87cf2040-11483959.pdf
[B5] https://cag.gov.in/uploads/course_material/CourseMaterial-0614eb862c670b7-44714923.pdf
[B6] https://edudel.nic.in/edu/e_offince_dt_11122024/EOfficeFAQ.pdf
[B7] https://learn.microsoft.com/en-us/connectors/approvals/
[B8] https://learn.microsoft.com/en-us/power-automate/approvals-markdown-support
[B9] https://learn.microsoft.com/en-us/purview/sensitivity-labels
[B10] https://learn.microsoft.com/en-us/microsoft-365/documentprocessing/esignature-overview
[B11] https://learn.microsoft.com/en-us/microsoft-365/documentprocessing/esignature-review-sign-requests?view=o365-worldwide
[B12] http://bdlaws.minlaw.gov.bd/act-details-950.html
[B13] https://cca.gov.bd/
[B14] https://ec.europa.eu/digital-building-blocks/sites/display/DIGITAL/eSignature+FAQ ; https://eur-lex.europa.eu/eli/reg/2014/910/oj (blocked when fetched)
