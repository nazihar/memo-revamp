---
status: accepted
date: 2026-11-11
deciders: Product owner (delegated gate, Phase 2 Q1), agent
---

# 0039 — Condition language: CEL with declared attributes, no macros, recorded evaluations

## Context and problem statement
Branch steps choose a path from item attributes (for example the memo type or, from Phase 3, an amount). Tenant administrators write these conditions, so they are untrusted input: they must not execute code, must not cost unbounded time, and must fail at publish time rather than when an item is in approval (phase overview risk R3, research R4).

## Decision drivers
- No code execution, no I/O, bounded evaluation cost.
- Errors an administrator understands, before publishing.
- Auditors can see why an item went one way.
- Maintained library under a permissive licence.

## Considered options
1. CEL (`dev.cel:cel`, Apache-2.0).
2. SpEL in a `SimpleEvaluationContext`.
3. JsonLogic.
4. Our own DSL.

## Decision outcome
Chosen option 1, CEL, restricted:
- **Variables:** only `item`, a map of the attributes the definition declares in `attributes` (name and type: `string`, `int`, `double`, `bool`, `timestamp`), each of which must exist in the subject type's attribute catalogue (`WorkflowSubject.attributes()`).
- **Language:** standard operators and functions; **standard macros disabled** (no `all`, `exists`, `map`, `filter`), so there are no loops; regular expressions run on RE2.
- **Limits:** expression at most 1,000 characters, at most 20 conditions per definition, result type must be `bool` (checked at compile time).
- **Compile at publish:** a version that does not compile is refused, with the CEL error and its position, under the JSON pointer of the branch.
- **Evaluate at runtime:** on the compiled program, with the subject's current attribute values. A runtime error (for example a missing value) routes to the branch step's `otherwise` and is recorded as such.
- **Record:** every evaluation that routes an item adds a `ROUTE` action to the history with the attribute values read, the condition index and the result.

### Consequences
- Good: a sandbox designed for untrusted policies, static type checking, linear evaluation cost.
- Good: E06's builder can generate CEL text from form fields; the stored form stays readable.
- Bad: new transitive dependencies (Guava, protobuf, ANTLR runtime, RE2/J); versions aligned in the build, recorded in the dependency register.
- Follow-ups: a Jazzer fuzz target over compile-and-evaluate (nightly), like the upload path.

### Confirmation
`ConditionsTest` (compile errors, type errors, size limits, no macros, evaluation results), the fuzz target, `BranchRoutingIT` (the recorded `ROUTE` action carries inputs and result).

## Pros and cons of the options
### CEL
- Good, because it is built for this (Kubernetes, IAM, Envoy), typed and non-Turing-complete.
- Bad, because its dependencies are large for one feature.
### SpEL (restricted context)
- Good, because it is already on the classpath.
- Bad, because Spring documents the restriction as best effort, and it has no static types or cost limits.
### JsonLogic
- Good, because rules are plain data.
- Bad, because it is hard to read and review by hand, and the Java ports have single maintainers.
### Own DSL
- Good, because we control everything.
- Bad, because we would maintain a parser, a type checker and their fuzzing.

## More information
Research: `docs/plan/phase-2-workflow-engine/epic-03-definition-runtime/research.md` (R4). Sources: https://github.com/google/cel-java, https://docs.spring.io/spring/reference/7.1/core/expressions/evaluation.html (accessed 2026-11-11).
