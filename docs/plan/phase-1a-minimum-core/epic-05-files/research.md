# E05 Files service — research

All sources were accessed on **2026-11-02**.

## R1 — Versions, licences, maintenance

| Component | Current | Licence | Decision |
|---|---|---|---|
| Google Tink (`com.google.crypto.tink:tink`) | 1.24.0 (2026-10-16); 1.23.0 (2026-07-09) | Apache-2.0 | **Adopt 1.24.0** (R14). Runtime deps: `protobuf-java` 4.33.x (BSD-3), `gson` (Apache-2.0), `jsr305`, `error_prone_annotations`; `google-http-client` and Conscrypt are optional and not pulled. Google-maintained, monthly to quarterly releases |
| Apache Tika (`tika-core`) | 4.2.0 (2026-10-28), 4.1.0 (2026-09-29), 4.0.0 (2026-08-22); 3.3.3 (2026-10-09) on the supported 3.x line | Apache-2.0 | **Adopt 4.1.0**: 4.x is the current line (Java 17+; we run 25). 4.2.0 is five days old; Renovate will propose it after the usual waiting period. Magic detection only, no parsers and no zip-container detector (R15) |
| Apache Commons Compress | 1.28.0 (2025-07-26) | Apache-2.0 | **Adopt** directly: `ZipFile` over a seekable channel reads the central directory of zips for our OOXML/ODF check |
| Apache PDFBox | 3.0.8 (2026-07-08) | Apache-2.0 | **Adopt 3.0.8** for PDF inspection at upload with limits (R15) |
| Apache POI (`tika-parser-microsoft-module`) | via Tika 4.1.0 | Apache-2.0 | **Not adopted.** Tika's OOXML and OLE2 container detectors live in this module, which pulls POI, XMLBeans and more (≈ 20 MB, large parse surface). We read `[Content_Types].xml` ourselves (R15) |
| ClamAV (`clamav/clamav`) | 1.5.4 (already pinned in Compose) | GPL-2.0, separate unmodified service | Keep; already in the licence register as a service |
| clamd Java clients | `xyz.capybara:clamav-client` 2.1.2 (last release 2022-02); `fi.solita.clamav:clamav-client` 1.0.1 (2016) | MIT / LGPL | **Not adopted**: both inactive. The `INSTREAM` protocol is a few lines over a socket; we own a small client with tests and a fuzz target |
| AWS SDK for Java v2 (`s3`) | 2.57.8 (2026-10-30); we pin 2.55.16 | Apache-2.0 | Already in the build; Renovate keeps it current. Multipart upload and ranged `GetObject` are core features |
| SeaweedFS | 4.48 pinned | Apache-2.0 | Keep (ADR-0005); object lock behaviour in R15 |
| Jazzer (`com.code-intelligence:jazzer-junit`) | 0.30.0 (2026-02-24) | Apache-2.0 | **Adopt (test only)** for the nightly fuzz target. Code Intelligence paused open-source maintenance in 2025 and has since returned Jazzer to open source under Apache-2.0; it runs continuously in OSS-Fuzz |

Sources: https://repo1.maven.org/maven2/com/google/crypto/tink/tink/1.24.0/tink-1.24.0.pom, https://repo1.maven.org/maven2/org/apache/tika/tika-core/, https://tika.apache.org/4.0.0/index.html, https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/, https://repo1.maven.org/maven2/xyz/capybara/clamav-client/, https://repo1.maven.org/maven2/com/code-intelligence/jazzer-junit/, https://code-intelligence.com/blog/jazzer-back-to-open-source, https://github.com/CodeIntelligenceTesting/jazzer

## R14 — Envelope encryption with range reads

Requirements: per-tenant keys wrapped by a key-encryption key (KEK) behind a `KeyProvider` (software in 1a, KMS/HSM in 1b); authenticated encryption; decrypt an arbitrary byte range without reading the object from the start; streaming (never the whole file in memory); vetted library rather than our own format (overview risk R4).

| Option | Range decrypt | Format / maturity | Key handling | Risk |
|---|---|---|---|---|
| **Tink `StreamingAead`** (`AES256_GCM_HKDF`) | Yes: `newSeekableDecryptingChannel(SeekableByteChannel, aad)`; each segment is an independent GCM unit, so a range read touches only its segments | Published wire format, designed for cloud object storage; Google-maintained | Keysets with key rotation (key id in the ciphertext prefix); keysets can be written encrypted under any `Aead` (the KEK) | Low; protobuf on the classpath |
| Bouncy Castle with our own segmented AES-GCM | Yes, if we design it | Our own format: nonce derivation, segment ordering, truncation detection all ours | Ours | High: exactly the crypto design risk R4 warns about |
| AWS Encryption SDK (raw AES keyring) | No seekable decryption; framed format is streamed from the start | Mature | Data key per message, keyrings | Range requests would decrypt from byte 0 every time |

Properties of Tink's AES-GCM-HKDF streaming:
- A per-ciphertext key is derived with HKDF from the keyset key and a random salt in the header, so **one tenant key safely encrypts many files**; nonces are a per-file prefix plus segment counter with a last-segment flag, which detects truncation and reordering.
- The 1 MB template is the recommended default; a 4 KB template exists for memory-constrained systems. The overhead is a 16-byte tag per segment plus a small header.
- Associated data is authenticated, not encrypted: we bind `tenantId/fileId/versionId`, so a ciphertext copied to another tenant's or file's row does not decrypt.

**Segment size:** the PDF viewer (Phase 5) and media seeking issue small ranges (PDF.js defaults to 64 KiB chunks). With 1 MiB segments a 64 KiB range costs up to 2 MiB of storage reads and decryption; with 64 KiB segments, at most 128 KiB, at a 0.02 % size overhead. We use custom parameters with **64 KiB ciphertext segments** (`AesGcmHkdfStreamingParameters`, AES-256, HKDF-SHA256); the parameters are stored per key, so a later change only affects new files.

**Key hierarchy** (APIs verified in the 1.24.0 jar: `AesGcmHkdfStreamingParameters.Builder.setCiphertextSegmentSizeBytes`, `TinkProtoKeysetFormat.serializeEncryptedKeyset(keyset, kekAead, associatedData)`): install KEK (from `KeyProvider`) → per-tenant streaming keyset (wrapped, stored in the database) → per-file derived key (inside Tink). The software KEK provider derives an AES-256-GCM key from the existing `MEMO_DATA_KEY` with HKDF and a distinct `info` label, so 1a adds no new secret; 1b adds PKCS#11 / OpenBao Transit providers behind the same interface. Rotation: adding a new primary key to the tenant keyset changes only new files; re-wrapping the keyset under a new KEK is a single row update per tenant.

**Recommendation:** Tink streaming AEAD (ADR-0028).

Sources: https://developers.google.com/tink/streaming-aead, https://developers.google.com/tink/wire-format, https://github.com/tink-crypto/tink-java, https://github.com/mozilla/pdf.js/blob/master/src/display/api.js (`rangeChunkSize` 65536)

## R15 — Upload pipeline, detection, scanning, object lock

### Where plaintext may exist
AGENTS §3.4: scan **before** encryption, never store plaintext at rest. Three places would silently write plaintext to disk and must be avoided:
1. **Servlet multipart** (`StandardServletMultipartResolver`) spools parts larger than the threshold to temp files. We do not use multipart: the upload body is the raw file stream, and Spring multipart is switched off.
2. **nginx** buffers request bodies larger than `client_body_buffer_size` to temp files unless `proxy_request_buffering off`. Our web image and the edge (Caddy) also cap bodies at 1 MB today. The upload route needs streaming and a higher cap.
3. **PDFBox scratch files** hold plaintext. Inspection uses main memory with a hard cap instead and rejects files that exceed it (below).

### Pipeline options
| Option | Plaintext at rest | Storage calls | Failure handling |
|---|---|---|---|
| A. Stream to `work` staging (encrypted), scan in parallel, server-side copy to `records` on clean | No | Multipart upload + CopyObject (+ lock headers on copy, not documented by SeaweedFS) | Staging objects to clean up |
| **B. One pass: hash + scan + encrypt to a local ciphertext spool file; inspect by decrypting the spool; upload the ciphertext once accepted** | No (spool is ciphertext) | One upload to the final bucket | Spool deleted in `finally`; startup sweep |
| C. Buffer whole file in memory, scan, encrypt, upload | No | One upload | Heap exhaustion; violates "stream files" |

**Recommendation: B.** The request body is read once; each chunk goes to the SHA-256 digest, to clamd (`INSTREAM`) and into Tink's encrypting channel writing a spool file under a private temp directory. Inspection (container detection, PDF checks) reads the spool through a seekable decrypting channel. Accepted files are uploaded from the spool (multipart for large ones), and nothing that was rejected ever reaches object storage — which matters because objects in the locked `records` bucket cannot be deleted.

### clamd
- Protocol: `zINSTREAM\0`, then chunks prefixed with a 4-byte big-endian length, then a zero-length chunk; reply `stream: OK`, `stream: <name> FOUND` or `... ERROR`. clamd closes the connection when `StreamMaxLength` is exceeded.
- Defaults in ClamAV 1.5.4 (`clamd.conf.sample`): `StreamMaxLength` 100M, `MaxFileSize` 100M, `MaxScanSize` 400M, `MaxRecursion` 17, `MaxFiles` 10000, `MaxScanTime` 120000 ms; heuristic alerts are **off** by default.
- We ship our own `clamd.conf` and turn on: `AlertExceedsMax yes` (files beyond the limits come back as `Heuristics.Limits.Exceeded` instead of silently "clean"), `AlertEncrypted yes` (encrypted archives and PDFs cannot be scanned), `AlertOLE2Macros yes` (VBA in OLE2 documents), `AlertBrokenExecutables yes`. Limits are set from our upload ceiling (`StreamMaxLength` ≥ the platform maximum upload size).
- **Memory:** the ClamAV image needs 3 GiB minimum, 4 GiB preferred; clamd uses over 1.2 GiB with signatures loaded and over 2.4 GiB briefly during the daily reload. `ConcurrentDatabaseReload no` avoids the peak but blocks scans during the reload. **Dev VM measured 2026-11-02:** 1.6 GiB used, 6.3 GiB available of 7.8 GiB, so ClamAV fits with the default concurrent reload and a 3 GiB container limit (overview Q7).
- Signatures: the image runs `freshclam`, which needs outbound HTTPS to the ClamAV mirror. Air-gapped on-prem installs need a private mirror (`cvdupdate`); runbook item.
- Tests: the `_base` image variant has no signatures. A test database directory with a small custom signature file (EICAR plus our own test markers) starts clamd in seconds; heuristics (limits, macros, encryption) are engine features and work without the official database. The nightly run uses the full database.

### Content detection
- `tika-core` magic detection identifies PDF, images, OLE2 (generic `application/x-tika-msoffice`), zip, OOXML packages (`application/x-tika-ooxml`, from `[Content_Types].xml` at offset 30) and ODF (from the stored `mimetype` entry), from the first bytes. Tika's zip-container detector (`tika-parser-zip-commons`) is **not** used: it can spool the stream to a temporary file through `TikaInputStream.getPath()`, which would put plaintext on disk (found in T4). Distinguishing **DOCX from DOCM** (macro-enabled) needs OOXML container detection, which Tika keeps in `tika-parser-microsoft-module` (Apache POI).
- We instead read `[Content_Types].xml` from the zip (streamed, entry-count, size and ratio limits, XML without DTDs or external entities) and map the main part's content type to the OOXML media types, including the macro-enabled variants (`application/vnd.ms-word.document.macroEnabled.12`, etc.). Macro-enabled types are never on an allow-list.
- Legacy OLE2 formats (`.doc`, `.xls`, `.ppt`) cannot be told apart, or checked for macros, without POI. In 1a no purpose allows them; ClamAV's `AlertOLE2Macros` is the second line. Phase 2 decides for memo attachments (convert through ONLYOFFICE, or adopt the POI detector).
- The declared file name extension must agree with the detected type (a PDF named `.png` is rejected as `TYPE_MISMATCH`).

### PDF inspection
PDFBox 3 loads from a `RandomAccessRead`; we adapt the seekable decrypting channel to it, so no plaintext copy is made. a memory-only stream cache with a byte cap (`MemoryUsageSetting.setupMainMemoryOnly(cap).streamCache`) bounds memory and writes no scratch files; exceeding it rejects the file. Checks: parses, not encrypted, page count ≤ the purpose limit. The parse runs on a small dedicated executor with a timeout; PDFBox ignores interrupts, so the bounded pool, the memory cap and the size limit are what contain a runaway parse.

### SeaweedFS object lock
- Object lock must be enabled at bucket creation (ADR-0005 does this) and needs versioning. GOVERNANCE (admins and holders of `s3:BypassGovernanceRetention` may override) and COMPLIANCE (nobody may delete or shorten) are both supported; legal hold is supported; maximum 100 years.
- The documentation does not cover CopyObject with lock headers or multipart uploads with lock headers. Option B avoids CopyObject; PUT with lock headers and `PutObjectRetention` extension are confirmed by integration tests against the pinned SeaweedFS before relying on them.
- **Orphans:** an object written to `records` before its database row commits cannot be deleted if the commit fails. We write record objects with a **short provisional retention** (7 days), commit, then extend retention to the final date (the `app` identity already has `PutObjectRetention`). A retry job extends any version whose lock is still provisional; a sweep removes pending uploads whose commit never happened once their provisional lock has expired.

### Hostile-file limits (summary)
| Limit | Where | Default |
|---|---|---|
| Upload size | per purpose, capped by the tenant setting `files.upload.max-bytes` and the platform ceiling | purpose-specific; ceiling 100 MiB |
| Concurrent uploads per node | semaphore | 8; beyond that `503` with `Retry-After` |
| Upload rate | Bucket4j (ADR-0025) | 30 per minute per user |
| Zip container read | our OOXML reader | 2,000 entries, `[Content_Types].xml` ≤ 1 MiB, compression ratio ≤ 100 |
| Archive recursion, files, scan size, scan time | clamd | 10 levels, 10,000 files, 400 MiB, 120 s; `AlertExceedsMax` |
| PDF | PDFBox | main memory ≤ 64 MiB, pages ≤ purpose limit (default 5,000), 20 s |
| Detection and inspection time | executor timeout | 20 s |

### How leaders handle uploads
- Google Drive scans files for malware only up to 100 MB; larger files and some types are stored unscanned and the user sees a "can't scan this file" warning at download. Board portals advertise encryption at rest and antivirus scanning of board materials without publishing limits. We do better on three points: a file we cannot scan is **rejected**, never stored with a warning; rejected files never reach storage; and record objects are object-locked, so even an operator cannot overwrite them.

Sources: https://docs.clamav.net/manual/Installing/Docker.html, https://raw.githubusercontent.com/Cisco-Talos/clamav/clamav-1.5.4/etc/clamd.conf.sample, https://docs.clamav.net/manual/Usage/Scanning.html, https://github.com/seaweedfs/seaweedfs/wiki/S3-Object-Lock-and-Retention, https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html, https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_request_buffering, https://caddyserver.com/docs/caddyfile/directives/request_body, https://docs.spring.io/spring-boot/reference/web/servlet.html, https://tika.apache.org/4.0.0/index.html, https://support.google.com/drive/answer/141702

## Range requests (download)
- RFC 9110 §14: a server may ignore `Range` and send `200`; multiple ranges may be refused. We serve **single ranges only** (`206` with `Content-Range`), answer multi-range requests with the full `200`, and an unsatisfiable range with `416` and `Content-Range: bytes */<size>`. `If-Range` with the strong `ETag` (version id + SHA-256) falls back to `200` when it does not match.
- Caddy's `encode` compresses responses; compressing a `206` or a stored PDF is wasted work and can break range arithmetic in clients. File content responses send `Content-Encoding: identity` semantics by being excluded from `encode` in the edge config, and nginx does not gzip `/api/`.

Sources: https://www.rfc-editor.org/rfc/rfc9110#name-range-requests, https://caddyserver.com/docs/caddyfile/directives/encode
