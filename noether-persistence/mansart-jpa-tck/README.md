# mansart-jpa-tck

Runner for the **official Jakarta Persistence 3.2 TCK** (3.2.1) against the Mansart provider, on **PostgreSQL**.

> **OUT OF THE REACTOR**: standalone `modelVersion 4.0.0` POM, no `<parent>`, never listed in a `<modules>`
> section, never built through `mvn -pl`. Same rule as `mansart-data-tck` and `mansart-transactions-tck`.

## Run

```bash
./run-official-tck-persistence-3.2.sh                    # whole suite (about 20 s without a provider)
./run-official-tck-persistence-3.2.sh --area core.lock   # one package under ee.jakarta.tck.persistence
./run-official-tck-persistence-3.2.sh --external         # existing database: JDBC_URL, JDBC_USER, JDBC_PASSWORD
TCK_VALIDATION=on ./run-official-tck-persistence-3.2.sh  # Bean Validation on the class path of execution 1
../check-validation-neutrality.sh                        # the suite with and without Bean Validation, compared
```

Needs **Docker** (a throw-away `postgres:17-alpine` container, removed at the end; `KEEP_DB=1` keeps it), or
`psql` and an existing PostgreSQL with `--external`. Output: `target/tck-report-persistence.txt`,
`target/tck-persistence-output.log`, `target/failsafe-reports/execution-{1,2}/`. Scores: [`../TCK.md`](../TCK.md).

The script exits 0 when the TCK ran and was reported, whatever the score; non-zero when it could not run.

## Provider-owned query identifier regression

`DelimitedProcedureTest` is not an official TCK test and is not selected by either official failsafe execution.
It reuses this runner's existing PostgreSQL driver and the core's `orm/delimited.xml` fixture; no dependencies
or official sources/DDL are added or changed. Against an isolated PostgreSQL database, run from this directory:

```bash
../../mvnw -ntp compiler:testCompile org.apache.maven.plugins:maven-surefire-plugin:3.5.5:test \
  -Dmaven.compiler.release=25 \
  -Dmaven.test.additionalClasspath=../mansart-jpa-core/src/test/resources \
  -Dtest=DelimitedProcedureTest -Dp10.jdbc.url=jdbc:postgresql://localhost:5432/p10 \
  -Dp10.jdbc.user=p10 -Dp10.jdbc.password=p10
```

The test creates/removes its own `P10.MixedSchema` and `P10MixedSchema` schemas. Do not run concurrent copies
against the same database. Without `p10.jdbc.url` it is skipped. Explicit compiler/surefire goals are needed
because this standalone runner has `pom` packaging; this command never invokes the official failsafe/TCK goals.

## How it works

1. **`install-tck.sh`** downloads the bundle from eclipse.org, checks its **SHA-256** against the one published
   next to it, installs `persistence-tck-spec-tests`, `persistence-tck-common`, `dbprocedures` (with their POMs and
   sources) and the parent POM into the local Maven repository, and unpacks the bundle into
   `../.tck-cache/persistence-tck-3.2.1/` (the SQL scripts exist only there). Idempotent; `--force`, `--verify`.
2. **The database**: the script starts PostgreSQL, applies the official `postgresql.ddl.persistence.sql` and
   `postgresql.ddl.persistence.sprocs.sql` with `psql` inside the container (the initial `DROP` errors are expected,
   see `target/ddl.log`), then checks that every table of the DDL exists.
3. **Two failsafe executions**, as in the reference runner of the bundle:
   - `persistence-tests-1`: the whole suite (`**/*Client*`, `**/*JPASigTest*`), minus
     `se.entityManagerFactory.Client2#createEntityManagerFactoryNoBeanValidatorTest`;
   - `persistence-tests-2`: that test alone, **never** with Bean Validation on the class path.
   Each writes its reports in its own directory (`Client2` runs in both and would be overwritten otherwise).
4. **No persistence.xml here.** In standalone mode the TCK builds its own deployment jar and `persistence.xml`
   from the template in `persistence-tck-common` (units `JPATCK` and `JPATCK2`). The provider is only named, through
   `-Djakarta.persistence.provider` (`tck.provider`, `io.vidocq.mansart.jpa.core.MansartPersistenceProvider`).
5. **System properties**: every one the TCK reads is set. A missing one is a `NullPointerException` in the TCK setup
   (it stores them in a `java.util.Properties`), i.e. a failure for the wrong reason.

## Pitfalls

- **Never put another persistence provider on the class path.** The `persistence-tck-spec-tests` POM declares
  Hibernate ORM: every transitive dependency of the TCK artifacts is excluded and what the tests need is listed
  explicitly (`dependency:tree` must show neither Hibernate nor EclipseLink).
- **Never add a class named `*Client*`**: it would match the selection and pass for a TCK test. The report counts
  only `ee.jakarta.tck.*` classes and warns about any other.
- The `pom` packaging is fine here: the failsafe executions are bound explicitly.
- `jpa.provider.implementation.specific.properties` must never be empty (same `Properties` reason); it is
  `io.vidocq.mansart.tck=true` by default.
- Before milestone P1 three kinds of tests pass without any provider; they are not progress (see `../TCK.md`).
