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
| Pre-P6 — clean checkout `1e982af`, including partial native results | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 909 | 1222 | 4 |
| P6 — required inheritance, polymorphic loading and JPQL `TYPE` | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 1005 | 1126 | 4 |
| P7 — JPQL, native queries and stored procedures | 2026-10-09 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 1063 | 1068 | 4 |
| P8 — runtime/canonical metamodel, Criteria and entity graphs; exposed converted-literal query fix | 2026-10-09 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2013 | 118 | 4 |

## P8 — metamodel, Criteria and entity graphs: 1063 → 2013

The 2026-10-09 full official run reports **2135 tests, 2013 passed, 0 failures, 118 errors, 4 skipped**.
Actual toolchain: **Temurin 25.0.4+7-LTS** (`openjdk version "25.0.4" 2026-07-21 LTS`), Maven **3.9.16**,
PostgreSQL **17-alpine**, the unmodified official **3.2.1** suite. `.sdkmanrc` still names 25.0.3; this measurement
explicitly selects the installed 25.0.4 JVM.

Compared by **class + test name** with the preserved 1063-pass baseline: **950 newly passing tests, no regression,
no added or missing test**. Of the improvement, 949 tests are P8 API unblocks; one is the earlier query-engine
converted-literal bug newly exposed during full-suite investigation (`BUG-20261009-10`), fixed in the shared
JPQL/Criteria path. Wrapped test logs, not only top-level exceptions, determined the attribution.

The P8 gate, also extracted from the final full XML reports:

| Gate | Pass | Error | Skipped | Total |
|---|---:|---:|---:|---:|
| `core.metamodelapi.*` | 258 | 0 | 1 | 259 |
| `core.criteriaapi.*` | 650 | 0 | 0 | 650 |
| `core.EntityGraph` | 13 | 0 | 0 | 13 |
| `jpa22.repeatable.namedentitygraph` | 3 | 0 | 0 | 3 |
| **P8 total** | **924** | **0** | **1** | **925** |

The one skip is the upstream-disabled `identifiabletype.Client#getDeclaredSingularAttributes`.
An intermediate focused gate reached 913 passes / 11 errors / 1 skip; SOME/ANY normalization, selection-free EXISTS
subqueries and shared numeric promotion cleared its remaining errors. No TCK tests, schemas or exclusions were edited.

Remaining **118** errors are explained, not waived:

| Owner | Errors | Evidence / blocker |
|---|---:|---|
| P9 — schema/container contracts | 28 | 25 `se.schemaGeneration` tests, two generator/secondary-table schema tests and `core.types.datetime.Client#dateTimeTest`, which fails at `Persistence.generateSchema` before Criteria execution |
| P10 — ORM XML | 78 | XML entity/mapping overrides, listener/callback XML, descriptor relationships/inheritance, native-query entities and stored-procedure XML overrides |
| P11 — second-level cache | 12 | `core.cache.basicTests` and `se.cache` inheritance/XML cache behavior |

The earlier provisional attribution (P8 950 / P9 27 / P10 78 / P11 13) mixed one schema setup error into P8 and
the converted-literal query defect into P11. Final source/log attribution corrects those owners; it does not claim
that old implementation phases were bug-free. The four XML-dependent P7-gate errors remain in the full suite.
Container/JTA/schema, XML and caching/validation implementation was not added to improve the score.
This remains **partial conformance, not Jakarta Persistence certification**; optional `TABLE_PER_CLASS` is still
refused and D5 remains open.

The clean selected JPA reactor reports **471 tests, 0 failures, 0 errors, 0 skipped**:
core 404, processor 24, dialect SPI 23, H2 6, PostgreSQL 8, and the two Java module vehicles 3 each.
Red regressions preceded the metamodel/Criteria/graph implementations and the later edge corrections:
canonical module/incremental lifecycle, raw collection targets, parameter identity/primitive bindings,
named tuple/query snapshots, entity LEFT joins with ON navigation, temporal extraction, lazy case assembly,
entity map-key navigation and joins attached to treated roots. No-opens integration asserts actual canonical
field population, not merely successful compilation.

Bounded code-generation, Java Modules and concurrency reviews were performed. Generation review findings were
fixed and covered by the processor/module tests; no dependency blocks, module descriptors, exports or opens changed.
Existing execution helpers still perform JDBC; no new executor or SQL execution path was introduced.

**Coexistence boundary:** the actual installed frozen Data processor and JPA processor compile compatible basic
canonical output in both orders. Claiming-producer regressions check both discovery orders and ensure incompatible
plural fields produce diagnostics without overwriting the owning producer's class. Data's existing
singular output for collection canonical fields remains incompatible; JPA diagnoses it and retains producer ownership.
Changing delivered Data requires a maintainer decision (`BUG-20261009-09`), and is not claimed as delivered here.

Commands from the repository root (scratch can be a session-owned directory; confine forked JVM scratch there):

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4-tem"
export PATH="$JAVA_HOME/bin:$PATH"
P8_SCRATCH="$PWD/mansart-persistence/mansart-jpa-tck/target/p8-jvm-scratch"
mkdir -p "$P8_SCRATCH"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$P8_SCRATCH"

# Install the current processor first: annotationProcessorPaths resolves its installed artifact.
./mvnw -q -ntp -pl mansart-persistence/mansart-jpa-processor -am install -DskipTests
./mvnw -q -ntp \
  -pl mansart-persistence/mansart-jpa-processor,mansart-persistence/mansart-jpa-processor-module-it,mansart-persistence/mansart-jpa-module-it \
  -am clean verify "-DargLine=-Djava.io.tmpdir=$P8_SCRATCH"

cd mansart-persistence/mansart-jpa-tck
./run-official-tck-persistence-3.2.sh -- \
  '-Dtck.tests=**/core/metamodelapi/**/*Client*,**/core/criteriaapi/**/*Client*,**/core/EntityGraph/**/*Client*,**/jpa22/repeatable/namedentitygraph/**/*Client*' \
  -Dtck.skip.execution2=true
./run-official-tck-persistence-3.2.sh
```

The full baseline, intermediate focused/full reports and final full XML/logs were preserved separately in session
evidence before another run could overwrite them. Counts and regressions were read from every XML execution;
the script's zero exit status alone is not a result.

## P6 — inheritance: 909 → 1005

The pre-P6 checkout was rebuilt from an archive of `HEAD` (`1e982af`) outside the user-owned checkout and measured
again: **2135 tests, 909 passed, 0 failures, 1222 errors, 4 skipped**. This is the baseline of the actual code,
including the partial native-result work; the earlier documented **897 / 1234 / 4** remains a historical measurement.
The P6 full run reports **2135 tests, 1005 passed, 0 failures, 1126 errors, 4 skipped**.
The table's “Fail” column combines failures and errors, as the runner does.

Compared test by test with the rebuilt baseline: **96 newly passing tests, no regression, no added or missing test**.
Compared with the last previously documented full score, the net improvement is 108 passes; only the 96-pass
test-by-test delta is attributed to P6.

Implemented and unit-tested (§2.13–2.14, §11.1.12–13, §11.1.45–46, §4.4.8, §4.6.17.5):

- explicit and default `SINGLE_TABLE`, root tables, default and explicit discriminator columns and values,
  `STRING` / `CHAR` / `INTEGER`, including provider-defined defaults for the latter two;
- `JOINED`, with or without an explicit discriminator, renamed and composite primary-key joins (matched by
  referenced column), default keys inherited through several levels, empty leaf tables and secondary tables;
- abstract entities, mapped-superclass attributes, nonentity gaps, concrete polymorphic `find` and loading,
  one identity shared by compatible base/subclass requests, incompatible sibling requests returning `null`;
- relationships to bases and joined subtypes, inherited join/element-collection defaults, cyclic references,
  inherited identifiers and generated identities, versions, optimistic locking, callbacks, dirty checking,
  merge, refresh and removal;
- JPQL polymorphic entity selection, subclass predicates and attributes, `TYPE` results as `Class` objects,
  entity literals and class parameters (including `IN`), and inheritance-aware bulk updates/deletes.
  Joined updates capture their qualifying keys and assignment values before modifying any table;
  deletes remove descendant tables first and count entities, not physical rows.

The dedicated H2 inheritance suite reports **18 / 18 passed**, including ordered inverse subtype collections.
The clean JPA reactor reports **433 tests, 0 failures, 0 errors, 0 skipped**, including both module-path vehicles
and a new APT inheritance test whose entity package is neither exported nor opened to the provider.
No access-generator or `AccessPlanner` logic was duplicated or changed.

Focused official gate (PostgreSQL 17): **21 tests, 19 passed, 0 failures, 2 errors, 0 skipped**:

| Area | Pass | Error | Remaining blocker |
|---|---:|---:|---|
| `core.inheritance` | 8 | 2 | `mappedsc.descriptors.Client#test1`, `#test2`: XML mappings, P10 |
| `core.annotations.discriminatorValue` | 2 | 0 | — |
| `core.callback.inheritance` | 9 | 0 | — |

The two descriptor tests are **not excluded** and remain in the full-suite failures; no required P6 implementation
defect remains in this gate. `TABLE_PER_CLASS` remains explicitly refused at bootstrap; it is optional, and
decision **D5 remains open**. No optional-feature exclusion or architectural decision was added.
The two previously known constructor-result failures in `core.annotations.nativequery` were not changed.
Remaining blockers concern P7's unfinished query/native/stored-procedure cases, P8's Criteria/metamodel/entity graphs,
P9 schema generation, P10 XML mappings and P11 caching/validation.

Commands (from the repository root, Java 25.0.3 / Maven 3.9.16 selected with SDKMAN):

```bash
./mvnw -q -ntp -pl mansart-persistence/mansart-jpa-core -am test \
  -Dtest=InheritanceTest,BulkQueryTest -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -q -ntp \
  -pl mansart-persistence/mansart-jpa-core,mansart-persistence/mansart-jpa-dialects/mansart-jpa-dialect-postgresql,mansart-persistence/mansart-jpa-processor,mansart-persistence/mansart-jpa-module-it,mansart-persistence/mansart-jpa-processor-module-it \
  -am clean verify

cd mansart-persistence/mansart-jpa-tck
./run-official-tck-persistence-3.2.sh -- \
  '-Dtck.tests=**/ee/jakarta/tck/persistence/core/inheritance/**/*Client*,**/ee/jakarta/tck/persistence/core/annotations/discriminatorValue/**/*Client*,**/ee/jakarta/tck/persistence/core/callback/inheritance/**/*Client*' \
  -Dtck.skip.execution2=true
./run-official-tck-persistence-3.2.sh
```

The runner's exit status alone is not a conformance result: it exits zero even with failing tests.
Counters were read from `target/tck-report-persistence.txt` and the failsafe XML reports. Baseline, focused-gate and
full-run XML evidence was preserved separately before running another selector.

## P7 — JPQL, native queries and stored procedures: 1005 → 1063

The 2026-10-09 full official run reports **2135 tests, 1063 passed, 0 failures, 1068 errors, 4 skipped** on
Temurin 25.0.3 and PostgreSQL 17. Compared test by test with the preserved P6 baseline: **58 newly passing tests,
no regressions, and no added or missing tests**. The JPA core reactor tests also pass.

The P7 gate totals **312 tests: 307 passed, 4 errored and 1 skipped**:

| Gate | Result | Remaining tests |
|---|---:|---|
| `core.query` | 244 / 245; 1 skipped | No P7-attributable failure |
| `core.annotations.nativequery` | 10 / 12 | Two constructor-result tests fail during setup because their `PurchaseOrder` entity is supplied by ORM XML, which is P10 |
| `core.StoredProcedureQuery` | 38 / 40 | Two XML override tests depend on ORM XML mapping, which is P10 |
| `core.lock.query` | 8 / 8 | — |
| `jpa22.query.stream` | 2 / 2 | — |
| `jpa22.repeatable.namednativequery` | 1 / 1 | — |
| `jpa22.repeatable.namedstoredprocedurequery` | 4 / 4 | — |

Delivered JPQL work includes map `KEY` / `VALUE` / `ENTRY` expressions, whole-embeddable comparisons and projections,
correlated enclosing-variable navigation, and `TREAT` path/join support. Native results cover scalar, tuple, entity,
constructor and column mappings; procedure queries support named and unnamed metadata, parameter modes, result/update
handling and PostgreSQL ref cursors through dialect-rendered calls. The four remaining P7-gate errors are blocked by
P10 XML mapping/override support, not by the P7 result or procedure execution paths. No TCK tests were changed or
excluded.

Review fixes to the stored-procedure lifecycle (`BUG.md` MANSART-008: repeated execution, retryable failures,
`null` results, safe paging, trailing `IN` defaults, temporal bindings, statement closing) were re-measured on
2026-10-09 (Temurin 25.0.4): the clean JPA reactor reports **453 tests, 0 failures, 0 errors, 0 skipped**; the full
official run again reports **2135 tests, 1063 passed, 0 failures, 1068 errors, 4 skipped**, identical test by test to
the preserved 1063 baseline (no regression, no newly passing, no added or missing test), P7 gate unchanged
(307 / 4 / 1).

Reproduction:

```bash
./mvnw -q -ntp -pl mansart-persistence/mansart-jpa-core -am test
cd mansart-persistence/mansart-jpa-tck
./run-official-tck-persistence-3.2.sh
```

## P7, slice 4 — set operations and casts: 893 → 897

Run of 2026-10-09: **897 pass, 1234 fail, 4 skipped**. The official suite now passes
`test_unionOperator`, `test_intersectOperator`, `test_exceptOperator`, `test_concatStringOperator` and
`test_castExpression` in `core.query.language`. The JPQL parser and SQL dialect AST cover `UNION` / `INTERSECT` /
`EXCEPT` (with `ALL`), result ordering and paging, and `CAST` target types. The unit suite also exercises execution on
H2, including casted parameter typing and set-query paging. The remaining failures are principally inheritance (P6),
Criteria and metamodel APIs (P8), schema generation (P9), and XML mappings (P10); P7 still has native query results and
stored procedures to implement.

## P7, slice 5 — initial native-query check (historical)

The initial focused official TCK run on 2026-10-09 (Temurin 25.0.3, PostgreSQL 17): `core.annotations.nativequery` reports
**10 passed, 2 failed, 0 skipped** of 12 tests. Scalar, tuple, entity and most constructor/column result cases pass;
`nativeQueryTestConstructorResultNoId` and `nativeQueryTestConstructorResultWithId` remain failing. This area-only
result is not a full-suite pass count. `StoredProcedureQuery` and named stored-procedure queries were not yet
implemented at this checkpoint; the final P7 implementation and current test attribution are recorded above.

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
