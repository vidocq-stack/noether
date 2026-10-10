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
