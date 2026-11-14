---
status: proposed
date: 2026-11-15
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0049 — Note sheet: rich remarks as validated JSON in the memo module, append-only, corrections by reference

## Context and problem statement
Today a memo's history is the workflow's action list: each submit, approve, return or reject carries a plain-text remark of up to 4,000 characters (`workflow_action.remark`, append-only by grant and trigger). Banks expect a **note sheet**: the running record of the file, where people write remarks with light formatting, mention a colleague, attach a supporting paper to a remark, and correct a mistake without rewriting history. Remarks are also written outside a decision (a comment, a reply to a mention).

Where does the richer entry live, in what format, and how do corrections work in an append-only record (overview ADR-NOTES)?

## Decision drivers
- The record stays append-only, enforced in the database.
- Safe to render in the browser under our strict CSP, and simple to draw into a PDF/A (E04) and to index for search (E05).
- Readable decades later: a stable, versioned format.
- The workflow engine stays generic; it should not know about rich text, mentions or attachments.

## Considered options
1. **A memo-module note table**, one entry per remark, linked to the workflow action when it is a decision's remark; body as ProseMirror JSON against a strict schema.
2. Add a JSON body, mentions and attachments to `workflow_action`.
3. Store sanitised HTML (OWASP Java HTML Sanitizer).
4. Store Markdown.

## Decision outcome
**Option 1.**

- **Entries** (`memo_note`, append-only by grants and a trigger, as `workflow_action`):
  - author, acting-for, time, round;
  - kind (`REMARK` for a decision's remark, `COMMENT` otherwise, `CORRECTION`);
  - the action id for decision remarks;
  - `corrects_id`;
  - body: `jsonb`, `body_schema` (1), and server-derived `body_text`.
- A **decision's remark** is written in the same transaction as the action. The workflow keeps a plain-text copy in `workflow_action.remark` (the derived text), so the engine, inbox and history keep working unchanged.
- **Format `noteDoc/v1`** (research §A.8):
  - Allowed: paragraphs; bullet and ordered lists (nesting ≤ 3); bold and italic; hard breaks; `https:` links; `mention` (user id plus a name snapshot); `memoRef` (memo id plus a reference snapshot).
  - Anything else is **rejected**, unknown keys included.
  - Limits: 64 KiB JSON, 2,000 nodes, 4,000 characters of text, 20 mentions, links and memo references each.
  - Parsed into a Kotlin sealed-class tree. The same tree yields the plain text, the mentions, the web view and (E04) the PDF.
  - Entries keep their schema version for ever; a new node type means `noteDoc/v2`, and the v1 renderers stay.
- **Mentions:**
  - The server derives `memo_note_mention` rows and notifies the people mentioned.
  - A mention of someone who cannot read the memo is refused, with the option to share first (ADR-0050).
  - A mention never grants access.
- **Attachments on a remark:** files of purpose `memo.note.attachment`, bound to the entry when it is posted. They follow the memo's classification, or a stricter one as for memo attachments (ADR-0046). They are kept with the entry and cannot be removed.
- **Corrections:** a new entry of kind `CORRECTION` that references the entry it corrects. Only the author, or the person they acted for, may correct, and only while the memo is open. The corrected entry stays; the view shows it marked "Corrected" with a link to the correction.
- **Drafts:** one private, auto-saved draft per person and memo (`memo_note_draft`, mutable, not part of the record), removed when posted. A draft is never shown to anyone else, and it is not audited.
- **Editor:** Tiptap 3 (MIT packages only) with `injectCSS: false`, lazy-loaded where remarks are written. Rendering is our own React walk over the tree: no raw HTML, and links get `rel="noopener noreferrer nofollow"`.

### Consequences
- Good: rich remarks without HTML parsing on the server or raw HTML in the browser; one tree for web, PDF and search.
- Good: the workflow module is unchanged apart from receiving the derived text.
- Bad: two stores for a decision's remark (the note and the action's text). Kept consistent by writing both in one transaction and checked by a test.
- Bad: we own the schema validator and both renderers.

### Confirmation
- Unit and property tests:
  - the validator accepts every allowed shape and rejects each forbidden one (unknown node, unknown key, `javascript:` link, depth, size);
  - text extraction is stable (golden files per schema version).
- Integration tests:
  - append-only (UPDATE and DELETE refused);
  - correction rules;
  - mention notification and refusal;
  - the decision remark matches the action text.
- E2E: the note sheet with no CSP violation.

## Pros and cons of the options
### Option 1 — memo-module notes, JSON
- Good: generic engine; a strict schema; no HTML anywhere.
- Bad: a second table beside the actions.
### Option 2 — rich `workflow_action`
- Good: one row per decision.
- Bad: puts memo concepts (mentions, attachments, classification) into the generic engine; comments are not actions.
### Option 3 — sanitised HTML
- Bad: sanitiser advisories (four in November 2026); HTML parsing differences between server and browser; hard to draw in a PDF.
### Option 4 — Markdown
- Bad: parsers disagree; no standard mention syntax.

## More information
Research R9 and §B (`epic-03-templates-notes-access/research.md`), ADR-0035 (workflow data and append-only actions), ADR-0046 (attachments), ADR-0050 (who can read and be mentioned).
