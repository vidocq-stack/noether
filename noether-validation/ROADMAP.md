# Mansart Validation — Implementation Roadmap

> Jakarta Validation 3.1 (Bean Validation) implementation in the Vidocq style: zero third-party
> implementation libraries (Jakarta spec APIs only), Java 25, strict Java Modules, no runtime
> reflection on constrained types, no runtime bytecode library — APT and the Class-File API instead.
> Mansart-wide vision: [`../PLAN.md`](../PLAN.md) and [`../ROADMAP.md`](../ROADMAP.md).

## Why this brick exists

No Bean Validation implementation is available for the Vidocq runtime. `mansart-persistence`
needs one for §3.7 of the Jakarta Persistence spec and for the two `entityManagerFactory` validation
tests of its TCK (decision D6 in [`../mansart-persistence/ROADMAP.md`](../mansart-persistence/ROADMAP.md)).
The brick is useful on its own: it validates any CDI bean, REST resource or record, independently of
persistence.

## Guiding Principles

| Principle | Concrete application |
|---|---|
| TCK first | No implementation milestone starts before the official TCK runs and yields a counter (V0). Every later milestone is closed by a TCK delta. |
| Strict TDD | Red → Green → Refactor. A unit test citing the spec section precedes every production line. |
| Zero implementation library | Only `jakarta.validation-api` (+ CDI / inject APIs in the integration module). Expression Language is **not** brought in: message interpolation is hand-written (see D2). |
| No runtime reflection on constrained types | Constraint metadata and validators are generated at compile time (APT, Maven plugin for jars); runtime access goes through generated accessors. |
| No proxy / bytecode library | Method validation (parameters / return values) is woven by generated code, never by dynamic proxies. |
| Independent at runtime | No dependency on `mansart-jakarta-data`, `mansart-persistence`, `mansart-pool` or the dialect SPI. Only the Jakarta API. |
| Strict Java Modules | One `module-info.java` per module, provider published via `provides`, internals not exported. |
| Measured performance | JMH from V6; numbers only in `BENCH.md`. |

## Module Architecture (target)

```
mansart-validation-core        io.vidocq.mansart.validation.core
  requires transitive jakarta.validation
  provides jakarta.validation.spi.ValidationProvider with …MansartValidationProvider
  → bootstrap (validation.xml), ValidatorFactory, Validator, constraint model,
    validation engine (groups, group sequences, composition), message interpolation,
    built-in constraints, ConstraintViolation model, container element validation

mansart-validation-processor   io.vidocq.mansart.validation.processor   (APT, RELEASE_25)
  → per-type metadata + accessors generated for application sources, method validation
    entry points, validator lookup tables

mansart-validation-maven-plugin
  → same generation for constrained types located in dependency jars

mansart-validation-el          io.vidocq.mansart.validation.el      (OPTIONAL — D2, D5)
  → evaluator for `${…}` message expressions, discovered by ServiceLoader

mansart-validation-cdi         io.vidocq.mansart.validation.cdi
  requires jakarta.cdi
  → BuildCompatibleExtension: injectable Validator / ValidatorFactory,
    CDI-managed ConstraintValidator instances, method validation interception

mansart-validation-tck         (out of the Vidocq reactor — standalone Model 4.0.0 POM)
```

In-reactor counterpart: a `vidocq-runtime-tck-validation` runner in the `vidocq` repository
(`vidocq-runtime-integration-tests`, `tck` profile) certifies the assembled runtime.

## Testing Strategy

- **Unit tests (TDD)**: drive each component of `mansart-validation-core` with no container.
- **Integration tests** (`mansart-validation-tests`): module-path smoke tests (`*-module-it`),
  CDI scenarios, APT-generated metadata.
- **Official TCK, two runners**:
  - `mansart-validation-tck` — **out of the Vidocq reactor**, authoritative for the building
    block, launched through its `run-official-tck-validation-3.1.sh` script, never via `mvn -pl`.
  - `vidocq-runtime-tck-validation` — **inside the Vidocq reactor**, guarded by the `tck` profile
    (`./mvnw -Ptck -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-validation test`),
    certifies the assembled runtime.
  Both must reach 100%; a gap between the two is a bug in the runtime integration, not in the brick.
- **JMH** (`mansart-validation-bench`, V6).

## Milestones

Status legend: ⏳ not started · 🚧 in progress · ✅ delivered. Every milestone records its TCK
delta in `TCK.md` when it closes.

### V0 — The TCK instrument ⏳

**Goal**: runners that execute the official Jakarta Validation 3.1 TCK against *our* (still absent)
provider and print a counter. PASS = 0 is the expected, correct outcome.

- [ ] Locate the official TCK bundle and its artifacts (Maven Central vs. download bundle — to be
      re-checked at V0, as for the persistence TCK); document the install procedure in the
      runner README.
- [ ] `mansart-validation-tck/` — standalone Model 4.0.0 POM, **out of the reactor**, copied from
      `mansart-data-tck` / `mansart-transactions-tck` then adapted.
- [ ] Counter comes from the TCK's own packages (never from a class of the runner — same trap as
      the persistence TCK `Client` pitfall: verify that `Tests run` is not 0).
- [ ] `run-official-tck-validation-3.1.sh` (executable), per-area filter.
- [ ] In-reactor runner `vidocq-runtime-tck-validation` in `vidocq` (`tck` profile), same suite.
- [ ] `TCK.md` with the baseline: executed count, and every failure *for the right reason*
      (no validation provider), not on setup or wiring.

**Done when**: both runners print a counter for the official suite and the baseline is in `TCK.md`.

### V1 — Bootstrap, provider SPI, constraint model ⏳

Spec: ch. 5 (bootstrapping, `ValidationProvider`, `validation.xml`), ch. 2 (constraint definition).

- [ ] `mansart-validation-core` module; `MansartValidationProvider` published via `provides` and
      `META-INF/services` (classpath **and** module path).
- [ ] `Validation.buildDefaultValidatorFactory()`, `Configuration`, `validation.xml` parser (StAX),
      `ValidatorFactory` / `Validator` lifecycle (`close`, `unwrap`), `ConstraintValidatorFactory`,
      `MessageInterpolator`, `TraversableResolver`, `ParameterNameProvider`, `ClockProvider`
      defaults.
- [ ] Constraint annotation model: `@Constraint`, `groups`, `payload`, `message`, composed
      constraints, `@ReportAsSingleViolation`, repeatable constraints.

**TCK gate**: bootstrap and `validation.xml` areas.

### V2 — Built-in constraints and bean validation ⏳

Spec: ch. 4 (built-in constraints), ch. 6 (constraint declaration and validation).

- [ ] All built-in constraints of the API (`@NotNull`, `@Size`, `@Min`/`@Max`, `@Digits`,
      `@Pattern`, `@Email`, `@Past`/`@Future` families, `@Positive`/`@Negative` families,
      `@NotBlank`/`@NotEmpty`, `@AssertTrue`/`@AssertFalse`) with their `List` variants.
- [ ] `Validator.validate`, `validateProperty`, `validateValue`; field and getter constraints;
      class-level constraints; cascading with `@Valid`; inheritance of constraints (superclasses,
      interfaces); `ConstraintViolation` / `Path` model; `getConstraintsForClass` metadata API.
- [ ] Metadata generated by APT (`mansart-validation-processor`) and Maven plugin; runtime path
      for opaque archives documented (requires `opens` — decision D3).

**TCK gate**: built-in constraints, bean validation, metadata API areas.

### V3 — Groups, group sequences, composition, container elements ⏳

Spec: ch. 3 (groups), ch. 6.4–6.6 (group sequences, redefined default group, container elements).

- [ ] Groups, group inheritance, `Default`, `@GroupSequence` (class-level redefinition, interface
      sequences). `@GroupSequenceProvider` is Hibernate-specific, not part of the spec.
- [ ] Container element constraints (`List<@NotNull String>`, `Map`, `Optional`), type-use
      annotations, `ValueExtractor` SPI, cascading through containers.
- [ ] Constraint composition with `@OverridesAttribute` / `@ConstraintComposition`.

**TCK gate**: groups, group sequences, container-element, composition areas.

### V4 — Method and constructor validation ⏳

Spec: method and constructor validation chapter, `ExecutableValidator` (section numbers to be pinned at V0).

- [ ] `ExecutableValidator`: parameters, return values, cross-parameter constraints, constructor
      validation, `@Valid` on parameters and return values, Liskov-style rules on overriding.
- [ ] Generated entry points — no dynamic proxy, no runtime bytecode library; the interception
      for CDI beans lives in V5.

**TCK gate**: method validation areas.

### V5 — Message interpolation and CDI integration ⏳

Spec: ch. 5.3 (message interpolation), ch. 8 (CDI integration is spec-defined for the container).

- [ ] Hand-written message interpolation: `{param}` resolution, `ValidationMessages` bundles,
      `ContributorValidationMessages`, locale handling, `ResourceBundleLocator`. Expressions
      `${…}` are delegated to the optional `mansart-validation-el` module (D2, D5).
- [ ] `mansart-validation-cdi`: Vauban BCE exposing `Validator` / `ValidatorFactory`, CDI-managed
      `ConstraintValidator` instances, method validation on CDI beans (generated, no proxy).
- [ ] Vidocq runtime extension (in the `vidocq` repository) — planned with the runtime roadmap.

**TCK gate**: message interpolation, CDI integration areas (the latter via the in-reactor runner,
as the standalone TCK does not cover the container).

### V6 — Certification, performance, ecosystem ⏳

- [ ] TCK **100% PASS** on both runners (official exclusions only), score and command in `TCK.md`,
      `tck` page in the Antora docs (`docs/en`).
- [ ] JMH benchmarks vs Hibernate Validator (`BENCH.md`).
- [ ] GraalVM native-image / Leyden CDS smoke test using the APT path only.
- [ ] `mansart-persistence` wiring check: the persistence TCK validation tests pass with this
      brick on the classpath (closes D6 on the persistence side).

## Decisions

Recorded before implementation; each entry: date, decision, reason.

| # | Date | Decision | Status |
|---|---|---|---|
| D1 | 2026-10-07 | Brick named `mansart-validation`, inside Mansart; Java modules `io.vidocq.mansart.validation.*`. Born from persistence decision D6. | Actioned |
| D2 | 2026-10-07 | Message interpolation: `${…}` expressions are normative (spec §6.3.2 "Message expressions", `tck-testable`) and exercised by `ExpressionLanguageMessageInterpolationTest` (arithmetic, parentheses, `groups[0].simpleName`, `formatter.format(…)`, unchanged text on error). They are isolated behind an **optional module** `mansart-validation-el`: `mansart-validation-core` implements parameter interpolation (`{param}`, bundles) and leaves `${…}` unchanged when no evaluator is present (like Hibernate Validator's `ParameterMessageInterpolator`); the evaluator is a `ServiceLoader` SPI. The module is on the classpath of both TCK runners. Implementation of the module (in-house EL subset vs. `jakarta.el-api` + user-provided implementation): open, see D5. | Decided (optional module) |
| D5 | 2026-10-07 | `mansart-validation-el` is an **in-house evaluator** for the subset the spec and TCK require (arithmetic with parentheses, member access with indexing, method call on `formatter`, literals, `validatedValue` and constraint attributes in scope). No dependency on `jakarta.el-api` or any EL implementation, no reflection (AOT-compatible). Anything outside the subset fails explicitly and leaves the `${…}` text unchanged (spec: errors keep the expression as is). Rejected alternative: `jakarta.el-api` + application-supplied implementation (third-party implementation in the TCK runners, reflection-based). | Decided |
| D3 | — | Constrained types in opaque jars: Maven plugin generation only, or also bootstrap-time Class-File API parsing (as for `mansart-persistence` P2). | Open |
| D4 | — | `ExecutableValidator` interception for non-CDI objects, given no dynamic proxies. | Open |

## Out of Scope

- Runtime bytecode enhancement, Java agents, dynamic proxies.
- Hibernate-Validator-specific constraints (`@Length`, `@Range`, `@URL`, …) — spec ones only.
- Reactive validation — virtual threads cover the need.
