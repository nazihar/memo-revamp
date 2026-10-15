---
status: accepted
date: 2026-10-14
deciders: product owner (delegated), agent
---

# 0008 — Backend test libraries (assertions, property-based testing, ids)

## Context and problem statement
`TESTING_STRATEGY.md §2/§3.1` leaves two choices open: AssertJ or Kotest for assertions, and jqwik or Kotest for property-based tests. Phase 2 requires stateful property tests (random action sequences against the workflow engine). Research: `epic-02-data-and-tenancy/research.md §R9`.

## Decision outcome
- **Assertions: AssertJ.** It is Boot-managed, already used, and backs Spring's `MockMvcTester`, which gives one style across the code base.
- **Property-based: jqwik 1.10.2** (EPL-2.0, dev-only). It is a JUnit Platform engine, shrinks failing cases, and has stateful action chains. Kotest property was rejected because its stateful support is less mature and it would add a second assertion idiom.
- **Ids: FasterXML java-uuid-generator 5.2.0** (Apache-2.0, runtime). UUIDv7 comes from `TimeBasedEpochGenerator` (counter-based, monotonic within a millisecond; the `…RandomGenerator` variant is not, as a property test showed) behind our `IdGenerator` interface.

### Consequences
- Bad: jqwik has one main maintainer. It is dev-only, so the risk is accepted; re-evaluate when Phase 2 starts.

### Confirmation
`docs/dependencies.md` rows; a jqwik property test on `IdGenerator` ordering in the PR stage.
