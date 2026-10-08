# TCK status — Mansart JPA (Jakarta Persistence 3.2)

Official suite: **Jakarta Persistence 3.2.1** TCK (bundle from eclipse.org, SHA-256 checked), standalone mode,
**PostgreSQL 17** (official DDL and stored procedures of the bundle). Runner and command:
[`mansart-jpa-tck/`](mansart-jpa-tck/README.md), `./run-official-tck-persistence-3.2.sh`.

## Progress

| Milestone | Date | Java | Database | Tests | Pass | Fail | Skipped |
|---|---|---|---|---:|---:|---:|---:|
| P0 — instrument, no provider | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 17 | 2114 | 4 |
| P1 — bootstrap, provider SPI, resource-local transactions | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 220 | 1911 | 4 |
| P2a — entity model and generated access, mapped at bootstrap | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 220 | 1911 | 4 |
| P2b — accesses generated at build time (the TCK compiles nothing with the processor: runtime path unchanged) | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 220 | 1911 | 4 |
| P3 — persistence context and flush engine (flush at every commit, PostgreSQL dialect on the class path) | 2026-10-08 | Temurin 25.0.3 | postgres:17-alpine | 2135 | 220 | 1911 | 4 |

## P3 — the flush runs at every commit, the counts do not move

Every TCK commit now flushes the persistence context of its entity manager, with the PostgreSQL dialect detected from
the JDBC metadata; nothing is managed yet (the entity operations are P4), so the counts stay those of P1. A first run
found a regression the unit tests had not: an entity whose `@IdClass` holds a relationship (`derivedid.ex6a`) failed
the bootstrap while its statements were built; such entities now wait for P5, and the mapping probe maps the 161
units of the TCK again, statements included. Final split: P4 877, P7 661, P8 348, P9 23, P5 2 — none unexplained.

Bean Validation neutrality (2026-10-08, with the flush at commit): NEUTRAL, 2135 tests, same results both ways.

## P2a — the whole TCK is mapped, the counts do not move

From P2a on, every `createEntityManagerFactory` of the TCK maps its managed classes: entity model, generated access,
binders. The counts stay those of P1 on purpose: the P2 gate areas (`core.types`, `core.enums`, `core.annotations.*`,
`jpa22.generators`, …) persist and read entities back, so they open with P4. What P2a proves is that the mapping
breaks nothing:

- the first run with the mapping at bootstrap fell to 135 passes: the access type of mixed hierarchies, uncapitalised
  accessors (`getdescription`), derived identities (§2.4.1) and attributes left to `orm.xml` failed the bootstrap;
- a one-off probe mapping every package of the TCK spec-tests jar as one unit found them; after the fixes, 161 units
  out of 161 map;
- the failure split is the P1 one exactly (P4 877, P7 661, P8 348, P9 23, P5 2), and no report holds a mapping error.

Bean Validation neutrality (2026-10-08, with the mapping at bootstrap): NEUTRAL, 2135 tests, same results both ways.

## P1 — what the 1911 failures are

Every failure names the milestone that delivers the missing operation (the provider throws
`UnsupportedOperationException: Mansart JPA does not support … yet (milestone Pn …)`, or a `PersistenceException` for
schema generation):

| Count | Milestone | What the tests need |
|---:|---|---|
| 877 | P4 | `persist`, `find`, `remove`, … on entities |
| 661 | P7 | Jakarta Persistence queries, native queries, named queries, stored procedures |
| 348 | P8 | the metamodel, the Criteria API, entity graphs |
| 23 | P9 | schema generation |
| 2 | P5 | `PersistenceUnitUtil` |

No failure is left unexplained. P1 gate areas (`se.entityManagerFactory`, `core.entityManagerFactory`,
`core.entityTransaction`, `se.resource_local`): 20 pass, 1 skipped (official exclusion), 15 fail, all on P4, P5, P7 or P8.
`se.entityManagerFactory.Client2#createEntityManagerFactoryNoBeanValidatorTest` now passes for the right reason: the
provider refuses validation mode CALLBACK without Bean Validation.

Bean Validation neutrality (2026-10-08, with the provider): NEUTRAL, 2135 tests, same results both ways.

## Baseline — P0 (no provider)

Execution 1: 2134 tests (16 pass, 2114 fail, 4 skipped). Execution 2: 1 test (1 pass).

**Every one of the 2114 failures has `jakarta.persistence.PersistenceException: No Persistence provider for
EntityManager named JPATCK` in its failure text**: no failure comes from the setup, the wiring or the database.
(The report's "deepest cause" shows 22 of them as `X failed`: those tests catch the exception and rethrow a generic
one; the provider message is still in their failure text.)

### The 17 that pass without a provider — not progress

| Count | Tests | Why they pass |
|---:|---|---|
| 1 | `signaturetest.JPASigTest#signatureTest` | checks the signatures of `jakarta.persistence-api` itself: a genuine pass |
| 15 | `se.pluggability.contracts.resource_local.Client#*` | they exercise the TCK's own stub provider (`persistence-tck-common`, `altprovider`), not ours |
| 1 | `se.entityManagerFactory.Client2#createEntityManagerFactoryNoBeanValidatorTest` (execution 2) | expects a `PersistenceException` with no Bean Validation provider; today it gets one because there is no persistence provider at all. It becomes a real check at P1/P11 |

### The 4 skipped

`@Disabled` in the TCK sources, matching the official exclusion list (`docs/TCK-Exclude-List.txt`):
`core.metamodelapi.identifiabletype.Client#getDeclaredSingularAttributes`,
`core.query.language.Client6#resultContainsFetchReference`,
`se.schemaGeneration.annotations.tableGenerator.Client#tableGeneratorTest`, and the disabled
`se.entityManagerFactory.Client2#createEntityManagerFactoryStringMapTest`.

## Bean Validation neutrality

`./check-validation-neutrality.sh` (2026-10-08): the same 2135 tests with the same results with and without
`jakarta.validation-api` + `mansart-validation-core` on the class path of execution 1: **NEUTRAL**. Trivial while
there is no provider; the check becomes meaningful from P1 on and must stay NEUTRAL.
