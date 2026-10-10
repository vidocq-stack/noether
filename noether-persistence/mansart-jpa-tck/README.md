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
TCK_FIXTURES=official ./run-official-tck-persistence-3.2.sh  # untouched official spec-tests jar (no TCK-BUG-001)
../check-validation-neutrality.sh                        # the suite with and without Bean Validation, compared
```

Needs **Docker** (a throw-away `postgres:17-alpine` container, removed at the end; `KEEP_DB=1` keeps it), or
`psql` and an existing PostgreSQL with `--external`. Output: `target/tck-report-persistence.txt`,
`target/tck-persistence-output.log`, `target/failsafe-reports/execution-{1,2}/`. Scores: [`../TCK.md`](../TCK.md).

The script exits 0 when the TCK ran and was reported, whatever the score; non-zero when it could not run, or
(exit 3) when a failsafe report did not run on exactly the prepared spec-tests jar.

## Local fixture patch TCK-BUG-001 (enabled by default)

**What.** By default the runner executes a *derived copy* of `persistence-tck-spec-tests-3.2.1.jar` in which
two test resources lose one block, the local backport of upstream fix
[jakartaee/persistence#1175](https://github.com/jakartaee/persistence/issues/1175), commit
[`1fea05e58151f10954206a15d70b18008043d3d9`](https://github.com/jakartaee/persistence/commit/1fea05e58151f10954206a15d70b18008043d3d9)
(merged on the 4.0 line; no 3.2 backport published). Removed from
`ee/jakarta/tck/persistence/core/annotations/nativequery/orm.xml` and
`ee/jakarta/tck/persistence/core/entitytest/apitests/orm.xml`, byte for byte as upstream:

```xml
    <persistence-unit-metadata>
        <persistence-unit-defaults>
            <delimited-identifiers/>
        </persistence-unit-defaults>
    </persistence-unit-metadata>
```

**Why.** The unit-wide delimited default makes the declared upper-case names case-sensitive, whereas the official
PostgreSQL DDL creates them unquoted (folded to lower case) and the tests' native SQL is unquoted too. A correct
provider fails these 25 tests against the 3.2.1 fixtures; see TCK-BUG-001 in
[`../../PERISTENCE_TCK_PROPOSALS.md`](../../PERISTENCE_TCK_PROPOSALS.md). The provider is **not** changed: it keeps
honouring `<delimited-identifiers/>` (its own regression tests prove it); only the two fixtures are adapted. The
maintainer decided this adoption; Mansart does not seek a certification, and a patched score is never presented as
an official result.

**How.** `tools/tck_fixture_patch.py` (Python standard library only), called by the script:

- reads the installed official jar, never writes it: its SHA-256 must be the pinned
  `a6ad07d4442aace8630348f7aae990d31d79a31692e14ed463f482c58b364024` (the jar of the SHA-256-verified bundle);
- writes `target/tck-fixtures/persistence-tck-spec-tests-3.2.1-tck-bug-001.jar`, same entries in the same order,
  same entry metadata and bytes, except the two resources, whose original and patched SHA-256 are pinned too;
- validates each resource: exactly one exact upstream block, a direct child of the 3.2 `entity-mappings` root,
  holding nothing but `<delimited-identifiers/>`; namespace, `version="3.2"`, schema location and all other mappings
  unchanged. Unknown checksums, an absent block (already patched input), duplicates, reformatting, richer unit
  defaults, DOCTYPE, signed jars, duplicate or missing entries are errors (exit 2), never a silent no-op;
- is deterministic and idempotent (re-running produces the identical jar), writes atomically, and records
  `target/tck-fixtures/fixture-provenance.json` (upstream issue/commit, reason, checksums).

The POM takes the spec-tests jar from `${tck.spec.tests.jar}` (system scope, same GAV, no transitive
dependency, test-only out-of-reactor runner); the script passes the derived jar, or the untouched installed jar with
`TCK_FIXTURES=official`. The test classes, `dependenciesToScan` and the resources the TCK copies into its ShrinkWrap
deployment jars therefore all come from that single jar. The report states **PATCHED** or **UNMODIFIED**, the
provenance and checksums, and checks the `java.class.path` of every failsafe report: any other spec-tests jar on it
fails the run.

`../check-validation-neutrality.sh` calls this script, so both of its sides use the same (default: patched)
fixtures; export `TCK_FIXTURES=official` to compare on the untouched jar.

Tool tests (red first, synthetic jars plus the real pinned jar when the bundle is unpacked):

```bash
python3 -m unittest discover -s tools -v
```

Comparing two report directories by execution + class + test name:

```bash
python3 tools/tck_fixture_patch.py compare <base>/failsafe-reports target/failsafe-reports
```

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
   Then `tools/tck_fixture_patch.py prepare` selects the spec-tests jar (TCK-BUG-001 derived copy by default).
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
