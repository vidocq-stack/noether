# mansart-validation-tck

Runner for the **official Jakarta Validation 3.1 TCK** against the Mansart provider
(`mansart-validation-core`).

> **OUT OF THE REACTOR** — standalone `modelVersion 4.0.0` POM, no `<parent>`, never listed in a
> `<modules>` section and never built through `mvn -pl`. Same rule as `mansart-data-tck` and
> `mansart-transactions-tck`. The assembled-runtime counterpart (`vidocq-runtime-tck-validation`,
> `tck` profile) lives in the `vidocq` repository.

## Run

```bash
./run-official-tck-validation-3.1.sh            # whole suite
./run-official-tck-validation-3.1.sh -Dtest=…   # extra arguments go to Maven
```

The script installs `mansart-validation-core` in the local Maven repository, then runs the suite.
Output: `target/tck-validation-output.log`, `target/tck-report-validation.txt`,
`target/surefire-reports/index.html`. Scores: [`TCK.md`](TCK.md).

Nothing to install by hand: the TCK is on Maven Central. `TCK_VER=… ./run-…` selects another
published version.

## How it works

- No Java in this module. The tests come from `jakarta.validation:validation-tck-tests`; the TestNG
  suite file (classifier `suite`) is downloaded by `maven-dependency-plugin`.
- Container-less: TestNG + the `local` Arquillian container of
  `validation-standalone-container-adapter` (config in `src/test/resources/arquillian.xml`),
  integration tests excluded (`-DexcludeIntegrationTests=true`).
- The provider under test is selected with `-Dvalidation.provider=<class>`
  (`io.vidocq.mansart.validation.core.MansartValidationProvider`) and found through
  `META-INF/services` — this run is on the class path, production is on the module path.
- Only API jars are added for the test classes; no CDI, EJB, EL or Validation implementation.

## Pitfalls

- **Never put another Jakarta Validation provider on the classpath** (no Hibernate Validator, even
  "just for the harness"): it would measure its conformance, not ours.
- **Never add a class to `org.hibernate.beanvalidation.tck`** (or any name the suite file selects):
  a green test of ours would pass for a TCK test. The counter must come from the official packages.
- The module is `jar`-packaged on purpose: with `packaging pom` Maven has no test phase and the
  run silently reports `BUILD SUCCESS` with no test executed.
- The Maven build succeeds even when the TCK is red (`testFailureIgnore`); read `TCK.md`.
