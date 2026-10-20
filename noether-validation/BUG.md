# Bugs — Noether Validation

Reproducible implementation defects in the provisional local provider.
Convention: workspace `AGENTS.md`. Status: OPEN → INVESTIGATING → FIXED → CLOSED.

## BUG-20261018-01 — TraversableResolver reachability cache ignores the bean path

- **Date**: 2026-10-18
- **Status**: OPEN
- **Affected module**: `noether-validation-core`, `engine.ValidationRun.reachable`
- **Symptom**: a path-dependent resolver answer for a shared cascaded instance
  is reused at another path, producing violations for a path the resolver forbids.
- **Minimal reproduction**: root fields `a` and `b` both have `@Valid` and point
  to the same child with a null `@NotNull name`. A resolver forbids traversal
  under path `b`. Validation produces `a.name` and `b.name`, instead of only `a.name`.
- **Confirmed cause**: the per-bean reachability cache key includes the operation
  and member name but omits the resolver's path input.
- **Investigations**:
  - 2026-10-18: a scratch regression using the real validator reproduces both
    violations for the shared instance; distinct child instances correctly
    produce only `a.name`. Resolver tracing confirms no request for the shared
    child's second path. Evidence: session artifact
    `java25-review/b/src/repro/PathReachabilityRepro.java` and `b/output.txt`.
    No production fix or tracked regression has been applied.
