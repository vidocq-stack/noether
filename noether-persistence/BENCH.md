> Historical evidence: recorded before the 2026-10-11 extraction from Mansart.
> Original names, commands, source revisions and measurements are retained verbatim;
> they are not results measured on Noether. See the repository README for current commands.

# BENCH.md — mansart-persistence

Performance history for Jakarta Persistence. Convention: see `../CLAUDE.md`.

Every published performance figure must point to an entry here. The numbers below are local
measurements, not a general provider ranking or a production-performance guarantee.

---

## BENCH-20261010-01 — JPA provider CRUD, find, query and write comparison

- **Date**: 2026-10-10
- **Commit**: `393327a` (`persistence-work`); benchmark harness and P12 documentation are uncommitted working-tree changes
- **JVM**: Temurin 25.0.4+7-LTS; 64-Bit Server VM; default JVM flags, with JMH's `-Dbench.provider=<provider>` selection
- **Hardware**: Apple M5 / 10 cores (4 performance + 6 efficiency) / 24 GB RAM
- **OS**: Darwin 27.0.0, arm64 (`MacBook-Pro-ASD.local`)
- **Exact command**:
  ```bash
  cd mansart/main && \
    ./mvnw -ntp -pl mansart-persistence/mansart-jpa-processor-module-it -am install -DskipTests && \
    cd mansart-persistence/mansart-jpa-bench && \
    ./run-bench.sh mansart && \
    ./run-bench.sh hibernate && \
    ./run-bench.sh eclipselink
  ```
- **Configuration**:
  - JMH 1.37; one fork and one thread; 3 x 2 s warmup and 5 x 2 s measurement iterations; throughput mode; `-prof gc`.
  - Mansart 0.4.0-SNAPSHOT, Hibernate ORM 7.4.12.Final and EclipseLink 5.0.2, each isolated to its own test-only provider profile and selected by provider class name.
  - Java 25.0.4; H2 2.3.232 in memory; 128 seeded rows, one manually created schema, the same benchmark entity and version field, resource-local transactions, validation mode `NONE`, second-level/query caches disabled, and a new entity manager per operation.
  - Workloads: primary-key `find`, JPQL query/materialization (32 rows), versioned update, and insert/update/delete (three flushed DML operations in one transaction). Before timing, the runner checks exact database/query counts and exercises/rechecks persisted insert, update and delete behavior.
- **Results** (throughput ± JMH 99.9% error, and normalized allocation ± error):

  | Workload | Mansart | Hibernate ORM | EclipseLink |
  |---|---:|---:|---:|
  | `findById` | 506,276 ± 13,699 ops/s; 9,958.382 ± 0.003 B/op | 549,280 ± 291,298 ops/s; 6,533.825 ± 0.053 B/op | 769,249 ± 74,412 ops/s; 7,046.567 ± 0.001 B/op |
  | `jpqlQuery` | 33,273 ± 2,059 ops/s; 108,808.142 ± 0.051 B/op | 91,840 ± 8,715 ops/s; 18,331.080 ± 0.363 B/op | 73,647 ± 860 ops/s; 39,200.049 ± 0.011 B/op |
  | `update` | 433,871 ± 257,800 ops/s; 9,878.384 ± 0.008 B/op | 594,332 ± 53,483 ops/s; 6,821.825 ± 0.059 B/op | 756,393 ± 86,594 ops/s; 7,102.567 ± 0.001 B/op |
  | `createUpdateDelete` | 175,820 ± 43,165 ops/s; 29,448.035 ± 0.069 B/op | 215,548 ± 41,564 ops/s; 19,984.040 ± 0.149 B/op | 185,598 ± 15,036 ops/s; 31,352.033 ± 0.069 B/op |

- **Comparison with previous run**: first comparable run for each provider; no prior baseline.
- **Invalidated — `update` row (2026-10-10, see BENCH-20261010-03)**: the timed `update` workload
  set the label to `updated-<id>`, which is identical on every visit to a row after the first 128
  invocations. From then on each provider's dirty check found no change, so the measurement was
  mostly a no-op find/commit with no `UPDATE` statement, not a write. The `update` scores above are
  therefore **INVALID** as a write comparison and are kept only as raw history. The other rows are
  unaffected by this flaw; BENCH-20261010-03 replaces this comparison.
- **Notes**: This short, single-machine in-memory H2 run is a reproducible comparison of these specific operations, not an application-level workload or broad performance ranking. Several 99.9% confidence intervals are wide (notably Hibernate `findById` and Mansart `update`), so the observed point scores do not support a firm ranking; use longer repeated runs before making optimization or product claims. JMH's experimental compiler blackhole was enabled consistently across providers. Java 25 execution was exercised for all selected provider versions; this is not a vendor certification claim. Each run writes its full JMH output, JSON, dependency tree and classpath under the benchmark project's ignored `target/`; the next provider run cleans that directory, while this summary retains the results.

## BENCH-20261010-02 — Mansart harness invocation recheck

- **Date**: 2026-10-10
- **Commit**: `393327a` (`persistence-work`); benchmark harness and P12 documentation are uncommitted working-tree changes
- **JVM**: Temurin 25.0.4+7-LTS; 64-Bit Server VM; default JVM flags, with JMH's `-Dbench.provider=mansart` selection
- **Hardware**: Apple M5 / 10 cores (4 performance + 6 efficiency) / 24 GB RAM
- **OS**: Darwin 27.0.0, arm64 (`MacBook-Pro-ASD.local`)
- **Exact command**:
  ```bash
  mansart/main/mansart-persistence/mansart-jpa-bench/run-bench.sh mansart
  ```
- **Results** (throughput ± JMH 99.9% error; normalized allocation):
  ```
  JpaProviderBenchmark.createUpdateDelete  160127.073 ± 66499.478 ops/s; 29616.039 ± 0.077 B/op
  JpaProviderBenchmark.findById            508170.897 ± 19837.853 ops/s; 10006.382 ± 0.004 B/op
  JpaProviderBenchmark.jpqlQuery             33974.175 ±   954.050 ops/s; 108744.140 ± 0.051 B/op
  JpaProviderBenchmark.update               486405.604 ±  5733.082 ops/s; 10302.383 ± 0.004 B/op
  ```
- **Comparison with previous run**: against Mansart in BENCH-20261010-01, throughput point estimates changed by `findById` +0.37%, `jpqlQuery` +2.11%, `update` +12.11%, and `createUpdateDelete` -8.93%. The `createUpdateDelete` allocation estimate changed from 29448.035 to 29616.039 B/op; the other allocation estimates remain close to the prior values. The 99.9% error intervals of this run and of BENCH-20261010-01 overlap for all four workloads (for example `update` 433,871 ± 257,800 vs 486,405 ± 5,733 and `createUpdateDelete` 175,820 ± 43,165 vs 160,127 ± 66,499), so no change is established; this single repeat is not enough to establish a trend.
- **Invalidated — `update` row (2026-10-10, see BENCH-20261010-03)**: same no-op `update` workload flaw as BENCH-20261010-01 (fixed label, no `UPDATE` after the first 128 invocations). The `update` score is **INVALID** as a write measurement and kept only as raw history.
- **Notes**: This was a bounded recheck of the runner after switching it to the repository Maven wrapper; only the Mansart provider was rerun. It does not replace or extend the three-provider comparison. Workload, H2, warmup/measurement schedule, and validation/cache settings match BENCH-20261010-01.

## BENCH-20261010-03 — JPA provider comparison rerun with a real versioned `update` workload

- **Date**: 2026-10-10
- **Commit**: `393327a` (`persistence-work`); benchmark harness, runner and P12 documentation are uncommitted working-tree changes
- **JVM**: Temurin 25.0.4+7-LTS (`~/.sdkman/candidates/java/25.0.4-tem`, set explicitly as `JAVA_HOME` and first on `PATH`); 64-Bit Server VM; default JVM flags, with JMH's `-Dbench.provider=<provider>` selection
- **Hardware**: Apple M5 / 10 cores (4 performance + 6 efficiency) / 24 GB RAM
- **OS**: Darwin 27.0.0, arm64 (`MacBook-Pro-ASD.local`)
- **Exact command** (the AOT smoke script performs the Mansart `install -DskipTests` of the
  persistence reactor up to `mansart-jpa-processor-module-it`; the benchmark then resolves those
  snapshots):
  ```bash
  export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.4-tem PATH=$HOME/.sdkman/candidates/java/25.0.4-tem/bin:$PATH
  cd mansart/main/mansart-persistence/mansart-jpa-processor-module-it && ./run-aot-smoke.sh
  cd ../mansart-jpa-bench && export BENCH_RUN_ID=20261010T065740Z && \
    ./run-bench.sh mansart && ./run-bench.sh hibernate && ./run-bench.sh eclipselink
  ```
- **Raw files** (ignored, under `mansart-persistence/mansart-jpa-bench/`):
  `target/bench-reports/20261010T065740Z/{mansart,hibernate,eclipselink}/` — `jmh.log`, `jmh.json`,
  `maven.log`, `java-version.txt`, `test-dependencies.txt`, `test-classpath.txt`.
- **Configuration**: identical JMH settings to BENCH-20261010-01 (JMH 1.37; `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1`;
  throughput; `-prof gc`) plus `-foe true`. Same providers (Mansart 0.4.0-SNAPSHOT, Hibernate ORM
  7.4.12.Final, EclipseLink 5.0.2), H2 2.3.232 in memory, 128 seeded rows, manual schema, validation
  mode `NONE`, second-level/query caches disabled, a new entity manager per operation. Only change in
  the workloads: `update` now sets `updated-<id>-r<revision + 1>` and returns the persisted version,
  so every invocation is a dirty versioned `UPDATE`. Setup now runs the timed `update` method twice on
  row 1 and checks via plain JDBC that each call changed the label and incremented `revision` by one,
  and that the returned value is the persisted version (this check runs before timing only).
- **Results** (raw JMH summary lines, throughput ± 99.9% error; `gc.alloc.rate.norm` ± error):
  ```
  mansart
  JpaProviderBenchmark.createUpdateDelete  177523.466 ± 19294.417 ops/s; 29448.035 ± 0.070 B/op
  JpaProviderBenchmark.findById            498991.465 ± 77505.039 ops/s;  9950.382 ± 0.005 B/op
  JpaProviderBenchmark.jpqlQuery            33654.620 ±   593.762 ops/s; 109144.141 ± 0.055 B/op
  JpaProviderBenchmark.update              227786.501 ± 46897.076 ops/s; 19359.618 ± 0.265 B/op
  hibernate
  JpaProviderBenchmark.createUpdateDelete  229083.299 ± 19346.882 ops/s; 20128.038 ± 0.139 B/op
  JpaProviderBenchmark.findById            636594.321 ± 17262.440 ops/s;  6741.824 ± 0.052 B/op
  JpaProviderBenchmark.jpqlQuery            93638.005 ±  1754.670 ops/s; 18242.079 ± 0.358 B/op
  JpaProviderBenchmark.update              299008.350 ± 15079.231 ops/s; 14390.909 ± 0.158 B/op
  eclipselink
  JpaProviderBenchmark.createUpdateDelete  184457.161 ± 34714.273 ops/s; 31344.026 ± 0.055 B/op
  JpaProviderBenchmark.findById            778762.615 ± 12713.606 ops/s;  7046.567 ± 0.001 B/op
  JpaProviderBenchmark.jpqlQuery            72936.374 ±  1094.433 ops/s; 39200.050 ± 0.011 B/op
  JpaProviderBenchmark.update              290427.274 ±  1699.464 ops/s; 17448.521 ± 0.047 B/op
  ```
- **Comparison with previous run** (point estimates vs BENCH-20261010-01, same provider):
  Mansart `findById` -1.44%, `jpqlQuery` +1.15%, `createUpdateDelete` +0.97%; Hibernate ORM
  `findById` +15.90%, `jpqlQuery` +1.96%, `createUpdateDelete` +6.28%; EclipseLink `findById`
  +1.24%, `jpqlQuery` -0.96%, `createUpdateDelete` -0.61%. `update` has **no valid baseline**: the
  BENCH-20261010-01/02 `update` rows measured a mostly no-op workload and are invalid, so no delta is
  computed. This entry replaces the BENCH-20261010-01 comparison.
- **Notes**: Single fork, single machine, short in-memory H2 run of these specific operations — not
  an application-level workload, not a CI-wide or general provider ranking; no winner is claimed.
  Several error intervals remain wide (Mansart `findById` and `update`, EclipseLink
  `createUpdateDelete`). Provider defaults still differ beyond the shared settings: each provider uses
  its own connection pool from the `jakarta.persistence.jdbc.*` properties (Mansart: `mansart-pool`,
  decision D7; Hibernate: built-in `DriverManagerConnectionProvider`, min 1 / max 20, logged as not
  intended for production; EclipseLink: its internal default pool), and their internal
  statement/metadata caches are left at defaults. No production optimization was made for this run. The red preflight run before the fix is
  kept in `target/bench-reports/red-preflight-20261010T065710Z/mansart/` (JMH exits 1 with
  `-foe true`; a control run without `-foe` on the same build exits 0, `jmh-without-foe-control.log`).

## BENCH-20261018-01 — D43 closure comparison with unchanged workloads

- **Date**: 2026-10-18
- **Commit**: `352e684efe8b35f510e9d955e66d662c0ea73283`
  (`feat/secondary-composite-business-keys`); only bug-journal changes were
  uncommitted when the reactor and harness were built. No production optimization.
  Tested core jar SHA-256:
  `b909fd4c03c64e50337dc6d565bf7d8a26cbde63b73a7dd40b4ef09411067cc1`.
- **JVM**: Temurin 25.0.4.1+1-LTS, 64-Bit Server VM, default JVM flags.
- **Hardware**: Apple M5 / 10 cores / 24 GiB RAM.
- **OS**: Darwin 27.0.0 arm64.
- **Exact command**, from the workspace:
  ```sh
  cd noether/main
  ./mvnw -ntp clean install
  cd noether-persistence/noether-jpa-bench
  export BENCH_RUN_ID=20261018-closure-baseline
  ./run-bench.sh noether
  ./run-bench.sh hibernate
  ./run-bench.sh eclipselink
  ```
- **Configuration**: unchanged JMH 1.37 harness and corrected versioned-update
  preflight; `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 -foe true -prof gc -rf json`.
  Noether 0.4.0-SNAPSHOT, Hibernate ORM 7.4.12.Final, EclipseLink 5.0.2,
  H2 2.3.232; identical manual schema, 128 rows, 32-row JPQL result,
  validation NONE, L2/query caches off, new entity manager per operation.
  Providers ran sequentially, not concurrently. Pool/statement-cache defaults
  remain provider-specific, as in BENCH-20261010-03.
- **Raw results**: throughput ± JMH 99.9% error; allocation ± error in B/op.
  ```text
  noether
  createUpdateDelete 153425.285 ±  1974.784 ops/s;  36224.041 ± 0.094 B/op
  findById           442563.659 ± 16994.195 ops/s;  12262.758 ± 0.004 B/op
  jpqlQuery           29311.922 ±   764.775 ops/s; 144024.160 ± 0.063 B/op
  update             209312.979 ± 29543.366 ops/s;  22079.795 ± 3.614 B/op
  hibernate
  createUpdateDelete 226491.270 ± 11798.225 ops/s;  20032.038 ± 0.140 B/op
  findById           621852.411 ± 42061.979 ops/s;   6645.824 ± 0.052 B/op
  jpqlQuery           94061.862 ±  1744.339 ops/s;  18483.079 ± 0.353 B/op
  update             312809.389 ±  9084.123 ops/s;  14150.908 ± 0.101 B/op
  eclipselink
  createUpdateDelete 181463.791 ± 12232.568 ops/s;  31376.034 ± 0.072 B/op
  findById           782182.170 ±  6363.592 ops/s;   7046.567 ± 0.001 B/op
  jpqlQuery           75079.502 ±   279.390 ops/s;  39216.048 ± 0.011 B/op
  update             293656.854 ±  4056.193 ops/s;  17512.521 ± 0.050 B/op
  ```
- **Comparison with previous matching run**, BENCH-20261010-03; workload order
  `createUpdateDelete / findById / jpqlQuery / update`:
  - Noether versus historical Mansart: throughput
    **-13.57% / -11.31% / -12.90% / -8.11%**; allocation
    **+23.01% / +23.24% / +31.96% / +14.05%**.
  - Hibernate: throughput **-1.13% / -2.32% / +0.45% / +4.62%**;
    allocation **-0.48% / -1.42% / +1.32% / -1.67%**.
  - EclipseLink: throughput **-1.62% / +0.44% / +2.94% / +1.11%**;
    allocation **+0.10% / +0.00% / +0.04% / +0.37%**.
  Historical and current JVM patch levels differ; some confidence intervals
  overlap. These are descriptive point-estimate deltas, not attribution to
  a specific follow-up or proof of a statistically established regression.
- **Interpretation**: Noether is still behind both competitors on these local
  workloads. The largest robust gap is JPQL: roughly 3.21x lower throughput
  than Hibernate and 2.56x lower than EclipseLink, with roughly 7.79x and
  3.67x their allocation respectively. This is not a general provider ranking,
  a network-database measurement or a virtual-thread scalability benchmark.
- **Raw evidence**:
  `noether-jpa-bench/target/bench-reports/20261018-closure-baseline/{noether,hibernate,eclipselink}/`
  contains full JMH logs/JSON, Maven logs, JVM, dependencies and classpaths.
  A second copy is retained in session artifacts under `closure-bench-evidence`.

## BENCH-20261018-02 — Reverse-order repeat of D43 closure comparison

- **Date / commit / JVM / hardware / OS / configuration**: identical to
  BENCH-20261018-01. Same binaries and workloads, separate JMH forks;
  providers ran sequentially in reverse order.
- **Exact command**, from `noether/main/noether-persistence/noether-jpa-bench`:
  ```sh
  export BENCH_RUN_ID=20261018-closure-repeat
  ./run-bench.sh eclipselink
  ./run-bench.sh hibernate
  ./run-bench.sh noether
  ```
- **Raw results**: throughput ± JMH 99.9% error; allocation ± error in B/op.
  ```text
  noether
  createUpdateDelete 156699.259 ±  16779.120 ops/s;  36216.040 ± 0.082 B/op
  findById           451487.274 ±  17678.732 ops/s;  12102.758 ± 0.004 B/op
  jpqlQuery           29257.200 ±    632.725 ops/s; 144432.167 ± 0.060 B/op
  update             217987.265 ±  15868.735 ops/s;  22064.080 ± 1.132 B/op
  hibernate
  createUpdateDelete 216235.619 ±  57811.260 ops/s;  20128.042 ± 0.169 B/op
  findById           625637.963 ±  12734.937 ops/s;   6781.824 ± 0.053 B/op
  jpqlQuery           94500.093 ±   1147.178 ops/s;  18242.078 ± 0.350 B/op
  update             306064.830 ±  19529.270 ops/s;  14030.908 ± 0.131 B/op
  eclipselink
  createUpdateDelete 186395.210 ±  20920.081 ops/s;  31328.033 ± 0.068 B/op
  findById           750836.935 ± 103947.988 ops/s;   7046.567 ± 0.001 B/op
  jpqlQuery           70576.042 ±  20105.396 ops/s;  39200.051 ± 0.021 B/op
  update             289921.984 ±   8557.974 ops/s;  17448.521 ± 0.049 B/op
  ```
- **Comparison with previous matching run**, BENCH-20261018-01; workload order
  `createUpdateDelete / findById / jpqlQuery / update`:
  - Noether throughput **+2.13% / +2.02% / -0.19% / +4.14%**;
    allocation **-0.02% / -1.30% / +0.28% / -0.07%**.
  - Hibernate throughput **-4.53% / +0.61% / +0.47% / -2.16%**;
    allocation **+0.48% / +2.05% / -1.30% / -0.85%**.
  - EclipseLink throughput **+2.72% / -4.01% / -6.00% / -1.27%**;
    allocation **-0.15% / +0.00% / -0.04% / -0.37%**.
- **Interpretation**: changing provider order does not remove Noether's gap.
  Several intervals are wide, especially EclipseLink JPQL/find and Hibernate
  CRUD on the repeat. Do not pool iteration samples as independent machines
  or claim a universal ranking. JPQL allocation and Hibernate's JPQL throughput
  remain clearly separated from Noether in both runs.
- **Raw evidence**:
  `noether-jpa-bench/target/bench-reports/20261018-closure-repeat/{noether,hibernate,eclipselink}/`,
  also copied to session `closure-bench-evidence`; computed deltas are in
  `closure-bench-comparison.json`.

## BENCH-20261018-03 — Diagnostic JFR and SQL-count probes, not a new ranking

- **Date / commit / JVM / hardware / OS**: identical to BENCH-20261018-01.
- **Exact JFR command**, from the benchmark directory; `ARTIFACTS` denotes the
  session's `files` directory:
  ```sh
  ARTIFACTS=/Users/antoine/.copilot/session-state/f0e6bb7b-2f1f-4ac2-b65c-74a0b1d54081/files
  java -Dbench.provider=io.vidocq.noether.jpa.core.NoetherPersistenceProvider \
    -cp "target/noether/test-classes:target/noether/classes:$(cat target/noether/test-classpath.txt)" \
    org.openjdk.jmh.Main 'io.vidocq.noether.jpa.bench.JpaProviderBenchmark.*' \
    -wi 3 -i 3 -w 2s -r 3s -f 1 -t 1 -foe true -prof gc \
    -prof "jfr:dir=$ARTIFACTS/closure-jfr;stackDepth=96" \
    -rf json -rff "$ARTIFACTS/closure-jfr-results.json"
  ```
- **Raw profiled results**, throughput ± JMH 99.9% error; allocation ± error:
  ```text
  createUpdateDelete 149900.110 ± 13037.684 ops/s;  36675.104 ±  313.263 B/op
  findById           437616.188 ± 11121.015 ops/s;  12170.531 ±  106.852 B/op
  jpqlQuery           28572.296 ±  1688.573 ops/s; 147562.173 ± 1644.448 B/op
  update             210867.372 ± 40969.499 ops/s;  22055.800 ±  214.248 B/op
  ```
- **Comparison**: first matching JFR run; no same-configuration predecessor.
  The profiling configuration and instrumentation differ from entries 01/02;
  these scores are diagnostic, not an optimization delta or comparison winner.
- **CPU/allocation observations**: among recorded benchmark-worker samples,
  JPQL's first Noether frame is `EntityLoader.row` in 250/652 CPU samples;
  `JpqlQuery.execute` in 100 and `EntityStatements.reference` in 55.
  Approximately 60% of sampled allocation weight is attributed to stacks
  whose first Noether frame is `EntityLoader.row`. This includes JDBC/H2 work
  below that frame; it is not exclusive CPU time in the Java method.
  Find allocation also includes `EntityManagerImpl` construction; write
  allocation is concentrated under `FlushEngine.flush/update`.
  Sampling weight is an estimate, not exact allocated bytes or GC-pause time.
- **Untimed SQL probe**: after the harness's normal fixture/write preflight,
  enable H2 `SET QUERY_STATISTICS TRUE`, invoke the actual benchmark
  `jpqlQuery` once, then read `INFORMATION_SCHEMA.QUERY_STATISTICS`.
  Each provider returns the same **32 rows**:
  ```text
  noether:    1 x SELECT t0.id ... WHERE t0.category = ? ORDER BY t0.id
             32 x SELECT id, category, label, revision ... WHERE id = ?
  hibernate:  1 x SELECT id, category, label, revision ... category = ? ORDER BY id
  eclipselink:1 x SELECT ID, CATEGORY, LABEL, REVISION ... CATEGORY = ? ORDER BY ID
  ```
  Count data SELECTs only; connection/COMMIT/ROLLBACK control statements are
  reported separately in raw logs. This is **33 data SELECTs versus 1**,
  an ordinary entity-query N+1 materialization path, not an association graph.
  `JpqlQuery.resolve` calls `runtime.find` for each selected identity; plain
  entity selection currently carries key binders rather than complete state.
- **Probe reproduction**, from the benchmark directory for each provider and
  its corresponding provider class (as in `run-bench.sh`):
  ```sh
  CP="target/$provider/test-classes:target/$provider/classes:$(cat target/$provider/test-classpath.txt)"
  javac -cp "$CP" -d "$ARTIFACTS/closure-query-probe" \
    "$ARTIFACTS/closure-query-probe/QueryStatementProbe.java"
  java "-Dbench.provider=$provider_class" \
    -cp "$ARTIFACTS/closure-query-probe:$CP" \
    io.vidocq.noether.jpa.bench.QueryStatementProbe
  ```
- **Prioritized proposals, not implemented gains**:
  1. Hydrate eligible plain entity selections from their result row through
     the existing staged loader. Gate the 32-row case at one data SELECT and
     preserve identity, dirty state, callback ordering, versions, cache,
     converters and complete association loading.
  2. Cache parameter-independent JPQL parsing/validation/SQL templates within
     a factory, with bounded retention. Creation currently translates for
     validation and execution translates again. Keep parameters, graph/hint
     mutation, IN-list arity, pagination and locking execution-specific.
     Profiling supports investigating it, not calling it the main cost.
  3. Reduce per-entity-manager, reference lookup and flush scratch allocation
     using bootstrap metadata and operation-local reuse where safe. Keep
     snapshots, callback/reentrancy and thread-safety contracts; do not disable
     correctness checks or share mutable entity-manager state.
  Validate each separately with failing-first correctness/SQL-count tests,
  the clean reactor and TCK gate, then repeated identical benchmarks.
- **Evidence**: session `closure-jfr` recordings, `closure-jfr-results.json`,
  `closure-jfr.log`, `closure-profile-summary.json`, analysis script
  `closure-bench-analyze.py`, and `closure-query-probe/QueryStatementProbe.java`
  with `closure-query-{noether,hibernate,eclipselink}.log`.
  No runtime code, dependency, benchmark workload or runner was changed.

## BENCH-20261019-01 — PERF1 result-row hydration comparison

- **Date**: 2026-10-19.
- **Commit**: `352e684efe8b35f510e9d955e66d662c0ea73283` plus uncommitted
  PERF1 production changes in `Translator` and `JpqlQuery`; branch
  `feat/secondary-composite-business-keys`. Exact production diff retained as
  session `row-hydration-production.patch`. Tested core jar SHA-256:
  `00b47af94dd961f6e4f9e05b3b1649840d2aaf146d7237637840d3c6d12bcd1e`.
- **JVM / hardware / OS**: Temurin 25.0.4.1+1-LTS, default flags;
  Apple M5, 10 cores, 24 GiB RAM; Darwin 27.0.0 arm64.
- **Exact commands**, from the Noether root:
  ```sh
  ./mvnw -ntp clean install
  cd noether-persistence/noether-jpa-bench
  BENCH_RUN_ID=20261019-row-hydration ./run-bench.sh noether
  BENCH_RUN_ID=20261019-row-hydration ./run-bench.sh hibernate
  BENCH_RUN_ID=20261019-row-hydration ./run-bench.sh eclipselink
  ```
- **Configuration**: unchanged harness/preflight, JMH 1.37,
  `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 -foe true -prof gc -rf json`.
  H2 2.3.232, Hibernate ORM 7.4.12.Final, EclipseLink 5.0.2,
  Noether 0.4.0-SNAPSHOT. Same 128-row fixture, 32-row query, actual versioned
  update, manual schema, validation/L2/query caches off, fresh entity manager
  per operation. Provider-specific pool/cache defaults remain unchanged.
  Providers ran sequentially after the reactor and TCK completed.
- **Raw results**: throughput ± JMH 99.9% error; allocation ± error:
  ```text
  noether
  createUpdateDelete 144551.762 ±   9581.774 ops/s;  36216.044 ± 0.103 B/op
  findById           431938.102 ±   8244.165 ops/s;  12150.759 ± 0.004 B/op
  jpqlQuery           36954.210 ±    528.358 ops/s; 108824.125 ± 0.045 B/op
  update             210735.977 ±  12102.378 ops/s;  21983.815 ± 3.429 B/op
  hibernate
  createUpdateDelete 221024.461 ±   2106.439 ops/s;  20008.039 ± 0.146 B/op
  findById           611616.168 ±  42090.106 ops/s;   6717.825 ± 0.055 B/op
  jpqlQuery           92561.282 ±    891.613 ops/s;  18282.080 ± 0.361 B/op
  update             294766.902 ±   2869.628 ops/s;  14230.908 ± 0.142 B/op
  eclipselink
  createUpdateDelete 184274.659 ±  10275.641 ops/s;  31472.034 ± 0.072 B/op
  findById           769363.467 ±  13258.634 ops/s;   7046.567 ± 0.001 B/op
  jpqlQuery           74431.720 ±    358.545 ops/s;  39200.049 ± 0.011 B/op
  update             208651.952 ± 159616.906 ops/s;  17336.525 ± 0.065 B/op
  ```
- **Delta versus most recent matching run**, BENCH-20261018-02, order
  `createUpdateDelete / findById / jpqlQuery / update`:
  - Noether throughput **-7.75% / -4.33% / +26.31% / -3.33%**;
    allocation **+0.00% / +0.40% / -24.65% / -0.36%**.
  - Hibernate throughput **+2.21% / -2.24% / -2.05% / -3.69%**;
    allocation **-0.60% / -0.94% / +0.22% / +1.43%**.
  - EclipseLink throughput **-1.14% / +2.47% / +5.46% / -28.03%**;
    allocation **+0.46% / -0.00% / -0.00% / -0.64%**.
  These are descriptive point-estimate deltas, not controlled same-session
  A/B attribution. Unchanged find/write paths are not claimed to improve or
  regress; some intervals are wide, especially EclipseLink update.
- **SQL-shape verification**: reran the untimed BENCH-20261018-03 probe
  unchanged against the rebuilt Noether harness:
  ```text
  Provider=io.vidocq.noether.jpa.core.NoetherPersistenceProvider resultRows=32
  1 x SELECT t0.id, t0.id, t0.category, t0.label, t0.revision
      FROM bench_record t0 WHERE t0.category = ? ORDER BY t0.id
  ```
  One data SELECT, not the former identity query plus 32 row reloads;
  transaction-control statements excluded. Duplicate ID selection preserves
  existing item/hydration offsets; no new mapping representation.
- **Interpretation**: JPQL throughput rises from 29257.200 to 36954.210 ops/s,
  with allocation falling from 144432.167 to 108824.125 B/op.
  Hibernate remains about 2.50x faster and EclipseLink about 2.01x faster on
  this query; Noether still allocates about 5.95x/2.78x their bytes per op.
  Eliminating reloads does not eliminate the remaining query/materialization
  allocation. No projection, write semantics or result cardinality was reduced.
- **Evidence**: ignored benchmark `target/bench-reports/20261019-row-hydration/`
  per-provider JMH JSON/logs and environment/dependency records, copied to session
  `row-hydration-bench-evidence`; `row-hydration-query-noether.log` and
  `row-hydration-bench-comparison.json`. Probe source/command is unchanged
  from BENCH-20261018-03. All measurements remain local single-thread H2
  microbenchmarks, not network latency or production scalability results.

## BENCH-20261019-02 — Reverse-order repeat of PERF1 comparison

- **Date / commit / tested jar / JVM / hardware / OS / configuration**:
  identical to BENCH-20261019-01; separate forks, reverse provider order.
- **Exact commands**, from the benchmark directory:
  ```sh
  BENCH_RUN_ID=20261019-row-hydration-repeat ./run-bench.sh eclipselink
  BENCH_RUN_ID=20261019-row-hydration-repeat ./run-bench.sh hibernate
  BENCH_RUN_ID=20261019-row-hydration-repeat ./run-bench.sh noether
  ```
- **Raw results**: throughput ± JMH 99.9% error; allocation ± error:
  ```text
  noether
  createUpdateDelete 147858.017 ±  12914.975 ops/s;  36784.042 ± 0.095 B/op
  findById           397248.407 ± 283543.868 ops/s;  12086.760 ± 0.009 B/op
  jpqlQuery           36104.460 ±   7396.533 ops/s; 107200.127 ± 0.035 B/op
  update             217735.519 ±   7106.169 ops/s;  21903.979 ± 2.032 B/op
  hibernate
  createUpdateDelete 207415.612 ±  31929.291 ops/s;  19696.041 ± 0.149 B/op
  findById           581790.666 ±  35253.938 ops/s;   6749.825 ± 0.056 B/op
  jpqlQuery           87239.886 ±  21450.963 ops/s;  18147.082 ± 0.352 B/op
  update             291332.252 ±  75624.263 ops/s;  14134.910 ± 0.167 B/op
  eclipselink
  createUpdateDelete 175144.308 ±  12290.840 ops/s;  31160.035 ± 0.074 B/op
  findById           754287.557 ±  29681.719 ops/s;   7046.567 ± 0.001 B/op
  jpqlQuery           69485.694 ±   4947.134 ops/s;  39200.052 ± 0.012 B/op
  update             264497.434 ±  41095.242 ops/s;  17524.519 ± 0.053 B/op
  ```
- **Delta versus BENCH-20261019-01**, order
  `createUpdateDelete / findById / jpqlQuery / update`:
  - Noether throughput **+2.29% / -8.03% / -2.30% / +3.32%**;
    allocation **+1.57% / -0.53% / -1.49% / -0.36%**.
  - Hibernate throughput **-6.16% / -4.88% / -5.75% / -1.17%**;
    allocation **-1.56% / +0.48% / -0.74% / -0.67%**.
  - EclipseLink throughput **-4.95% / -1.96% / -6.65% / +26.76%**;
    allocation **-0.99% / +0.00% / +0.00% / +1.08%**.
- **Interpretation**: Noether JPQL remains around 36–37k ops/s and
  107–109 kB/op across both new runs, versus around 29.3k and 144 kB/op in
  both pre-optimization runs. The repeat has wide intervals (notably Noether
  find and Hibernate JPQL/update); do not average iteration samples into a
  false multi-machine confidence interval or infer unchanged-path regressions.
  The exact SQL-count improvement is independently established; performance
  improvements remain specific to this workload, runtime and configuration.
- **Evidence**: `target/bench-reports/20261019-row-hydration-repeat/`,
  copied to session `row-hydration-bench-evidence`; the comparison script
  `row-hydration-bench-analyze.py` and its JSON preserve all scores and deltas.

## BENCH-20261019-03 — Post-PERF1 JFR: flush and metadata allocation

- **Date**: 2026-10-19.
- **Commit**: `352e684efe8b35f510e9d955e66d662c0ea73283` plus the same
  uncommitted PERF1 changes as BENCH-20261019-01/02. Tested core SHA-256:
  `00b47af94dd961f6e4f9e05b3b1649840d2aaf146d7237637840d3c6d12bcd1e`.
  No production or harness change during profiling.
- **Environment**: Apple M5, 10 cores, 24 GiB RAM; Darwin 27.0.0 arm64;
  Temurin 25.0.4.1+1-LTS, JMH 1.37, H2 2.3.232. Same fixtures,
  cache-disabled configuration and operation semantics as entry 01.
- **Exact command**, from `noether-persistence/noether-jpa-bench`:
  ```sh
  ARTIFACTS=/Users/antoine/.copilot/session-state/f0e6bb7b-2f1f-4ac2-b65c-74a0b1d54081/files
  java -Dbench.provider=io.vidocq.noether.jpa.core.NoetherPersistenceProvider \
    -cp "target/noether/test-classes:target/noether/classes:$(cat target/noether/test-classpath.txt)" \
    org.openjdk.jmh.Main 'io.vidocq.noether.jpa.bench.JpaProviderBenchmark.*' \
    -wi 3 -i 3 -w 2s -r 3s -f 1 -t 1 -foe true -prof gc \
    -prof "jfr:dir=$ARTIFACTS/perf1-jfr;stackDepth=96" \
    -rf json -rff "$ARTIFACTS/perf1-jfr-results.json" \
    > "$ARTIFACTS/perf1-jfr.log" 2>&1
  ```
- **Raw instrumented results**, throughput ± JMH 99.9% error; allocation ± error:
  ```text
  createUpdateDelete 137069.886 ± 340278.146 ops/s;  37054.111 ±  407.781 B/op
  findById           433745.356 ±  51219.299 ops/s;  12282.539 ±  106.898 B/op
  jpqlQuery           31807.540 ±  87641.833 ops/s; 108879.104 ± 1607.090 B/op
  update             160014.270 ± 657126.486 ops/s;  22265.523 ±  267.041 B/op
  ```
- **Delta versus matching pre-PERF1 JFR configuration**, BENCH-20261018-03,
  order `createUpdateDelete / findById / jpqlQuery / update`:
  throughput **-8.56% / -0.88% / +11.32% / -24.12%**; allocation
  **+1.03% / +0.92% / -26.21% / +0.95%**.
  Throughput intervals are extremely wide: do not interpret these
  point-estimate deltas as a speed comparison. Use entries 01/02 for the
  unprofiled comparison. The allocation reduction is consistent with PERF1.
- **Initial JPQL profile**: 599 workload CPU samples, approximately 10.5%
  on stacks containing parser/translator work, 1.8% dialect/render and
  20.2% entity-loader (disjoint categories, in that precedence order).
  Estimated allocation weight includes 40.4% under
  `FlushEngine.identitySet` and 14.0% under `EntityStatements.reference`.
  The identity-set stacks originate in transaction commit's ordinary flush,
  not query SQL execution. These weights are sampling estimates, not exact
  bytes/op, exclusive method costs or promised removable overhead.
- **Targeted confirmation**, same directory/environment, longer JPQL-only run:
  ```sh
  java -Dbench.provider=io.vidocq.noether.jpa.core.NoetherPersistenceProvider \
    -cp "target/noether/test-classes:target/noether/classes:$(cat target/noether/test-classpath.txt)" \
    org.openjdk.jmh.Main 'io.vidocq.noether.jpa.bench.JpaProviderBenchmark.jpqlQuery' \
    -wi 3 -i 5 -w 2s -r 3s -f 1 -t 1 -foe true -prof gc \
    -prof "jfr:dir=$ARTIFACTS/perf1-jfr-confirm;stackDepth=96" \
    -rf json -rff "$ARTIFACTS/perf1-jfr-confirm-results.json" \
    > "$ARTIFACTS/perf1-jfr-confirm.log" 2>&1
  ```
  ```text
  jpqlQuery 35662.053 ± 1622.793 ops/s; 108852.368 ± 222.797 B/op
  ```
  First run of this longer profiling configuration; do not infer a speed delta
  from the previous three-iteration run. Its allocation and throughput are
  consistent with the unprofiled PERF1 runs within instrumentation/uncertainty.
- **Confirmed stack interpretation**: 1018 workload CPU samples;
  parser/translator 10.6%, dialect/render 2.6%, entity-loader 19.1%.
  Of the filtered estimated allocation weight, `EntityStatements.reference`
  accounts for 28.7%, `JpqlQuery.execute` 12.9%, `EntityLoader.loadRows` 8.9%,
  `FlushEngine.hasInverseIndexes` 5.5% and `JpqlQuery.fetched` 4.8%.
  Parse/translation categories total 12.1% of weight and render 3.2%.
  `reference`'s dominant stacks run through
  `EntityOperations.beforeFlush -> EntityManagerImpl.flush -> beforeCommit`.
  It creates a stream/filter/findFirst even for a scalar attribute with no
  relationship. `hasInverseIndexes` similarly scans collection metadata.
  Both execute during commit of this read-only benchmark transaction.
  The first run's identity-set share does not reproduce in the second run:
  allocation sampling is site-sensitive; do not extrapolate an exact
  per-site saving or rank identity sets alone above the repeated metadata signal.
- **Next optimization recommendation**: precompute/index immutable relationship
  metadata by attribute and inverse-index presence; avoid stream pipelines on
  repeated negative lookups. Then investigate lazy allocation of flush
  workspaces and redundant state copies on genuinely empty work lists.
  Preserve ordinary dirty checking, callbacks, cascades, mutable values,
  optimistic checks, forced increments and collection changes: a read query
  does not justify skipping transaction commit's flush.
  A bounded query-plan cache remains a candidate, but the profiles do not
  establish it as the main cost. No speedup is predicted from sample shares.
- **Analysis and evidence**: four `perf1-jfr` recordings plus
  `perf1-jfr-confirm` recording, raw logs/JSON and exported ExecutionSample/
  ObjectAllocationSample JSON in session artifacts. Reproduce summaries with
  `python3 "$ARTIFACTS/perf1-profile-analyze.py"` and
  `python3 "$ARTIFACTS/perf1-profile-analyze.py" perf1-jfr-confirm`.
  Summary files are `perf1-jfr-profile-summary.json` and
  `perf1-jfr-confirm-profile-summary.json`. Analysis keeps benchmark-worker
  threads only and requires the actual workload method in each stack:
  JMH barrier/bookkeeping allocations on the same thread are excluded.
  Categories include descendants; JIT inlining and sample variation limit
  precise attribution. Recordings cover the profiled measurement window,
  not a production traffic sample. No implementation was changed by this study.

## BENCH-20261022-01 — PERF2 same-session PERF1 baseline

- **Date**: 2026-10-22.
- **Commit**: `8af6b60` (`perf/query-row-hydration`), before PERF2 production
  edits. PERF1 is present; the benchmark harness is unchanged.
- **JVM**: Temurin 25.0.4.1+1-LTS, default JVM/GC flags.
- **Hardware**: Apple M5 / 10 cores / 24 GiB RAM.
- **OS**: Darwin 27.2.0.
- **Exact command**, from the Noether root:
  ```sh
  ./mvnw -ntp -q clean install -DskipTests
  cd noether-persistence/noether-jpa-bench
  BENCH_RUN_ID=20261022-perf2-before ./run-bench.sh noether
  ```
  The runner uses `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 -foe true -prof gc -rf json`.
- **Results**, JMH 99.9% error:
  ```text
  createUpdateDelete 155405.012 +/-   3786.594 ops/s;  36480.040 +/-   0.083 B/op
  findById           407660.315 +/- 195291.841 ops/s;  12414.759 +/-   0.004 B/op
  jpqlQuery           38395.991 +/-   2333.792 ops/s; 108988.846 +/- 109.551 B/op
  update             211107.488 +/-  28074.710 ops/s;  22111.824 +/-   3.335 B/op
  ```
- **Delta versus BENCH-20261019-02**, same workload order:
  throughput **+5.10% / +2.62% / +6.35% / -3.04%**; allocation
  **-0.83% / +2.71% / +1.67% / +0.95%**. No production code differs from
  that PERF1 state; these changes illustrate run/JIT/environment variation,
  not a new optimization. In particular the find throughput interval is wide.
- **Evidence**: `noether-jpa-bench/target/bench-reports/20261022-perf2-before/noether/`
  contains raw JSON, JMH log, JVM version and dependency/classpath records.
  Session artifacts use `perf2-before-`. No build, test suite or other
  benchmark was launched concurrently by this session during measurement.
  The runner's normal write/version preflight remains enabled. H2 2.3.232,
  fresh entity manager per operation, L2/query caches disabled; these short
  single-fork, single-thread results are not a production scalability claim.

## BENCH-20261022-02 — PERF2 indexed metadata, first comparison

- **Date / JVM / hardware / OS / workload**: identical to BENCH-20261022-01.
- **Commit**: `8af6b60` plus uncommitted PERF2 metadata indexing and tests.
  Tested `noether-jpa-core` jar SHA-256:
  `67de77d779a4c178419101fd3ffa586e3b376f059626674d6f6b9cd54dba8fbe`.
- **Exact command**, from the Noether root:
  ```sh
  ./mvnw -ntp -q clean install
  TCK_FIXTURES=patched TCK_VALIDATION=off \
    ./noether-persistence/noether-jpa-tck/run-official-tck-persistence-3.2.sh
  cd noether-persistence/noether-jpa-bench
  BENCH_RUN_ID=20261022-perf2-after ./run-bench.sh noether
  ```
  Reactor and TCK finish before the benchmark. JMH settings are unchanged:
  `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 -foe true -prof gc -rf json`.
- **Results**, JMH 99.9% error:
  ```text
  createUpdateDelete 165615.010 +/-  9963.106 ops/s; 34512.038 +/-   0.075 B/op
  findById           453642.628 +/- 39570.584 ops/s; 11790.758 +/-   0.003 B/op
  jpqlQuery           42426.712 +/-  2019.375 ops/s; 77829.209 +/- 438.278 B/op
  update             226993.104 +/- 15067.289 ops/s; 21376.112 +/-   0.852 B/op
  ```
- **Delta versus same-session BENCH-20261022-01**, same workload order:
  throughput **+6.57% / +11.28% / +10.50% / +7.52%**; allocation
  **-5.39% / -5.03% / -28.59% / -3.33%**.
- **Interpretation**: the largest allocation reduction is in JPQL, where
  each operation commits 32 managed entities. Bootstrap-indexed references
  eliminate the repeated stream lookup for every basic attribute, and the
  prefiltered inverse-index list eliminates the per-entity stream predicate.
  The benchmark still runs commit flush and dirty checking; no columns,
  result rows, writes, version checks or preflight are removed. No query-plan
  cache, read-only transaction shortcut or provider setting is introduced.
  Throughput improves in these point estimates, but 99.9% intervals overlap
  the baseline (especially find); do not treat percentages as precise or
  statistically established speedups. Allocation reduction is clearer.
- **Evidence / limitations**:
  `noether-jpa-bench/target/bench-reports/20261022-perf2-after/noether/`;
  session `perf2-after-bench.log`, `perf2-comparison.json` and
  `perf2-compare.py`. No concurrent build/test/benchmark was launched by this
  session during measurement. The clean reactor and locally patched TCK
  retain their contracts (`TCK.md`). Hibernate/EclipseLink were not rerun in
  this experiment: comparisons to their 2026-10-19 scores are cross-date
  context, not a newly measured provider ranking.

## BENCH-20261022-03 — PERF2 indexed metadata, independent-fork repeat

- **Date / commit / binary / JVM / hardware / OS**: identical to
  BENCH-20261022-02. No source change between these two campaigns.
- **Exact command**, from `noether-persistence/noether-jpa-bench`:
  ```sh
  BENCH_RUN_ID=20261022-perf2-after-repeat ./run-bench.sh noether
  ```
  Same JMH settings as entries 01/02, new forks, after campaign 02 completes.
- **Results**, JMH 99.9% error:
  ```text
  createUpdateDelete 162483.852 +/-  3285.420 ops/s; 34520.039 +/-   0.080 B/op
  findById           467966.885 +/- 21168.670 ops/s; 11478.758 +/-   0.003 B/op
  jpqlQuery           43207.439 +/-  2785.894 ops/s; 77828.206 +/- 309.133 B/op
  update             230642.463 +/-  7945.563 ops/s; 21080.112 +/-   0.842 B/op
  ```
- **Delta versus preceding BENCH-20261022-02**, same workload order:
  throughput **-1.89% / +3.16% / +1.84% / +1.61%**; allocation
  **+0.02% / -2.65% / -0.001% / -1.38%**.
- **Delta versus pre-PERF2 BENCH-20261022-01**:
  throughput **+4.56% / +14.79% / +12.53% / +9.25%**; allocation
  **-5.37% / -7.54% / -28.59% / -4.67%**.
- **Interpretation / limitations**: JPQL allocation repeats at approximately
  77.8 kB/op instead of 109.0 kB/op. All workload throughput point estimates
  remain above baseline, but this is one baseline campaign followed by two
  optimized campaigns, not randomized multi-fork A/B evidence. Wide baseline
  intervals and the find/update allocation variation across optimized forks
  limit exact attribution. Do not claim parity with Hibernate, production
  latency/scalability gains, or a universal gain from these short H2 runs.
- **Evidence**:
  `noether-jpa-bench/target/bench-reports/20261022-perf2-after-repeat/noether/`.
  All three raw report directories and the core patch are also copied to
  session `perf2-bench-evidence`; `perf2-compare.py` computes deltas from the
  raw JSON without rounding intermediate scores.

## BENCH-20261111-01 — Hibernate ORM 5.6 joins the comparison (Vidocq/noether#45)

- **Date**: 2026-11-11
- **Commit**: `f7746b6` (`fix-proposals`, identical to `main`) plus the uncommitted `hibernate5` harness
  profile of branch `bench/hibernate-5`. Noether jars installed by `./mvnw -ntp install` of that commit.
- **JVM**: Temurin 25.0.3 (2026-04-21 LTS), 64-Bit Server VM, default flags.
- **Hardware**: Apple M5 Max / 18 cores / 128 GB RAM. **Not the machine of the earlier entries**
  (Apple M5 / 10 cores / 24 GB): cross-entry deltas are context only.
- **OS**: macOS 27.2, arm64.
- **Providers**: Noether 0.4.0-SNAPSHOT; Hibernate ORM **5.6.15.Final** (`hibernate-core-jakarta`, the
  Persistence 3.0 build of 5.6, with Byte Buddy replaced by 1.17.8 because its own 1.12 cannot read
  Java 25 class files); Hibernate ORM 7.4.12.Final. H2 2.3.232 in memory, same entity, schema, seeded rows,
  settings and preflight checks for every provider. Hibernate 5.6 predates `PersistenceConfiguration`
  (3.2): the harness hands it the same unit as a `PersistenceUnitInfo` through
  `createContainerEntityManagerFactory` (`-Dbench.bootstrap=unit-info`), with Hibernate's own
  `hibernate.connection.*` keys added; both Hibernate lines use their built-in 20-connection pool
  (`HHH10001002`), Noether its `mansart-pool` pool.
- **Exact command**, from the Noether root:
  ```sh
  ./mvnw -ntp install
  cd noether-persistence/noether-jpa-bench
  export BENCH_RUN_ID=20261111-baseline
  ./run-bench.sh hibernate5 && ./run-bench.sh noether && ./run-bench.sh hibernate
  ```
  JMH settings unchanged: `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 -foe true -prof gc -rf json`.
- **Results**, JMH 99.9% error, normalized allocation:
  ```text
  noether
  createUpdateDelete 164213.383 +/-  3884.010 ops/s; 34600.038 +/-   0.078 B/op
  findById           466085.882 +/- 10499.548 ops/s; 11390.758 +/-   0.004 B/op
  jpqlQuery           41843.872 +/-  1184.590 ops/s; 78757.358 +/- 114.208 B/op
  update             218329.098 +/- 23406.159 ops/s; 21360.130 +/-   0.703 B/op
  hibernate5 (5.6.15.Final)
  createUpdateDelete 250798.545 +/-  5027.971 ops/s; 17864.036 +/-   0.143 B/op
  findById           536978.356 +/-  8054.610 ops/s;  7525.827 +/-   0.069 B/op
  jpqlQuery           67386.726 +/-   998.652 ops/s; 26674.322 +/-   0.177 B/op
  update             284889.000 +/-  4986.130 ops/s; 13670.912 +/-   0.164 B/op
  hibernate (7.4.12.Final)
  createUpdateDelete 237486.403 +/-  1566.256 ops/s; 19976.036 +/-   0.136 B/op
  findById           627351.368 +/- 28047.362 ops/s;  7021.824 +/-   0.054 B/op
  jpqlQuery           89985.802 +/- 12024.165 ops/s; 18379.080 +/-   0.356 B/op
  update             305915.200 +/-  8553.596 ops/s; 14318.907 +/-   0.101 B/op
  ```
- **Noether versus Hibernate 5.6**, same session (throughput; allocation ratio):
  `createUpdateDelete` **-34.5%** (x1.94 B/op), `findById` **-13.2%** (x1.51), `jpqlQuery` **-37.9%**
  (x2.95), `update` **-23.4%** (x1.56). Versus Hibernate 7.4: -30.9% / -25.7% / -53.5% / -28.6%.
  The 99.9% intervals do not overlap on any workload.
- **Comparison with previous run**: Noether versus BENCH-20261022-03 (same code paths, other machine and
  JDK patch): +1.06% / -0.40% / -3.16% / -5.34%, within that entry's intervals except `update`; no
  change is claimed. First Hibernate 5.6 measurement.
- **Notes**: Noether allocates 1.5x to 3x Hibernate 5.6 per operation; the JPQL gap (32 managed rows per
  operation) is the largest. Hibernate 5.6 is measured as shipped, without bytecode enhancement, like the
  other providers. Raw evidence:
  `noether-jpa-bench/target/bench-reports/20261111-baseline/{hibernate5,noether,hibernate}/`.

## BENCH-20261111-02 — Hot-path profile after BENCH-20261111-01, diagnostic only (Vidocq/noether#45)

- **Date**: 2026-11-11
- **Commit**: `45880aa` (`bench/hibernate-5`), the code of BENCH-20261111-01; no production change.
- **JVM / hardware / OS**: Temurin 25.0.3, Apple M5 Max / 18 cores / 128 GB RAM, macOS 27.2 arm64.
- **Exact command**, from `noether-jpa-bench` after `./mvnw -ntp install -DskipTests` and a `noether`
  profile build of the harness (`$CP` = worktree `target/classes` of core and dialects, then the harness
  classes and `target/noether/test-classpath.txt`):
  ```sh
  java -Dbench.provider=io.vidocq.noether.jpa.core.NoetherPersistenceProvider -Dbench.bootstrap=configuration \
    -cp "$CP" org.openjdk.jmh.Main 'io.vidocq.noether.jpa.bench.JpaProviderBenchmark.(.*)' \
    -prof "jfr:dir=$OUT/jfr;stackDepth=128;configName=$OUT/alloc.jfc" -jvmArgsAppend -XX:-UseTLAB \
    -wi 2 -i 2 -w 2s -r 5s -f 1 -t 1 -foe true -prof gc -rf json -rff "$OUT/jmh.json"
  ```
  `alloc.jfc` is the JDK `profile.jfc` with `jdk.ObjectAllocationSample` throttled at 5000/s.
- **Method note**: with TLABs, `jdk.ObjectAllocationSample` is taken where a TLAB is refilled. Each
  operation allocates the same sequence, so the samples alias on a few sites (one run put 33% of the
  weight on a 24-byte array, another 43% on the JPQL lexer). `-XX:-UseTLAB` makes every allocation a
  candidate and the throttle picks a subset: the shares below are **counts of allocations**, not bytes,
  about 48,000 samples per workload. Throughput under this flag (0.5k to 4k ops/s) means nothing.
- **Raw instrumented results** (allocation only is meaningful):
  ```text
  createUpdateDelete 34753.099 B/op
  findById           11527.141 B/op
  jpqlQuery          77330.830 B/op
  update             21673.948 B/op
  ```
- **Hot spots, share of allocation count (inclusive frames)**:
  - `findById`: `PooledConnection.prepareStatement` 27.1% (H2 parses the statement at every call),
    `ResourceLocalTransaction.end` 13.2% (the release of the connection), `EntityManagerImpl.flush` 20.3%,
    `createEntityManager` 7.1%.
  - `createUpdateDelete`: `EntityManagerImpl.flush` 83.2% (three flushes), of which
    `PooledConnection.prepareStatement` 27.2%; `ResourceLocalTransaction.end` 4.4%; six
    `PersistenceContext.entries()` copies and six identity hash maps per flush.
  - `jpqlQuery`: about 45 allocations per result row (72.3% under `JpqlQuery.execute`, 35.3% under
    `hydrateCollections`, 29.1% under `EntityLoader.loadRows`); `Translator.translate` 10.1% and
    `Parser.parse` 4.8% (the string is parsed once and translated twice per query);
    `PooledConnection.prepareStatement` 9.7%; commit flush 8.0%, four reads of each entity's state.
  - `update`: same shape as `findById` plus one `FlushEngine.update`.
  - CPU (`jdk.ExecutionSample`, TLAB run, 804 samples on `jpqlQuery`): 17.2% in `executeQuery`, 9.8% in
    `prepareStatement`, 7.3% leaf in `Long` boxing of `ManagedAccess.read`, 10.2% in `createQuery`.
- **H2 probe** (plain JDBC, same table, 10,000 measured iterations after 20,000 of warm-up,
  `ThreadMXBean.getThreadAllocatedBytes`), `java -cp h2-2.3.232.jar H2Probe.java`:
  ```text
  autocommit select by id:                                              1680 B/op
  setAutoCommit(false), select, commit, rollback, setAutoCommit(true):  7325 B/op
  setAutoCommit(false), select, commit, setAutoCommit(true):            2504 B/op
  ```
  The second line is what a Noether transaction did through `mansart-pool`: the pool resets a
  connection returned out of auto-commit mode with `rollback()` then `setAutoCommit(true)`. On H2 that
  rollback costs about 4.8 kB and empties the session's query cache, hence the statement parsing above.
- **`JdbcExecution.call`**: no sample of any workload contains it. The four workloads run inside a
  resource-local transaction, which goes through `ResourceLocalTransaction.onConnection`. It is on the
  path of non-transactional reads, `runWithConnection` / `callWithConnection`, schema generation and
  the JTA integration of `noether-jpa-cdi`. Probe from a platform thread, 200,000 calls of a constant
  task, third round (`java -cp "$CP" CallProbe.java`):
  ```text
  JdbcExecution.call from a platform thread:         6924 ns/op, 760 B/op on the caller
  one virtual thread and a FutureTask, no executor:  6763 ns/op, 431 B/op on the caller
  ```
  The cost is the hand-off to another thread and back, not the executor: about 7 µs, five times a whole
  `findById` operation of this harness. Not changed here (see BENCH-20261111-04, remaining gaps).
- **Comparison with previous run**: allocation within 2% of BENCH-20261111-01 on every workload; this
  entry is a diagnostic, not a ranking.

## BENCH-20261111-03 — Hot-path optimizations, allocation after each commit (Vidocq/noether#45)

- **Date**: 2026-11-11
- **Commits** (`perf/hot-paths`, on top of `45880aa`):
  `61855a0` the transaction connection goes back in auto-commit mode;
  `3eff983` entity state read and copied once per load and flush;
  `e35457e` parse, translation and SQL of a query string reused;
  `b10c563` identity sets of a flush sized to what it writes, and
  `bf4a3cb` rows of an entity without relationships loaded in one pass (measured together).
- **JVM / hardware / OS**: as BENCH-20261111-02. Another session ran Maven builds on the machine during
  some of these runs: throughput is indicative, normalized allocation is the signal.
- **Exact command**: the command of BENCH-20261111-02 without the JFR profiler and `-XX:-UseTLAB`, with
  `-wi 3 -i 3 -w 1s -r 2s -f 1 -t 1 -foe true -prof gc`, the worktree classes compiled by
  `./mvnw -ntp -q -o compile -pl <core and dialects>` ahead of the installed jars.
- **Results**, ops/s +/- JMH 99.9% error (3 iterations); B/op:
  ```text
  61855a0
  createUpdateDelete 258868.817 +/-  26465.547 ops/s; 26400.031 B/op
  findById           748928.028 +/- 119871.688 ops/s;  7686.755 B/op
  jpqlQuery           53054.598 +/-   6223.148 ops/s; 64068.314 B/op
  update             364988.164 +/- 120745.975 ops/s; 14535.373 B/op
  3eff983
  createUpdateDelete 261735.019 +/-   7675.518 ops/s; 25864.031 B/op
  findById           771609.622 +/-  71632.461 ops/s;  7150.380 B/op
  jpqlQuery           58623.635 +/-   3562.430 ops/s; 54488.069 B/op
  update             378329.348 +/-  57776.554 ops/s; 14295.075 B/op
  e35457e
  createUpdateDelete 269670.415 +/-   3704.326 ops/s; 26024.030 B/op
  findById           751958.618 +/-  83001.331 ops/s;  7238.380 B/op
  jpqlQuery           71514.751 +/-   3211.777 ops/s; 41001.403 B/op
  update             394319.216 +/-  33632.935 ops/s; 14255.216 B/op
  b10c563 + bf4a3cb
  createUpdateDelete 276686.735 +/-  16573.615 ops/s; 22088.029 B/op
  findById           830705.035 +/-  79808.698 ops/s;  5486.380 B/op
  jpqlQuery           80623.648 +/-   4126.106 ops/s; 33488.047 B/op
  update             404054.356 +/-  54291.428 ops/s; 12463.329 B/op
  ```
- **Delta versus BENCH-20261111-01**, allocation, order
  `createUpdateDelete / findById / jpqlQuery / update`:
  `61855a0` **-23.7% / -32.5% / -18.7% / -32.0%**;
  `3eff983` **-25.2% / -37.2% / -30.8% / -33.1%**;
  `e35457e` **-24.8% / -36.5% / -47.9% / -33.3%**;
  `b10c563` + `bf4a3cb` **-36.2% / -51.8% / -57.5% / -41.6%**.
  The +0.6% and +1.2% of `e35457e` on `createUpdateDelete` and `findById`, which it does not touch, is
  the fork-to-fork variation of these short runs.
- **Interpretation**: the first commit is the largest step on every workload and is not a Noether
  hot spot in the usual sense: one JDBC call per transaction (the pool's reset rollback) and the H2
  statement cache it emptied. The others remove provider work: repeated reads of entity state and
  defensive snapshot copies, a parse and two translations per query, default-sized identity maps per
  flush, two of three passes over the rows of an entity without relationships. Throughput intervals of
  these 3-iteration runs are wide and overlap from one commit to the next; see BENCH-20261111-04 for the
  campaign. No column, row, dirty check, version check, callback, flush ordering or cache semantics is
  removed, and no provider setting is added; `./mvnw -ntp install` passes (core: 897 tests).

## BENCH-20261111-04 — Hot-path optimizations, final campaign against Hibernate ORM 5.6 (Vidocq/noether#45)

- **Date**: 2026-11-11
- **Commit**: `bf4a3cb` (`perf/hot-paths`), installed by `./mvnw -ntp install` right before the run; the
  SHA-256 of the installed `noether-jpa-core` jar was the worktree's before and after the Noether run.
- **JVM**: Temurin 25.0.3 (2026-04-21 LTS), 64-Bit Server VM, default flags.
- **Hardware**: Apple M5 Max / 18 cores / 128 GB RAM.
- **OS**: macOS 27.2, arm64.
- **Providers / configuration**: as BENCH-20261111-01 (Noether 0.4.0-SNAPSHOT, Hibernate ORM 5.6.15.Final,
  H2 2.3.232, unchanged harness and preflight checks).
- **Exact command**, from the Noether root:
  ```sh
  ./mvnw -ntp install
  cd noether-persistence/noether-jpa-bench
  export BENCH_RUN_ID=20261111-hot-paths
  ./run-bench.sh noether && ./run-bench.sh hibernate5
  ```
  JMH settings unchanged: `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 -foe true -prof gc -rf json`.
- **Results**, JMH 99.9% error, normalized allocation:
  ```text
  noether
  createUpdateDelete 272638.018 +/- 12879.227 ops/s; 22000.023 +/- 0.053 B/op
  findById           822140.937 +/- 21201.665 ops/s;  5534.379 +/- 0.002 B/op
  jpqlQuery           79495.962 +/-   926.126 ops/s; 34976.047 +/- 0.026 B/op
  update             406857.003 +/-  7646.871 ops/s; 12423.826 +/- 0.025 B/op
  hibernate5
  createUpdateDelete 239289.043 +/-  1796.078 ops/s; 17864.038 +/- 0.150 B/op
  findById           494709.122 +/-  6012.950 ops/s;  7541.828 +/- 0.075 B/op
  jpqlQuery           67585.005 +/-   221.418 ops/s; 26506.322 +/- 0.178 B/op
  update             276198.405 +/- 29428.916 ops/s; 13790.913 +/- 0.177 B/op
  ```
- **Delta versus BENCH-20261111-01**, order `createUpdateDelete / findById / jpqlQuery / update`:
  - Noether throughput **+66.0% / +76.4% / +90.0% / +86.4%**; allocation
    **-36.4% / -51.4% / -55.6% / -41.8%**. The 99.9% intervals of the two campaigns do not overlap on
    any workload.
  - Hibernate 5.6 throughput **-4.6% / -7.9% / +0.3% / -3.1%**; allocation within 0.9%. Its code is the
    same: this is the variation between two sessions of this machine, and the scale against which the
    Noether deltas are to be read.
- **Noether versus Hibernate 5.6, this session**: throughput **+13.9% / +66.2% / +17.6% / +47.3%**, the
  99.9% intervals do not overlap on any workload; allocation **1.23x / 0.73x / 1.32x / 0.90x** Hibernate's.
  In BENCH-20261111-01 Noether was -34.5% / -13.2% / -37.9% / -23.4%.
- **Remaining gaps**: Noether still allocates more than Hibernate 5.6 on `jpqlQuery` (+8.5 kB/op) and
  `createUpdateDelete` (+4.1 kB/op).
  - `jpqlQuery`: about 29 small objects per row remain (the row's key, values and nulls arrays, two
    records, three identity keys, the entries of four maps, two state arrays at flush and their boxed
    `long`s). Fewer of them means one row representation from the result set to the persistence
    context instead of three (`Fetched`, `FetchedEntity`, `Row`), and one read of the state per flush
    shared by the entity manager and the flush engine.
  - `createUpdateDelete`: three flushes per operation, each with six copies of the context's entries,
    its lists and iterators, and the `Long` boxes of `ManagedAccess.read`; H2 itself is 32% of the
    allocation count.
  - Every transaction still pays one H2 commit for `setAutoCommit(true)` (about 0.57 kB): the pool only
    skips its reset rollback for a connection in auto-commit mode. A pool that knew the connection was
    committed could skip both; that is a `mansart-pool` change.
  - `JdbcExecution.call` (BENCH-20261111-02) is not measured by this harness and costs about 7 µs per
    call from a platform thread.
- **Limitations**: one fork per provider, single thread, in-memory H2, a new entity manager per
  operation, the entity has no relationship (the one-pass row load of `bf4a3cb` applies to exactly that
  case). Not a production latency or scalability claim. Hibernate 7.4 and EclipseLink were not rerun.
  Raw evidence: `noether-jpa-bench/target/bench-reports/20261111-hot-paths/{noether,hibernate5}/`.

## BENCH-20261111-05 — Before and after the hot-path optimizations, against Hibernate ORM 5.6, on PostgreSQL 17 (Vidocq/noether#45)

- **Date**: 2026-11-11
- **Code**: "after" = `00074aa` (`bench/postgresql`: `c74687f` of #52 plus the configurable-database harness);
  "before" = `45880aa` (#46, without the optimizations) with the harness of `00074aa` applied on top,
  uncommitted. Each version was installed into its own scratch local repository
  (`-Dmaven.repo.local=<scratch> -Dmaven.repo.local.tail=~/.m2/repository`), handed to `run-bench.sh`
  through `MAVEN_ARGS`; `~/.m2` was not written.
- **JVM**: Temurin 25.0.3 (2026-04-21 LTS), 64-Bit Server VM, default flags.
- **Hardware**: Apple M5 Max / 18 cores / 128 GB RAM.
- **OS**: macOS 27.2, arm64.
- **Database**: PostgreSQL 17.11 (`postgres:17-alpine`, default settings: `shared_buffers` 128 MB, `fsync`
  and `synchronous_commit` on) in a disposable OrbStack container (Docker 29.8.2) on `127.0.0.1:55432`,
  sharing the machine with the JVM; PostgreSQL JDBC driver 42.7.4. Every JMH trial drops and recreates
  `bench_record`.
- **Providers**: as BENCH-20261111-01 (Noether 0.4.0-SNAPSHOT, Hibernate ORM 5.6.15.Final). Noether runs
  with `noether-jpa-dialect-postgresql`; Hibernate 5.6 detects its dialect.
- **Exact command**, from `noether-persistence/noether-jpa-bench`:
  ```sh
  docker run -d --rm --name noether-bench-pg -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench \
    -p 127.0.0.1:55432:5432 postgres:17-alpine
  export BENCH_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/bench BENCH_JDBC_DRIVER=org.postgresql.Driver \
    BENCH_JDBC_USER=postgres BENCH_JDBC_PASSWORD=bench
  export MAVEN_ARGS="-Dmaven.repo.local=<scratch repository of the version> -Dmaven.repo.local.tail=$HOME/.m2/repository"
  BENCH_RUN_ID=<run> ./run-bench.sh noether   # or hibernate5
  ```
  JMH settings unchanged: `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 -foe true -prof gc -rf json`.
- **Order**: three rounds, ten runs. Round 1: after, Hibernate 5.6, before, Hibernate 5.6. Round 2: before,
  after, Hibernate 5.6. Round 3: Hibernate 5.6, after, before. Hibernate 5.6 is the same code in both
  trees, so its four runs are one configuration.
- **Results**, mean of the 5 measured iterations of each run, ops/s, rounds 1 / 2 / 3:
  ```text
                      Noether before          Noether after           Hibernate 5.6
  findById            5,715 / 6,908 / 7,650   7,433 / 7,548 / 7,601   5,579 + 6,077 / 7,500 / 7,462
  jpqlQuery           6,243 / 5,595 / 5,291   6,435 / 6,451 / 6,432   6,514 + 4,801 / 6,324 / 6,349
  update              1,293 / 1,002 / 1,285   1,339 /   884 / 1,343     872 +   882 / 1,186 / 1,151
  createUpdateDelete  1,047 /   998 / 1,241   1,125 / 1,053 / 1,222   1,131 +   842 / 1,128 / 1,172
  ```
  All measured iterations pooled (15 per Noether version, 20 for Hibernate 5.6), median ± standard deviation:
  ```text
                      Noether before   Noether after   Hibernate 5.6
  findById            6,882 ± 968      7,528 ± 109     7,273 ± 1,060
  jpqlQuery           5,894 ± 542      6,440 ±  74     6,356 ±   748
  update              1,254 ± 175      1,325 ± 302       956 ±   209
  createUpdateDelete  1,091 ± 122      1,147 ± 107     1,132 ±   145
  ```
  Normalized allocation, B/op (the same in every run within 3%):
  ```text
                      Noether before   Noether after   Hibernate 5.6
  findById             6,370            4,202           4,682-4,738
  jpqlQuery           71,617           41,561          30,867-31,074
  update               8,798            6,992           6,438-6,629
  createUpdateDelete  18,132           13,499           8,583-8,630
  ```
- **Comparison**: medians of the pooled iterations, order `findById / jpqlQuery / update /
  createUpdateDelete`: after versus before **+9.4% / +9.3% / +5.7% / +5.2%**; after versus Hibernate 5.6
  **+3.5% / +1.3% / +38.6% / +1.4%**; before versus Hibernate 5.6 -5.4% / -7.3% / +31.1% / -3.6%.
  Allocation after versus before -34.0% / -42.0% / -20.5% / -25.6%.
- **Reading**:
  - The database dominates: 130 µs to 1.1 ms per operation here, against 1.2 to 13 µs on in-memory H2
    (BENCH-20261111-04). The CPU and allocation differences between providers become a few percent.
  - Only one difference holds in every run: on `jpqlQuery` every "after" run (6,432 to 6,451) is above every
    "before" run (5,291 to 6,243). Everywhere else the runs of the configurations overlap; Hibernate 5.6
    alone spans 5,579 to 7,500 on `findById` across its four runs.
  - `update` is bimodal for every configuration (about 880 or about 1,340 ops/s per run) and the mode
    follows when the run happened, not the provider (round 2: Noether after 884, Hibernate 5.6 1,186). The
    +38.6% median is not a ranking.
  - Noether after #52 is the steadiest configuration on reads (standard deviation 109 on `findById` and 74
    on `jpqlQuery`, against 968 and 542 before and 1,060 and 748 for Hibernate 5.6). Not explained by this
    entry.
  - Conclusion: on this PostgreSQL set-up, Noether before or after #52 and Hibernate 5.6 are at parity
    within the run-to-run variation. The +14% to +66% of BENCH-20261111-04 measures the provider's own path
    on H2 and does not carry over to a database round trip. Noether still allocates more than
    Hibernate 5.6 except on `findById`.
- **Noise**: shared machine. Sampled once during round 1: load averages 6.85 / 5.45 / 4.69 (1 / 5 / 15
  min), `mediaanalysisd` at about 68% CPU, the container runtime at about 124%.
- **Limitations**: one fork per run, single thread, one entity without relationships, the database on the
  same machine behind a virtual machine (no physical network). Hibernate 7.4 and EclipseLink not run. Not a
  production latency or scalability claim.
- **Raw evidence**, in the `bench/postgresql` worktree, `noether-jpa-bench/target/bench-reports/`:
  `20261111-pg-after/`, `20261111-pg-r2/`, `20261111-pg-r3/` (the "after" tree, and the Hibernate 5.6
  runs started from it); `20261111-pg-before/`, `20261111-pg-r2-before-code/`,
  `20261111-pg-r3-before-code/` (the "before" tree, and the Hibernate 5.6 runs started from it). Each
  provider directory has `database.txt` naming the PostgreSQL URL.
