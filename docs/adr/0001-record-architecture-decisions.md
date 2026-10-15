---
status: accepted
date: 2026-10-14
deciders: product owner, agent
---

# 0001 — Record architecture decisions with MADR

## Context and problem statement
`AGENTS.md §5 Step 2` requires one record for each significant decision. Agents lose context between sessions, so decisions must live in the repository in a stable format that grep can find.

## Considered options
1. MADR 4 (Markdown Architectural Decision Records)
2. The original ADR format from Michael Nygard
3. A wiki outside the repository

## Decision outcome
Chosen option: **MADR 4**. Each ADR is one file, `docs/adr/NNNN-kebab-title.md`, copied from `docs/adr/template.md`, with YAML front matter (`status`, `date`, `deciders`). Numbers are never reused. A superseded ADR stays in place, and its status points to the ADR that replaces it. `docs/adr/README.md` is the index.

### Consequences
- Good: decisions are reviewed in the same PRs as the code, and agents can find them with grep.
- Bad: the index is maintained by hand. E04 adds a CI check that every ADR file appears in the index.

## More information
- https://adr.github.io/madr/ (accessed 2026-10-14)
