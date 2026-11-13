# Persistence TCK — coverage gaps found while implementing Noether

> Purpose: collect, with evidence, the places where the Jakarta Persistence TCK lets a specification requirement go
> untested, so they can be reported upstream (`jakartaee/persistence`, `jakartaee/platform-tck`). Each entry gives
> the normative text, what the TCK checks today, the proposed test, and the Noether change that exposed the gap, so
> the proposal can be validated locally before it is submitted.
>
> Reference suite: Jakarta Persistence TCK **3.2.1** (`jakarta.tck:persistence-tck-spec-tests:3.2.1` and
> `jakarta.tck:persistence-tck-common:3.2.1`), run through `noether-persistence/noether-jpa-tck`.
>
> The defects that Mansart found in the TCK fixtures before Persistence moved to Noether (TCK-BUG-001, delimited
> identifiers) are historical evidence. They stay in Mansart's
> [`PERISTENCE_TCK_PROPOSALS.md`](https://codefloe.com/Vidocq/mansart/src/branch/main/PERISTENCE_TCK_PROPOSALS.md).

---

## TCK-GAP-001 — `<qualifier>` and `<scope>` in `persistence.xml` are never exercised

- **Date**: 2026-11-13. **Status**: DRAFT, not yet reported upstream.
- **Specification**:
  - Jakarta Persistence 3.2, §8.2.1.5 (`qualifier`) and §8.2.1.6 (`scope`): a unit names the CDI qualifiers and the
    CDI scope of its injectable beans. `PersistenceUnitInfo.getQualifierAnnotationNames()` and
    `getScopeAnnotationName()` hand both to the provider.
  - Jakarta EE 11 Platform, §13.12.1: for each persistence unit, the container makes available a bean of type
    `EntityManager`, with the qualifiers of the `qualifier` elements or `@Default`, and the scope of the `scope`
    element or `@TransactionScoped`.
  - Jakarta EE 11 Platform, §13.12.2: an `EntityManagerFactory` bean with the same qualifiers, `@ApplicationScoped`
    and named after the unit, and five `@Dependent` beans of types `CriteriaBuilder`, `PersistenceUnitUtil`, `Cache`,
    `SchemaManager` and `Metamodel`.
- **Exposed by**: Vidocq/noether#72. `noether-jpa-cdi` parsed both elements but bound neither, and the TCK stayed green.

### What the TCK checks today

Checked on 2026-11-13 by unpacking both 3.2.1 jars:

1. **No descriptor uses the elements.** The jars hold 12 `persistence.xml` files (2 in `persistence-tck-common`, 10 in
   `persistence-tck-spec-tests`); none declares `<qualifier>` or `<scope>`. The only XML matches for those tags are
   Maven `<scope>compile</scope>` lines in the jars' own `pom.xml`.
2. **No test injects with `@Inject`.** No class of either jar refers to `jakarta/inject/Inject` or to
   `jakarta/transaction/TransactionScoped`.
3. **The `PersistenceUnitInfo` accessors are never read.** The only class that names `getQualifierAnnotationNames` or
   `getScopeAnnotationName` is the pluggability test's own
   `ee.jakarta.tck.persistence.common.pluggability.altprovider.implementation.PersistenceUnitInfoImpl`, which
   implements both by returning `null`. The other match is the signature file
   `ee/jakarta/tck/persistence/signaturetest/jakarta.persistence.sig_3.2`.

The Platform TCK was not checked.

### Proposed TCK change

These tests belong where CDI is available: the Platform TCK, or a Persistence TCK profile that runs on a CDI
container. One unit declares two qualifiers and the `@RequestScoped` scope. A second unit declares no `<qualifier>`
and no `<scope>`. A third, resource-local, unit only reaches the factory beans.

- **The `EntityManager` bean.**
  - `@Inject @Q1 @Q2 EntityManager` resolves.
  - `BeanManager.getBeans(EntityManager.class, Q1, Q2)` has the scope `RequestScoped`.
  - Within one JTA transaction, an entity it persists is `contains`-ed by the `@PersistenceContext(unitName = …)` of
    the same unit: one persistence context per transaction, §7.6.4.
- **The unit without qualifiers.** It gives `@Default` beans:
  - its `EntityManager` bean is `@TransactionScoped`;
  - outside a transaction, a call on it throws `ContextNotActiveException`.
- **The `EntityManagerFactory` bean.**
  - It is `@ApplicationScoped`.
  - `BeanManager.getBeans("<unit name>")` finds it.
  - It has the same `Metamodel` as the factory that `@PersistenceUnit(unitName = …)` injects.
  - Its `close()` throws `IllegalStateException`.
- **The five dependent beans.** They resolve under the unit's qualifiers, are `@Dependent`, and the `Metamodel` bean
  is the factory's `getMetamodel()`.
- **Negative case.** A `<qualifier>` naming a class that is not a qualifier fails the deployment.

### Why it matters

A provider or container can ship without the 3.2 CDI integration and still pass the Persistence TCK. Noether did,
until this gap was closed. An application that relies on `@Inject @Q EntityManager` then fails at deployment with an
unsatisfied dependency on that implementation, although the implementation is certified.

### Local validation

`noether-persistence/noether-jpa-cdi-module-it`, `InjectedUnitModuleTest`, runs every assertion of the proposed
change except the negative case:

- the units `xml-ledger` (`@Ledger`), `xml-archive` (`@Archive`, `@ApplicationScoped`) and the unqualified units;
- Vauban, on the strict module path;
- Mansart's transaction manager and `@TransactionScoped` context.

The negative cases (a class that cannot be loaded, is not an annotation, is not a qualifier or is not a scope) are
unit tests in `noether-jpa-cdi`: `UnitAnnotationsTest`.
