---
status: accepted
date: 2026-10-14
deciders: product owner (delegated), agent
---

# 0003 — Cross-platform developer entry point (`pnpm dev`)

## Context and problem statement
The exit criterion asks for "`make dev` (or equivalent)" to start the full local stack. Developers use Windows, macOS and Linux, and the reference Windows machine has no `make`.

## Considered options
1. `make`
2. Task (go-task)
3. just
4. A Node script run with `pnpm dev`

## Decision outcome
Chosen option: **`pnpm dev`**, which runs `scripts/dev.mjs` (plain Node, no dependencies). Node and pnpm are already required for `/web`, so this needs no extra tool and behaves the same on every OS.

| Command | What it does |
|---|---|
| `pnpm dev` | Starts the full stack (Compose profile `full`), the backend (`gradlew :backend:bootRun`) and the web app (Vite). |
| `pnpm dev --core` | Same, but without ONLYOFFICE and ClamAV, for small machines. |
| `pnpm stack:up` / `stack:down` / `stack:reset` | Starts, stops or resets only the Compose stack. |

On first run the script writes `deploy/compose/.env` with random local credentials. The file is git-ignored, so no secret is ever committed.

### Consequences
- Good: nothing extra to install, and CI can use the same commands.
- Bad: the process orchestration (about 150 lines) is our own code to maintain.

## Pros and cons of the options
- make: a familiar standard, but missing on stock Windows and dependent on the shell.
- Task / just: clean, but they add a binary to every machine and CI runner.
