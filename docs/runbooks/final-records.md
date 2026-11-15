# Final records (PDF/A)

**What they are:** when a memo is finally approved, a background job makes its **final record** (ADR-0053). It is one PDF/A-2u file holding:
- a cover;
- the approved document (the version the final stamp binds, converted unchanged);
- the approval history, with how each person authenticated, the document hash and the hash chain;
- the note sheet by round;
- the attachments.

Attachments that cannot be shown as PDF/A (ZIP files, unknown types, encrypted or broken files) get a placeholder page that names them by SHA-256. The originals stay locked record files of their own.

The record is:
- validated by veraPDF;
- stored as a record file (purpose `memo.rendition`: encrypted per tenant, object-locked);
- audited with its hash;
- re-hashed every week.

Generated pages are in English (Phase 3 §10 Q3).

**Who:**
- **Readers of the memo** see the record's state. They download it when they are cleared to its classification, which is the highest of the memo and its attachments.
- **Holders of `audit.read`** also see its SHA-256 and its validation.
- **Holders of `memo.rendition.regenerate`** retry a failed record or make a new version with a reason and a step-up. The permission is granted on purpose and is in no built-in role; the demo gives it to the demo admin as "Records keeper".

## What must be running
| Component | Why | Without it |
|---|---|---|
| The scheduler (`MEMO_JOBS_ENABLED=true`) | Builds records (`memo-final-rendition`), the backfill (`memo-rendition-backfill`, every 6 hours), the lock extension at closure (`memo-rendition-retention`) and the fixity check (`memo-rendition-fixity`, weekly) | Records stay "being prepared"; nothing is lost, the jobs catch up |
| ONLYOFFICE Docs (`documents` profile) | Converts the approved .docx and Office attachments to PDF/A (`outputtype: pdfa`) | Those parts become placeholder pages. Tell the requester, then make a new version once the converter is back |
| The malware scanner and object storage | The record is stored through the files pipeline | The build retries (5 attempts), then fails; use Retry |

## Settings
- `memo.rendition.validation-timeout` (default `60s`): veraPDF's limit per record.
- **Retention:**
  - from the memo type: its years count from approval or closure;
  - otherwise the tenant's `files.records.retention.years` (default 10), in the tenant's lock mode (`files.records.retention.mode`).
  - Locks are only ever extended.

## Monitoring
| Signal | Meaning | Action |
|---|---|---|
| Audit `memo.rendition.created` with `compliant: false` and a log warning | veraPDF rejected the record; it was stored anyway, flagged | Look at the failed rules in the auditor's view. Usually an uploaded PDF that could not be repaired. Make a new version after fixing the cause |
| Audit `memo.rendition.failed` (`HASH_MISMATCH`) | The final stamp's document hash differs from the stored document | **Incident:** the approval record or the document was changed outside the application. Run Check on the stamps (ADR-0051) and compare with the audit chain |
| Audit `memo.rendition.failed` (`CONVERTER_*`, `UNEXPECTED`) after 5 attempts | The converter or storage was down for too long | Fix the service, then Retry on the memo |
| Audit `memo.rendition.mismatch` | The weekly fixity check found a stored record whose hash changed | **Incident.** Object lock should make this impossible through S3. Check for access to the storage servers outside S3 (filer, volume, `weed shell`; ADR-0005). Keep the evidence, then make a new version from the memo |
| Audit `memo.rendition.verified` missing for a week | The fixity job did not run | Check the scheduler |
| Many records with placeholder pages | Attachment types the converter cannot handle | Expected for ZIP files and similar; the originals are kept and named by hash |

## Regenerating
- A new version is made only by a holder of `memo.rendition.regenerate`, with a reason and a step-up. Use it after a converter defect, a missing font or a validation failure.
- The new version is a new version of the same record file. Earlier versions stay locked and downloadable, and `memo.rendition.superseded` names both hashes and the reason.
- The latest made version is the record of reference.

## Backfill
- Memos approved before E04 get version 1 from the backfill: 100 per tenant every 6 hours, oldest first.
- On a large tenant this takes a while.
- Nothing needs to be done by hand.

## Known limits
- **Generated pages are English only.** Complex scripts (Bengali, Arabic, Hindi) need a shaping engine behind the same interface (ADR-0052).
- **Rasterised pages:** parts of uploaded PDFs that break a PDF/A rule even after cleaning (fonts not embedded, CMYK) become images of their pages. They have no text, and the auditor's view counts them.
- **No cryptographic seal yet.** Integrity rests on the lock, the stored hash and the audit chain. PAdES B-LTA with an RFC 3161 timestamp is the upgrade path.
- **Fixity checks every record weekly.** Sampling above 10,000 records per tenant (design §6) is not built yet.
- **Retention counts from storage time,** minutes after approval. For backfilled memos it is later than approval, which errs on the longer side.
