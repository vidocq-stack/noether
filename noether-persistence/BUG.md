# Bugs — Mansart JPA

Reproducible implementation defects. Status: OPEN → INVESTIGATING → FIXED → CLOSED.

## BUG-20261009-01 — A nullable foreign key to a primitive identifier loads identifier zero

- **Date**: 2026-10-09
- **Status**: FIXED (P6 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `EntityLoader`
- **Symptom**: a NULL relationship to an entity with a primitive `long` identifier tries to load identifier `0`.
- **Minimal reproduction**: `InheritanceTest#joinedTablesLoadConcreteLeavesAndUpdateInheritedState`, with
  `Worker.department == null` and `Department.id` declared as `long`.
- **Cause hypothesis**: the primitive identifier binder returns zero for SQL NULL; the loader records `wasNull`
  but does not preserve NULL when reconstructing foreign keys.
- **Investigation**:
  - 2026-10-09: reproduced by the P6 relationship tests; basic primitive attributes must still keep their defaults,
    but foreign key columns must retain SQL NULL.
  - 2026-10-09: the loader now preserves NULL for foreign key columns after checking `wasNull`.
    The joined relationship regression and the clean JPA reactor pass.

## BUG-20261009-02 — Ordered inverse subtype collections write a nonexistent subtype table

- **Date**: 2026-10-09
- **Status**: FIXED (P6 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `EntityStatements`, `EntityLoader`
- **Symptom**: flushing an ordered inverse collection targeting a SINGLE_TABLE subtype issues an update against
  the subtype's nonexistent default table; loading the inverse collection also admits NULL entries for sibling rows.
- **Minimal reproduction**: `InheritanceTest#orderedInverseSubtypeCollectionsWriteTheDeclaringTableAndFilterSiblings`.
- **Cause hypothesis**: the stored index update uses the target model's default table rather than the owning
  relationship's declaring entity table; inverse collection loading does not filter incompatible concrete types.
- **Investigation**:
  - 2026-10-09: regression added before the production fix; H2 reports `Table "ORDEREDLEAF" not found`.
  - 2026-10-09: index updates now use the declaring entity's relational table/key; inverse loads skip incompatible types.
    The ordered subtype regression and the targeted inheritance/bulk suite pass.
