# TCK status — Mansart JPA (Jakarta Persistence 3.2)

Official suite: **Jakarta Persistence 3.2.1** TCK (bundle from eclipse.org, SHA-256 checked), standalone mode,
**PostgreSQL 17** (official DDL and stored procedures of the bundle). Runner and command:
[`mansart-jpa-tck/`](mansart-jpa-tck/README.md), `./run-official-tck-persistence-3.2.sh`.

## Progress

| Milestone | Date | Java | Database | Tests | Pass | Fail | Skipped |
|---|---|---|---|---:|---:|---:|---:|
| P0 — instrument, no provider | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 17 | 2114 | 4 |
| P1 — bootstrap, provider SPI, resource-local transactions | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 220 | 1911 | 4 |
| P2a — entity model and generated access, mapped at bootstrap | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 220 | 1911 | 4 |
| P2b — accesses generated at build time (the TCK compiles nothing with the processor: runtime path unchanged) | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 220 | 1911 | 4 |
| P3 — persistence context and flush engine (flush at every commit, PostgreSQL dialect on the class path) | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 220 | 1911 | 4 |
| P4 — entity operations, identifier generation, callbacks, locking, secondary tables, native updates | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 609 | 1522 | 4 |
| P5 (in progress) — single-valued relationships: foreign keys, loading, orphan removal | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 611 | 1520 | 4 |
| P5 (in progress) — collection-valued relationships: join tables, inverse sides, orphans, merge | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 615 | 1516 | 4 |
| P5 (in progress) — element collections, `@OrderBy` paths | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 622 | 1509 | 4 |
| P5 (in progress) — maps and order columns | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 627 | 1504 | 4 |
| P5 — relationships and collections, derived identities, `PersistenceUnitUtil` | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 635 | 1496 | 4 |
| P7 (in progress) — slice 1: the query path, selects, joins, predicates, aggregates, parameters | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 780 | 1351 | 4 |
| P7 (in progress) — slice 2: functions, cases, constructors, subqueries, collection expressions, literals | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 840 | 1291 | 4 |
| P7 (in progress) — slice 3: bulk updates and deletes, named queries, lock modes, hints | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 893 | 1238 | 4 |
| P7 (in progress) — slice 4: set operations and casts | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 897 | 1234 | 4 |

## P7, slice 4 — set operations and casts: 893 → 897

Run of 2026-10-09: **897 pass, 1234 fail, 4 skipped**. The official suite now passes
`test_unionOperator`, `test_intersectOperator`, `test_exceptOperator`, `test_concatStringOperator` and
`test_castExpression` in `core.query.language`. The JPQL parser and SQL dialect AST cover `UNION` / `INTERSECT` /
`EXCEPT` (with `ALL`), result ordering and paging, and `CAST` target types. The unit suite also exercises execution on
H2, including casted parameter typing and set-query paging. The remaining failures are principally inheritance (P6),
Criteria and metamodel APIs (P8), schema generation (P9), and XML mappings (P10); P7 still has native query results and
stored procedures to implement.

## P7, slice 5 — focused native-query area

Focused official TCK run on 2026-10-09 (Temurin 25.0.3, PostgreSQL 17): `core.annotations.nativequery` reports
**10 passed, 2 failed, 0 skipped** of 12 tests. Scalar, tuple, entity and most constructor/column result cases pass;
`nativeQueryTestConstructorResultNoId` and `nativeQueryTestConstructorResultWithId` remain failing. This area-only
result is not a full-suite pass count. `StoredProcedureQuery` and named stored-procedure queries are not implemented,
so slice 5 remains in progress.

## P7, slice 3 — bulk statements, named queries, locks: 840 → 893

Run of 2026-10-09: **893 pass, 1238 fail, 4 skipped**. Compared test by test with the run at 840: 53 tests pass that
failed, none fails that passed — `se.entityManager.Client#*MethodsAfterClose*` (31: every method of a query of a closed
entity manager is an `IllegalStateException`), `core.query.apitests.Client1` (10), `core.lock.query` (7),
`core.entitytest.apitests` (3)… What fails around them now: the Criteria API (P8), named queries of mapping files and
`core.types.datetime` schema generation (P10, P9), stored procedures (slice 5).

## P7, slice 2 — expressions: 780 → 840

Functions, `CASE`, constructor expressions, subqueries, collection expressions, enum and date literals, temporal
parameters, paths into derived identifiers, `INDEX`. Run of 2026-10-09: **840 pass, 1291 fail, 4 skipped**. Compared test by
test with the run at 780: 60 tests pass that failed, none fails that passed — `core.query.language.Client2`/`Client3` (24),
`core.annotations.access.field.Client4` (14), `core.annotations.ordercolumn` (3), `core.derivedid.ex{2b,3b,6b}`, the
temporal `setParameter` tests of `core.query.apitests.Client1`, `core.enums`, `core.annotations.basic.Client2`…

What the query language still owes: bulk updates and deletes, named queries, lock modes (slice 3); set operations and
`CAST` (slice 4); native query results and stored procedures (slice 5). Most of `core.query.language` and of the Criteria
tests stop earlier, at their test data (inheritance, P6).

## P7, slice 1 — the query path: 635 → 780

JPQL selects end to end: the parser, the translation to the SQL AST of the dialect SPI, `Query` / `TypedQuery`. Run of
2026-10-09: **780 pass, 1351 fail, 4 skipped**. Compared test by test with the run at 635: 146 tests pass that failed —
most of `core.query.apitests.Client1`, the `core.derivedid` examples and `core.annotations.mapsid`, the map key tests
reading with queries, `core.callback.*#postLoadTest`, `core.annotations.{basic,entity,access.field}`, `nestedembedding`,
`core.exceptions.Client#QueryTimeoutExceptionTest`… — and one fails that passed:

- `core.inheritance.abstractentity.Client#abstractEntityTest3` catches every exception and checks nothing then: it passed
  vacuously while `createQuery` refused. Its query now runs and finds none of the employees its test data could not
  persist (`FullTimeEmployee`, entity inheritance): it waits for **P6**.

## P5 — closed at 635: relationships, collections, derived identities

The last slice brings `PersistenceUnitUtil`: 8 tests pass that failed, none fails that passed (compared test by test
with the run at 627) — `core.persistenceUnitUtil.Client` (7) and `core.entityManagerFactory.Client2#getPersistenceUnitUtil`.
Final run, 2026-10-09: **635 pass, 1496 fail, 4 skipped** (P4: 609).

No report names P5 any more. The gate areas, failing / total: `core.relationship` 8 / 33 (`descriptors`, mapping
files: P10), `core.derivedid` 12 / 12 and `core.annotations.mapsid` 1 / 1 (queries: P7, `ex1a` the Criteria API: P8),
`core.nestedembedding` 2 / 3 (P7), `core.annotations.ordercolumn` 3 / 3, `mapkey` 2 / 6, `mapkeycolumn` 2 / 6 (P7),
`elementcollection` 1 / 3 (`elementCollectionBasicTypeXMLTest`: P10); `orderby`, `mapkey{class,joincolumn,enumerated,temporal}`,
`collectiontable`, `onexmanyuni`, `assocoverride`, `embeddableMapValue`, `persistenceUnitUtil`, `persistenceUtil` all pass.
The tests of `core.relationship` and `core.entitytest` that passed before P5 checked instances the persistence context
returned; they now pass with their rows written and read back.

One test changed hands on the way: `core.override.joincolumn.Client#testOverrideJoinTable` passed vacuously while no
join row was written; its `orm.xml` replaces the join table, so it waits for P10.

Bean Validation neutrality (2026-10-09, final P5 state): NEUTRAL, 2135 tests, 635 / 1496 / 4 both ways.

## P5, fifth slice — derived identities: 627 → 627

The six derived identity shapes of §2.4.1.3 (`@IdClass` with a relationship part, `@EmbeddedId` with `@MapsId`, an
`@Id` relationship, `@MapsId` on the whole identifier, a dependent sharing its parent's id class) persist, load and are
found by their identifier objects. Run of 2026-10-09: **627 pass, 1504 fail, 4 skipped**, the same tests as before. The 12
tests of `core.derivedid` and `core.annotations.mapsid.Client#persistMX1Test1` no longer stop at the flush: they now
read their results back with Jakarta Persistence queries (P7) — `ex1a` with the Criteria API (P8).

## P5, fourth slice — maps and order columns: 622 → 627

Map keys (`@MapKey`, `@MapKeyColumn`, `@MapKeyJoinColumn`, `@MapKeyClass`, `@MapKeyEnumerated`, `@MapKeyTemporal`) and
`@OrderColumn`, in join tables, collection tables, and the tables of the targets of inverse one-to-many relationships.
Run of 2026-10-09: **627 pass, 1504 fail, 4 skipped**. Compared test by test with the run at 622: 5 tests pass that
failed, none fails that passed — `core.annotations.mapkeyclass.Client#mapKeyClass`,
`core.annotations.mapkeycolumn.Client#mapKey{Insertable,Updatable}FalseTest`,
`core.annotations.mapkeyjoincolumn.Client#mapKeyJoinColumn`, `jpa22.repeatable.mapkeyjoincolumn.Client#mapKeyJoinColumnTest`.
What still fails in `core.annotations.{mapkey,mapkeycolumn,ordercolumn}` reads its results with Jakarta Persistence
queries (P7).

## P5, third slice — element collections: 615 → 622

`@ElementCollection` of basic and embeddable values in their collection tables, and `@OrderBy` on paths into
embeddables or on the value of basic elements. Run of 2026-10-09: **622 pass, 1509 fail, 4 skipped**. Compared test by
test with the run at 615: 7 tests pass that failed, none fails that passed —
`core.annotations.convert.Client#elementCollectionBasicType`,
`core.annotations.elementcollection.Client2#elementCollectionBasicType`,
`core.annotations.orderby.Client2#{field,property}DotNotationTest`,
`core.annotations.orderby.Client3#{field,property}ElementCollectionBasicType`,
`core.types.property.Client2#elementCollectionTest`.

## P5, second slice — collection-valued relationships: 611 → 615

`@OneToMany` and `@ManyToMany`, owning and inverse sides: join tables written and read, the elements loaded with their
owner in their `@OrderBy`, orphans removed, collections merged. Run of 2026-10-09: **615 pass, 1516 fail, 4 skipped**.
Compared test by test with the final P4 run: 7 tests pass that failed (the two of the first slice,
`core.annotations.orderby.Client1#orderByTest1` to `4`, `core.entitytest.detach.manyXmany.Client#detachMXMTest1`), and
one fails that passed:

- `core.override.joincolumn.Client#testOverrideJoinTable` — its `orm.xml` replaces the join table the annotations name
  (`CUST_ORDER` for `CUST_RETAIL`, which the DDL does not create). It passed vacuously while no join row was written;
  it now waits for the mapping files (**P10**).

The small delta is expected: most tests of `core.relationship.*` and `core.entitytest.*.{oneXmany,manyXmany}` already
passed, checking instances the persistence context returned without reading the database; they now pass with their rows
written and read back. What P5 still owes in these areas: derived identities (`core.derivedid`, `core.annotations.mapsid`),
map collections (`core.annotations.mapkey*`), element collections, `@OrderColumn`, `@OrderBy` on embeddable paths and on
element collections, nested embeddables. `core.relationship.descriptors` waits for P10.

## P5, first slice — single-valued relationships: 609 → 611

Foreign key columns of `@ManyToOne` / `@OneToOne`, written in an order the constraints accept (cycles fixed by an
update), the targets loaded with their owner, the inverse side of a one-to-one, orphan removal and merge of single-valued
relationships. Run of 2026-10-09: **611 pass, 1520 fail, 4 skipped**. Compared test by test with the final P4 run: two
tests pass that failed (`core.annotations.mapkey.Client2#joinColumnInsertable`, `#joinColumnUpdatable`), none fails
that passed. The relationship areas wait for the collections (`@OneToMany`, `@ManyToMany`, element collections), which
most of their tests read back, and for `PersistenceUnitUtil` and derived identities.

## P4 — the entity operations open the suite: 220 → 609

P4 delivers `find` / `persist` / `merge` / `remove` / `refresh` / `detach` with their cascades, identifier generation,
callbacks and entity listeners, optimistic and pessimistic locks, the exception contract, secondary tables and native
`executeUpdate` (brought forward from P7: the TCK cleans its tables with it between tests). Final run, 2026-10-08:
**609 pass, 1522 fail, 4 skipped**.

Failures by the first milestone they meet (the reports name it, or the area does: mapping files, the cache):

| Count | Milestone | What the tests need |
|---:|---|---|
| 599 | P8 | the metamodel, the Criteria API, entity graphs |
| 451 | P6 | entity inheritance — mostly query and Criteria tests whose test data persist a `HardwareProduct` (they need P7 / P8 next) |
| 333 | P7 | Jakarta Persistence queries, native query results, stored procedures |
| 69 | P10 | mapping files: default listeners and `callback.xml`, `core.override.*`, entities declared in `orm.xml` |
| 41 | P5 | relationship columns, collections, element collections, map keys, ordering, derived identities |
| 27 | P9 | schema generation |
| 2 | P11 | the second-level cache (`core.cache.basicTests` evictions) |

None is P4's. The gate areas, pass / fail: `core.entityManager` 67 / 20, `core.entitytest` 140 / 16,
`core.callback` 21 / 45 (33 mapping files, 9 queries, 3 inheritance), `core.lock` 11 / 7, `core.versioning` 0 / 1,
`core.annotations.version` 14 / 0, `core.exceptions` 16 / 1, `se.entityManager` 53 / 50 — every failure a query,
the Criteria API, an entity graph, a mapping file, inheritance or a relationship. The P2 areas, which persist and read back,
opened too: `core.types` 45 / 7, `core.enums` 51 / 2, `core.annotations.{id,lob,temporal}` all pass.

What the runs found on the way (each fixed test first, then confirmed by the TCK):

- the runner set `persistence.second.level.caching.supported=false` (P0: no cache before P11). The TCK's
  `PMClientBase.clearCache()` only clears the persistence context (`em.clear()`) when the property is `true`, its
  default, and 63 test classes count on that detachment: the runner now leaves it `true`. The `se.cache` tests that
  check the cache itself (10, which passed vacuously) fail honestly until P11;
- a loaded instance keeps the identifier object it was found with (`detach.basic` compares `String` identifiers
  with `==`, as other providers allow);
- §3.2.4 applies to every relationship from X, the inverse side included; the remove of a new instance still
  cascades (§3.2.3);
- `@Lob byte[]` maps to a PostgreSQL large object (the DDL's `BYTEARRAYDATA OID`): bound through a JDBC `Blob`, which
  unlocked about 60 tests of `core.types` and `core.annotations.access`;
- `@Convert(attributeName = …)` on an embedded attribute and on the entity for an inherited one, auto-applied
  converters of `char[]`, converter exceptions wrapped in a `PersistenceException`;
- a `UUID` identifier stored in a `VARCHAR(96)` column: PostgreSQL refuses `character varying = uuid`, so its
  dialect binds UUIDs as untyped literals.

Bean Validation neutrality (2026-10-08, final P4 state): NEUTRAL, 2135 tests, 609 / 1522 / 4 both ways.

## D7 — the TCK runs on a mansart-pool pool

From 2026-10-08 the TCK units, configured by `jakarta.persistence.jdbc.*`, get their connections from a
`mansart-pool` pool (decision D7). Run that day: 220 / 2135, the same split, no exhausted database connections. The
TCK writes little before P4; if the pool ever changes results, `io.vidocq.mansart.jpa.pool=false` in the runner's
provider properties tells the pool from the provider. The runner installs `mansart-jpa-core` with `-am`, which builds
`mansart-pool` too.

## P3 — the flush runs at every commit, the counts do not move

Every TCK commit now flushes the persistence context of its entity manager, with the PostgreSQL dialect detected from
the JDBC metadata; nothing is managed yet (the entity operations are P4), so the counts stay those of P1. A first run
found a regression the unit tests had not: an entity whose `@IdClass` holds a relationship (`derivedid.ex6a`) failed
the bootstrap while its statements were built; such entities now wait for P5, and the mapping probe maps the 161
units of the TCK again, statements included. Final split: P4 877, P7 661, P8 348, P9 23, P5 2 — none unexplained.

Bean Validation neutrality (2026-10-08, with the flush at commit): NEUTRAL, 2135 tests, same results both ways.

## P2a — the whole TCK is mapped, the counts do not move

From P2a on, every `createEntityManagerFactory` of the TCK maps its managed classes: entity model, generated access,
binders. The counts stay those of P1 on purpose: the P2 gate areas (`core.types`, `core.enums`, `core.annotations.*`,
`jpa22.generators`, …) persist and read entities back, so they open with P4. What P2a proves is that the mapping
breaks nothing:

- the first run with the mapping at bootstrap fell to 135 passes: the access type of mixed hierarchies, uncapitalised
  accessors (`getdescription`), derived identities (§2.4.1) and attributes left to `orm.xml` failed the bootstrap;
- a one-off probe mapping every package of the TCK spec-tests jar as one unit found them; after the fixes, 161 units
  out of 161 map;
- the failure split is the P1 one exactly (P4 877, P7 661, P8 348, P9 23, P5 2), and no report holds a mapping error.

Bean Validation neutrality (2026-10-08, with the mapping at bootstrap): NEUTRAL, 2135 tests, same results both ways.

## P1 — what the 1911 failures are

Every failure names the milestone that delivers the missing operation (the provider throws
`UnsupportedOperationException: Mansart JPA does not support … yet (milestone Pn …)`, or a `PersistenceException` for
schema generation):

| Count | Milestone | What the tests need |
|---:|---|---|
| 877 | P4 | `persist`, `find`, `remove`, … on entities |
| 661 | P7 | Jakarta Persistence queries, native queries, named queries, stored procedures |
| 348 | P8 | the metamodel, the Criteria API, entity graphs |
| 23 | P9 | schema generation |
| 2 | P5 | `PersistenceUnitUtil` |

No failure is left unexplained. P1 gate areas (`se.entityManagerFactory`, `core.entityManagerFactory`,
`core.entityTransaction`, `se.resource_local`): 20 pass, 1 skipped (official exclusion), 15 fail, all on P4, P5, P7 or P8.
`se.entityManagerFactory.Client2#createEntityManagerFactoryNoBeanValidatorTest` now passes for the right reason: the
provider refuses validation mode CALLBACK without Bean Validation.

Bean Validation neutrality (2026-10-08, with the provider): NEUTRAL, 2135 tests, same results both ways.

## Baseline — P0 (no provider)

Execution 1: 2134 tests (16 pass, 2114 fail, 4 skipped). Execution 2: 1 test (1 pass).

**Every one of the 2114 failures has `jakarta.persistence.PersistenceException: No Persistence provider for
EntityManager named JPATCK` in its failure text**: no failure comes from the setup, the wiring or the database.
(The report's "deepest cause" shows 22 of them as `X failed`: those tests catch the exception and rethrow a generic
one; the provider message is still in their failure text.)

### The 17 that pass without a provider — not progress

| Count | Tests | Why they pass |
|---:|---|---|
| 1 | `signaturetest.JPASigTest#signatureTest` | checks the signatures of `jakarta.persistence-api` itself: a genuine pass |
| 15 | `se.pluggability.contracts.resource_local.Client#*` | they exercise the TCK's own stub provider (`persistence-tck-common`, `altprovider`), not ours |
| 1 | `se.entityManagerFactory.Client2#createEntityManagerFactoryNoBeanValidatorTest` (execution 2) | expects a `PersistenceException` with no Bean Validation provider; today it gets one because there is no persistence provider at all. It becomes a real check at P1/P11 |

### The 4 skipped

`@Disabled` in the TCK sources, matching the official exclusion list (`docs/TCK-Exclude-List.txt`):
`core.metamodelapi.identifiabletype.Client#getDeclaredSingularAttributes`,
`core.query.language.Client6#resultContainsFetchReference`,
`se.schemaGeneration.annotations.tableGenerator.Client#tableGeneratorTest`, and the disabled
`se.entityManagerFactory.Client2#createEntityManagerFactoryStringMapTest`.

## Bean Validation neutrality

`./check-validation-neutrality.sh` (2026-10-08): the same 2135 tests with the same results with and without
`jakarta.validation-api` + `mansart-validation-core` on the class path of execution 1: **NEUTRAL**. Trivial while
there is no provider; the check becomes meaningful from P1 on and must stay NEUTRAL.
