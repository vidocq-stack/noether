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
