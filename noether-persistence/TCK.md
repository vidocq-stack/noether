# TCK status — Mansart JPA (Jakarta Persistence 3.2)

Official suite: **Jakarta Persistence 3.2.1** TCK (bundle from eclipse.org, SHA-256 checked), standalone mode,
**PostgreSQL 17** (official DDL and stored procedures of the bundle). Runner and command:
[`mansart-jpa-tck/`](mansart-jpa-tck/README.md), `./run-official-tck-persistence-3.2.sh`.

Since 2026-10-10 the runner applies by default the local fixture patch **TCK-BUG-001** (backport of upstream
[jakartaee/persistence#1175](https://github.com/jakartaee/persistence/issues/1175), commit `1fea05e`) to a derived
copy of the spec-tests jar; `TCK_FIXTURES=official` runs the untouched jar. Rows are labelled *untouched* or
*patched*: a patched score is a local development result, not an official result, and no certification is claimed.

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
| P9 — schema, CDI/JTA, Vidocq extension and Arquillian integration | 2026-10-09 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2040 | 91 | 4 |
| P10 initial baseline — ORM XML; quotation ignored (superseded) | 2026-10-09 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2121 | 10 | 4 |
| P10 correctness hardening — partial, case-preserving quotation | 2026-10-09 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2096 | 35 | 4 |
| P10 final verification — embedded associations, embeddable map keys and query identifiers; gate blocked | 2026-10-09 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2096 | 35 | 4 |
| P10 delivered — *untouched* fixtures (`TCK_FIXTURES=official`), provider unchanged | 2026-10-10 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2096 | 35 | 4 |
| P10 delivered — *patched* fixtures TCK-BUG-001 (runner default), provider unchanged | 2026-10-10 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2121 | 10 | 4 |
| P11 delivered — *patched* fixtures TCK-BUG-001 (runner default) | 2026-10-10 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2131 | 0 | 4 |
| P11 correctness follow-up — *patched* fixtures, validation off/on | 2026-10-10 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 each | 2131 each | 0 | 4 each |
| P12 final verification — *patched* fixtures, default runner mode | 2026-10-10 | Temurin 25.0.4+7-LTS | postgres:17-alpine | 2135 | 2131 | 0 | 4 |

## P11 — second-level cache and Bean Validation

**Patched full TCK, 2026-10-10: 2135 tests, 2131 passed, 0 failures, 0 errors, 4 official skips.** Compared by
execution + class + test name against the preserved P10 patched run: the same 2135 identities, all 10 prior
second-level-cache errors now pass, with zero regressions or other outcome changes. The patched result is a local
development gate under TCK-BUG-001, **not** an untouched-fixture result or a certification claim. The fixture patch,
official jar and exclusions were not changed for P11.

Validation neutrality was checked with the patched TCK in both modes using
`../check-validation-neutrality.sh`: **2135/2135 test identities and statuses identical**, 2131 pass, 0 failures,
0 errors, 4 skips in each mode. `off` supplies neither Validation API nor provider; `on` adds the API and the
test-only `mansart-validation-core`; the runner's second failsafe execution remains without either. Reports:
`target/validation-neutrality/off/` and `target/validation-neutrality/on/`.

The P11 full-run reports are at `target/failsafe-reports/`; the preserved P10 patched baseline used for the exact
comparison is at `target/p11-baseline/pre-run-patched-reports/`. The untouched-fixture P10 history above is retained;
an untouched-fixture full run was not repeated for P11.

**Correctness follow-up, 2026-10-10 (BUG-20261010-02 through -05):** the full patched TCK was rerun
with validation off (04:46:41Z) and on (04:48:00Z), after the validation bootstrap/group and cache
eviction/type/publication corrections. Each run retains **2135 identities, 2131 passes, zero failures/errors
and four skips**, with **zero outcome differences** against the preserved 04:38:13Z P11 baseline.
Both second executions pass their single absent-validation test; their reported Java class paths contain
neither `jakarta.validation-api` nor `mansart-validation-core`. Full persistence `clean install` passes
**575 tests (485 core), zero failures/errors/skips**; 25 focused validation/cache tests also pass.
Evidence: `target/p11-correctness/`, `target/p11-correctness-neutrality/{off,on}/` and
`target/p11-correctness-baseline/failsafe-reports/`. Commands from the Mansart root:

```bash
./mvnw -ntp -f mansart-persistence/pom.xml clean install
cd mansart-persistence
OUT=mansart-jpa-tck/target/p11-correctness-neutrality ./check-validation-neutrality.sh
```

The original P11 neutrality evidence and P10 patched baseline remain preserved. No Validation implementation,
official TCK fixtures, DDL, exclusions or Data sources were changed in this follow-up.

**P12 final TCK verification, 2026-10-10 08:45:02–08:46:26 +0200:** ran the full default patched runner
once from a clean provider source state, with `TCK_FIXTURES` unset (so TCK-BUG-001 remained the runner default).
PostgreSQL 17, Temurin 25.0.4+7-LTS: **2,135 run, 2,131 passed, 0 failures, 0 errors, 4 official skips**.
The report contains the same 2,135 test identities and outcomes as the prior P11 baseline: zero additions,
removals, regressions or outcome changes. Exact invocation:

```bash
cd mansart/main/mansart-persistence/mansart-jpa-tck
env -u TCK_FIXTURES ./run-official-tck-persistence-3.2.sh
```

Current runner output: `mansart-jpa-tck/target/tck-report-persistence.txt` and
`mansart-jpa-tck/target/tck-persistence-output.log`. This final replay is local fixture-patched evidence,
not an untouched-fixture result or a formal certification claim.

## TCK-BUG-001 local fixture patch — P10 gate met (2026-10-10)

Provider code is unchanged since `bb37a0a` (no source change in this step); only the runner changed
(`tools/tck_fixture_patch.py`, `run-official-tck-persistence-3.2.sh`, `pom.xml`). The two 3.2.1 fixtures
`core/annotations/nativequery/orm.xml` and `core/entitytest/apitests/orm.xml` lose their
`persistence-unit-metadata`/`<delimited-identifiers/>` block in a derived jar, exactly as upstream commit
[`1fea05e58151f10954206a15d70b18008043d3d9`](https://github.com/jakartaee/persistence/commit/1fea05e58151f10954206a15d70b18008043d3d9)
does on the 4.0 line; the 3.2 namespace/version and every other entry are kept. Reasons and the planned backport
request: TCK-BUG-001 in [`../PERISTENCE_TCK_PROPOSALS.md`](../PERISTENCE_TCK_PROPOSALS.md); mechanics:
[`mansart-jpa-tck/README.md`](mansart-jpa-tck/README.md).

| Artifact | SHA-256 |
|---|---|
| Bundle `jakarta-persistence-tck-3.2.1.zip` (unchanged) | `1d282675f43fa13cf8ab2537d6dbfb1e1c95f7b838ab7cdd053e185c363a6519` |
| Official `persistence-tck-spec-tests-3.2.1.jar`, M2 and `.tck-cache` (unchanged, before and after) | `a6ad07d4442aace8630348f7aae990d31d79a31692e14ed463f482c58b364024` |
| Derived `target/tck-fixtures/persistence-tck-spec-tests-3.2.1-tck-bug-001.jar` (1126 entries, identical in two runs) | `1f7e82bac32a04a903d41fc5755f5c3f348a08865ccf89792ee28d08b1e4d9a4` |
| `nativequery/orm.xml` original → patched | `fdf87f63…72ed` → `0afff5fb…5126` |
| `apitests/orm.xml` original → patched | `64810777…f39d` → `b01999cf…c767` |

Commands (from `mansart-persistence/mansart-jpa-tck`, `JAVA_HOME` = Temurin 25.0.4+7-LTS, Maven 3.9.16):

```bash
python3 -m unittest discover -s tools -v               # 30 tests, OK (red first: module missing)
./run-official-tck-persistence-3.2.sh                    # patched (default)
TCK_FIXTURES=official ./run-official-tck-persistence-3.2.sh
python3 tools/tck_fixture_patch.py compare <base>/failsafe-reports target/failsafe-reports
```

Results, compared by **execution + class + test name** with the preserved untouched run of 2026-10-09 20:43:24Z:

- **Patched** (03:53:12Z, repeated 03:56:13Z with the identical derived jar and identical outcomes):
  **2135 tests, 2121 passed, 0 failures, 10 errors, 4 skipped**; 0 added, 0 missing, **25 newly passing**,
  0 regressions. The report verifies that all 259 failsafe reports ran with the derived jar alone on the class
  path; the ShrinkWrap deployment `jpa_core_annotations_nativequery.jar` carries `META-INF/orm.xml` with the patched
  SHA-256 `0afff5fb…5126`.
- **Untouched** (`TCK_FIXTURES=official`, 03:54:47Z): **2096 passed, 35 errors, 4 skipped**, identical to the
  preserved run test by test (0 changes): the raw mode reproduces the official situation.

The 25 newly passing tests are exactly the 25 fixture errors recorded below:
`core.annotations.nativequery.Client` — `createNativeQueryResultClassTQTest`, `createNativeQueryResultClassTest`,
`createNativeQueryStringTest`, `getSingleResultTest`, `nativeQueryColumnResultTypeTest`, `nativeQueryTest2`,
`nativeQueryTest3`, `nativeQueryTestConstructorResult`, `nativeQueryTestConstructorResultNoId`,
`nativeQueryTestConstructorResultWithId`, `setParameterTest`; `core.entitytest.apitests.Client` — `entityAPITest2`,
`entityAPITest12` to `entityAPITest16`, `entityAPITest18`, `getReferenceTest`, `namedNativeQueryInMappedSuperClass`,
`namedQueryInMappedSuperClass`, `xmlNamedNativeQueryTest`, `xmlNamedQueryTest`, `xmlOverridesNamedNativeQueryTest`,
`xmlOverridesNamedQueryTest`.

P10 gate in the patched run: `core.annotations.nativequery` 12 / 12, `core.entitytest.apitests` 21 / 21,
`core.override.*` 26 / 26, `core.callback.*` 66 / 66, `core.relationship.descriptors` 8 / 8, `se.descriptor` 1 / 1,
`core.inheritance.mappedsc.descriptors` 2 / 2, `core.StoredProcedureQuery` 40 / 40,
`core.annotations.elementcollection` 3 / 3. The **10 remaining errors are all P11** second-level cache assertions:
`core.cache.basicTests.Client#evictTest1`, `#evictTest2`; `se.cache.inherit.Client#subClassInheritsCacheableTrue`,
`#subClassInheritsCacheableFalse`; `se.cache.xml.all.Client#containsTest`, `#cacheStoreModeUSETest`,
`#cacheStoreModeREFRESHTest`; `se.cache.xml.disableselective.Client#containsTest`;
`se.cache.xml.enableselective.Client#containsTest`;
`jpa22.se.repeatable.secondarytable.Client#subClassInheritsCacheableTrue`.
The 4 skips are the official ones. No provider code changed, so the clean reactor (549 tests) and Vidocq
Arquillian (6/6) results recorded below for the committed provider are unaffected (not re-run in this step).

## P10 hardening — partial; quotation must not be ignored to obtain a green score

**Final full untouched official run (2026-10-09 20:43:24Z): 2135 tests, 2096 passed, 0 failures,
35 errors, 4 skipped.** Comparison by execution + class + test name against the retained initial full run
confirms zero added/missing tests and exactly the same 25 delimited-fixture errors described below.
The 51 embeddable-type regressions introduced during hardening are repaired; no other newly failing tests remain.
The final clean persistence reactor passes **549 tests** (459 core), and the final Vidocq Arquillian vehicle
passes **6/6** against the installed final artifacts. These results include all embedded, map-key and query
identifier fixes. The fixture/DDL case conflict still blocks the P10 delivery gate; the 10 cache errors are P11.

Final commands, from the corresponding repository roots:

```bash
./mvnw -q -ntp -f mansart-persistence/pom.xml clean install
mansart-persistence/mansart-jpa-tck/run-official-tck-persistence-3.2.sh
# From vidocq:
./mvnw -q -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-it-mansart-persistence -am test
```

Retained pre-embedded full untouched official run (2026-10-09 **19:59:13Z**): **2135 tests, 2096 passed, 0 failures, 35 errors, 4 skipped**.
The clean persistence reactor passes: dialect SPI 23, H2 6, PostgreSQL 8, core **437**, processor 24,
module-path IT 3, processor module-path IT 3, CDI/JTA 13 and Vauban module-path vehicle 4.
`OrmXmlTest` covers real H2 quoted CRUD, JPQL/bulk queries, native entity/scalar results, embedded basics,
collections, inherited association overrides/foreign-key metadata, mixed-case sequence/table/identity generators,
indexes/unique constraints, escaped explicit quotes and joined inheritance/discriminators.

Comparison against the retained 2121-pass baseline by **execution + class + test name**:
**25 regressions, zero newly passing, zero added/missing tests**. They are not hidden or reclassified as P11:

| Gate | Initial pass/error | Hardened pass/error | Total |
|---|---:|---:|---:|
| `core.annotations.nativequery` | 12 / 0 | 1 / 11 | 12 |
| `core.entitytest.apitests` | 21 / 0 | 7 / 14 | 21 |
| `core.override.*` | 26 / 0 | 26 / 0 | 26 |
| `core.callback.*` | 66 / 0 | 66 / 0 | 66 |
| `core.relationship.descriptors` | 8 / 0 | 8 / 0 | 8 |
| `se.descriptor` | 1 / 0 | 1 / 0 | 1 |
| `core.inheritance.mappedsc.descriptors` | 2 / 0 | 2 / 0 | 2 |
| `core.StoredProcedureQuery` | 40 / 0 | 40 / 0 | 40 |
| `core.annotations.elementcollection` | 3 / 0 | 3 / 0 | 3 |

The only official resources declaring `<delimited-identifiers/>` are
`core/annotations/nativequery/orm.xml` and `core/entitytest/apitests/orm.xml` in the spec-tests artifact.
The former explicitly maps uppercase `PURCHASE_ORDER`, `ID`, `TOTAL`; the latter applies the unit-wide default to
annotated names. The PostgreSQL DDL creates these names **unquoted**, folding them to lowercase. The old warning
and ignored default therefore passed despite violating the mapping contract. Correct SQL uses the declared,
case-preserving quoted identifiers and receives missing-relation errors against that DDL.
See Jakarta Persistence 3.2 [§2.15 and §12.2.1.3](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html):
the unit-wide delimited default cannot be overridden. No official fixtures, SQL or exclusions were changed;
native SQL from the application remains caller-owned, not rewritten.

The original **10 P11 cache errors remain unchanged** in this baseline. P10 is **not delivered**: the fixture/DDL
case conflict needs resolution without a provider case-folding exception or official-source edits.
The final full gate above covers the embedded execution fix described below.
The remaining query identifier contract is now covered by provider-owned red/green tests: qualified routine
names and named argument targets use the unit policy and explicit delimiters; native/procedure result labels
(including `EntityResult.discriminatorColumn`) use exact delimited text or unambiguous folded lookup.
Real PostgreSQL tests execute mixed-case schemas/routines, quoted dots and doubled quotes, reversed named
IN/INOUT registration with ordinal JDBC binding, and both named/dynamic calls under the unit-wide default.
These tests do not run or alter the official suite and do not update the official counters above.
The smallest core/dialect reactor passes **452 core tests + 41 dialect tests**, without failures/errors/skips;
both provider-owned PostgreSQL tests pass, and the tested core/dialects are installed locally. Red/green and
install logs are retained in `mansart-jpa-core/target/p10-query-identifiers/`.
BUG-20261009-20 (ignored quotation) and the inherited scope of BUG-20261009-21 (association overrides) are fixed.

### Embedded association execution follow-up — 2026-10-09 (no new official TCK run)

BUG-20261009-22 is fixed for entity-owned embedded relationships: annotation/XML member and class dotted overrides
feed per-owner immutable mapping views; composed generated accesses lower nested foreign keys and collections to
the existing executable state slots. Real H2 schema/CRUD regressions prove overrides, `NO_CONSTRAINT`, ordered
join tables, embedded basic element collections, dotted inverse `mappedBy`, JPQL/Criteria/collection predicates,
persist/merge/refresh/detach/remove cascades, orphan removal, dirty updates/nulls and record reconstruction.
A closed application Java module proves the APT-generated-provider path without new opens/exports.

Validation: Temurin **25.0.4-tem**, Maven wrapper **3.9.16**,
`cd mansart-persistence && ../mvnw -ntp clean install`: **526 tests, 0 failures, 0 errors, 0 skips** —
core 441, dialects 37, processor 24, runtime module-path 3, APT module-path 4, CDI/JTA 13, actual Vauban module-path 4.
The earlier full official counters remain historical, not a measured result of this follow-up.
Existing P5 boundaries (to-one join tables, unidirectional one-to-many foreign-key collections, relationships
inside collection-table embeddable elements) remain explicit refusals, not successful mappings that drop state.

### Embeddable map-key regression repair — 2026-10-09

The subsequent full-suite regression snapshot reported **2135 tests, 2045 passes, 86 errors and 4 skips**:
51 previously green `core.metamodelapi.embeddabletype` tests rejected the valid embedded `address.mZipcode`
map during bootstrap. BUG-20261009-24 fixes the missing executable embeddable-key mapping, not the validation.
Key columns and generated accesses now share the existing flattening, JDBC binding and hydration paths;
owner-scoped defaults, nested key/value overrides, conversions, record reconstruction, key snapshots and
independent merge copies are exercised against real generated H2 schemas. A closed Java module also verifies
APT-generated key access without adding opens/exports.

Final targeted untouched official PostgreSQL 17 run (**20:40:50Z**):
**51 tests, 51 passes, 0 failures, 0 errors, 0 skips**. Command:

```bash
mansart-persistence/mansart-jpa-tck/run-official-tck-persistence-3.2.sh --area core.metamodelapi.embeddabletype
```

Final `cd mansart-persistence && ../mvnw -ntp clean install`: **549 tests, 0 failures, 0 errors, 0 skips** —
core 459, dialects 41, processor 24, runtime module-path 3, APT module-path 5, CDI/JTA 13, Vauban module-path 4.
The TCK runner is excluded from that reactor. The subsequent full-suite result appears at the start of this
section. Official sources/DDL and identifier policies remain unchanged.
Evidence: `mansart-jpa-tck/target/failsafe-reports/execution-1/`,
`target/tck-report-persistence.txt`, `target/embeddable-keys-tck-launch.log` and
`target/embeddable-keys-clean-reactor.log` under the same runner directory.

Commands (the TCK script exits zero even when tests error; inspect the XML):

```bash
./mvnw -q -ntp -f mansart-persistence/pom.xml clean install
(cd mansart-persistence/mansart-jpa-tck && ./run-official-tck-persistence-3.2.sh --area core.annotations.nativequery)
(cd mansart-persistence/mansart-jpa-tck && ./run-official-tck-persistence-3.2.sh)
```

Evidence in the runner's ignored `target/`: `p10-baseline/reports`, `p10-nativequery-report.txt`,
`p10-hardening-full.log`, `failsafe-reports`, `p10-hardening-comparison.json`.
The clean reactor log is retained as `p10-hardening-reactor.log` in the same directory.
The targeted native-query run also reports **12 tests, 1 pass, 0 failures, 11 errors**.
The earlier Vidocq Arquillian 6/6 result is a baseline, not a new runtime validation claim.

## P10 initial baseline — ORM XML mapping descriptors: 2040 → 2121 (superseded)

The 2026-10-09 full official run reports **2135 tests, 2121 passed, 0 failures, 10 errors, 4 skipped**
(Temurin 25.0.4+7-LTS, Maven 3.9.16, PostgreSQL 17-alpine, unmodified official 3.2.1 tests, DDL and exclusions).
Compared by **class + test name** with the preserved P9 XML: **81 newly passing, zero regressions, zero
added/missing tests**; execution 2 keeps its single pass.

| Gate (full-run XML) | Pass | Error | Total |
|---|---:|---:|---:|
| `core.override.*` | 26 | 0 | 26 |
| `core.callback.*` (incl. `xml`, `listener*`, `method*`) | 66 | 0 | 66 |
| `core.relationship.descriptors` | 8 | 0 | 8 |
| `se.descriptor` | 1 | 0 | 1 |
| `core.inheritance.mappedsc.descriptors` | 2 | 0 | 2 |
| `core.StoredProcedureQuery` | 40 | 0 | 40 |
| `core.annotations.nativequery` | 12 | 0 | 12 |
| `core.annotations.elementcollection` | 3 | 0 | 3 |
| `core.entitytest.apitests` | 21 | 0 | 21 |

All 78 P10-attributed errors pass. Three tests attributed to P11 also pass
(`se.cache.xml.all.Client#cacheRetrieveModeBYPASSTest`, `#cacheRetrieveModeUSETest`, `#cacheStoreModeBYPASSTest`):
their unit is declared through `orm.xml`, and none of them asserts cache contents. The **10 remaining errors are all
P11** (each reports `Cache returned: false` / `cache did not contain`): `core.cache.basicTests` `evictTest1`/`2`,
`jpa22.se.repeatable.secondarytable#subClassInheritsCacheableTrue`, `se.cache.inherit` ×2,
`se.cache.xml.all` `containsTest`/`cacheStoreModeUSETest`/`cacheStoreModeREFRESHTest`,
`se.cache.xml.{disable,enable}selective#containsTest`.

`core.override.joincolumn.Client#testOverrideJoinColumns` exposed a flush defect (BUG-20261009-19, fixed): removing
related rows together nulled `NOT NULL` foreign keys. At this initial baseline, `<delimited-identifiers/>` was
accepted with a warning and identifiers stayed unquoted (BUG-20261009-20); the hardening above fixes that deviation
and supersedes the initial claim that only P11 remained.

Clean JPA reactor (`./mvnw -ntp -f mansart-persistence/pom.xml clean install`): BUILD SUCCESS — dialect SPI 23,
H2 6, PostgreSQL 8, core 427, processor 24, module-path IT 3, processor module-path IT 3, CDI/JTA 13, Vauban
module-path vehicle 4. Vidocq Arquillian (command below, against the freshly installed Mansart): **6/6**.

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4-tem"; export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -ntp -f mansart-persistence/pom.xml clean install
(cd mansart-persistence/mansart-jpa-tck && ./run-official-tck-persistence-3.2.sh)  # exits 0 even on errors: read the XML
(cd mansart-persistence/mansart-jpa-tck && ./run-official-tck-persistence-3.2.sh --area core.override)
```

Evidence (full XML, logs, class/name comparison) is kept in session evidence (`files/p10-baseline`,
`files/p10-full/comparison.json`).

## P9 — schema/JTA implementation and actual container contract complete

The final 2026-10-09 official run reports **2135 tests, 2040 passed, 0 failures, 91 errors, 4 skipped**.
Actual toolchain: **Temurin 25.0.4+7-LTS**, Maven **3.9.16**, PostgreSQL **17-alpine**,
unmodified official **3.2.1** tests. `.sdkmanrc` still names 25.0.3; the commands explicitly select 25.0.4.

Compared by **class + test name** against the preserved P8 execution-1 XML:
**27 newly passing, zero previously passing regressions, zero added/missing tests**.
Execution 1 remains 2134 tests (2039 passed / 91 errors / 4 skips); execution 2 adds its unchanged single pass.
The total P8 baseline was 2013 passes / 118 errors / 4 skips. Original XML was preserved before focused runs.

The P9 gate extracted from the final full reports:

| Gate | Pass | Error | Skipped | Total |
|---|---:|---:|---:|---:|
| `se.schemaGeneration.*` | 25 | 0 | 1 | 26 |
| `se.pluggability.*` | 15 | 0 | 0 | 15 |
| `core.types.datetime.*` | 1 | 0 | 0 | 1 |
| **Total** | **41** | **0** | **1** | **42** |

The schema skip is the upstream-disabled table-generator case. All four official skips are unchanged.
The remaining newly passing generator case is outside this gate. No TCK source, DDL, exclusions or upstream
dependencies were edited to improve the score. The shell script exits zero even when XML contains errors;
these counters come from the XML, not its exit code.

| Remaining owner | Errors | Evidence |
|---|---:|---|
| P10 — ORM XML | 78 | The same descriptor/override/callback/listener/native/stored-procedure mapping blockers as P8 |
| P11 — second-level cache | 13 | The former 12 cache blockers plus `jpa22.se.repeatable.secondarytable.Client#subClassInheritsCacheableTrue` |

That secondary-table test previously stopped during schema setup (hence P9's former 28-error attribution).
It now persists the entities, then reports `Cache returned: false` for Product and HardwareProduct.
This is a newly reached P11 assertion, not a remaining schema failure or a baseline pass regression.

Implemented boundaries:

- Provider `PersistenceUnitInfo` bootstrap; no transformer registration (the existing bootstrap test rejects
  any `addTransformer` call).
- Database/scripts actions, metadata/script source ordering, load scripts and caller-owned URL/Reader/Writer/
  Connection ownership; schema projection and DDL through the JPA dialect AST. `SchemaManager` create/drop,
  mapped table/column validation and child-first/grouped truncation with initial load reapplication.
- Core's isolated `TransactionIntegration`/session SPI, with **no** CDI/Inject/JTA/Vauban import.
  `mansart-jpa-cdi` adapts the existing Transactions JDBC `ConnectionXAResource`; delivered
  Pool/Transactions/Data implementation files are unchanged.
- Synchronized/unsynchronized JTA joining, commit flush, rollback detach, close-before-completion,
  container transaction identity and extended ownership. Static contextual query wrappers retain settings and
  parameters while resolving execution against the current transaction; nontransactional reads detach.
  Deployment-owned injected factories/managers reject application closure.
- Portable CDI Lite BCE field/setter enhancements, synthetic factories/contexts and default unit discovery.
  Actual-container behavior is tested through Vauban, not inferred from API doubles.

**Standalone JPA validation is not fully green yet** because P10 and P11 remain open. The clean selected JPA reactor ran **490 tests:
488 passed, 1 failure, 1 error, 0 skips**. Core 410 (including six schema regressions), processor 24,
dialect SPI 23, H2 6, PostgreSQL 8, existing module vehicles 3 each, CDI/JTA 9, actual-container vehicle 4.
The 15 focused schema/JTA tests pass. Real H2/Mansart-TM reds preceded the fixes for AUTO-query offload,
failed-join release, nested flush connection reuse, shared-facade metadata races, contextual queries and closure.

In the actual named-module Vauban/H2 vehicle, all **4** tests now pass (clean run, assertions unchanged):
programmatic transaction-scoped/extended identity, explicit-source-`@Inject` field synthesis (diagnostic control),
and the two required-contract tests that were previously retained red:

1. `ContainerModuleTest#persistenceAnnotationsInjectThroughTheActualContainer`: plain `@PersistenceContext`
   and `@PersistenceUnit` fields are injected.
2. `#persistenceSetterParametersReceiveTheirEnhancedQualifier`: the enhanced setter parameter qualifier is honored.

Both were fixed upstream (Vauban `BUG-20261009-01`/`-02`, Vauban working tree, uncommitted; installed locally as `0.4.0-SNAPSHOT`; see `BUG.md` →
`BUG-20261009-16`). Selected rerun, `-pl mansart-persistence/mansart-jpa-cdi-module-it -am clean test`: core 410,
dialect SPI 23, H2 6, CDI/JTA 13, actual-container vehicle 4 — all green. The 490-test reactor figure above predates it.

The local transaction-scoped facade creates stored-procedure queries outside a transaction
(`ContextStoredProcedureQuery`, four real H2/Mansart-TM tests in `JtaIntegrationTest`; CDI/JTA module 13 tests green,
actual Vauban module-path vehicle 4/4). The Vidocq Arquillian deployment covers `@PersistenceContext` field and
`@PersistenceUnit` setter injection, synchronized commit/rollback, transaction-scoped identity, explicit joining
of an unsynchronized context, named CDI `DataSource` selection and deployment-owned factory closure semantics.
A sixth integration-module test drives the Vidocq Arquillian adapter through undeploy and asserts CDI closes
the factory while leaving the application-owned `DataSource` open (6/6 total). Extended-context lifetime is
covered by the Vauban module-path vehicle; the Vidocq adapter has no stateful-session-bean lifecycle.

Final full standalone rerun on 2026-10-09: **2135 tests, 2040 passed, 0 failures, 91 errors, 4 skipped**,
identical to the recorded P9 result. The unmodified official TCK's remaining errors are P10 (78 ORM XML) and
P11 (13 cache); four tests remain officially skipped. This is not full Jakarta Persistence certification.
Optional `TABLE_PER_CLASS`/D5 and the frozen Data producer gap remain outside P9.

The Vidocq extension discovers persistence units without external XML entity access, initializes CDI synthetic
factories during runtime startup, supplies the active JTA integration and passes an available CDI `DataSource`
instance directly to Mansart. The persistence CDI disposer closes deployment-owned factories; the runtime
extension never closes the application-owned data source.

Arquillian command from the Vidocq repository root:

```bash
./mvnw -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-it-mansart-persistence \
 -am test
```

Result: **6 tests, 6 passed, 0 failures/errors/skips**. The dependency build uses the local Vauban
`0.4.0-SNAPSHOT` containing `BUG-20261009-01`/`-02`; the upstream fix is not released yet.

The persistence extension adds only Jakarta Persistence/CDI/JTA/Inject spec dependencies and a named module;
no new thread or executor is introduced. The non-release vehicle's Central publishing guard was added.
The final concurrency review's nested-connection and shared-metadata findings were reproduced red and fixed
without modifying the delivered manager's ThreadLocal association or adding a new one.

Commands from the repository root:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4-tem"
export PATH="$JAVA_HOME/bin:$PATH"
P9_SCRATCH="$PWD/mansart-persistence/mansart-jpa-tck/target/p9-jvm-scratch"
mkdir -p "$P9_SCRATCH"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$P9_SCRATCH"

./mvnw -q -ntp -pl mansart-persistence/mansart-jpa-processor,mansart-persistence/mansart-jpa-cdi \
  -am install -DskipTests
# Currently fails only on the two retained Vauban required-contract regressions described above.
./mvnw -q -ntp \
  -pl mansart-persistence/mansart-jpa-processor,mansart-persistence/mansart-jpa-processor-module-it,mansart-persistence/mansart-jpa-module-it,mansart-persistence/mansart-jpa-cdi-module-it,mansart-persistence/mansart-jpa-dialects/mansart-jpa-dialect-postgresql \
  -am clean verify
./mvnw -q -ntp -pl mansart-persistence/mansart-jpa-cdi -am test \
  -Dtest=SchemaGenerationTest,JtaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false

cd mansart-persistence/mansart-jpa-tck
./run-official-tck-persistence-3.2.sh
```

Final full XML/logs, clean reactor XML/logs, original baseline and the exact class/name comparison are preserved
in session evidence (`files/p9-baseline`, `files/p9-final/comparison.json`).

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
