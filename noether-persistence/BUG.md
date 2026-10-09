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

## BUG-20261009-11 — Schema projection omits synthetic and relational columns

- **Date**: 2026-10-09
- **Status**: FIXED (P9 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `SchemaPlan`, JPA dialect AST
- **Symptom**: generated schemas fail for discriminator columns, Year values, order columns and named
  foreign keys/secondary-table joins; the schema gate initially retained six errors after basic generation.
- **Minimal reproduction**: `SchemaGenerationTest#inheritanceDiscriminatorsAndYearAreSchemaColumns`;
  official `se.schemaGeneration` and repeatable generator/secondary-table cases.
- **Cause hypothesis**: physical columns were assumed to have an entity attribute index; relational
  mapping metadata and class-file foreign-key definitions were not fully projected.
- **Investigation**: 2026-10-09: retain synthetic columns, physical mapping columns and named constraints;
  dialect-rendered DDL clears the schema gate. The secondary-table test now reaches its separate P11 cache failure.

## BUG-20261009-12 — Virtual JTA flush loses transaction association and nested connection ownership

- **Date**: 2026-10-09
- **Status**: FIXED (P9 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `EntityManagerImpl`; `mansart-jpa-cdi`, `JtaIntegration`
- **Symptom**: AUTO queries omit pending changes; a nested relationship existence check during flush reads a
  separate non-enlisted connection and falsely treats an already-flushed detached target as new.
- **Minimal reproduction**: `JtaIntegrationTest#autoQueryFlushUsesTheCallerTransactionBeforeVirtualOffload`;
  `#nestedFlushReadsReuseTheEnlistedConnectionForAutoQueriesAndCommit` (real H2, platform-thread caller).
- **Cause hypothesis**: the delivered transaction manager's ThreadLocal association is absent on a virtual
  worker; nested `onConnection` reconsults the manager instead of retaining the execution's connection.
- **Investigation**: 2026-10-09: both real regressions failed before correction. Capture the transaction decision
  on the caller and retain/restore the current execution connection around callbacks and JDBC work.
  AUTO-query and before-completion flush now see their own uncommitted writes, without adding ThreadLocal.

## BUG-20261009-13 — Failed JTA join leaks the acquired connection

- **Date**: 2026-10-09
- **Status**: FIXED (P9 working tree; no commit requested)
- **Module**: `mansart-jpa-cdi`, `JtaIntegration`
- **Symptom**: joining a rollback-only transaction leaves its newly acquired JDBC connection open;
  a synchronization registration failure after enlistment gives ownership to neither completion nor the caller.
- **Minimal reproduction**: `JtaIntegrationTest#failedJoinToRollbackOnlyTransactionReleasesItsRealJdbcConnection`.
- **Cause hypothesis**: failure after acquiring/enlisting bypasses release; registration happened too late.
- **Investigation**: 2026-10-09: real rollback-only reproduction was red. Guard completion, register synchronization
  before enlistment, and release idempotently on every failed join. XA JDBC callbacks execute on virtual threads.

## BUG-20261009-14 — Container query creation binds to the wrong transaction lifetime

- **Date**: 2026-10-09
- **Status**: FIXED (JPQL/Criteria/native/named queries and, 2026-10-09, stored-procedure facade creation)
- **Module**: `mansart-jpa-cdi`, `ContainerEntityManager`, `ContextQuery`
- **Symptom**: creating a query without a transaction throws `TransactionRequiredException`; retaining a query
  across transaction boundaries otherwise retains the old persistence context.
- **Minimal reproduction**: `JtaIntegrationTest#contextQueriesResolveIdentityAtExecutionAndReadDetachedWithoutATransaction`;
  `JtaIntegrationTest#storedProcedureCreatedOutsideATransactionKeepsMetadataAndExecutesBoundToTheCurrentTransaction`
  (plus the named, result-lifetime and `executeUpdate`/closed-state tests) for stored procedures.
- **Cause hypothesis**: query creation directly forwarded to the active transaction's entity manager.
- **Investigation**: 2026-10-09: a red real-H2 query test precedes a static forwarding wrapper which recreates
  execution against the current context, retaining metadata/parameters/settings. Outside-transaction results are
  materialized and detached. Stored procedures got `ContextStoredProcedureQuery` after red H2/Mansart-TM tests: it
  records registration/parameters/settings, replays them on the current context at `execute()`, and copies result
  sets, update counts and outputs (REF_CURSOR rows are already materialized by the core query) before that
  context ends, so they survive its closure. Paging is applied to the copy; closure follows the facade. H2 has no
  OUT/REF_CURSOR procedures, so those parameter modes rely on the core implementation's tests.

## BUG-20261009-15 — Shared container facade metadata races across virtual requests

- **Date**: 2026-10-09
- **Status**: FIXED (P9 working tree; no commit requested)
- **Module**: `mansart-jpa-cdi`, `ContainerEntityManager`
- **Symptom**: an application-scoped CDI owner concurrently setting properties/creating queries encounters
  `ConcurrentModificationException`; lazy metadata contexts can be duplicated.
- **Minimal reproduction**: `JtaIntegrationTest#containerFacadeMetadataIsSafeWhenOwnedByASharedCdiBean`
  (100 real virtual tasks and a real JTA factory).
- **Cause hypothesis**: mutable settings were iterated without a snapshot and metadata creation was unguarded.
- **Investigation**: 2026-10-09: the stress regression failed with `ArrayList.forEach` in `configured`.
  Publish immutable bounded-per-setting snapshots and guard only non-JDBC metadata operations with ReentrantLock;
  destroy/release occurs outside that lock. The regression now passes.

## BUG-20261009-16 — Vauban cannot apply portable persistence injection enhancements

- **Date**: 2026-10-09
- **Status**: FIXED UPSTREAM (Vauban `BUG-20261009-01`/`-02`, Vauban working tree, uncommitted; installed locally as `0.4.0-SNAPSHOT`); no Mansart workaround
- **Module**: `vauban-core`, enhancement application; consumer `mansart-jpa-cdi-module-it`
- **Symptom**: plain persistence-annotated fields remain null; an already-`@Inject` persistence setter retains
  `@Default` on its parameter and fails deployment despite the BCE adding `PersistenceBinding`.
- **Minimal reproduction**: `ContainerModuleTest#persistenceAnnotationsInjectThroughTheActualContainer`;
  `#persistenceSetterParametersReceiveTheirEnhancedQualifier` (actual Vauban, named modules, real H2/TM).
- **Cause hypothesis**: `EnhancementApplier` modifies existing field injection points but does not synthesize
  missing points; method parameter annotation enhancements are not applied to injection-point qualifiers.
  Generated factories therefore omit the plain resource fields.
- **Upstream evidence**: `vauban-core/.../extensions/EnhancementApplier.java`, field loop at lines 330–368;
  `applyParameterEnhancement()` at lines 394–398 is a no-op for injection qualifiers.
- **Investigation**: 2026-10-09: both required-contract tests remain red. Explicit source `@Inject` fields are a
  passing diagnostic control, including actual synthetic factory/context injection and transactional writes.
  This workaround is not treated as fulfillment of the plain persistence annotation contract. P9 remains partial.
- **Investigation**: 2026-10-09: fixed upstream. Vauban now turns BCE-added `@Inject` fields/initializers into
  injection points with their enhanced qualifiers (BUG-20261009-01), and its APT provider has in-module write cases
  for public mutable fields (BUG-20261009-02; a follow-up fix stopped that scan completing the compiled module,
  which had broken this module's `provides … _VaubanComponents`). Clean
  `./mvnw -ntp -pl mansart-persistence/mansart-jpa-cdi-module-it -am clean test`: `ContainerModuleTest` 4/4 green on
  the module path with assertions unchanged, CDI/JTA 13, core 410, dialect SPI 23, H2 6. Upstream limitation:
  generated writes cover public mutable reference fields only; other BCE-enhanced members need `opens` and fail
  loudly without it. P9 itself remains partial (Arquillian gate and Vidocq extension).

## BUG-20261009-17 — Fresh drop-and-create tries to drop a constraint on an absent table

- **Date**: 2026-10-09
- **Status**: FIXED (P9 working tree; no commit requested)
- **Module**: `mansart-jpa-dialect-spi`, `StandardDialect`
- **Symptom**: bootstrapping a fresh H2 database with a to-one relationship fails before CREATE,
  `Table JTA_OWNER not found` at `ALTER TABLE ... DROP CONSTRAINT IF EXISTS`.
- **Minimal reproduction**: `JtaIntegrationTest#nestedFlushReadsReuseTheEnlistedConnectionForAutoQueriesAndCommit`;
  create its mapped `Owner`/`Record` unit with `database.action=drop-and-create`.
- **Cause hypothesis**: DROP guarded the constraint name but not the table.
- **Investigation**: 2026-10-09: the new nested-flush regression first exposed this schema defect.
  The dialect renders `ALTER TABLE IF EXISTS ... DROP CONSTRAINT IF EXISTS`; fresh H2 bootstrap passes.

## BUG-20261009-18 — Destroyed container contexts silently accept nontransactional operations

- **Date**: 2026-10-09
- **Status**: FIXED (P9 working tree; no commit requested)
- **Module**: `mansart-jpa-cdi`, `ContainerEntityManager`
- **Symptom**: `contains`, `clear` and `detach` without an associated transaction bypass closed-state checks.
- **Minimal reproduction**: `JtaIntegrationTest#containerFactoryOwnershipAndContextConfigurationDoNotRequireATransaction`;
  destroy the facade and call `contains(new Record(1))`.
- **Cause hypothesis**: the nontransactional no-op branch skipped the underlying entity manager's validation.
- **Investigation**: 2026-10-09: the lifecycle regression was red. Require an open facade, and use a transient
  detached context to preserve entity argument validation for nontransactional `contains`/`detach`.
