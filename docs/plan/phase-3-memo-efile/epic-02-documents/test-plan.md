# E02 Documents — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. CI stages: PR (unit, integration, matrix, smoke E2E), nightly (hostile corpus, PIT, browsers, visual, pseudo-locale), weekly (load).

## Scope and risks
- **Risks covered:**
  - hostile files reaching parsers;
  - lost or split edits;
  - attachments leaking past classification;
  - the record losing what approvers saw;
  - outbound requests from the document server.
- **Seed data:**
  - the demo tenant's memos, with attachments added in T8;
  - `user@` (initiator), `headCredit@` (co-author), the approvers;
  - a reader cleared below one attachment.

## Tests by task
| Task | Layer | Test |
|---|---|---|
| T1 | Spike (manual, recorded) | 10.0.0.1 editor flow; conversion with an `operation` token; insert image from an internal URL refused; `/ai-proxy` 403; tmpfs cache gone after restart |
| T1 | E2E (smoke) | `document-editing.spec` passes on 10.0.0.1; `security.spec` checks the `/office/` denies |
| T1 | Deploy lint | Compose, on-prem Compose, Helm and the E2E stack use the same image and override; no `ALLOW_PRIVATE_IP_ADDRESS` |
| T2 | Unit | Final vs retryable error codes; key derivation; state machine |
| T2 | Integration | `RenditionIT` with fake ports: `PENDING → DONE`, retries, `FAILED`, `NOT_POSSIBLE`, recovery job, tenancy (another tenant's version id refused), read audit, page limit |
| T2 | Integration (stack) | `OnlyOfficeConversionIT` (tagged, runs where the E2E stack runs): DOCX, XLSX and PPTX to PDF |
| T3 | Integration (stack) | `TikaExtractionIT`: DOCX, XLSX, PDF text; truncation mark |
| T3 | Nightly | Hostile corpus (zip bomb, deep nesting, huge XLSX, malformed PDF, polyglots) through both services: bounded time and memory, the backend unaffected |
| T4 | Integration | `MemoAttachmentsIT`: stricter-only, effective rank after the memo is raised, lock during review, rules at submit, binding per round, removal kept, limits, types by tenant setting |
| T4 | Matrix | `p3-e02-attachments.yaml` |
| T5 | Integration | `EditRoomIT` (join, force-save, close, stale, saver membership); `CoAuthorsIT` (rights, limit, DRAFT only, read access after submit) |
| T6 | Integration | `DocumentCompareIT` (texts, audit, clearance) |
| T6 | Unit (web) | The diff worker: lines and words, budget fallback |
| T7 | Unit (web) | Attachments section, preview dialog, compare view, co-authors picker; axe in English and Arabic |
| T7 | Visual (nightly) | Attachments section, preview, compare: light/dark, LTR/RTL, phone |
| T8 | E2E (smoke) | `attachments.spec`: stricter attachment restricted for an uncleared approver; preview; compare; two co-authors in one room |
| T8 | Performance | §9 budgets; recorded in the log |

## Special tests (`TESTING_STRATEGY §12`)
- **Hostile files:** the corpus extends to conversion and Tika (overview §6).
- **Record integrity:** a round's binding cannot change (trigger test); removed attachments stay in older rounds' bindings.

## Exit checklist
- [ ] Every test above green in its stage
- [ ] Matrix and audit coverage tests green with the new endpoints and actions
- [ ] Spikes recorded in the log
- [ ] ASVS rows updated (V2, V3, V8, V12, V14, V15, V16)
- [ ] Dependency register: ONLYOFFICE 10.0.0.1, Tika 4.2.0 (service), `tika-core` 4.2.0, `diff` 9.0.0, `pdfjs-dist` 6.4.299
- [ ] Licence brief in `docs/legal/` and named in STATUS for the owner
