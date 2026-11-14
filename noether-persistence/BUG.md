> Historical evidence: recorded before the 2026-10-11 extraction from Mansart.
> Original names, commands, source revisions and measurements are retained verbatim;
> they are not results measured on Noether. See the repository README for current commands.

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
- **Status**: FIXED upstream, Vidocq/mansart#95 (MANSART-048); pinned by `MansartDataCoexistenceTest` (branch
  `test/metamodel-coexistence`, pull request into `fix-proposals`, Vidocq/noether#45)
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
  - 2026-11-11 (audit F15, #45): fixed upstream by Vidocq/mansart#95 (MANSART-048, 5d5fd8e6): the maintainer chose
    that Mansart Data stops writing `Book_`; it writes its Jakarta Data metamodel `_Book` only, and the JPA canonical
    metamodel belongs to the persistence provider's processor. New `MansartDataCoexistenceTest` (noether-jpa-processor)
    compiles one entity with the real `mansart-data-processor` (test scope) and `NoetherJpaProcessor`, in both orders:
    the compilation succeeds, `Book_` is Noether's with `ListAttribute<datacoexist.Book, java.lang.String> tags`, and
    `_Book` is Mansart's. Green on mansart-data-processor 0.4.0-20261111.191239-69. Red on the earlier
    0.4.0-20261111.124140-68 (temporary pin): `Book_` carries `@Generated("…MansartProcessor")` and has no `tags`. With a
    raw `List` field instead, the same build printed this bug's diagnostic: "Existing canonical metamodel
    datacoexist.Book_.tags was emitted with jakarta.persistence.metamodel.SingularAttribute instead of ListAttribute
    … Noether JPA does not overwrite it". The fixture maps its plural attribute by property access over a
    `@Transient` field, because Mansart Data cannot yet write `_Book` for a collection-valued field (BUG-20261111-06).
  - 2026-11-12: BUG-20261111-06 is fixed upstream, so the `datacoexist` fixture's plural attribute is a plain
    `@ElementCollection List<String> tags` field again. `MansartDataCoexistenceTest` is green on
    mansart-data-processor 0.4.0-20261112.131711-31 (Mansart e46cd5a5, read from maven.vidocq.dev) and red, as
    expected, on 0.4.0-20261111.191239-69 (`_Book.java:29: error: <identifier> expected`).

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
  2026-11-11 (audit F6, #45, branch `fix/f6-f12-f14`): JDBC work no longer leaves the calling thread (decision
  D44), so the virtual worker of this bug is gone; the retained execution connection stays. The first
  reproduction is renamed `JtaIntegrationTest#autoQueryFlushUsesTheCallerTransaction`.
  2026-11-11 (audit F4, #45, branch `feat/container-jta`, decision D45): `JtaIntegration` is removed;
  `noether-jpa-cdi` uses `noether-jpa-jta`'s registry bridge. `JtaIntegrationTest` becomes
  `ContainerEntityManagerTest` (same method names, over a registry and an enlisting data source); the caller-thread
  test moves to `SynchronizationRegistryIntegrationTest#theJdbcWorkOfAJoinedContextRunsOnTheCallerThread`.

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
  2026-11-11 (audit F6, #45, branch `fix/f6-f12-f14`): the XA callbacks run on the thread of the transaction
  manager that calls them (decision D44); Mansart's `ConnectionXAResource` is enlisted without the
  `VirtualResource` wrapper.
  2026-11-11 (audit F4, #45, branch `feat/container-jta`, decision D45): `JtaIntegration` is removed. The registry
  bridge that replaces it acquires no connection when it joins, and a container refuses a synchronization on a
  transaction marked for rollback: the join now fails with a `PersistenceException` and holds no connection,
  `SynchronizationRegistryIntegrationTest#joiningATransactionMarkedForRollbackFailsWithoutTakingAConnection` (red
  first: the registry's `IllegalStateException` escaped unwrapped).

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
  2026-11-11 (decision D45): the reproductions live in `ContainerEntityManagerTest`, same method names.

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
  2026-11-11 (decision D45): the reproduction lives in `ContainerEntityManagerTest`, same method name.

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
  2026-11-11 (decision D45): the reproduction lives in `ContainerEntityManagerTest`, same method name.

## BUG-20261009-18 — Destroyed container contexts silently accept nontransactional operations

- **Date**: 2026-10-09
- **Status**: FIXED (P9 working tree; no commit requested)
- **Module**: `mansart-jpa-cdi`, `ContainerEntityManager`
- **Symptom**: `contains`, `clear` and `detach` without an associated transaction bypass closed-state checks.
- **Minimal reproduction**: `JtaIntegrationTest#containerFactoryOwnershipAndContextConfigurationDoNotRequireATransaction`;
  destroy the facade and call `contains(new Record(1))`. Since decision D45 (2026-11-11) the test lives in
  `ContainerEntityManagerTest`, same method name.
- **Cause hypothesis**: the nontransactional no-op branch skipped the underlying entity manager's validation.
- **Investigation**: 2026-10-09: the lifecycle regression was red. Require an open facade, and use a transient
  detached context to preserve entity argument validation for nontransactional `contains`/`detach`.

## BUG-20261009-19 — Removing related rows together nulls NOT NULL foreign keys

- **Date**: 2026-10-09
- **Status**: FIXED (P10 working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `FlushEngine`
- **Symptom**: removing an entity and the entity it references in one flush first issues
  `UPDATE ... SET fk = NULL`, which fails on a `NOT NULL` foreign key (official TCK
  `core.override.joincolumn.Client#testOverrideJoinColumns`, exposed once P10 mapped its orm.xml).
- **Minimal reproduction**: `FlushEngineTest#rowsDeletedTogetherAreDeletedReferencingFirstWithoutClearingNotNullForeignKeys`;
  `Book.author_id NOT NULL`, remove the author then the book, flush.
- **Cause hypothesis**: `referencesBetween` cleared every reference between rows deleted in the same flush, even
  when the delete order (referencing rows first) already satisfied the constraint.
- **Investigation**: 2026-10-09: the H2 regression was red with the TCK's error. Only references whose target is
  deleted earlier in the sorted delete list (a cycle or an order the ranks cannot give) are now cleared first.

## BUG-20261009-20 — orm.xml `<delimited-identifiers/>` does not quote identifiers

- **Date**: 2026-10-09
- **Status**: FIXED (P10 hardening working tree; no commit requested)
- **Module**: `mansart-jpa-core`, `OrmOverlay`
- **Symptom**: a mapping file declaring `<persistence-unit-defaults><delimited-identifiers/>` is accepted with a
  WARNING naming the file and line; identifiers are rendered unquoted (JPA 3.2 §2.13 requires them delimited).
- **Minimal reproduction**: official TCK `core/entitytest/apitests/orm.xml` and `core/annotations/nativequery/orm.xml`.
- **Cause hypothesis**: the dialect SPI has no delimited-identifier rendering; the official PostgreSQL TCK DDL
  creates unquoted (folded) names, so quoting would also need case-preserving DDL to pass.
- **Investigation**: 2026-10-09: kept as a logged warning rather than a silent success; quoting needs a dialect
  identifier-rendering contract (outside P10's descriptor scope).
  - 2026-10-09: the existing `Identifier` and dialects already support case-preserving quotation. The missing part
    was propagating the unit default to all identifier construction sites. The overlay now exposes the policy;
    schema generation, flush/load SQL, JPQL/Criteria discriminator expressions and sequence/table/identity
    generation share it. Index/unique columns also carry `Identifier`, not untyped SQL fragments.
  - Red/green H2 tests: `OrmXmlTest#delimitedIdentifiersApplyToDefaultsAnnotationsAndOtherMappingFiles`,
    `#delimitedMixedCaseGeneratorsIndexesAndGeneratedKeysMatchTheirSchema`,
    `#explicitDelimitedNamesAndDefaultForeignKeysWorkWithoutUnitDefaults`. Defaults, XML and annotated names,
    embedded basics, collection/join tables, updates/deletes, mixed case and escaped explicit quotes are exercised.
  - The untouched PostgreSQL TCK's two delimited-default fixtures conflict with its unquoted, lowercase-folded
    DDL. The warning/ignore deviation is removed, not replaced by case folding or an official-DDL edit.
    The resulting compatibility failures and exact counters are recorded in `TCK.md`.
  - The current specification reference is §2.15 / §12.2.1.3 (the initial investigation cited §2.13).
    Joined primary-key reference matching now respects delimited case as well; the H2 wrong-case regression is
    `OrmXmlTest#aDelimitedPrimaryKeyReferenceCannotMatchADifferentlyCasedColumn`.
  - 2026-10-09: the remaining native-result/stored-procedure identifier surface is fixed and tested separately
    in BUG-20261009-23. This does not weaken case preservation or change the known official fixture/DDL conflict.

## BUG-20261009-21 — Association overrides are rejected in XML and ignored in annotations

- **Date**: 2026-10-09
- **Status**: FIXED for inherited mapped-superclass relationships (working tree); embedded scope remains BUG-20261009-22
- **Module**: `mansart-jpa-core`, `OrmOverlay`, `EntityModelBuilder`, `SchemaPlan`
- **Symptom**: `@AssociationOverride` on an inherited relationship keeps the base foreign key/join table;
  the XML equivalent fails as unsupported. Override foreign-key metadata does not reach schema generation.
- **Minimal reproduction**: `OrmXmlTest#annotationAssociationOverridesRetainTheInheritedRelationshipContract`,
  `#xmlAssociationOverridesReplaceAnnotationsWithoutReplacingRelationshipDefaults`,
  `#overriddenInheritedJoinsStoreAndLoadWithTheUnitIdentifierPolicy`.
- **Cause hypothesis**: the annotation builder had no association-override consumer; the XML overlay therefore
  rejected the element. Schema generation separately reread the member's original join annotations.
- **Investigation**:
  - 2026-10-09: red annotation/model/XML and quoted H2 storage tests precede production changes. The same builder
    now applies annotation/XML overrides to inherited mapped-superclass relationships, retaining target, access,
    cascade, fetch, optionality and other base metadata. XML overrides annotated declarations by name.
  - 2026-10-09: schema generation uses effective override join-table/foreign-key metadata, including
    `NO_CONSTRAINT`; quoted H2 storage/loading and native constraint checks pass.
  - 2026-10-09: overrides cannot silently change a default join-table relationship to foreign-key columns;
    `OrmXmlTest#joinColumnsCannotSilentlyOverrideADefaultJoinTable` is red before the mapping-shape validation fix.

## BUG-20261009-22 — Embedded relationship overrides have no executable mapping

- **Date**: 2026-10-09
- **Status**: FIXED (working tree; executable entity-owned embedded relationship overrides)
- **Module**: `mansart-jpa-core`, embedded relationship mapping
- **Symptom**: the model/metamodel can describe embedded relationships, but statement flattening has no nested
  foreign-key/collection execution path. Accepting an override would misleadingly omit it from SQL.
- **Minimal reproduction**: `OrmXmlTest#anEmbeddedRelationshipOverrideCannotBeAcceptedAndThenOmittedFromSql`;
  an embedded `@ManyToOne` with `@AssociationOverride(name="target", ...)`.
- **Cause hypothesis**: embedded column paths support basic values; nested references/collections are not mapped
  by the current flush/load/cascade planners. Merely adding an XML annotation would not implement the contract.
- **Investigation**:
  - 2026-10-09: the regression originally raised no error. Embedded association overrides are now rejected at
    bootstrap on annotation and XML paths (XML includes file/line). Existing embeddable metadata remains usable;
    no unrelated metadata-only TCK regression is introduced. Full executable embedded joins remain undelivered.
  - 2026-10-09: red H2 schema/CRUD tests now precede executable mapping. `EmbeddedPaths` lowers nested
    associations and element collections to dotted execution-state slots, composing existing generated accesses;
    the semantic model/metamodel stays hierarchical. The existing foreign-key, join-table, load, cascade,
    merge, orphan-removal and snapshot planners consume those slots, rather than introducing a second engine.
  - 2026-10-09: annotation/XML member and class dotted overrides clone the owner's embeddable view, retain the
    relationship contract and never mutate the shared embeddable. JPQL/Criteria navigation, explicit joins,
    collection predicates, inverse `mappedBy` dotted paths and effective schema foreign-key metadata are tested.
    Nullable foreign keys no longer inherit the referenced identifier's non-nullability; a null embedded
    indexed collection has no positions rather than throwing during flush.
  - 2026-10-09: green real-schema regressions cover two uses of the same embeddable with distinct overrides,
    ordered join tables, embedded basic element collections, persist/merge/refresh/detach/remove cascades,
    dirty updates/nulls, orphan removal, record reconstruction and `NO_CONSTRAINT`. A closed application
    Java module also executes through APT-generated access providers without new exports/opens.
  - 2026-10-09: existing P5 boundaries remain explicit: to-one join tables, unidirectional one-to-many
    foreign-key collections and relationships inside collection-table embeddable elements are not implemented
    by this fix. Unsupported embedded collection shapes fail bootstrap instead of silently dropping state.
    No official TCK sources/DDL, identifier case policy, delivered Data/Pool/Transactions modules or commits changed.

## BUG-20261009-23 — Native result labels and stored-procedure names lose delimited identifier semantics

- **Date**: 2026-10-09
- **Status**: FIXED (query identifier working tree; no commit requested)
- **Module**: `mansart-jpa-core` native/procedure execution; JPA dialect SPI and PostgreSQL dialect
- **Symptom**: native result mappings cannot find explicitly quoted labels, silently choose ambiguous folded
  labels, and ignore unit-wide exact case. `EntityResult.discriminatorColumn` is omitted from the model.
  Quoted qualified routine names are rejected only on execution; unquoted dynamic/named routine names ignore
  the unit default, and named parameter targets are discarded in favor of registration order.
- **Minimal reproduction**:
  ```bash
  ./mvnw -ntp -pl mansart-persistence/mansart-jpa-core -am test \
    -Dtest=NativeUpdateTest,StoredProcedureQueryTest,InheritanceTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
  PostgreSQL reproduction: provider-owned `DelimitedProcedureTest` (command in the runner README).
- **Cause hypothesis**: native lookup compared raw annotation strings case-insensitively against labels and
  underlying column names; routine AST carried a restricted raw string and no argument identifiers.
- **Investigation**:
  - 2026-10-09: genuine execution red showed six initial failures (quoted labels/routines, unit defaults,
    ambiguous labels, late validation); discriminator and non-key field tests added three independent failures.
    The provider-owned PostgreSQL test was red against the pre-fix installed runtime, and the named-target
    dialect test was red with positional SQL.
  - 2026-10-09: mapped `Identifier` policy now resolves JDBC labels by exact unescaped delimited text, or by
    case-insensitive lookup only when unambiguous. Field/discriminator/constructor/scalar mappings share it;
    discriminator values use the existing inheritance binder and generated-access entity loading.
  - 2026-10-09: qualified routines parse quoted dots and doubled quotes; malformed/unclosed or unsafe unquoted
    parts fail before execution. SQL punctuation inside a delimited name is re-escaped, never executed as SQL.
    Parameter API keys stay unchanged; PostgreSQL named target aliases are rendered while JDBC binds/reads
    by ordinal. H2 retains registration order.
  - 2026-10-09: targeted and smallest core/dialect reactor tests are green; two provider-owned real PostgreSQL
    tests verify explicit/default quotation for named/dynamic calls, reversed IN/INOUT registration, output
    retrieval, mixed case and escaped quotes. No full TCK, official fixture edits, P11 or Data changes.

## BUG-20261009-24 — Embedded embeddable-key maps reject otherwise valid persistence units

- **Date**: 2026-10-09
- **Status**: FIXED (working tree; no commit requested)
- **Module**: `mansart-jpa-core` collection index model and executable mapping
- **Symptom**: 51 official `core.metamodelapi.embeddabletype` scenarios fail bootstrap after embedded
  collections become executable: `address.mZipcode` has no executable collection-table mapping.
- **Minimal reproduction**:
  ```bash
  mansart-persistence/mansart-jpa-tck/run-official-tck-persistence-3.2.sh --area core.metamodelapi.embeddabletype
  ./mvnw -ntp -pl mansart-persistence/mansart-jpa-core -am test \
    -Dtest=EmbeddableMapKeyTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause hypothesis**: the collection index marks embeddable keys unsupported; index binders,
  generated key access, flattened key columns, reconstruction and key snapshots are missing.
- **Investigation**:
  - 2026-10-09: retained strict embedded mapping validation; added schema/CRUD regression coverage for
    default tables, reusable embedded collections, nested mutable and record keys, overrides and key projection.
  - 2026-10-09: all five initial H2 regressions reproduced the same bootstrap failure before implementation.
    `ByEmbedded` now carries the owner-specific key mapping; the existing generated-access preparation,
    column flattening, binders and hydration execute keys in element collections and relationship maps.
    Key/value overrides and converters remain independent, including nested paths and quoted identifiers;
    schema generation preserves effective key types, length and nullability.
  - 2026-10-09: key snapshots compare independent persistent state, including in-place mutations, while basic/entity
    key comparisons retain their existing lookup path. Merge copies embedded keys, including nested record state.
    Seven real H2 schema/CRUD tests and a closed-module APT access test pass without new opens or dependencies.
  - 2026-10-09: final untouched official PostgreSQL 17 gate at 20:40:50Z passes **51/51**, with zero failures,
    errors or skips. Final persistence `clean install` passes **549 tests**, including **459 core tests**.
    Reports: `mansart-jpa-tck/target/failsafe-reports/execution-1/`; reactor log:
    `mansart-jpa-tck/target/embeddable-keys-clean-reactor.log`. No full TCK run, official fixture/DDL edits,
    delivered Data/Pool/Transactions changes, commit or push.

## BUG-20261010-01 — Concurrent cache fills can resurrect stale state after commit

- **Date**: 2026-10-10
- **Status**: FIXED (working tree; no commit requested)
- **Module**: `mansart-jpa-core` shared-cache generation and resource-local commit completion
- **Symptom**: a database read racing a cache invalidation could install its pre-commit snapshot after the invalidation;
  a cache publication could also be skipped when JDBC connection release failed after a successful commit.
- **Minimal reproduction**:
  ```bash
  ./mvnw -ntp -pl mansart-persistence/mansart-jpa-core -am test \
    -Dtest=SecondLevelCacheTest#publishesTransactionalReadsOnlyAfterCommitAndFencesStaleFills+SecondLevelCacheTest#runsCacheCompletionAfterCommitWhenConnectionReleaseFails \
    -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause hypothesis**: cache fills had no generation fence, commits republished every managed snapshot, and the release exception
  escaped before the post-commit listener could invalidate or publish committed state.
- **Investigations**:
  - 2026-10-10: added generation-conditional cache stores and transaction-scoped read candidates; commits invalidate changed
    identities and publish only eligible snapshots. A deterministic stale-fill test verifies that an old generation cannot
    repopulate the cache.
  - 2026-10-10: run the post-commit listener after a successful database commit even if connection release fails, while
    preserving the release failure. A regression test verifies committed cache visibility under a simulated close error.
    Both targeted tests pass; fix remains uncommitted as requested.

## BUG-20261010-02 — Validation incorrectly applies Default groups to removal and empty group settings

- **Date**: 2026-10-10
- **Status**: FIXED (working tree; no commit requested)
- **Module**: `mansart-jpa-core` / `ValidationProviderBridge`
- **Symptom**: default removal validates constraints, empty groups still validate Default, comma-separated
  group names are rejected, and invalid group settings fail only on the first entity operation.
- **Minimal reproduction**:
  ```bash
  ./mvnw -ntp -f mansart-persistence/pom.xml -pl mansart-jpa-core -am test \
    -Dtest=PersistenceValidationTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause hypothesis**: all events shared one Default fallback; zero groups were passed to `Validator.validate`,
  which means Default in the validation API, and properties were resolved lazily.
- **Investigations**:
  - 2026-10-10: two new regression methods failed before implementation. Resolve and validate group interfaces
    at bootstrap using the unit loader; accept comma-separated names and existing Class/Class[]/List conveniences.
    Persist/update default to Default; remove defaults to no groups; explicitly empty groups skip validation entirely.
    Five callback/group/ownership tests pass, including default persist/update rollback and default remove success.

## BUG-20261010-03 — Validation bootstrap rejects supplied factories or hides broken available providers

- **Date**: 2026-10-10
- **Status**: FIXED (working tree; no commit requested)
- **Module**: `mansart-jpa-core` / `BeanValidation`, `ValidationProviderBridge`
- **Symptom**: CALLBACK requires a service provider even with a caller-supplied factory; AUTO silently disables
  validation on provider/configuration/linkage failures; an owned factory leaks when `usingContext()` fails.
- **Minimal reproduction**:
  ```bash
  ./mvnw -ntp -f mansart-persistence/pom.xml -pl mansart-jpa-core -am test \
    -Dtest=BeanValidationTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause hypothesis**: provider discovery preceded the supplied-factory check, AUTO caught all runtime/linkage
  failures as absence, and context creation had no owned-factory cleanup.
- **Investigations**:
  - 2026-10-10: configuration and API-linkage regression checks reproduced silent AUTO success before the fix.
    Only missing API/provider is optional; available-provider failures become `PersistenceException`.
    An available API missing its SPI is broken, not a provider-absence simulation.
  - 2026-10-10: isolated unnamed-loader fixtures verify an actually empty ServiceLoader, supplied-factory validation
    and caller ownership, AUTO no-op/CALLBACK refusal without providers, and owned-factory closure on provider
    context failure. Discovery and default-provider bootstrap both use the persistence-unit loader.
    No validation implementation, dependency manifest or module descriptor was changed in this correction.

## BUG-20261010-04 — Class eviction and hierarchy cache misses retain or restore unrelated stale state

- **Date**: 2026-10-10
- **Status**: FIXED (working tree; no commit requested)
- **Module**: `mansart-jpa-core` / `SecondLevelCache`
- **Symptom**: class eviction retains owner graphs referencing the evicted hierarchy and allows old-generation
  fills; subtype misses populate the context with the wrong type; root lookups may cache a noncacheable subtype
  using root metadata, while `contains` claims sibling subtype hits.
- **Minimal reproduction**:
  ```bash
  ./mvnw -ntp -f mansart-persistence/pom.xml -pl mansart-jpa-core -am test \
    -Dtest=SecondLevelCacheTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause hypothesis**: class eviction examined only entry root keys and did not advance generation; restore
  checked assignability after graph hydration; storage/contains used requested instead of actual entity type.
- **Investigations**:
  - 2026-10-10: three new regressions failed before implementation. Class eviction now fences fills and removes
    every graph containing hierarchy references. Restore checks the actual root type and graph cacheability
    before touching context; contains uses the same predicate; capture resolves actual subtype metadata.
  - 2026-10-10: all six SharedCacheMode settings are covered for explicitly noncacheable subtypes.
    Unsupported optional state is skipped with explicit DEBUG logging rather than silently claimed as cached.

## BUG-20261010-05 — Post-commit cache publication reads live state instead of the committed snapshot

- **Date**: 2026-10-10
- **Status**: FIXED (working tree; no commit requested)
- **Module**: `mansart-jpa-core` / `SecondLevelCache`, `EntityManagerImpl`
- **Symptom**: changing a live entity between SQL flush and cache completion publishes a value never committed
  to the database. A JDBC commit-hook regression returned `"not flushed"` instead of `"flushed"`.
- **Minimal reproduction**:
  ```bash
  ./mvnw -ntp -f mansart-persistence/pom.xml -pl mansart-jpa-core -am test \
    -Dtest=SecondLevelCacheTest#publicationUsesFlushedStateRatherThanLiveInstancesAfterJdbcCommit \
    -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause hypothesis**: afterCommit captured managed instances rather than immutable state staged after flush.
- **Investigations**:
  - 2026-10-10: deterministic commit-hook regression failed red. Stage copied graphs after the final flush and
    before JDBC/JTA commit, then publish only that snapshot on successful completion. Rollback discards the stage;
    generation fencing and invalidation remain commit-only. Concurrent readers still see only committed values.
  - 2026-10-10: 25 focused tests pass, followed by the full persistence `clean install`: 575 tests
    (485 core), zero failures/errors/skips. Logs are retained under `mansart-jpa-tck/target/p11-correctness/`.
  - 2026-10-10: full patched PostgreSQL TCK revalidated with validation off and on: 2131/2135 pass,
    zero failures/errors and four official skips each, all identities/outcomes unchanged against the preserved
    pre-correction P11 baseline. Both second executions pass without the validation API/provider jars.
    Reports: `mansart-jpa-tck/target/p11-correctness-neutrality/{off,on}/`.

## BUG-20261015-01 — Target-side collection inserts can reference an owner still pending in a cyclic hierarchy

- **Date**: 2026-10-15
- **Status**: FIXED (working tree; no commit requested)
- **Module**: `noether-jpa-core` / `FlushEngine.insertWithCollectionKeys`
- **Symptom**: while implementing table-per-class target-side foreign keys, a class-level
  association cycle caused `Book` to be inserted before `Shelf`, with `selected_shelf = 1`.
  H2 rejected the insert with a foreign-key integrity violation, even though the owner
  foreign key was nullable and both instances were present in the same transaction.
- **Minimal reproduction**:
  ```bash
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test \
    -Dtest=TablePerClassTest#polymorphicTargetForeignKeysAreWrittenAndQueriedInEachConcreteTable \
    -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause hypothesis**: the target-side collection insert path injected the owner key
  without checking the existing pending-insert set. Ordinary owned references already
  defer nullable keys when the owner row will only be inserted later.
- **Investigations**:
  - 2026-10-15: the extended table-per-class regression failed with H2 error 23506.
    Defer nullable collection owner keys while their owners remain pending; the existing
    post-insert collection phase writes them once both rows exist. Concrete table selection
    and insertable/updatable policies are retained.
  - 2026-10-15: table-per-class, existing alternate relationship mappings, inheritance,
    dialect rendering and closed APT-module regressions pass. Full reactor: 795 tests,
    zero failures/errors/skips. Full patched PostgreSQL TCK: 2135 tests, 2131 passes,
    zero failures/errors and four unchanged official skips.

## BUG-20261015-02 — Second-level cache restore of a `SortedSet` collection throws `ClassCastException`

- **Date**: 2026-10-15
- **Status**: FIXED (working tree; no commit requested)
- **Module**: `noether-jpa-core` / `SecondLevelCache.restore` (collection rebuild)
- **Symptom**: an entity with a `SortedSet` (or `NavigableSet`) collection attribute stored in
  the shared cache could not be restored: the cached set state was rebuilt as a
  `LinkedHashSet` and assigned through the generated access to the `SortedSet` field,
  failing with `ClassCastException` instead of a cache hit.
- **Minimal reproduction**:
  ```bash
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core test \
    -Dtest=LazyCollectionTest#sharedCacheRestoresUnloadedCollectionsWithoutLoadingThem \
    -Dsurefire.failIfNoSpecifiedTests=false
  ```
  (red before the fix with an `ERROR`, the cached `sorted` `SortedSet<Tag>` attribute.)
- **Cause hypothesis**: confirmed — the restore chose the collection implementation from the
  cached shape (set/list/map) only, ignoring the declared attribute type.
- **Investigations**:
  - 2026-10-15: found by the red lazy-collection regression suite. Restore now builds the
    collection from the declared type (`CollectionMapping.newCollection` / `IndexMapping.newMap`),
    so sorted sets and maps come back as `TreeSet`/`TreeMap`. `LazyCollectionTest` (19) and the
    full `noether-jpa-core` suite (528) pass.

## BUG-20261015-03 — Lazy subclass eligibility accidentally replans non-entity accesses

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/access/AccessGenerator`
- **Symptom**: the first lazy-to-one implementation rejected existing embeddables, id classes and
  XML-only entities with "no identifier" during bootstrap.
- **Minimal reproduction**: `./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test`.
- **Cause hypothesis**: confirmed — optional subclass generation invoked the annotation entity
  planner for every generated access, including non-entities and XML-only mappings.
- **Investigations**:
  - 2026-10-15: reproduced in the core regression suite; optional generation now first requires
    an annotated entity with an annotated property identifier. Other accesses retain their original
    generation path; XML-only target mappings remain eager.

## BUG-20261015-04 — Cached eager graphs can retain an unloaded to-one reference

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/session/SecondLevelCache`
- **Symptom**: restoring an eager graph reused an existing lazy reference without initializing it;
  `isLoaded(owner, attribute)` was false despite the eager mapping.
- **Minimal reproduction**: `./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test
  -Dtest=LazyToOneTest#cachedEagerGraphInitializesAnAlreadyManagedLazyReference
  -Dsurefire.failIfNoSpecifiedTests=false`.
- **Cause hypothesis**: confirmed — cache restore skipped state writes for already managed identities,
  assuming all managed instances were fully loaded.
- **Investigations**:
  - 2026-10-15: reproduced red; restore now declines such a graph before allocating nodes.
    The ordinary loader initializes the existing reference in place, preserving eager semantics and identity.

## BUG-20261015-05 — Generated lazy factory resolves application names instead of Java types

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-processor/NoetherJpaProcessor`
- **Symptom**: compilation failed in application packages declaring their own `Override` or `Object`,
  because the added lazy factory used unqualified Java names.
- **Minimal reproduction**: `./mvnw -ntp -pl noether-persistence/noether-jpa-processor -am test
  -Dtest=EdgeCasesTest -Dsurefire.failIfNoSpecifiedTests=false`.
- **Cause hypothesis**: confirmed — unlike the existing accessor templates, the lazy factory
  insertion did not fully qualify `java.lang.Override` and `java.lang.Object`.
- **Investigations**:
  - 2026-10-15: three existing processor regressions failed in the clean reactor;
    all names in the factory insertion are now qualified.

## BUG-20261015-06 — Identifier-named overload bypasses reference initialization

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/model/build/LazySubclassGenerator`
- **Symptom**: a non-identifier method such as `String getId(int ignored)` could return
  an uninitialized default value instead of loading the target row.
- **Minimal reproduction**: `./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test
  -Dtest=LazyToOneTest#anOverloadNamedLikeTheIdentifierGetterStillInitializesState
  -Dsurefire.failIfNoSpecifiedTests=false`.
- **Cause hypothesis**: confirmed — the exemption matched the identifier getter name without
  checking the no-argument signature.
- **Investigations**:
  - 2026-10-15: reproduced red (`null` instead of `"target"`); only the actual zero-argument
    identifier getter is now exempt. Bootstrap subclass names also differ from APT output so
    an old build-time class cannot substitute for freshly planned bootstrap interception.

## BUG-20261015-07 — Refreshing an unloaded reference reads its row twice

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/session/EntityOperations`
- **Symptom**: refreshing a pending reference selected its row twice and invoked the load path
  again while collecting its previous state.
- **Minimal reproduction**: `./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test
  -Dtest=LazyToOneTest#refreshingAPendingReferenceHydratesItInPlaceWithOneSelect
  -Dsurefire.failIfNoSpecifiedTests=false`.
- **Cause hypothesis**: confirmed — ordinary refresh assumes it can read previous entity state;
  an intercepted getter initialized the reference during that read.
- **Investigations**:
  - 2026-10-15: reproduced red with two identical selects; a pending reference now uses its
    initial hydration as the refresh and then applies the normal refresh cascades.

## BUG-20261015-08 — Optional bootstrap subclasses require an undeclared module read edge

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/access/AccessGenerator`
- **Symptom**: a property entity in an opened application module that reads only
  `jakarta.persistence` failed bootstrap when an optional subclass referenced the provider SPI.
- **Minimal reproduction**: `./mvnw -ntp -pl noether-persistence/noether-jpa-module-it -am test
  -Dtest=OpensModulePathTest#aModuleWithoutAReadEdgeToTheGeneratedReferenceSpiKeepsEagerLoading
  -Dsurefire.failIfNoSpecifiedTests=false`.
- **Cause hypothesis**: confirmed — package opens allow accessor lookups, but do not make the
  application module read the generated reference SPI.
- **Investigations**:
  - 2026-10-15: reproduced in the existing strict runtime module vehicle. Such modules now retain
    eager loading, with no added read edge or module-descriptor change. The closed APT module
    already declares the required provider read edge and retains static lazy subclasses.

## BUG-20261015-09 — Null collection elements are omitted by the existing assembler

- **Date**: 2026-10-15
- **Status**: FIXED (working tree, D13)
- **Module**: `noether-jpa-core/flush/EntityLoader`
- **Symptom**: an ordered basic element collection containing `["z", "a", "z", null]`
  reloads as `["z", "a", "z"]`, through both ordinary loading and fetch joins.
- **Minimal reproduction**: persist an `@ElementCollection @OrderColumn List<String>` with
  the four values above, clear the context/cache, then read the collection.
- **Cause hypothesis**: the shared `assemble` method filters every null element before
  constructing the collection, without distinguishing absent outer-join rows.
- **Investigations**:
  - 2026-10-15: reproduced before production changes in the collection-fetch red gate.
    Fetch hydration retains SQL row presence separately, but intentionally reuses the existing
    assembler's null-element semantics. Null-element support is not claimed in D12.
  - 2026-10-15: D13 first added three failing ordinary-lazy, graph/eager and SQL LEFT-fetch
    regressions. The common assembler now retains null element-collection rows; entity
    associations still discard nonexistent targets. SQL fetch row presence remains determined
    by the owner foreign key, not the element value, so empty LEFT joins create no phantom
    element. Ordered lists, duplicate bags and basic maps retain persisted null values.

## BUG-20261015-10 — SQL collection DISTINCT used application equality instead of identity

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/query/JpqlQuery`
- **Symptom**: during D12 development, two different owner identifiers whose entities compare
  equal collapsed to one `DISTINCT` result; single-result checks could miss non-unique owners.
- **Minimal reproduction**: `CollectionFetchJoinTest#distinctAndSingleResultUseManagedIdentityNotApplicationEquality`.
- **Cause hypothesis**: confirmed — deduplicating hydrated roots with `LinkedHashSet` invoked
  the application's `equals`, instead of testing the managed identity.
- **Investigations**:
  - 2026-10-15: reproduced red (one owner instead of two). Query-local deduplication now uses
    instance identity; the persistence context already guarantees one instance per entity identity.

## BUG-20261015-11 — Blanket collection-fetch pagination refusal regressed four TCK scenarios

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/query/Translator`, `JpqlQuery`
- **Symptom**: the initial D12 full patched TCK had four new errors in Criteria metamodel
  collection-fetch tests using finite `setMaxResults`; the initial blanket pagination guard refused them.
- **Minimal reproduction**: `CollectionFetchJoinTest#paginationLimitsDistinctOwnersInSqlThenLoadsTheirWholeCollections`;
  official area `core.criteriaapi.metamodelquery` (`test_fetchjoin_1xM`, the two
  `fetchPluralAttribute*1xMTest` methods and `test_fetchjoin_MxM`).
- **Cause hypothesis**: confirmed — safety was enforced with an exception even where the existing
  identifier-query / secondary-fetch pipeline can safely page distinct owner identities in SQL.
- **Investigations**:
  - 2026-10-15: full run independently reported 2135 tests / 2127 passes / four errors / four skips.
    Added a red owner-pagination regression, then a bounded SQL distinct-owner pagination fallback
    with complete ordinary collection initialization. Complex projections, grouping, locks and
    non-scalar-root ordering remain explicitly refused; no in-memory pagination or partial hydration.
  - 2026-10-15: verified fixed with 151/151 Criteria metamodel-query tests and the final full
    patched TCK: 2135 tests, 2131 passes, zero failures/errors, four unchanged official skips.

## BUG-20261015-12 — Entity-join aliases bypassed the collection pagination guard

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/query/Translator`, `JpqlQuery`
- **Symptom**: during final D12 review, pagination of a collection fetch rooted at an explicit
  entity-join alias did not take the safe owner-page path or the documented complex-shape refusal.
- **Minimal reproduction**: `CollectionFetchJoinTest#unsupportedPaginationIsExplicitlyRefusedBeforeReadingPartialCollections`,
  the `select c ... join CfOwner c ... join fetch c.children` case.
- **Cause hypothesis**: confirmed — the query's separate AST/model walk did not track entity-join aliases.
- **Investigations**:
  - 2026-10-15: reproduced red (no exception). Removed that duplicate walk: the actual translator
    now records whether a declared fetch joins a collection as it resolves the mapping, and propagates
    that fact through set operands. Pagination safety uses this authoritative compiled metadata.

## BUG-20261015-13 — Sorted fetch collections compared partially hydrated targets

- **Date**: 2026-10-15
- **Status**: FIXED (working tree)
- **Module**: `noether-jpa-core/flush/EntityLoader`
- **Symptom**: assembling a fetched `SortedSet` invoked target `compareTo` before its
  relationships had been resolved; a comparator reading the owner's identifier threw `NullPointerException`.
- **Minimal reproduction**: `CollectionFetchJoinTest#sortedCollectionComparatorSeesResolvedElementRelationships`.
- **Cause hypothesis**: confirmed — identities and scalar fields were registered, but collection
  assembly ran before the graph's ordinary relationship-resolution phase.
- **Investigations**:
  - 2026-10-15: reproduced red. Resolve all staged entity relationships first (skipping only
    the fetched collection itself), then assemble complete collections, snapshots and callbacks.
    This preserves the shared assembler's existing ordering contract and complete lifecycle state.

## BUG-20261015-14 — Batch candidate inspection initialized unrelated owner references

- **Date**: 2026-10-15
- **Status**: FIXED (working tree, D13)
- **Module**: `noether-jpa-core/flush/EntityLoader`
- **Symptom**: during batch development, scanning compatible collection owners called a generated
  getter on an unloaded owner reference, issuing an extra entity SELECT and loading beyond the group.
- **Minimal reproduction**: `LazyBatchTest#collectingCollectionCandidatesNeverInitializesUnloadedOwnerReferences`.
- **Cause hypothesis**: confirmed — candidate selection inspected attributes before checking the
  candidate entity's unloaded reference state.
- **Investigations**:
  - 2026-10-15: reproduced red with an owner pointing lazily to another collection owner.
    Candidate selection now excludes unloaded owner references without invoking their accessors.
    The original reference remains unloaded after the collection batch.

## BUG-20261015-15 — Batch initialization ignored a replaced snapshot wrapper

- **Date**: 2026-10-15
- **Status**: FIXED (working tree, D13)
- **Module**: `noether-jpa-core/flush/EntityLoader`
- **Symptom**: the first batch implementation failed existing replacement, merge and shared-cache
  flush regressions with "The lazy collection ... was not loaded".
- **Minimal reproduction**: `LazyCollectionTest#replacingAnUnloadedCollectionWritesTheNewOneAndOrphansTheOldChildren`.
- **Cause hypothesis**: confirmed — flush loads the old snapshot wrapper, whereas the batch
  completion path intentionally preserves the already replaced current collection.
- **Investigations**:
  - 2026-10-15: three existing regressions failed in the first combined green gate.
    A replaced snapshot wrapper now uses ordinary initialization; only the current unloaded
    wrapper participates in collection batching. New collection state and orphan baselines survive.

## BUG-20261015-16 — Batch readers erased primitive SQL NULL binder defaults

- **Date**: 2026-10-15
- **Status**: FIXED (working tree, D13)
- **Module**: `noether-jpa-core/flush/EntityLoader`
- **Symptom**: a nullable external-schema primitive column caused null-unboxing errors
  when hydrating a batched entity or a partially non-null embedded collection element.
  Ordinary loading keeps the primitive binder's default value and a separate SQL-null flag.
- **Minimal reproduction**: the `LazyBatchTest#sqlNullPrimitive*` regressions relax the
  H2 fixture's generated NOT NULL constraint, write SQL NULL and load via a two-owner batch.
- **Cause hypothesis**: confirmed — the initial generic batch reader normalized every
  SQL NULL to Java null, unlike the ordinary entity/element state readers.
- **Investigations**:
  - 2026-10-15: both regressions failed with null-unboxing errors before the fix.
    Batch state payloads now preserve binder values plus null flags. Foreign keys and
    collection indexes are normalized separately, matching the existing ordinary loader.
    The combined D10–D13 focused gate passes 104 tests, including both regressions.

## BUG-20261016-01 — Ordinary find callbacks can reenter a loading collection

- **Date**: 2026-10-16
- **Status**: FIXED (scoped loader fix delivered; D19 partial-cache follow-up verified in the current working tree, pending commit)
- **Module**: `noether-jpa-core/flush/EntityLoader`
- **Symptom**: an ordinary `find` of an owner whose `PostLoad` reads a lazy collection,
  and whose elements' `PostLoad` reads that same collection, throws
  `The lazy collection Group.members was accessed while it was being loaded`.
- **Minimal reproduction**: use `CollectionFetchJoinTest.Group` and `Member`, persist a
  group with one member, clear/evict, then call `em.getReference(Group.class, id)`.
  This API currently delegates to ordinary `find`; it does not create a lazy reference.
- **Cause hypothesis**: ordinary owner `PostLoad` starts lazy batching; element callbacks
  run before that initializing wrapper has been completed.
- **Investigations**:
  - 2026-10-16: observed while adding the D16 unloaded-managed-root regression, before
    executing a graph find. The graph regression now obtains an actual unloaded root
    through an owning lazy association, rather than `getReference`. D12/D16 staged
    collection hydration completes the collection before both callbacks. No ordinary
    callback/batch architecture change is included in this bounded feature.
  - 2026-10-16: cause confirmed. `EntityLoader` invoked `PostLoad` inside each read: at the
    end of `managed`, `hydrateReference` and `refresh`, and at the end of every `loadRows`,
    including the target rows `batchCollection` hydrates before `loadCollection` completes
    the requested wrapper. The owner's callback initializes `members`; the ordinary loader
    (`collection` -> `inverse` -> `find` -> `load`) or the batch loader then runs the
    element callback while `AbstractLazy.loading` is still set, so the element reenters the
    wrapper and hits its guard. A batch size of 1 or 16 fails the same way.
  - 2026-10-16: red before production edits: new `PostLoadCollectionCycleTest` ran
    **20 tests / 0 failures / 15 errors**, every error this guard (`Group.members`,
    `Club.members`, `Node.children`); the `CollectionFetchJoinTest` reproducer (actual
    `Group`/`Member`, `getReference`) failed with the same error.
  - 2026-10-16: fixed with a scoped load orchestration. `PersistenceContext.loading`
    counts the nested loads of the thread-confined persistence context; every public
    `EntityLoader` read (`load`, row `load`, `loadCollection`, `initialize`,
    `initializeReference`, `refresh`) runs inside it, and `PostLoad` is queued through
    `postLoad`. The queue is drained in deferral order only when the outermost load
    returns, so the initializing wrapper itself has been completed in place (and every
    batch or staged collection assembled, every snapshot taken) before any callback runs.
    A callback that loads opens its own outermost load and drains its own callbacks before
    it returns; a failed load discards its queued callbacks and propagates. The guard, the
    D12/D16 staged pipeline, snapshots before callbacks, identity and refresh semantics are
    unchanged; no lock, executor, `ThreadLocal`, reflection or SQL is added.
  - 2026-10-16: green: `PostLoadCollectionCycleTest` 20/20 (batch sizes 1 and 16:
    ordinary find and `getReference`, empty collection, queried groups sharing identity,
    direct lazy access without an owner callback, child-first find, a three-level
    self-referencing tree, persisted callback mutation, propagated callback failure marking
    rollback, refresh loading a new element) asserting the actual element identifiers each
    callback observed, without duplicates and exactly one callback per loaded entity; the
    `CollectionFetchJoinTest` reproducer passes with the D12/D16 tests unchanged.
  - 2026-10-16: full validation: `./mvnw -ntp clean install` **946/946** (86 XML reports,
    925 + 21); full patched PostgreSQL 17 TCK **2135 / 2131 passed / 0 failures / 0 errors /
    4 official skips**, identities and outcomes independently verified equal to the D16
    baseline, official inputs and SHA-256 unchanged (`TCK.md`). Remaining edge: the
    shared-cache restore path still invokes `PostLoad` directly after registering its whole
    graph (no loader scope is open there).
  - 2026-10-16: D19 extends the same load scope across all split root-collection
    collectors and query/find graph completion. Registering every identity and assembling
    every collection before dispatch prevents a callback from observing an uncompleted
    sibling collection. `CollectionSubgraphTest` covers owner/line/payment callbacks
    inspecting all requested branches, once-per-identity callbacks, mutation/flush,
    failure/rollback and managed dirty wrapper/reference preservation. Existing
    `PostLoadCollectionCycleTest` remains green at batch sizes 1 and 16. At this initial
    D19 stage, the direct shared-cache callback implementation was unchanged.
  - 2026-10-16: a further D19 red test exposed that recorded cache edge: warm a root
    with to-one graph state but unloaded collections, clear the context, then find it
    with three root-collection branches and a `PostLoad` asserting those branches
    are already loaded. `partiallyCachedRootDefersCallbacksUntilEveryRequestedCollectionIsComplete`
    fails before production edits (`unloaded(lines)` is true); allowing the callback
    to initialize them instead costs six SELECTs rather than three. Fixed by routing
    cache-restored callbacks through the same `context.postLoad` queue and keeping
    restore plus graph completion in `context.loading`. Known-reference and query
    completion use that scope too. No separate queue or cache representation is added;
    the regression covers both cache-hit find and query reuse of that partial cache.
  - 2026-10-16: final D19 gate **159/159**, clean reactor **977/977** (87 reports),
    full patched PostgreSQL 17 TCK **2135 / 2131 passed / 0 failures / 0 errors /
    4 unchanged official skips**. All identities/outcomes and immutable fixture/input
    hashes independently verified; the previously recorded direct-cache callback edge
    is now covered and closed.

## BUG-20261016-02 — Removing one duplicate bag occurrence deletes every equal join row

- **Date**: 2026-10-16
- **Status**: FIXED in the working tree, full verification complete, pending commit
- **Module**: `noether-jpa-core/flush/FlushEngine.writeJoinRows`
- **Symptom**: a managed many-to-many bag `[hammer, hammer, saw]` loses both hammer
  join rows when one occurrence is removed; a reload returns `[saw]` instead of
  `[hammer, saw]`.
- **Minimal reproduction**: run `CollectionSubgraphTest#nestedFindCompletesExistingWrappersAndReferencesWithoutOverwritingDirtyStateOrFlushing`;
  load the nested discounts bag, remove its first occurrence, commit, clear/evict
  and reload. The final size assertion expected 2 but observed 1.
- **Cause hypothesis**: confirmed — the collection diff counts occurrences, but the
  join-row DELETE identifies only owner and target, deleting all duplicate rows.
- **Investigations**:
  - 2026-10-16: discovered during D20 dirty nested-collection verification. The
    pre-fix focused gate reproduced the persisted-content loss. Duplicate-bearing
    snapshots now reuse the existing complete owner-row rewrite used for indexed
    collections, preserving the remaining multiplicities. Unchanged/unloaded
    collections still write nothing; unique unindexed collections retain diff writes.
  - 2026-10-16: verified by the **168/168** focused graph/lazy/cache/callback gate,
    full clean reactor **986/986**, and full locally patched PostgreSQL 17 TCK
    **2135 / 2131 passed / 0 failures / 0 errors / 4 unchanged official skips**.
    Nested graph reads preserve dirty wrappers and bag multiplicities before and
    after commit/clear/eviction; immutable fixture hashes and all TCK identities/
    outcomes remain unchanged (`TCK.md`).

## BUG-20261016-03 — Initial nested completion routing acquired a connection for complete graph hits

- **Date**: 2026-10-16
- **Status**: FIXED in the D20 working tree, full verification complete, pending commit
- **Module**: `noether-jpa-core/session/EntityManagerImpl.completeGraph`
- **Symptom**: the initial D20 routing preserved zero SELECTs but acquired a new
  JDBC connection on a fully complete managed graph hit outside a transaction.
- **Minimal reproduction**: `CollectionSubgraphTest#nestedFindCompletesPartiallyCachedGraphsBeforeCallbacksAndPreservesFastHits`;
  after completing the nested graph, repeat the managed find and assert that the
  counting data source's connection count is unchanged. The red test expected
  4 acquisitions but observed 5.
- **Cause hypothesis**: confirmed — replacing D19's root-only completeness shortcut
  with unconditional connection routing bypassed the existing JDBC-free fast path.
- **Investigations**:
  - 2026-10-16: added connection-count assertions in addition to existing SQL counts.
    The shared graph-completeness traversal now runs before connection acquisition,
    without initializing lazy wrappers/references. Unloaded nested state still
    reaches the shared split planner; complete managed/cache hits remain JDBC-free.
  - 2026-10-16: final focused gate **168/168**, clean reactor **986/986** and full
    locally patched TCK **2135 / 2131 passed / 0 failures / 0 errors / 4 unchanged
    skips**. Both managed and fully cached nested graph finds assert no new JDBC
    acquisition. Fixture bytes, input file sets and TCK identities/outcomes are
    independently verified unchanged.

## BUG-20261016-04 — Composite unique indexes are not referenceable constraints on H2

- **Date**: 2026-10-16
- **Status**: FIXED (pending commit)
- **Module**: `SchemaPlan`, dialect SPI schema AST.
- **Symptom**: the new composite business-key regression fails during DDL with
  `Constraint "PRIMARY KEY | UNIQUE (REVISION, PART_CODE)" not found`.
- **Minimal reproduction**:
  `./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test -Dtest=BusinessKeyToOneTest -Dsurefire.failIfNoSpecifiedTests=false`
- **Cause**: `@Table(uniqueConstraints=...)` was emitted as a unique index, not
  a unique constraint. H2 does not accept that index as the referenced constraint.
- **Investigations**:
  - 2026-10-16: single-column business-key roundtrip passed; adding the reordered
    composite reference reproduced the DDL failure before its implementation fix.
  - 2026-10-16: added `ADD_UNIQUE_CONSTRAINT` to Noether's own SQL AST and standard
    renderer. Table/XML unique constraints now precede foreign keys as real
    constraints. The composite and XML roundtrips pass without changing H2 settings.

## BUG-20261016-05 — Business-key bring-up exposed identity and flush safety gaps

- **Date**: 2026-10-16
- **Status**: FIXED (pending commit)
- **Module**: `EntityOperations`, `EntityManagerImpl`, `JpqlQuery`, `FlushEngine`.
- **Symptom**: intermediate D22 implementations accepted contradictory detached
  tuples, silently cleared an association when bulk SET named a missing identity,
  compared converted domain values with database-value snapshots, and deferred
  required same-type business foreign keys to SQL NULL.
- **Minimal reproduction**:
  `./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test -Dtest=BusinessKeyToOneTest -Dsurefire.failIfNoSpecifiedTests=false`
- **Cause**: identity-key shortcuts, raw snapshot equality and type-only insert
  ordering were insufficient for non-ID tuples.
- **Investigations**:
  - 2026-10-16: separate failing regressions retained in the `non-key-to-one-`
    detached, bulk, converter and ordering red logs. These are bring-up defects,
    not regressions claimed against the previously unsupported baseline.
  - 2026-10-16: detached values are checked against actual managed identity with
    existing state policies; bulk targets must exist; immutability uses converter-aware
    dirty checking. Required business-key dependencies are ordered per instance,
    same-row references bind directly, nullable cycles retain deferred writes, and
    non-null dependency cycles are explicitly refused before SQL.
  - 2026-10-16: a further bootstrap red protects the bounded write contract:
    read-only/shared-column business joins are explicitly refused, avoiding the
    existing deferred-FK path's unrestricted reference updates for these shapes.
  - 2026-10-16: a COMMIT-flush-mode red exposed the same bulk-assignment safety
    gap for a managed but not-yet-inserted target. Validation now checks and
    locks the actual database identity row, not the persistence context/cache,
    before executing the business-tuple scalar subqueries.

## BUG-20261016-06 — MapsId generated parents leave phantom zero IDs and allow reassignment

- **Date**: 2026-10-16
- **Status**: FIXED (pending commit)
- **Module**: `MappedEntity`, `MappedUnit`, `FlushEngine`, `EntityLoader`, `SchemaPlan`.
- **Symptom**: a cascaded IDENTITY parent is inserted correctly, but its dependent's
  primitive ID remains zero, including PostPersist and cache state. Reassigning or
  clearing a persisted MapsId relationship silently changes its identity tuple.
  Invalid names/types are accepted at bootstrap.
- **Minimal reproduction**:
  `./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test -Dtest=MapsIdToOneTest -Dsurefire.failIfNoSpecifiedTests=false`
- **Cause**: derivation ran before cascaded generated IDs were assigned and was
  not repeated at insert; MapsId shapes and managed identities were not validated.
- **Investigations**:
  - 2026-10-16: initial red, before production edits: 9 tests, 9 failures,
    no errors. Preserved as `mapsid-to-one-red.log` in session artifacts.
  - 2026-10-16: initial full patched TCK exposed four refresh-related errors
    (annotations.mapsid and derivedid.ex1b/ex2b/ex3b): refresh rebuilt embedded
    state without deriving relationship-mapped components. New provider-owned
    red proves the same defect and the specification's read-only embedded-part
    contract. Refresh now derives before snapshots/PostLoad; direct writes to
    mapped embedded components are ignored, not misclassified as identity changes.
  - 2026-10-16: follow-up reds cover sequence cascade timing, immutable-record
    keys, missing-parent diagnostics, pre-flush/callback/bulk identity changes,
    physical incoming-FK names and BIGINT types, cyclic insertion order and
    unsupported nested shapes. Normative §2.4.2.1 requires OneToOne for a shared
    whole ID; a dedicated red prevents accepting simple-ID ManyToOne mappings.
  - 2026-10-16: final focused gate **305/305**, full clean reactor **1058/1058**
    (39 new provider-owned tests), and full locally patched PostgreSQL TCK
    **2135 tests / 2131 passed / 0 failures / 0 errors / 4 unchanged skips**.
    Independent aggregation verifies 259 XML reports, unchanged test identities/
    outcomes, 518 effective classpath checks and unchanged file sets/hashes for
    49 official/derived inputs. No fixture or exclusion was modified.

## BUG-20261016-07 — Collection-element relationship bring-up exposes override, hydration and index gaps

- **Date**: 2026-10-16
- **Status**: FIXED (working tree; D24, not yet committed)
- **Affected modules**: `EntityModelBuilder`, `EntityStatements`,
  `ElementCollectionMapping`, `Translator`
- **Symptoms**:
  - Map `@AssociationOverride(name = "value.target")` is treated as an unknown
    embeddable path instead of stripping the map-value prefix.
  - A relationship-only embeddable element loads as null because entity hydration
    skips leading FK columns even after they have been resolved to managed targets.
  - `INDEX(v)` over an ordered element-collection join has no position expression.
  - Unsupported owner keys/indexes silently omit a relationship-bearing
    collection's executable mapping instead of refusing it at bootstrap.
- **Minimal reproduction**:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test \
    -Dtest=ElementCollectionRelationsTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause**: association overrides did not share attribute overrides' map-prefix
  handling; entity FK hydration assumes references are resolved separately after
  constructing entity state; element joins omitted the existing position slot;
  historical unsupported collection mappings returned an empty optional.
- **Investigations**:
  - 2026-10-16: initial red before production edits: **7 tests, 1 failure,
    6 errors**. After the map-prefix correction, missing collection-row FK
    execution remains independently red.
  - 2026-10-16: the extended safety gate produces **22 tests, 20 passed,
    2 errors** for a reference-only value followed by flush and for `INDEX`.
    Hydration now explicitly admits already-resolved collection FK values,
    without changing ordinary entity FK hydration; ordered element joins carry
    their existing index expression.
  - 2026-10-16: owner/index safety red: **1 test, 1 failure**. Relationship-bearing
    collections now reject unmapped owner keys or unsupported index shapes.
  - 2026-10-16: unloaded REFRESH cascade red: **1 test, 1 failure**. Previously
    managed targets kept dirty state when their only cascade path was an unloaded
    value collection. Required REFRESH/REMOVE cascades now initialize the collection;
    PERSIST/MERGE still skip unloaded state and unrelated lazy collections stay lazy.
  - 2026-10-16: final focused **303/303**, clean reactor **1082/1082**,
    PostgreSQL 17 annotation/XML probe **2 scenarios / 40 checks**, and full
    locally patched TCK **2135 / 2131 passed / 0 failures / 0 errors / 4 skips**.
    Independent verification retains all 259 reports, identities/outcomes,
    518 effective classpaths and 49 official/derived input hashes.

## BUG-20261017-01 — Unsupported inverse collection paths and embeddable targets pass bootstrap

- **Date**: 2026-10-17
- **Status**: FIXED (working tree; verification in `TCK.md`)
- **Affected module**: `noether-jpa-core`, `mapping.MappedUnit`
- **Symptom**: factory creation accepts `@OneToMany(mappedBy="values.target")`
  when `values` is an element collection, the analogous inverse one-to-one,
  and an entity association whose target is an embeddable. The inverse loader
  cannot resolve a collection path as an owning entity attribute; no element
  entity identity exists. Annotation and metadata-complete XML reproduce it.
- **Minimal reproduction**:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test \
    -Dtest=InverseElementCollectionTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Cause**: ordinary inverse plans defer owning-path resolution until load.
  `EmbeddedPaths` lowers single embedded attributes only; collection elements
  are not execution slots of the containing entity. Inverse associations also
  bypass the owning-FK target check.
- **Investigations**:
  - 2026-10-17: normative §2.7–2.8/§2.11/§11.1.31/§11.1.42 gate stops inverse
    element execution. Entity targets that are embeddables and inverse to-ones
    inside collection values are illegal; inverse *containing-entity* paths across
    collections remain explicitly unsupported, not universally declared illegal.
  - 2026-10-17: failing-first annotation/XML gate: **14 tests / 10 failures /
    0 errors / 4 passes**. An isolated repeat removes unrelated invalid mappings
    from the embeddable-target case and retains exactly the same failures.
  - 2026-10-17: semantic-model bootstrap validation rejects these mappings
    before access/mapping/schema generation; focused gate **97/97**, including
    D24 owning tests and positive single-embedded inverse entity identity checks.
  - 2026-10-17: final clean reactor **1096/1096**, **91 reports**; isolated
    PostgreSQL 17 annotation/XML probe **2 scenarios / 38 checks**; full locally
    patched TCK **2135 / 2131 passed / 0 failures / 0 errors / 4 official skips**
    across **259 reports**. All **2135** identities/outcomes, **518** effective
    classpaths and **49** immutable input hashes checked independently; no
    official fixture or exclusion change. Not a certification claim.

## BUG-20261018-01 — Whole MapsId key copying depends on parent attribute declaration order

- **Date**: 2026-10-18
- **Status**: FIXED (D39, 2026-10-18)
- **Affected module**: `noether-jpa-core`, `mapping.MappedEntity.deriveId`
- **Symptom**: persisting a dependent with a whole EmbeddedId MapsId from an
  IdClass parent fails at commit with `@MapsId identity ... cannot be changed
  or cleared` when the parent declares its ID attributes in a different order
  from the shared key class. The values are swapped during key derivation.
- **Minimal reproduction**:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core \
    -Dtest=ElementCollectionRelationsTest#wholeMapsIdCopiesParentKeyByAttributeRatherThanDeclarationOrder \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **Confirmed cause**: whole-key derivation copies the parent's flattened
  database tuple into the dependent key by position instead of reading the
  parent's identifier object through the dependent's generated key access.
- **Investigations**:
  - 2026-10-18: the D39 collection scenario exposes the issue after widening
    eligibility. An isolated regression without any collection owner reproduces
    the same commit failure, proving the existing whole-key lifecycle is affected.
  - 2026-10-18: read the parent's identifier object through the dependent key's
    generated access before constructing its independent copy. This aligns key
    attributes without depending on the parent's physical tuple order.
  - Both failing regressions now pass; annotation/XML collection, cache and
    mutation checks pass in the **91/91** coupled gate. Full clean install:
    **1129/1129**, 92 reports. Full locally patched TCK:
    **2135 / 2131 passed / 0 failures / 0 errors / 4 official skips**, 259 reports,
    with every case identity/outcome identical to D26 and D38. Not certification.

## BUG-20261018-02 — Record MapsId hydration unboxes an unread derived primitive

- **Date**: 2026-10-18
- **Status**: FIXED (D42, 2026-10-18)
- **Affected module**: `noether-jpa-core`, `mapping.EntityStatements.embeddable`
- **Symptom**: reloading a record EmbeddedId with a named primitive MapsId
  component fails with an unboxing NullPointerException. Persist and the
  physical identifier tuple are correct; initial row hydration constructs the
  record before the relationship-derived primitive has been populated.
- **Minimal reproduction**:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core \
    -Dtest=ElementCollectionRelationsTest#multipleCompositeMapsIdRecordAndSimpleComponentsKeepDefaultJoinOrder \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **Confirmed cause**: named MapsId components are omitted from embedded
  scalar columns. Mutable keys can defer those slots, but a record constructor
  must receive the primitive's value before relationship resolution.
- **Investigations**:
  - 2026-10-18: D42 mixed generated-simple/composite record-key regression
    persists correctly and fails on find in `EntityStatements.embeddable`.
  - 2026-10-18: isolated find without a collection owner reproduces the same
    unboxing failure. A bootstrap record-identifier plan now maps scalar key
    components to their own columns or their parent's FK column; row hydration
    constructs the record with those actual values before relationships resolve.
    Composite component objects are derived through existing generated access.
    No guessed primitive defaults or reflection are introduced.
  - Isolated and collection regressions pass, including refresh and an entirely
    scalar-derived record key with no independent identifier columns.
    Coupled gate **105/105**; full clean install **1143/1143**, 92 reports.
    Full locally patched TCK **2135 / 2131 passed / 0 failures / 0 errors /
    4 official skips**, 259 reports; all case identities/outcomes match D26
    and D41. This is local regression evidence, not certification.

## BUG-20261018-03 — Persistence-context property maps can share a binding key

- **Date**: 2026-10-18
- **Status**: FIXED (fix/binding-key, #45)
- **Affected module**: `noether-jpa-cdi`, `NoetherPersistenceExtension.binding`
- **Symptom**: distinct persistence-context configurations receive the same
  qualifier; only the first configuration survives registration.
- **Minimal reproduction**: register two otherwise identical context injection
  points with properties `{x="a, y=b"}` and `{x="a", y="b"}`.
  Both bindings end in `{x=a, y=b}` and the context map contains one entry.
- **Confirmed cause**: `Map.toString()` is not an injective encoding of property
  names and values; `putIfAbsent` discards the second configuration.
- **Investigations**:
  - 2026-10-18: a scratch regression invokes the actual binding method with
    annotation metadata and checks the context registry. Equal qualifier values
    and one retained configuration reproduce the defect. Evidence:
    session artifact `java25-review/a/BindingCollisionRepro.java` and `a/output.txt`.
    No production fix or tracked regression has been applied.
  - 2026-11-11: fixed during the audit (#45). `PersistenceBindingKeyTest` reproduces both
    collisions (`Map.toString()` of `{x="a, y=b"}` and `{x="a", y="b"}`, separators inside a unit
    name or a value), red on the extracted original key, green once every part of the key is
    written as its length, a colon and itself.

## BUG-20261018-04 — TABLE generation cannot allocate with an occupied one-slot pool

- **Date**: 2026-10-18
- **Status**: FIXED (branch `fix/table-generator-pool`, pull request into `fix-proposals`, Vidocq/noether#45)
- **Affected module**: `noether-jpa-core`, `generation.IdGenerators`
- **Symptom**: a valid TABLE-generated persist fails with a connection acquisition
  timeout when the resource-local transaction already holds the pool's only connection.
- **Minimal reproduction**: configure `io.vidocq.noether.jpa.pool.max-size=1`
  and `io.vidocq.noether.jpa.pool.acquire-timeout=PT0.5S`; begin a transaction,
  execute `select 1`, then persist a TABLE-generated entity.
- **Confirmed cause**: allocation requests an independent connection while the
  transaction retains the sole pooled connection; it cannot release it until completion.
- **Investigations**:
  - 2026-10-18: a scratch H2 regression using the existing TABLE-generated
    `Badge` fixture fails with `PersistenceException` caused by pool acquisition
    timeout at max-size 1; the same scenario succeeds at max-size 2.
    Evidence: session artifact `java25-review/c/TablePoolRepro.java` and `c/output.txt`.
    Persist as the first transaction operation was not evaluated. No production
    fix or tracked regression has been applied.
  - 2026-11-11 (audit F14, #45, branch `fix/f6-f12-f14`): allocating on the transaction's connection, as decided,
    fixes the reproduction but is not safe with the factory-wide block cache of `IdGenerators`, so it was not
    applied. A scratch change that runs the TABLE allocation on the caller's transaction connection makes the
    reproduction above pass (H2, `Badge`, max-size 1, `PT0.5S`). A second scratch test then fails on the same
    change: a first transaction allocates `Badge` ids 100–109 and rolls back, which removes the generator row
    again; a second transaction takes 101–109 from the block the factory kept and commits; a third transaction
    allocates 100–109 again and its commit fails with `EntityExistsException`, duplicate primary key 101. The ids
    of a rolled-back allocation are not private to that transaction: the factory cache hands them to others before
    the rollback. Options for the maintainer: a block private to its transaction (each inserting transaction then
    holds the generator row until it ends, so concurrent inserting transactions serialize), a block private until
    its transaction commits and shared after that (needs a completion hook for resource-local and JTA), or the
    current separate connection with a documented or enforced minimum pool size of 2. No production change.
  - 2026-11-11 (fix, branch `fix/table-generator-pool`, #45): the maintainer kept the separate connection, as
    Hibernate and EclipseLink do (the generator row is locked only for the allocation, and a block shared by the
    factory is safe because its allocation is committed on its own), and chose a refusal at bootstrap. Reproduced
    first on the module path with the real `noether-jpa-pool-mansart` pool (`max-size=1`, `PT0.5S`, a `Ticket` with a
    TABLE generator, `select 1`, then persist): `PersistenceException: Unable to generate an identifier: no connection
    available within PT0.5S`, caused by `io.vidocq.mansart.pool.PoolException`. Fix: `ConnectionSource.maxSize()`
    reports the `io.vidocq.noether.jpa.pool.max-size` of a Noether pool, and `IdGenerators.checkConnections`, called
    by `NoetherPersistenceProvider` before schema generation, refuses a unit that has a TABLE generator (or `AUTO`
    resolved to one: not a `UUID` identifier, no sequence generator named) and a pool below 2 connections. The
    `PersistenceException` names the unit, each TABLE generator and the property. A `DataSource` of the application
    or the container, a pool left at its default size and a unit without a pool are not checked; the minimum of two
    connections is documented (Javadoc of `ConnectionSources`, the module page of the Antora docs). Tests:
    `ConnectionPoolModulePathTest.aTableGeneratorRefusesAOneConnectionPoolWhenTheFactoryIsCreated`, red on 6968229
    (the factory was created), green after the fix; with two connections the block 1–10 is committed beside a
    transaction that rolls back; a one-connection pool without a TABLE generator and a TABLE generator without a pool
    are still accepted. `IdGeneratorsTest` 5/5, `ConnectionSourcesTest` 10/10. The JTA path of a server is a separate
    defect, BUG-20261111-04.

## BUG-20261111-01 — A JTA unit bootstrapped by a Jakarta EE server is refused

- **Date**: 2026-11-11
- **Status**: FIXED (branch `feat/openliberty`, pull request into `fix-proposals`, Vidocq/noether#45)
- **Affected module**: `noether-jpa-core`, `NoetherPersistenceProvider.build` (no transaction bridge outside
  `noether-jpa-cdi`)
- **Symptom**: on Open Liberty 26.0.0.10 (`persistenceContainer-3.2`), a WAR whose `persistence.xml` names
  Noether with a `<jta-data-source>` fails to bootstrap: `CWWJP0015E … Persistence unit library is JTA: JTA
  entity managers come with the container integration (noether-jpa-cdi); supply its TransactionIntegration
  bridge`. Liberty then leaves the `@PersistenceContext` field `null` and every request fails with a
  `NullPointerException`.
- **Minimal reproduction**:
  ```sh
  ./mvnw -ntp -pl noether-it-other-containers,noether-it-other-containers/noether-it-openliberty verify
  ```
  (3/3 `OpenLibertyPortabilityIT` failures before the fix.)
- **Confirmed cause**: a container bootstraps a JTA unit through `createContainerEntityManagerFactory` with its
  own JTA data source (§9.6) and never passes Noether's `io.vidocq.noether.jpa.transaction-integration` object;
  the core refused every JTA unit without it. The only bridge, `noether-jpa-cdi`'s `JtaIntegration`, needs a CDI
  `TransactionManager` bean and enlists its own Mansart `ConnectionXAResource`, which a server's JTA data source
  (already enlisted by the server) must not receive.
- **Investigations**:
  - 2026-11-11: red `OpenLibertyPortabilityIT` (writes, server rollback of a flushed insert, find outside a
    transaction), Liberty `messages.log` shows the refusal above.
  - 2026-11-11: fix — new SPI `TransactionIntegrationProvider` (core, `ServiceLoader` on the unit's loader when no
    bridge is passed) and new module `noether-jpa-jta`: `SynchronizationRegistryIntegration` joins through an
    interposed synchronization of the standard `TransactionSynchronizationRegistry`, flushes before completion,
    detaches after it, never enlists, commits or rolls back a connection and never moves JDBC work to another
    thread; `JndiTransactionIntegrationProvider` reads `java:comp/TransactionSynchronizationRegistry`.
    `SynchronizationRegistryIntegrationTest` 7/7 (real transaction manager, enlisting test data source), Liberty
    IT 3/3.

## BUG-20261111-02 — `noether-jpa-cdi` cannot inject a persistence context on Quarkus

- **Date**: 2026-11-11
- **Status**: FIXED (branch `feat/quarkus`, pull request into `fix-proposals`, Vidocq/noether#45)
- **Affected module**: `noether-jpa-cdi`, `NoetherPersistenceExtension`
- **Symptom**: a Quarkus 3.40.1 application with `noether-jpa-cdi` and `@PersistenceContext EntityManager em`
  fails at build: `UnsatisfiedResolutionException: Unsatisfied dependency for type jakarta.persistence.EntityManager
  and qualifiers [@Default]`. Once that is fixed, the first injection fails at run time:
  `No bean found for required type EntityManagerFactory and qualifiers [@PersistenceBinding("unit:library")]`.
- **Minimal reproduction**:
  ```sh
  ./mvnw -ntp -pl noether-it-other-containers/noether-it-quarkus -am \
    -Dtest=QuarkusPortabilityTest -Dsurefire.failIfNoSpecifiedTests=false verify
  ```
- **Confirmed causes**:
  1. ArC indexes the application, not every dependency: it never saw that `PersistenceBinding`, the annotation the
     extension adds to each injection point, is a qualifier, and resolved the injection as `@Default`.
  2. ArC removes the beans no injection point uses. The per-unit `EntityManagerFactory` synthetic bean is only looked
     up programmatically by the persistence-context creator, so it was removed. Proven by running the same test with
     `quarkus.arc.remove-unused-beans=none`: 3/3 green.
- **Investigations**:
  - 2026-11-11: fix in the extension, portable CDI 4.1 Lite API only: `@Discovery` declares `PersistenceBinding`
    through `MetaAnnotations.addQualifier`; each factory bean gets an internal name (a named bean is never removed).
    `QuarkusPortabilityTest` 3/3 without any Quarkus setting, Vauban `ContainerModuleTest` 4/4,
    `JtaIntegrationTest` 13/13, clean reactor 1188 tests green.

## BUG-20261111-03 — On Vauban, the extension run at build time injects no persistence context

- **Date**: 2026-11-11
- **Status**: FIXED upstream, Vidocq/vauban#285 (Vauban BUG-20261111-01 and BUG-20261111-02); found by the Noether audit (#45, F9)
- **Affected module**: `noether-jpa-cdi` on Vauban; the cause is in `vauban-core`
- **Symptom**: with `noether-jpa-cdi` on the annotation processor path, as a Vauban
  application declares it, the container boot fails: `Unsatisfied dependency: field
  Repository.factory of type EntityManagerFactory with qualifiers [@PersistenceBinding("unit:container")]`.
  A container of a subset of the processed classes fails earlier:
  `IllegalStateException: Enhanced class is missing from the index`.
- **Minimal reproduction**: `noether-jpa-cdi-module-it`, `BuildTimeExtensionModuleTest`:
  `VaubanContainer.builder().scanClasspath().build()`, then select `Repository`.
- **Confirmed cause**: Vauban's build-time synthetic-bean metadata kept the qualifier
  type and dropped its members, so none of the six synthetic beans matched; and the frozen
  enhancement patch failed the boot for a class outside the deployment.
- **Investigations**:
  - 2026-11-11: the module-path IT only registered the extension at runtime
    (`addBeanClass(NoetherPersistenceExtension.class)`), so the build-time path was never
    run. With the extension on the processor path: red on the current Vauban snapshot
    (5 errors), green on Vidocq/vauban#285 (5/5), installed in a scratch local repository.

## BUG-20261111-04 — TABLE allocation runs on an enlisted connection of a server's JTA data source

- **Date**: 2026-11-11
- **Status**: FIXED (branch `fix/table-non-jta`, pull request into `fix-proposals`, Vidocq/noether#45)
- **Affected module**: `noether-jpa-core`, `generation.IdGenerators`, on a JTA unit bootstrapped by a server
  (`noether-jpa-jta`, `SynchronizationRegistryIntegration`)
- **Symptom**: on Open Liberty 26.0.0.10, every persist of an entity with a `TABLE` generator in a JTA transaction
  fails: `PersistenceException: Unable to generate an identifier: DSRA9250E: Opération setAutoCommit non autorisée au
  cours d'une transaction globale pour les connexions partageables.` (the server's French locale; the English message
  is "Operation setAutoCommit is not allowed during a global transaction for Shareable Connections"), caused by
  `jakarta.resource.spi.SharingViolationException`. The generator table stays empty and nothing is inserted.
- **Minimal reproduction**: add to the `noether-it-openliberty` WAR an entity with
  `@GeneratedValue(strategy = TABLE, generator = "vouchers")` and
  `@TableGenerator(name = "vouchers", table = "VOUCHER_IDS", initialValue = 1, allocationSize = 10)`, list it in the
  `library` unit, and persist then flush it in a `@Transactional` method called from a REST resource. Then:
  ```sh
  ./mvnw -ntp -pl noether-it-other-containers/noether-it-openliberty -am verify -Dtest=none \
    -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=TableGeneratorExperimentIT -Dfailsafe.failIfNoSpecifiedTests=false
  ```
- **Confirmed cause**: `IdGenerators` allocates through the unit's `ConnectionSource`. For a JTA unit that a server
  bootstraps (§9.6), that source is the server's JTA data source. Called inside the transaction, it returns a
  connection that the server enlists in the global transaction (on Liberty, a handle shared with the persistence
  context's connection). `IdGenerators` then calls `setAutoCommit(false)`, `commit()` and `close()` on it, as for its
  own local transaction. A connection in a global transaction must not be committed by the application, and Liberty
  refuses the first call. `SynchronizationRegistryIntegration` is designed never to commit or switch the auto-commit
  mode of a connection; the allocation path bypasses it.
- **Investigations**:
  - 2026-11-11 (audit F14, #45, found while fixing BUG-20261018-04): three scratch experiments, not committed.
    Evidence: session artifacts `f14-jta-table-experiment.diff` (the test code) and
    `f14-jta-table-experiment-output.txt` (their output).
    1. Open Liberty, the reproduction above: tx1 (persist, flush, application exception) and tx2 to tx12 (persist,
       flush, commit) all fail with the DSRA9250E above. `VOUCHER_IDS` and the entity table stay empty. No duplicate
       identifier can occur there, because nothing is ever allocated.
    2. `noether-jpa-jta` stand-in (`EnlistingDataSource` over `MansartTransactionManager`): a persist that allocates
       enlists a second connection in the JTA transaction (2 enlisted, 1 otherwise). The H2 connection accepts the
       commit, so the block is visible from outside before the business transaction ends and survives its rollback:
       no duplicate over 12 transactions (one rollback, then 11 commits). But the connection is closed before
       completion, and the transaction that fetched the second block fails at commit: `RollbackException: Last-resource
       commit failed — transaction rolled back` caused by `XAException` caused by H2 "object already closed"; its row
       (id 11) was nevertheless written. The application is told "rolled back" for a committed row.
    3. `noether-jpa-cdi` `JtaIntegration` (Vauban, Quarkus): not affected. The unit's connections come from
       `jakarta.persistence.jdbc.url` (`DriverManager` or the Noether pool); only the persistence context's connection
       is enlisted, explicitly. The rollback of tx1 leaves `COUPON_IDS` at 10, tx2 to tx12 commit ids 2 to 12, and the
       second block (11 to 20) is allocated cleanly. With a Noether pool, the bootstrap check of BUG-20261018-04
       applies.
    The duplicate hazard of the shared block (BUG-20261018-04) would only come back on a container whose JTA data
    source ignores `commit()` on an enlisted connection, so that the allocation completes with the business
    transaction. Not observed.
  - Options for the maintainer, none applied: (a) allocate through the unit's `non-jta-data-source` when it has one
    (§8.2.1.5; on Liberty a `dataSource` with `transactional="false"`), and refuse at bootstrap a JTA unit with a
    TABLE generator and no non-JTA data source; (b) suspend the JTA transaction around the allocation: the standard
    `TransactionSynchronizationRegistry` cannot suspend, and a server's `TransactionManager` has no portable JNDI name,
    so the bridge SPI would need a new operation per server; (c) refuse a TABLE generator (and `AUTO` resolved to
    one) on a JTA unit bootstrapped by a server, pointing to `SEQUENCE` or `IDENTITY`; (d) allocate on the
    transaction's connection without committing: this brings back the duplicate after a rollback of BUG-20261018-04
    unless each block stays private to its transaction.
  - 2026-11-11 (branch `feat/container-jta`, decision D45): item 3 above no longer holds. A JTA unit on a CDI container
    now takes its connections from the container's `DataSource` bean, which enlists them, so the allocation fails there
    too. Scratch experiment on `noether-it-quarkus` (an entity with a `TABLE` generator, persist then flush in
    `@Transactional`, 3 times; not committed, session artifacts `cj-quarkus-table-experiment.diff` and
    `cj-quarkus-table-experiment.log`): every persist fails with `PersistenceException: Unable to generate an
    identifier: Attempting to rollback while enlisted in a transaction` (Agroal refuses `commit()` and `rollback()` on
    an enlisted connection). The test data sources of `noether-jpa-jta`, `noether-jpa-cdi` and
    `noether-jpa-cdi-module-it` refuse them the same way since BUG-20261111-05. The options above apply unchanged;
    the `non-jta-data-source` of option (a) would be a second `DataSource` bean on a CDI container.
  - 2026-11-11 (branch `fix/table-non-jta`): the maintainer chose option (a). A JTA unit allocates its TABLE blocks
    (and those of `AUTO` resolved to TABLE) through its `non-jta-data-source`, on a connection of its own that it
    commits itself, as a resource-local unit does. A server passes that data source as
    `PersistenceUnitInfo.getNonJtaDataSource()` or the `jakarta.persistence.nonJtaDataSource` property; on a CDI
    container `noether-jpa-cdi` looks up the `DataSource` bean `@Named` after the `non-jta-data-source` and passes it
    as that property. A JTA unit with a TABLE generator and no non-JTA data source is refused when the factory is
    created, with a `PersistenceException` that names the unit, its TABLE generators and the two ways out. Resource-local
    units are unchanged, the refusal of a one-connection Noether pool (BUG-20261018-04) included. Red first, then
    green: `noether-jpa-jta` `SynchronizationRegistryIntegrationTest` (allocation on a non-enlisted connection; a
    rollback then 11 commits with no duplicate; the refusal), `noether-jpa-cdi-module-it` `DescriptorUnitModuleTest`
    (the named bean on Vauban; the refusal without it), `noether-it-openliberty` `TableGeneratorIT` (red: Liberty's
    `createContainerEntityManagerFactory` gets the refusal; green with a second `dataSource`, `transactional="false"`,
    as the `non-jta-data-source`: no DSRA9250E, no duplicate after a rollback), and `noether-it-quarkus`
    `TableGeneratorTest` (a datasource with `jdbc.transactions=disabled`; no duplicate, no Agroal warning).

## BUG-20261111-05 — On Quarkus, Agroal warns at every commit that Noether left a connection open

- **Date**: 2026-11-11
- **Status**: FIXED (branch `feat/container-jta`, pull request into `fix-proposals`, Vidocq/noether#45)
- **Affected module**: `noether-jpa-jta`, `SynchronizationRegistryIntegration`
- **Symptom**: each Quarkus transaction that touches a JTA unit logs
  `WARN [io.agroal.pool] Datasource '<default>': Closing 1 open connection(s) prior to commit`
  (12 times in one run of `noether-it-quarkus`). Production log noise on every transaction.
- **Minimal reproduction**: `noether-it-quarkus`, `AgroalConnectionReleaseTest`: a `java.util.logging` handler on
  `io.agroal.pool` around `library.add("...")` records the warning.
- **Confirmed cause**: the bridge took one connection handle per transaction and closed it only in its
  `afterCompletion`. Agroal's interposed synchronization checks the handles in `beforeCompletion` and closes, with a
  warning, every one still open ("on after completion" for a handle still open when the transaction ends).
- **Investigations**:
  - 2026-11-11: red first, 4 of 5 `AgroalConnectionReleaseTest` cases (commit, flush before commit, the application's
    connection taken first, and the commit that follows a rollback).
  - 2026-11-11: closing the handle at the end of Noether's `beforeCompletion` clears the Quarkus warnings, because
    Narayana runs Noether's synchronization first here. It still leaves the handle open while an interposed
    synchronization registered earlier runs, and JTA leaves the order of interposed synchronizations open: the
    `noether-jpa-jta` tests that register such a synchronization before Noether's stay red with it.
  - 2026-11-11: fix: each unit of work takes a handle from the container's data source and closes it when the work
    ends, the flush before completion included. Within a transaction the container hands out the connection it
    enlisted again (connection sharing: Agroal, Open Liberty, the test data sources). Only the connection handed out by
    `EntityManager.unwrap(Connection.class)` stays open until Noether's synchronization. `AgroalConnectionReleaseTest`
    5/5, `SynchronizationRegistryIntegrationTest` 15/15; the test data sources of `noether-jpa-jta`, `noether-jpa-cdi`
    and `noether-jpa-cdi-module-it` now share one enlisted connection per transaction, as a container does.

## BUG-20261111-06 — Mansart Data cannot compile an entity with a collection-valued field

- **Date**: 2026-11-11
- **Status**: FIXED upstream (Vidocq/mansart#96, MANSART-049: 69be31a4, a760c235; reported from Vidocq/noether#64)
- **Affected module**: `mansart-data-processor`, `EntityScanner` and `MansartMetamodelWriter` (consumer impact: a
  Vidocq application that runs Noether's and Mansart Data's processors on its entities)
- **Symptom**: with `mansart-data-processor` on the processor path, an entity with a collection field does not
  compile, because the Jakarta Data metamodel `_Book` that Mansart writes is invalid. For
  `@ElementCollection List<String> tags`: `_Book.java:29: error: <identifier> expected` on
  `NumericAttribute<Book, java.util.List<java.lang.String>> tags = new NumericAttribute<>(…,
  java.util.List<java.lang.String>.class, …)`. For a raw `List` with `targetClass`: `type argument java.util.List is not
  within bounds of type-variable V`. The parameterized case fails on 0.4.0-20261111.124140-68 and on
  0.4.0-20261111.191239-69 (the build of Vidocq/mansart#95); the raw case was run on -68 only.
- **Minimal reproduction**: in `noether-jpa-processor`, point `MansartDataCoexistenceTest` at the `coexist` fixture
  (an entity with `@ElementCollection public java.util.List<String> tags`) instead of `datacoexist`:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-processor -am -Dtest=MansartDataCoexistenceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **Confirmed cause**: `EntityScanner` takes every non-transient field as an attribute and has no plural kind; a
  field that is not an identifier, version, reference, enum, text, boolean or temporal falls to `NUMERIC`.
  `MansartMetamodelWriter` then writes `typeMirror.toString()` followed by `.class`, which is not a class literal for a
  parameterized type, and `NumericAttribute` bounds its value type to numbers. An `@OneToMany` or `@ManyToMany`
  collection takes the same path.
- **Investigations**:
  - 2026-11-11 (audit F15, #45): found while writing `MansartDataCoexistenceTest` for BUG-20261009-09. Evidence:
    session artifacts `logs/coexistence-red-68-elementcollection.txt` and `logs/coexistence-latest.log`. The test
    keeps a plural attribute by mapping it through property access over a `@Transient` field, which Mansart skips.
    Options for Mansart: skip collection-valued attributes in `_Book` (the Jakarta Data 1.0 static metamodel has no
    attribute type for a collection), or give them a kind of their own with the erased type in the class literal. No
    Mansart code is changed here.
  - 2026-11-12: Mansart took the first option. Since MANSART-049 (Vidocq/mansart#96), an `@ElementCollection`,
    `@OneToMany` or `@ManyToMany` field, or one typed as a collection or a map, is left out of `_Book` and of the
    run-time model with a compiler note, and a derived query on one is a build error. `MansartDataCoexistenceTest` maps
    a plain `@ElementCollection List<String>` field again: green on 0.4.0-20261112.131711-31 (Mansart e46cd5a5), red
    on -69. Those snapshots are on maven.vidocq.dev, which Noether's CI reads through Vidocq/ci `setup-maven`; a local
    build reads it only with the same repository in its `settings.xml` (the parent declares it for plugins only).

## BUG-20261112-01 — The processor refuses a module that reads Noether's core through a `requires transitive`

- **Date**: 2026-11-12
- **Status**: FIXED in Vidocq/noether#70 (6c6fdf2): `NoetherJpaProcessor` follows the `requires transitive` of the
  modules the application requires, as the module system does, before it asks for the directive.
- **Affected module**: `noether-jpa-processor`, `NoetherJpaProcessor.readsProvider`
- **Symptom**: an application module that reads `io.vidocq.noether.jpa.core` through a library, `requires lib;`
  where `lib` declares `requires transitive io.vidocq.noether.jpa.core;`, does not compile: `error: The accesses
  Noether generates for the entities of app implement its SPI: add to its module-info.java requires
  io.vidocq.noether.jpa.core;`. javac would compile the generated accesses, since the module reads the provider (JLS
  §7.7.1). Met while porting the Vidocq runtime extension (Vidocq/noether#45, A8): an extension module with
  `requires transitive io.vidocq.noether.jpa.core` did not satisfy the processor of the application that requires it.
- **Minimal reproduction**: `ModuleProvidesTest.aModuleThatReadsTheProviderThroughALibraryCompiles` (fixtures
  `modular-transitive-lib` and `modular-transitive`), red on `8f4d46e`:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-processor -am -Dtest=ModuleProvidesTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **Confirmed cause**: `readsProvider` looked for the provider among the module's own `requires` directives only.
- **Investigations**:
  - 2026-11-12: the Vidocq extension now follows the ecosystem's convention instead, its descriptor naming
    `module-info.requires = io.vidocq.noether.jpa.core` for `vidocq extension add` to write; the fix keeps the
    processor right for a library that hands the provider on.

## BUG-20261113-01 — With two persistence units on Vidocq, every `@PersistenceUnit` injection is ambiguous

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/factory-names-and-jrt-scan`: a unit's factory bean is named after the unit alone.
- **Affected module**: `noether-jpa-cdi`, `NoetherPersistenceExtension.factoryName`
- **Symptom**: a Vidocq application with two units (`ledger` and `archive`) does not boot: `CDI deployment
  validation failed: Ambiguous dependency: field Ledger.factory of type EntityManagerFactory. Matching beans:
  [EntityManagerFactory, EntityManagerFactory]`, the same for `Archive.factory`. The `@PersistenceContext` injections
  resolve. One unit boots.
- **Minimal reproduction**: `ContainerModuleTest.aUnitsFactoryHasTheSameNameWhateverOtherUnitsTheDeploymentHas`,
  red on `ff43f6d` (`expected: "noetherPersistenceUnit0_container" but was: "noetherPersistenceUnit1_container"`):
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-cdi-module-it -am -Dtest=ContainerModuleTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```
  End to end: `vidocq-runtime-it-noether-persistence` with the units `ledger` and `archive` (Vidocq/vidocq#541).
- **Confirmed cause**: the factory of a unit was named `noetherPersistenceUnit<rank>_<unit>`, its rank among the
  units the extension had met. The build of a Vidocq application records the synthetic beans
  (`META-INF/vauban-synthetic-metadata.properties`: `noetherPersistenceUnit0_archive`, `noetherPersistenceUnit1_ledger`),
  and its boot runs the extension again over the classes in another order: the factories came back under other names,
  so Vauban kept them as other beans, two per unit. The context beans have no name, so the two registrations of each
  one were the same bean.
- **Investigations**:
  - 2026-11-13: found by the two-unit IT of the Vidocq extension (Vidocq/noether#45, A8). Noether's own
    `BuildTimeExtensionModuleTest` boots from the build's record with several units and passes: Vauban does not run
    the extension again there. The name is now `noetherPersistenceUnit_<unit>`, a letter or a digit kept, any other
    character written as its code point between underscores (`FactoryNameTest`); the Vidocq IT is green with it
    (9 tests), `vauban-synthetic-metadata.properties` naming `noetherPersistenceUnit_archive` and
    `noetherPersistenceUnit_ledger`.

## BUG-20261113-02 — A unit declared in a module of a jlink image cannot list its managed classes

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/factory-names-and-jrt-scan`: `ManagedClasses` walks a `jrt:` root as a directory of the
  image's file system.
- **Affected module**: `noether-jpa-core`, `ManagedClasses.scan`
- **Symptom**: `PersistenceException: Unable to scan jrt:/<module>/ for the managed classes of the persistence unit`,
  caused by `IOException: <module>/ not found`, for a unit that does not exclude its unlisted classes.
- **Minimal reproduction**: `ManagedClassesTest.theRootOfAUnitInAModuleOfTheRuntimeImageIsScanned`, red on `ff43f6d`:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core -am -Dtest=ManagedClassesTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **Confirmed cause**: in a runtime image, the `persistence.xml` of a linked module is
  `jrt:/<module>/META-INF/persistence.xml`, so the unit root is `jrt:/<module>/`. `scan` read every root that was not
  a `file:` directory as an archive stream, and `jrt:/<module>/` is a directory of the image, not a stream.
- **Investigations**:
  - 2026-11-13: found while reviewing the Vidocq extension's documentation (`vidocq:jlink` links the application
    module into the image). The test scans a JDK module, which declares no managed class; the walk is the one of a
    `file:` directory, which `theAnnotatedClassesOfADirectoryRootAreAddedAfterTheListedOnes` covers with entities.
    Not yet run in a real jlink image of an application.

## BUG-20261113-03 — An unnamed and a named injection point of an application's only unit get two factories

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/one-factory-per-unit`: the factory of the injection points that name no unit is the
  factory of the only unit of `persistence.xml` when other injection points name it
  (`PersistenceFactoryCreator.factoryOfTheOnlyUnit`).
- **Affected module**: `noether-jpa-cdi`, `NoetherPersistenceExtension.persistenceBeans` and `PersistenceFactoryCreator`
- **Symptom**: in an application with one unit, `@PersistenceContext EntityManager` and
  `@PersistenceContext(unitName = "<unit>") EntityManager` do not share the persistence context of a transaction
  (§7.6.4): an entity one persists, the other does not contain. `@PersistenceUnit` and
  `@PersistenceUnit(unitName = "<unit>")` give two factories, two metamodels, and the unit's schema generation runs
  twice.
- **Minimal reproduction**: `QuarkusPortabilityTest.anInjectionPointWithoutAUnitNameSharesTheFactoryAndContextOfTheOnlyUnit`
  (`noether-it-quarkus`, whose descriptor has the one unit `library`; `Library` injects it with and without its name):
  ```sh
  ./mvnw -ntp -pl noether-it-other-containers/noether-it-quarkus -am -Dtest=QuarkusPortabilityTest \
    -Dsurefire.failIfNoSpecifiedTests=false install
  ```
  The CDI module-it cannot show it: Vauban reads the descriptors through the loader of the bean classes as well, which
  sees the module-it's five units, so a test class loader serving a one-unit descriptor changes nothing at synthesis.
- **Confirmed cause**: the extension kept one internal factory per unit name its injection points wrote, and the empty
  name was one of them: `""` and `"<unit>"` were two `@Singleton` factories, each created from the same descriptor,
  and the persistence contexts of a transaction are kept per factory.
- **Investigations**:
  - 2026-11-13: seen while reading `persistenceBeans` for Vidocq/noether#72 (the `@Inject` beans had to pick one of the
    two factories). Present before #72: the factory set was the set of written names.
  - 2026-11-13: a first fix made the synthesis register the unnamed binding as an alias of the unit's factory. Green in
    Noether, it broke the Vidocq IT `vidocq-runtime-it-noether-mansart-data`: Vauban had recorded the synthetic beans
    at build time, where no descriptor is read, and the runtime synthesis then added differently shaped ones
    (`Duplicate bean name 'noetherPersistenceUnit_'`, an ambiguous `EntityManager`). The fix keeps the bean
    definitions the build records and decides in the creator, at runtime, where the descriptor is read.

## BUG-20261113-04 — The lock and query timeouts of a unit are ignored, and a query ignores its entity manager's

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/timeouts-and-cache-modes`: a timeout is read from the operation's hint, else the entity
  manager's properties, else the unit's (`EntityManagerImpl.timeout`); a query starts with them
  (`QueryRuntime.defaults`), and every query reads the query timeout and cache mode hints (`AbstractQuery.standardHint`).
- **Affected module**: `noether-jpa-core`, `EntityManagerImpl.lockTimeout`, `JpqlQuery`, `NativeQuery.setHint`
- **Symptom**: `jakarta.persistence.lock.timeout` and `jakarta.persistence.query.timeout`, standard properties of a
  unit (§8.2.1.9, `persistence.xml` or `createEntityManagerFactory`), have no effect:
  - a `find`, `lock` or `refresh` without a lock timeout hint waits for the database's own timeout, even with the
    property on the unit;
  - a JPQL or criteria query takes neither timeout from its unit nor from its entity manager
    (`createEntityManager(Map)`, `setProperty`): `getTimeout()` is `null` and the lock timeout is the database's;
  - a native query ignores the `jakarta.persistence.query.timeout` hint (`setTimeout` works).
- **Minimal reproduction**: `QueryTimeoutsTest` and
  `LockingTest.anOperationTakesTheLockTimeoutOfItsUnitThenOfItsEntityManagerThenOfItsHints` (`noether-jpa-core`):
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core -am -Dtest='QueryTimeoutsTest,LockingTest' \
    -Dsurefire.failIfNoSpecifiedTests=false install
  ```
  H2 has no lock timeout scoped to a transaction (its dialect returns none), so the lock timeout a session or a query
  will apply is read through package-private accessors; on PostgreSQL it becomes `SET LOCAL lock_timeout`.
- **Confirmed cause**: `EntityManagerImpl.lockTimeout` read the operation's hints, then the entity manager's own
  properties, never the unit's. A query was created with no timeout and only its own hints set one; `NativeQuery`
  kept every hint as given without reading it.
- **Investigations**:
  - 2026-11-13: found while checking the property table of the reworked reference page against the code; the eight
    tests above fail before the fix.
  - 2026-11-13: fixed; every test of `noether-jpa-core` passes. The official TCK sets neither property and passes only
    an unrecognised hint to its queries, so it does not see the change.

## BUG-20261113-05 — The cache retrieve and store modes of a query are not applied

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/timeouts-and-cache-modes`: a query reads with its own cache modes
  (`QueryRuntime.read(FlushModeType, CacheRetrieveMode, CacheStoreMode, Function)`), which start as its entity
  manager's.
- **Affected module**: `noether-jpa-core`, `AbstractQuery`, `EntityManagerImpl` (its query runtime)
- **Symptom**: `setCacheRetrieveMode`, `setCacheStoreMode` and the `jakarta.persistence.cache.retrieveMode` /
  `storeMode` hints of a query (Jakarta Persistence 3.2 `Query`) are recorded but not applied: the entities a
  query loads follow the entity manager's modes. A query with `CacheStoreMode.BYPASS` still publishes its rows to the
  second-level cache, and one with `REFRESH` returns the cached state instead of the database's. A new query also
  reports `USE` / `USE` whatever its entity manager's modes, and the hints do not change what the getters report.
- **Minimal reproduction**: the four `QueryRowHydrationTest` tests marked BUG-20261113-05 (`noether-jpa-core`):
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core -am -Dtest=QueryRowHydrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false install
  ```
- **Confirmed cause**: the query runtime of `EntityManagerImpl` (`find`, `hydrate`, `hydrateCollections`, `stored`)
  reads the entity manager's `cacheRetrieveMode` and `cacheStoreMode` fields; a query's modes stay in `AbstractQuery`
  and nothing reads them.
- **Investigations**:
  - 2026-11-13: found with BUG-20261113-04, while documenting the query hints; the four tests fail before the fix.
  - 2026-11-13: fixed with BUG-20261113-04. The official TCK sets cache modes on the entity manager, `find` and
    `refresh` only.

## BUG-20261113-06 — Two persistence units of the same name: the first one on the class path wins silently

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/duplicate-unit-names`: two units of one name, in one `persistence.xml` or in two, are
  refused with a `PersistenceException` that names the unit and every file that declares it.
- **Affected module**: `noether-jpa-core`, `PersistenceUnits` (`find`, `all`, `names`)
- **Symptom**: an application whose modules each carry a `META-INF/persistence.xml` declaring a unit `ledger` gets
  the first `ledger` in class path order from `Persistence.createEntityManagerFactory("ledger")`, with no warning; the
  other declaration, its classes and its data source are ignored. `PersistenceUnits.all`, which `noether-jpa-cdi`
  and the Vidocq extension read, returns both, so the CDI extension registers two units of one name.
- **Minimal reproduction**: `PersistenceUnitsTest.twoUnitsOfOneNameInTwoJarsAreRefused` and
  `twoUnitsOfOneNameInOneFileAreRefused`, red before the fix:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-core -am -Dtest=PersistenceUnitsTest \
    -Dsurefire.failIfNoSpecifiedTests=false install
  ```
- **Confirmed cause**: `find` returned the first unit of the name it met, and `all` kept every unit without comparing
  names.
- **Investigations**:
  - 2026-11-13: found while answering whether an application may carry one `persistence.xml` per Maven module, so
    per Java module. The maintainer decided that a duplicate name fails. `find` refuses a duplicate of the name it
    looks for only, so a duplicate elsewhere does not stop an application that never uses it; `all`, which lists
    every unit for the CDI extension, refuses any duplicate. A file the class loader lists twice (through a parent
    loader) is read once and is not a duplicate. `find` now reads every `persistence.xml` of the loader, where it
    used to stop at the first match.

## BUG-20261114-01 — A converter of a closed package fails at bootstrap, even compiled with the processor

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/closed-package-codegen`: the processor generates `<Converter>$$NoetherAccess`, whose
  `instantiate()` calls the no-arg constructor from the converter's package, and `ValueBinders` takes it before the
  private lookup.
- **Affected module**: `noether-jpa-core` (`ValueBinders.converter`, `Handles.newInstance`), `noether-jpa-processor`
- **Symptom**: an entity of a package that is neither exported nor opened, compiled with `noether-jpa-processor`
  and handed over by `provides … with <pkg>._NoetherJpaAccess`, fails to start when an attribute goes through a
  converter of that package: `PersistenceException: The attribute text of ….Note cannot be stored: Noether cannot
  access the class ….Shout: compile it with noether-jpa-processor and declare the access it generates`, caused by
  `IllegalAccessException: module … does not open … to module io.vidocq.noether.jpa.core`. The message asks for
  what the application already does.
- **Minimal reproduction**: `ClosedPackageCodeModulePathTest.aConverterOfTheClosedPackageConvertsTheAttribute`:
  ```sh
  ./mvnw -ntp -pl noether-persistence/noether-jpa-processor-module-it -am verify \
    -Dtest=ClosedPackageCodeModulePathTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Hypothesised cause**: the processor generates accesses for entities and embeddables only; the converter is
  instantiated at bootstrap through a private lookup, which needs the package opened to the core.
- **Investigations**:
  - 2026-11-14: found by the compile-time design review of Noether's reflective sites (the maintainer asked to move
    every reflective path to compile time, reflection staying the fallback for unprocessed archives). Reproduced red.
  - 2026-11-14: cause confirmed. Every `@Converter` of the round now gets an access with no attribute, handed over
    by the provider of its package (a package without entity gets one too); a converter of a module that does not
    read `io.vidocq.noether.jpa.core` is left to the bootstrap without error. `ConvertersAndEnumsTest` covers the
    generated source, a private constructor and an incremental build.

## BUG-20261114-02 — An enum of a closed package mapped by its @EnumeratedValue field fails at bootstrap

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/closed-package-codegen`: the processor generates `<Enum>$$NoetherAccess`, a read-only
  access with the `@EnumeratedValue` field as its one attribute, and `ValueBinders` reads the values through it.
- **Affected module**: `noether-jpa-core` (`ValueBinders`, `Handles.getter`), `noether-jpa-processor`
- **Symptom**: same setting as BUG-20261114-01, with an attribute whose enum keeps its database value in a private
  `@EnumeratedValue` field: `Noether cannot access the class ….Tier`, caused by the same `IllegalAccessException`.
- **Minimal reproduction**: `ClosedPackageCodeModulePathTest.anEnumOfTheClosedPackageIsStoredAsItsEnumeratedValue`.
- **Hypothesised cause**: the field is read through a private lookup at bootstrap; the processor generates nothing
  for enums.
- **Investigations**:
  - 2026-11-14: reproduced red with BUG-20261114-01.
  - 2026-11-14: cause confirmed. `AccessWriter` gets a read-only shape for an enum: no instantiation, no setter, a
    handle from the enum's own lookup for a private field.

## BUG-20261114-03 — An @IdClass of a closed package fails at bootstrap: the processor never serves id classes

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/idclass-codegen`: the processor generates `<IdClass>$$NoetherIdAccess`, listing the
  attributes the bootstrap lists (field when the id class declares one of that name, else property), and
  `MappedUnit.idClassAccess` takes it before `Accesses.of`.
- **Affected module**: `noether-jpa-core` (`MappedUnit.idClassAccess`), `noether-jpa-processor`
- **Symptom**: same setting as BUG-20261114-01, with an entity whose `@IdClass` is in its own package: `Noether cannot
  access the class ….SeatKey`, caused by the same `IllegalAccessException`.
- **Minimal reproduction**: `ClosedPackageCodeModulePathTest.anIdClassOfTheClosedPackageFindsTheEntity`.
- **Hypothesised cause**: `MappedUnit.idClassAccess` always ends in `Accesses.of`, the start-time hidden class: no
  generated access is looked up for an id class, and the processor writes none.
- **Investigations**:
  - 2026-11-14: reproduced red with BUG-20261114-01.
  - 2026-11-14: fixed. Left to the bootstrap, as before: a relationship part without a field of its name
    (§2.4.1.3 example 5a), a field whose type is not the identifier attribute's, a record whose components do not
    follow the identifier attributes (`IdClassesTest`).
  - 2026-11-14: the record whose components do not follow the identifier attributes is served by the processor
    since BUG-20261114-06.

## BUG-20261114-04 — A constructor expression cannot build a result class of a closed package

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/result-constructors-codegen` for the classes the sources name: the processor generates
  `<Class>$$NoetherConstructors` (a `ResultConstructorAccess`) for each class a `@NamedQuery` constructor expression or
  a `@SqlResultSetMapping` names, and `ResultConstructors` takes it before the reflective lookup. A class only a query
  built at run time names stays reflective: its package must be exported to the core.
- **Affected module**: `noether-jpa-core` (`ResultConstructors`), `noether-jpa-processor`
- **Symptom**: `select new ….closed.AccountSummary(a.id, a.name) from Account a`, with the record in a package that
  is neither exported nor opened, fails at execution: `The constructor of ….AccountSummary cannot be called: access to
  public member failed`, an `IllegalAccessException` from `ResultConstructors$Candidate`.
- **Minimal reproduction**: `ClosedPackageCodeModulePathTest.aConstructorExpressionBuildsAResultClassOfTheClosedPackage`.
- **Hypothesised cause**: the public constructors are read with `getConstructors()` and unreflected from the core,
  which needs the package exported to it; the processor generates nothing for result classes.
- **Investigations**:
  - 2026-11-14: reproduced red with BUG-20261114-01.
  - 2026-11-14: fixed for named queries and result-set mappings (`ResultConstructorsTest` of the processor,
    `ClosedPackageCodeModulePathTest.aNamedQueryBuildsAResultClassOfTheClosedPackage` and
    `aResultSetMappingBuildsAResultClassOfTheClosedPackage`). The reproduction moved from a dynamic `createQuery`,
    whose string APT cannot see, to a `@NamedQuery`. Left reflective: a class with a public constructor that declares a
    checked exception, an abstract or inner class.

## BUG-20261114-05 — A record id class whose components follow another order gets the identifier values swapped

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/record-idclass-order`: the access of a record id class whose components come in another
  order is built in component order (read from the class file's fields) and wrapped in a `ReorderedAccess` that lists
  the identifier attributes in the entity's order.
- **Affected module**: `noether-jpa-core` (`MappedUnit.idClassAccess`, `AccessGenerator.canonicalConstructor`)
- **Symptom**: an entity with `@Id String aisle; @Id String place;` and `@IdClass(BoothKey.class)`, where
  `record BoothKey(String place, String aisle)`: `PersistenceUnitUtil.getIdentifier(new Booth("A", "7"))` returns
  `BoothKey[place=A, aisle=7]` instead of `BoothKey[place=7, aisle=A]`. `find` with the right key works: it reads
  the key field by field.
- **Minimal reproduction**: `RecordIdClassTest.theIdentifierOfAnEntityIsTheRecordOfItsOwnValues`.
- **Hypothesised cause**: the access of the id class lists its attributes in the order of the entity's identifier
  attributes, and `construct` passes them to the canonical constructor in that order, found by the parameter types:
  with components of the same types in another order, the constructor is found and fed the values swapped. With
  components of different types, the constructor is not found and the bootstrap fails.
- **Investigations**:
  - 2026-11-14: found while generating id class accesses (BUG-20261114-03, the processor leaves such a record to the
    bootstrap); reproduced red.
  - 2026-11-14: fixed; also covers components of other types in another order, which failed the bootstrap
    (`RecordIdClassTest.aRecordKeyWhoseComponentsHaveOtherTypesBuildsTheIdentifier`).

## BUG-20261114-06 — A record id class of a closed package whose components follow another order fails at bootstrap

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/idclass-codegen`: the processor generates the access of such a record with the identifier
  attributes in component order, and `Accesses.ofRecord` puts that access under the `ReorderedAccess` of
  BUG-20261114-05 instead of a start-time hidden class.
- **Affected module**: `noether-jpa-processor` (`NoetherJpaProcessor.idClass`), `noether-jpa-core`
  (`MappedUnit.idClassAccess`, `Accesses.ofRecord`)
- **Symptom**: same setting as BUG-20261114-03, with `@Id String aisle; @Id int place;` and
  `@IdClass(BoothKey.class)`, where `record BoothKey(int place, String aisle)` is in the entity's package: the factory
  fails with `Noether cannot access the class ….BoothKey: compile it with noether-jpa-processor and declare the access
  it generates`, caused by `IllegalAccessException: module … does not open … to module io.vidocq.noether.jpa.core`.
  The message asks for what the application already does.
- **Minimal reproduction**:
  `ClosedPackageCodeModulePathTest.aRecordIdClassWhoseComponentsComeInAnotherOrderFindsTheEntity`.
- **Hypothesised cause**: the processor writes no access for a record whose components do not follow the identifier
  attributes (BUG-20261114-03 left it to the bootstrap), and the bootstrap builds the in-order access of
  BUG-20261114-05 as a start-time hidden class, which needs the package opened.
- **Investigations**:
  - 2026-11-14: found while rebasing BUG-20261114-03 onto the fix of BUG-20261114-05; reproduced red (the IT above,
    and `IdClassesTest.theAccessOfARecordIdClassWhoseComponentsComeInAnotherOrderListsThemInComponentOrder`).
  - 2026-11-14: fixed. A record whose components do not name the identifier attributes (an id class §2.4.1 does
    not allow) is still left to the bootstrap, as before.
