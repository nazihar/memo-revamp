# E05 Files service — log

## 2026-11-02
- E04 merged and deployed; nightly green. Plan written: research, ADR-0028 (envelope encryption) and ADR-0029 (upload pipeline and range downloads), design, threat model, tasks, test plan. Gate delegated (overview Q1).
- Findings:
  - **Encryption:** Tink streaming AEAD (1.24.0) with one keyset per tenant, wrapped by a KEK from a `KeyProvider`. The 1a software provider derives the KEK from `MEMO_DATA_KEY` with its own HKDF label, so no new secret. 64 KiB ciphertext segments (a supported custom parameter, checked in the 1.24.0 jar) keep a 64 KiB range read to at most two segments.
  - **Pipeline:** one pass over the body (SHA-256, clamd `INSTREAM`, encryption into a local ciphertext spool), inspection by decrypting the spool, upload only after acceptance. Nothing rejected reaches storage, which matters because `records` objects cannot be deleted. Record objects get a 7-day provisional lock that is extended after the database commit, so a failed commit cannot leave an orphan locked for 10 years.
  - **Plaintext traps found:** Spring multipart temp files, nginx request buffering, and PDFBox scratch files. Multipart is disabled, the upload route streams, and PDFBox runs memory-only with a cap. Both nginx and Caddy currently cap bodies at 1 MB; the upload route gets the ceiling.
  - **Detection:** Tika 4.1.0 core + zip-commons. Tika's OOXML detector needs Apache POI, so we read `[Content_Types].xml` ourselves (fuzzed) to tell DOCX from DOCM. Legacy `.doc`/`.xls`/`.ppt` are not allowed by any 1a purpose; Phase 2 decides.
  - **ClamAV:** heuristics are off by default; our `clamd.conf` turns on `AlertExceedsMax`, `AlertEncrypted`, `AlertOLE2Macros`, `AlertBrokenExecutables`. No maintained Java clamd client exists, so we own a small one.
  - **Dev VM memory (Q7):** 1.6 GiB used, 6.3 GiB available of 7.8 GiB. ClamAV needs over 1.2 GiB and peaks above 2.4 GiB during the daily reload, so it fits with the default concurrent reload.
- No screens in E05: the upload component is built with E07's branding page, its first user.
