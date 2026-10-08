# Mansart Validation — Implementation Roadmap

> Jakarta Validation 3.1 (Bean Validation) implementation in the Vidocq style: zero third-party
> implementation libraries (Jakarta spec APIs only), Java 25, strict Java Modules, no runtime
> reflection on constrained types, no runtime bytecode library — APT and the Class-File API instead.
> Mansart-wide vision: [`../PLAN.md`](../PLAN.md) and [`../ROADMAP.md`](../ROADMAP.md).

## Status and scope — a stopgap

This implementation exists so that `mansart-persistence` can run its TCK (Jakarta Persistence section 3.7 and the
validation tests of its TCK). It only has to **work**: it is not meant to be polished, not meant to pass the whole
Jakarta Validation TCK, and **it is not published to Maven Central**. Another member of the team is writing the
real Jakarta Validation implementation; this one is dropped when that one is usable.

Consequences:

- V0 to V2 are delivered and are what the persistence work needs: bootstrap, `validation.xml`, the validation
  engine on beans, properties and values, the built-in constraints, the metadata API for beans and properties.
  The official TCK is used as a test bed (308 of 981 pass), not as a certification target.
- V3 to V6 are **not planned**. They are kept below as a description of what a complete implementation would
  need, and are picked up only if the persistence TCK is found to need one of them.
- No release, no `BENCH.md`, no documentation site page, no APT or Maven plugin.

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

### V0 — The TCK instrument 🚧

**Goal**: runners that execute the official Jakarta Validation 3.1 TCK against *our* (still absent)
provider and print a counter. PASS = 0 is the expected, correct outcome — here 12 of 981 pass,
because they exercise bootstrap behaviours that need no working provider (see `TCK.md`).

- [x] Locate the official TCK: **`jakarta.validation:validation-tck-tests:3.1.1` is on Maven
      Central** (plus `validation-standalone-container-adapter` and the TestNG suite file) — no
      manual install. It is **TestNG + Arquillian**, not JUnit.
- [x] `mansart-validation-tck/` — standalone Model 4.0.0 POM, **out of the reactor**, container-less
      run modelled on the TCK's own `setup-examples/maven/pom-local.xml`.
- [x] Counter comes from the TCK's own packages (`org.hibernate.beanvalidation.tck.*`), never from a
      class of the runner (no Java in the module); `Tests run` is not 0.
- [x] `run-official-tck-validation-3.1.sh` (executable), `README.md`.
- [x] `TCK.md` with the baseline: 981 run / 12 pass / 969 fail, every failure traced to the missing
      provider (one assertion, `ConstraintValidatorFactorySpecifiedInValidationXmlTest`, not traced
      individually), not to setup or wiring.
- [ ] In-reactor runner `vidocq-runtime-tck-validation` in the `vidocq` repository (`tck` profile),
      same suite — separate repository, separate PR.
- [ ] Integration tests (CDI / container) — only meaningful with the CDI module (V5); the
      container-less run excludes them.
- [ ] Signature test.
- [ ] Official exclusions for 3.1.1, if any.

**Done when**: both runners print a counter for the official suite and the baseline is in `TCK.md`.

### V1 — Bootstrap, provider SPI, configuration ✅

Spec: ch. 5 (bootstrapping, `ValidationProvider`), ch. 8 (`validation.xml`).

- [x] `mansart-validation-core` module; `MansartValidationProvider` published via `provides` and
      `META-INF/services` (classpath **and** module path).
- [x] `Validation.buildDefaultValidatorFactory()`, `Configuration` (`MansartConfiguration`),
      `ConfigurationState`, `validation.xml` parser (StAX, versions 1.0 to 3.0, no JAXB), programmatic
      settings over `validation.xml` over defaults, `default-provider` delegation,
      `ignoreXmlConfiguration`, mappings and properties kept for later milestones.
- [x] `ValidatorFactory` / `ValidatorContext` / `Validator` lifecycle (`close`, `unwrap`,
      `IllegalStateException` once closed); `Validator` methods throw until V2 / V4.
- [x] Default components: `ConstraintValidatorFactory` (method handles, no `Constructor.newInstance`),
      `ClockProvider`, `ParameterNameProvider`, `TraversableResolver`; `MessageInterpolator` is a
      placeholder returning the template until V5.
- [ ] *Moved to V2:* the constraint annotation model (`@Constraint`, groups, payload, composed and
      repeatable constraints) — it is the metadata of the engine, not of the bootstrap.
- [ ] *Moved to V2/V3:* constraint-mapping XML parsing and `ValueExtractor` registration checks
      (the mappings are read and kept by `addMapping`, not parsed yet).

**TCK gate**: bootstrap and `validation.xml` areas — 981 run, **42 pass** (from 12). The bootstrap
tests that remain red all need `validate(…)`; see `mansart-validation-tck/TCK.md`.

### V2 — Built-in constraints and bean validation ✅

Spec: ch. 4 (built-in constraints), ch. 6 (constraint declaration and validation), ch. 7 (metadata API).

- [x] All built-in constraints of the API with the types the spec lists, plus `Number` and `CharSequence` for
      `@Min` / `@Max` (the TCK requires them); 22 constraints, 156 validators, `BuiltInConstraints` registry.
- [x] `Validator.validate`, `validateProperty`, `validateValue`; field, getter and class-level constraints;
      cascading with `@Valid` into beans, lists, sets, maps and arrays; cycles cut, shared beans reported under
      each path; constraints of superclasses and interfaces; groups with inheritance.
- [x] `ConstraintViolation` and `Path` model with every node kind, container class, type argument index, index and
      key; `ConstraintValidatorContext` with its violation builder; validator resolution on the declared type
      (generic signatures read from the class files); composing constraints and `@ReportAsSingleViolation`.
- [x] The metadata API for beans and properties: `getConstraintsForClass`, `BeanDescriptor`, `PropertyDescriptor`,
      `ConstraintDescriptor`, the `ConstraintFinder`. Executable descriptors are V4.
- [x] Metadata read from the class files with the Class-File API, values through method handles, annotation
      instances generated with the Class-File API (decision D3).
- [ ] *Moved to a later milestone:* the APT and the Maven plugin that produce the same metadata model at
      compile time. The runtime path is complete and is what the TCK exercises.

**TCK gate**: built-in constraints, bean validation, metadata API — 981 run, **308 pass** (from 42). Every
remaining failure belongs to V3, V4 or V5, see `mansart-validation-tck/TCK.md`.

### V3 (not planned, see "Status and scope") — Groups, group sequences, composition, container elements ⏳

Spec: ch. 3 (groups), ch. 6.4–6.6 (group sequences, redefined default group, container elements).

- [ ] Groups, group inheritance, `Default`, `@GroupSequence` (class-level redefinition, interface
      sequences). `@GroupSequenceProvider` is Hibernate-specific, not part of the spec.
- [ ] Container element constraints (`List<@NotNull String>`, `Map`, `Optional`), type-use
      annotations, `ValueExtractor` SPI, cascading through containers.
- [ ] Constraint composition with `@OverridesAttribute` / `@ConstraintComposition`.

**TCK gate**: groups, group sequences, container-element, composition areas.

### V4 (not planned) — Method and constructor validation ⏳

Spec: method and constructor validation chapter, `ExecutableValidator` (section numbers to be pinned at V0).

- [ ] `ExecutableValidator`: parameters, return values, cross-parameter constraints, constructor
      validation, `@Valid` on parameters and return values, Liskov-style rules on overriding.
- [ ] Generated entry points — no dynamic proxy, no runtime bytecode library; the interception
      for CDI beans lives in V5.

**TCK gate**: method validation areas.

### V5 (not planned) — Message interpolation and CDI integration ⏳

Spec: ch. 5.3 (message interpolation), ch. 8 (CDI integration is spec-defined for the container).

- [ ] Hand-written message interpolation: `{param}` resolution, `ValidationMessages` bundles,
      `ContributorValidationMessages`, locale handling, `ResourceBundleLocator`. Expressions
      `${…}` are delegated to the optional `mansart-validation-el` module (D2, D5).
- [ ] `mansart-validation-cdi`: Vauban BCE exposing `Validator` / `ValidatorFactory`, CDI-managed
      `ConstraintValidator` instances, method validation on CDI beans (generated, no proxy).
- [ ] Vidocq runtime extension (in the `vidocq` repository) — planned with the runtime roadmap.

**TCK gate**: message interpolation, CDI integration areas (the latter via the in-reactor runner,
as the standalone TCK does not cover the container).

### V6 (not planned) — Certification, performance, ecosystem ⏳

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
| D3 | 2026-10-08 | Constrained types in opaque archives (the TCK jar, any pre-compiled library): **the class bytes are read at bootstrap with the Class-File API** (annotations, members, generic signatures) and values are reached through **`MethodHandle`s** (`findGetter` / `findVirtual`), never `java.lang.reflect`. Annotation instances handed to `ConstraintValidator.initialize` are classes generated with the Class-File API, not dynamic proxies. The APT (later) produces the *same metadata model* at compile time. In a named module the constrained package must be opened to `io.vidocq.mansart.validation.core` (always true on the class path). | Decided |
| D4 | — | `ExecutableValidator` interception for non-CDI objects, given no dynamic proxies. | Open |

## Out of Scope

- Runtime bytecode enhancement, Java agents, dynamic proxies.
- Hibernate-Validator-specific constraints (`@Length`, `@Range`, `@URL`, …) — spec ones only.
- Reactive validation — virtual threads cover the need.
