# Web UI redesign (design canvas)

A redesign of the web app's user-facing screens: every non-admin route, desktop and phone side by side, in light and dark. It is a **design**, not code. The app is built from it with shadcn/ui components (see "Building from this design" below).

- Live canvas: a private claude.ai Design artifact, `https://claude.ai/artifact/VRTQYT81opA8eMovd2rDo6`. It needs to be shared before others can open it.
- Snapshot in this folder: 2026-11-18, canvas version 78.

## What is here

```
canvas/project/      The design itself: what the live canvas holds
  canvas.json        Board positions, sizes, titles, the side notes and the canvas pages
  *.dc.html          One board each: 33 screens, desktop and phone, plus Rules.dc.html (the design rules page)
tools/               Python scripts and HTML fragments that produced or changed the boards
NOTES.md             The canvas side notes as text: API changes the design needs, behaviour notes, build note
IMPROVEMENTS.md      UX improvement backlog: ideas to design and build later, with what each needs from the API
```

The `.dc.html` files are the source of truth. Each is an HTML template for the Design artifact type: markup with `{{holes}}`, `<sc-for>`/`<sc-if>`, and a `class Component extends DCLogic { renderVals() … }` script that supplies the data and the interactions. The `data-props` attribute declares the tweaks a board offers, for example `theme`, `errors`, `dialog` or `state`. Boards load `./support.js` and render only inside the Design artifact; opened in a browser on their own they show raw template text.

Start with `Rules.dc.html`. It holds the principles, colour tokens, type, spacing, layout and breakpoints, component rules, wording and accessibility rules every board follows. The shadcn/ui component for each pattern is listed in its first section.

## Boards on the canvas

On the **Screens** page, in canvas order (`tools/layout.py`):

| Group | Boards |
|---|---|
| Start | Home, Inbox · My tasks (`Main`, `Phone`), Inbox with reading pane (`InboxReading`, desktop only), Inbox · Watching, Inbox · Team |
| A memo's life | New memo, Draft, Your memo in review (`Sent`), Memo in review for an approver (`Memo`), Returned, Approved, Closed, Rejected, Withdrawn, Cancelled, Discarded, Imported, Document |
| Memo pieces | Memo page parts (`MemoParts`), Memo dialogs (`MemoDialogs`), Confirm it is you (`StepUp`) |
| Finding things | Search, Saved searches (`Saved`), Notifications, Approvals dashboard (`Dashboard`) |
| Organisation | Organisation (`Org`), Position |
| Account | My security (`Security`), Notification settings (`Prefs`), Sign in (`SignIn`) |
| Platform | Platform status (`Status`), Page states (`States`) |

The **Admin** canvas page holds the administration screens: so far Open tasks (`Tasks`), with a note listing the 22 admin pages still to design. The **Operator** canvas page is for the operator console (6 pages, none designed yet). The **Design rules** page holds `Rules`.

Each desktop board `X.dc.html` has a phone board `XPhone.dc.html`, except `Main` (its phone board is `Phone`) and `InboxReading` (wide screens only; a note stands in its phone column).

## Changing the design

**Small changes:** edit the `.dc.html` file and publish it (below). Keep the token names from `tools/tokens.css` (`var(--bg)`, `var(--fg)`, `var(--border)` …) rather than colours, so light and dark both work.

**Check a board** after any edit: tags must balance, `renderVals()` must run, and every `{{hole}}` must resolve. Run this from this folder (needs Python 3 and Node):

```bash
python3 tools/smoke.py Memo.dc.html '{}' '{"props":{"errors":true}}'
```

Each extra argument is a JSON object `{"props": {...}, "state": {...}}` to render with. Reports of `false,true` are the template's own placeholder hints; other names listed as missing are worth a look. A hole inside a closed `<sc-if>` can show as missing harmlessly.

**Add a board:** add its entry to `canvas/project/canvas.json` (`boards`, a title note in `notes`, and `order`), add its row to `ROWS` in `tools/layout.py` (use `'note:<key>'` in the phone slot for a desktop-only board), then run `python3 tools/layout.py`.

## Regenerating

Most scripts in `tools/` were one-time edits applied in sequence while the design was made, for example "add Status to every sidebar" or "swap in the People with access panel". They are kept as a record. **Do not rerun them**: each expected the boards as they were at that moment, and several read copies of earlier boards that are not kept here. Rerunning one would undo later changes.

These can be rerun safely, and reproduce the committed boards exactly:

| Script | Rebuilds | Then run |
|---|---|---|
| `tools/dialogs_gallery.py` | `MemoDialogs`, `MemoDialogsPhone` (the dialog specs and copy are in `SPECS`) | `tools/otp.py`, `tools/card_surface.py`, then `tools/layout.py` |
| `tools/parts.py` | `MemoParts`, `MemoPartsPhone` | `tools/card_surface.py`, then `tools/layout.py` |
| `tools/otp.py` | Turns the code fields in the step-up, memo dialog and sign-in boards into six boxes (shadcn InputOTP); skips boards already done | — |
| `tools/card_surface.py` | Adds the `--card` colour and puts cards, tables, dialogs, menus, sheets and toasts on it; skips what is already done | — |
| `tools/layout.py` | Canvas order and spacing in `canvas.json` | — |
| `tools/smoke.py` | Nothing; checks a board | — |

Run them from this folder, for example:

```bash
cd docs/design/web-ui
python3 tools/dialogs_gallery.py && python3 tools/otp.py && python3 tools/card_surface.py && python3 tools/layout.py
```

The one-time scripts, roughly in the order they ran: `theme.py` (tokens and dark mode), `tabs.py`, `bell.py`, `phoneshell.py`, `tasks.py`, `dialogs.py`, `savedialog.py`, `auth.py`, `orgnav.py`, `orgfix.py`, `orgphone.py`, `position.py`, `ended.py`, `sent.py`, `closed.py`, `inbox_tabs.py`, `states.py`, `access_swap.py`, `review_fix.py`, `ux_next_unread.py` (next task, “Since you last looked”, unread markers), `reading_pane.py` (builds `InboxReading` from the Inbox board), `pane_toggle.py` (the reading-pane switch), `dark_options.py` and `dark_preview.py` (the temporary dark-palette comparison, since removed), `apply_dark.py` (made palette A, Crisp neutral, the dark theme everywhere). The `*-body.html`, `*-fields.html` and `*-logic.js` files are fragments they assembled.

## Publishing to the canvas

The canvas is a claude.ai Artifact of the Design type. Publishing is done from a Claude session with the Artifact tool, not from this repository:

1. Copy this folder's `canvas/` somewhere the session can read.
2. Publish to the canvas URL with `root` set to that `canvas` folder, `file_path` set to `canvas/project/canvas.json`, and `files` mapping each changed `project/X.dc.html` to itself.
3. Only send the `project/` files. The Design type's own files (`index.html`, `SKILL.md`, `artifact-type/…`) are fixed.

If someone changed a board on the canvas since this snapshot, read it from the canvas first and merge, rather than overwriting it with the copy here.

## Building from this design

- Use shadcn/ui components in `web/src/components/ui` for every control. Add missing ones with `npx shadcn@latest add <name>`. The boards are hand-written HTML only because the canvas cannot run React; do not copy their inline styles.
- Validation is done by the form (TanStack Form), never by the browser: `noValidate` on forms, our own messages under the field, focus moves to the first error, and the submit button stays enabled.
- Wording on the boards comes from the app's message catalogues where they exist. Copy the boards introduced is marked on the boards themselves (for example "New, not in the app yet" on Page states) and in `NOTES.md`.
- `NOTES.md` lists the API changes the design needs (items 1–11) and the behaviour the boards assume.
