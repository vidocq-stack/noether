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

## BUG-20261009-03 — Secondary-table bulk queries are refused by the P7 frontend

- **Date**: 2026-10-09
- **Status**: FIXED (P8 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `Translator`
- **Symptom**: eight official Criteria update/delete tests report the P7 secondary-table bulk refusal.
- **Minimal reproduction**: official `core.criteriaapi.CriteriaUpdate.Client` and `CriteriaDelete.Client` gates.
- **Cause hypothesis**: only JOINED inheritance selects the existing multi-table bulk execution plan.
- **Investigation**:
  - 2026-10-09: P8's first Criteria gate reproduced the refusal; secondary tables now use the same identity-capture
    and dialect-AST mutation plan as JOINED inheritance (§4.10), with no separate SQL execution path.
  - 2026-10-09: the official update/delete gate passes; full-suite comparison shows no passing-test regression.

## BUG-20261009-04 — Entity-valued Criteria literals cannot be members of a relationship collection

- **Date**: 2026-10-09
- **Status**: FIXED (P8 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `Translator`
- **Symptom**: `CriteriaBuilder.isMember(entity, collection)` compares an untyped scalar with an entity and fails.
- **Minimal reproduction**: official `core.criteriaapi.metamodelquery.Client2#queryTest29`.
- **Cause hypothesis**: entity parameters are decomposed through generated access, but entity literals are not.
- **Investigation**:
  - 2026-10-09: wrapped execution logs identify the equality lowering; literal identities now use the same mapped
    generated-access path and key binders as entity parameters (§6.5.9).
  - 2026-10-09: `queryTest29` and the full P8 gate pass; entity identity binding remains shared with JPQL.

## BUG-20261009-05 — Canonical generation loses module access and incremental ownership

- **Date**: 2026-10-09
- **Status**: FIXED (P8 working tree; no commit requested)
- **Module**: `mansart-jpa-processor`, `MetamodelWriter`
- **Symptom**: generated annotations require an unnecessary `java.compiler` read; global initializers reference
  foreign package-private types; partial rebuilds leave stale fields and omit unchanged types' initialization.
- **Minimal reproduction**: `CanonicalLifecycleTest`, `ModuleProvidesTest`, generated-access module-path tests.
- **Cause hypothesis**: canonical producer ownership was source-only, initialization was global rather than
  package-local, and retained access metadata did not retain metamodel helpers.
- **Investigation**:
  - 2026-10-09: code-generation review and red compilation tests reproduced these cases.
  - 2026-10-09: durable ownership markers, package-local public helpers and retained helper metadata fix them;
    explicit raw collection target types are honored. The processor and no-opens module tests pass.

## BUG-20261009-06 — Named Criteria queries reparse a synthetic query label

- **Date**: 2026-10-09
- **Status**: FIXED (P8 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `NamedQueries`, `JpqlQuery`
- **Symptom**: registered Criteria queries fail when recreated, losing their AST, settings and tuple result type.
- **Minimal reproduction**: `MetamodelContractTest#criteria32NodesPreserveTypesParametersAndNamedQuerySnapshots`;
  `JpqlQueryTest#criteriaSetParametersAndNamedDefinitionsKeepTheirSnapshot`,
  `#primitiveCriteriaParametersAndUntypedNamedTuplesRetainTheirContract`.
- **Cause hypothesis**: the registry retained `queryString()` instead of the immutable Criteria statement.
- **Investigation**:
  - 2026-10-09: preserve the immutable statement, parameter expressions, selections and original result class.
    Named/untyped tuple execution and primitive bindings pass; named Criteria TCK factory tests pass.

## BUG-20261009-07 — ON navigation references a later alias and loses unmatched LEFT roots

- **Date**: 2026-10-09
- **Status**: FIXED (P8 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `Translator`
- **Symptom**: an entity-join ON predicate navigating an owned relationship introduces an inner join after
  the join whose ON references it; H2 rejects the later alias, and unmatched roots would be lost.
- **Minimal reproduction**: `JpqlQueryTest#criteriaEntityLeftJoinsPreserveUnmatchedRoots`.
- **Cause hypothesis**: implicit path joins were appended globally and the ON clause was attached to the last join.
- **Investigation**:
  - 2026-10-09: retain the declared join's index; lower owned ON navigation through a correlated shared-AST scalar
    subquery, preserving outer-join roots. Identifier and nonidentifier ON navigation execution tests pass.

## BUG-20261009-08 — Criteria map-key and treated-root navigation lose their graph edges

- **Date**: 2026-10-09
- **Status**: FIXED (P8 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, Criteria frontend and shared `Translator`
- **Symptom**: `MapJoin.key().get(...)` is not navigable; joins added to a treated root are absent from FROM.
- **Minimal reproduction**: `IndexedCollectionTest#criteriaNavigatesEntityMapKeys`;
  `InheritanceTest#criteriaTreatRootsRetainTheirNewJoins`.
- **Cause hypothesis**: key expressions lost path metadata, and treated copies were disconnected from range traversal.
- **Investigation**:
  - 2026-10-09: red execution tests reproduced both cases; key-path metadata and treated-view traversal now preserve
    those edges. The shared translator handles key joins and subtype-parent navigation; both regressions pass.

## BUG-20261009-09 — Frozen Data canonical collection fields have singular kinds

- **Date**: 2026-10-09
- **Status**: OPEN (producer-owned; maintainer decision required, Data unchanged)
- **Module**: `mansart-data-processor`, `JpaMetamodelWriter` (consumer impact in persistence)
- **Symptom**: when Data owns `Entity_`, its plural attributes are emitted as `SingularAttribute`; a runtime
  `ListAttribute`/`SetAttribute`/`MapAttribute` cannot populate those fields.
- **Minimal reproduction**: run the Data and JPA processors together for an entity with a persistent collection;
  inspect the Data-owned canonical field and the JPA producer-mismatch diagnostic.
- **Cause hypothesis**: the Data canonical writer maps all discovered attributes to singular fields.
- **Investigation**:
  - 2026-10-09: compatible basic canonical output compiles with the actual installed Data/JPA processors in both
    orders. JPA delays emission, retains foreign canonical ownership, and diagnoses incompatible field kinds.
    Coexistence tests also cover a claiming producer in both orders; no Data implementation is modified.
  - Reconciling plural field kinds belongs to the frozen Data producer's authorized workstream, not P8.

## BUG-20261009-10 — Converted string/number literals bypass query conversion

- **Date**: 2026-10-09
- **Status**: FIXED (P7 defect exposed during P8; no commit requested)
- **Module**: `mansart-jpa-core`, `Translator`, `ValueBinders`
- **Symptom**: a converted domain string literal is written inline into a numeric database column; PostgreSQL
  reports `invalid input syntax for type numeric: "5#4#3#2#1.0"`.
- **Minimal reproduction**: official `core.annotations.convert.Client#convert3Test`;
  `BulkQueryTest#jpqlAndCriteriaLiteralsUseAttributeConverters`.
- **Cause hypothesis**: enum/custom literals use binder slots, but ordinary string/number literals stay inline.
- **Investigation**:
  - 2026-10-09: wrapped official execution logs identify this earlier query-engine defect, not a cache or XML blocker.
    A red H2 regression covers both JPQL and Criteria converted-literal assignments and predicates.
  - 2026-10-09: converter-backed literals now use existing binder slots in assignments and comparisons.
    The clean reactor and official `convert3Test` pass; full score 2013 / 2135 with no baseline regression.
