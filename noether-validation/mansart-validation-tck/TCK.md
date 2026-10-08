# TCK status — Mansart Validation

Official suite: **Jakarta Validation 3.1.1** TCK (`jakarta.validation:validation-tck-tests:3.1.1`,
Maven Central), container-less run (TestNG + local Arquillian container, integration tests excluded).
Command: `./run-official-tck-validation-3.1.sh` (from `mansart-validation/mansart-validation-tck/`).

## Progress

| Milestone | Date | Tests run | Pass | Fail |
|---|---|---:|---:|---:|
| V0 — provider skeleton | 2026-10-08 | 981 | 12 | 969 |
| V1 — bootstrap, `validation.xml`, factory lifecycle | 2026-10-08 | 981 | 42 | 939 |

### V1 — what the 939 failures are

| Count | Reason |
|---:|---|
| 618 | `UnsupportedOperationException: … does not validate yet (milestone V2)` — the validation engine. |
| 283 | `UnsupportedOperationException: … (milestone V4)` — `ExecutableValidator` (method and constructor validation). |
| 38 | Other: tests that expect an exception only the constraint-mapping parser or the value-extractor checks would raise (XML mapping files, duplicate declarations, `ValueExtractor` rules), or an assertion on them. All belong to V2/V3; not traced one by one. |

Every failure in the bootstrap areas (`bootstrap`, `validatorfactory`, `validatorcontext`,
`xmlconfiguration`) that is not in the third row is one of the 901 above: it builds a factory fine
and then calls `validate(…)`. The 30 tests added by V1 are the bootstrap ones: `BootstrapConfiguration*`
(executable types, mappings, properties), `validation.xml` versions 1.0 to 3.0 (the TCK found a
wrong namespace/version table in the first draft of the parser), components declared in
`validation.xml` (clock provider, message interpolator, traversable resolver, no-arg constructor
failures), `default-provider` selection and the default components.

## Baseline — V0 (history) (provider skeleton, no validation logic)

| Date | Java | Tests run | Pass | Fail | Error | Skip |
|---|---|---:|---:|---:|---:|---:|
| 2026-10-08 | Temurin 25.0.3 | 981 | 12 | 969 | 0 | 0 |

Expected and correct at V0: the provider is discovered (`META-INF/services`) but every bootstrap
method throws `UnsupportedOperationException("… milestone V1")`.

### Why the failures fail

| Count | Reason |
|---:|---|
| 927 | The test fails directly on `UnsupportedOperationException` from `MansartValidationProvider`. |
| 33 | `ValidationException: Unable to instantiate Configuration.` — the Jakarta API wraps the same `UnsupportedOperationException` (the cause is the V1 message). |
| 8 | An `Expected exception of type … but got UnsupportedOperationException` (3 × `ValueExtractorDeclarationException`, 3 × `ConstraintDeclarationException`, 2 × `ValueExtractorDefinitionException`). |
| 1 | `ConstraintValidatorFactorySpecifiedInValidationXmlTest.testConstraintValidatorFactorySpecifiedInValidationXmlCanBeOverridden`: assertion « The factory should have been called ». Same family (`validation.xml`), consistent with the missing provider; not traced individually. |

No failure comes from the setup or the wiring: all 981 tests are in `org.hibernate.beanvalidation.tck.*`
and the 969 failures have the V1 provider message as their cause, except the last row.

### The 12 tests that pass

They exercise bootstrap behaviours that do not need a working provider (no provider found, custom
resolvers, provider class structure). They are not progress: they must stay green.

- `ConfigurationTest.testProviderUnderTestDefinesSubInterfaceOfConfiguration`
- `ValidationProviderTest`: `testByDefaultProviderUsesTheFirstProviderReturnedByValidationProviderResolver`,
  `testFirstMatchingValidationProviderResolverIsReturned`, `testValidationExceptionIsThrownInCaseValidatorFactoryCreationFails`,
  `testValidationProviderContainsNoArgConstructor`
- `BootstrapNonAvailableValidationProviderTest`: `testConfiguredValidationProviderIsNotLoadable`,
  `testUnknownProviderConfiguredInValidationXml`
- `CustomPropertyPathTest.testAddParameterNodeForFieldLevelConstraintCausesException`
- `ValidationTest`: `testCustomValidationProviderResolution`, `testSpecificValidationProvider`,
  `testVerifyMethodsOfValidationObjects`
- `ValueExtractorWithNoPublicNoArgConstructorInValidationXmlTest.valueExtractorWithNoPublicNoArgConstructorInValidationXmlThrowsException`

## Not covered by this runner yet

- The TCK's **integration tests** (`-DexcludeIntegrationTests=true`: CDI, container). They belong to
  the in-reactor runner of the Vidocq runtime (`vidocq-runtime-tck-validation`, `tck` profile).
- The **signature test**.
- Official exclusions: none identified yet for 3.1.1 — to be checked when the counter starts moving.
