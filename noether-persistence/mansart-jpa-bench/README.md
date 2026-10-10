# mansart-jpa-bench

Standalone JMH harness (outside the Mansart reactor) comparing Mansart, Hibernate ORM and
EclipseLink on identical H2 workloads. Methodology and limitations:
`docs/en/modules/ROOT/pages/performance.adoc`. Recorded results: `../BENCH.md`.

## Run

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.4-tem PATH=$JAVA_HOME/bin:$PATH
cd mansart/main
./mvnw -ntp -pl mansart-persistence/mansart-jpa-processor-module-it -am install -DskipTests
cd mansart-persistence/mansart-jpa-bench
export BENCH_RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)   # optional; groups the three providers
./run-bench.sh mansart
./run-bench.sh hibernate
./run-bench.sh eclipselink
```

JMH settings: `-wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 -foe true -prof gc -rf json`. With `-foe true` a
failing setup, preflight or benchmark exits non-zero and the runner prints the full JMH log.

## Output locations

- Build per provider: `target/<provider>/` (POM property `bench.build.directory`); the runner
  cleans only this directory.
- Raw evidence, never cleaned or overwritten by the runner:
  `target/bench-reports/<run-id>/<provider>/` containing `jmh.log`, `jmh.json`, `maven.log`,
  `java-version.txt`, `test-dependencies.txt` and `test-classpath.txt`.

## Preflight

Before timing, setup checks fixture counts, insert/update/delete persistence, and runs the timed
`update` method twice on row 1, verifying through JDBC that each call changed the label and
incremented the `@Version` column, and that the returned value is the persisted version.

Every published figure must have a `../BENCH.md` entry (skill `/log-bench`).
