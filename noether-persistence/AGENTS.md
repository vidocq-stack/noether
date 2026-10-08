# AGENTS.md — mansart-persistence (Mansart JPA)

> Contribution guide for AI agents (GitHub Copilot, Copilot Chat, Claude Code…) working inside
> `mansart-persistence/`. It **adds to** the repository-root files, it never overrides them: the
> root `CLAUDE.md` / `AGENTS.md` (Mansart-wide conventions) and the workspace `CLAUDE.md`
> (Vidocq philosophy, commit rules) apply here in full. If a rule below
> contradicts them, the root file wins and this file must be fixed in the same change.

## Repository Mission

- `mansart-persistence` is the home of **Mansart JPA** (Maven parent `io.vidocq.mansart:mansart-jpa`):
  an implementation of **Jakarta Persistence 3.2** in Java 25 for the Vidocq ecosystem.
- **Zero third-party implementation libraries**: only spec APIs are compiled into production
  modules — `jakarta.persistence-api` (3.2.0), and, for the integration modules only,
  `jakarta.enterprise.cdi-api`, `jakarta.inject-api`, `jakarta.transaction-api`. JDBC, StAX
  (`java.xml`) and the Class-File API are in the JDK. Reused Mansart bricks:
  `mansart-transactions-api` (JTA). The SQL is Mansart JPA's own (decision D4): a sealed AST in
  `mansart-jpa-dialect-spi`, rendered by the `mansart-jpa-dialect-*` modules.
- **No Hibernate, EclipseLink, OpenJPA, ANTLR, ASM, Byte Buddy, Caffeine, Jackson** — not in
  production, and never as the provider of a TCK run (that would measure *their* conformance).
- **Virtual threads** for all I/O: every JDBC call runs on a virtual thread; no platform-thread
  pool without a documented reason.
- **Strict Java Modules**: one `module-info.java` per module, minimal `exports`, internal packages
  never exported, the provider published through `provides … with`, no unjustified `opens`.
- The contract is the **official Jakarta Persistence 3.2 TCK at 100% PASS** (minus the official
  exclude list and documented optional features). Track milestones in `ROADMAP.md` (P0…P12) and
  the measured score in `TCK.md`.

## Real Code State to Know Before Modifying

- Modules in the reactor: `mansart-jpa-dialects` (`-spi`: the SQL AST and dialect SPI of decision D4; `-h2`,
  `-postgresql`), `mansart-jpa-core` (provider, bootstrap, entity model, accesses, JDBC binders, persistence
  context, flush engine),
  `mansart-jpa-processor` (APT, entity accesses at build time) and two module-path test vehicles,
  `mansart-jpa-module-it` (runtime path, `opens`) and `mansart-jpa-processor-module-it` (build-time path,
  `provides`). `mansart-jpa-tck` stays out of the reactor.
- Milestones P0 to P3 are delivered: read `ROADMAP.md` (status per item) and `TCK.md`
  (measured score, failures attributed per milestone) before starting.
- The entity model is built at bootstrap from the class files; which members are persistent, and in which order, is
  decided by `AccessPlanner`, shared with the processor. Never duplicate that logic.
- Several abandoned JPA attempts live on remote branches (`feature/*mansart*persistence*`). They
  are history, not a base: nothing is cherry-picked from them without a test that justifies it.

## Planned Module Architecture

| Module (artifactId) | Java module | Role |
| --- | --- | --- |
| `mansart-jpa-core` | `io.vidocq.mansart.jpa.core` | `PersistenceProvider`, EMF/EM, persistence context, entity model, flush, JDBC execution. No CDI, no JTA import. |
| `mansart-jpa-query` | `io.vidocq.mansart.jpa.query` | JPQL parser (hand-written, sealed AST), semantic analysis, Criteria API, SQL lowering to the dialect SPI. |
| `mansart-jpa-processor` | `io.vidocq.mansart.jpa.processor` | APT: static metamodel `_Entity` + generated entity accessors/instantiators for application sources. |
| `mansart-jpa-maven-plugin` | — | Build-time generation for entities living in pre-compiled jars (mirror of `mansart-data-maven-plugin`). |
| `mansart-jpa-cdi` | `io.vidocq.mansart.jpa.cdi` | CDI 4.1 Lite BCE on Vauban: `@PersistenceContext` / `@PersistenceUnit`, JTA-bound contexts via `mansart-transactions`. |
| `mansart-jpa-tests` | — | Cross-module and PostgreSQL (Testcontainers) integration tests. |
| `mansart-jpa-tck` | — | Official TCK runner — **OUT OF REACTOR**, standalone Model 4.0.0 POM, never listed in `<modules>`. |

Module names are a plan, not a contract: a module is only created when a milestone needs it.

## Boundaries Not to Break

- **Delivered modules are not modified by JPA work**: `mansart-jakarta-data`, `mansart-transactions`,
  `mansart-pool` and everything under them. JPA *depends on* them. A required change
  in the shared dialect SPI is a maintainer decision, recorded in `ROADMAP.md` before any edit.
- **The official TCK is read-only, forever.** Only our runner (`mansart-jpa-tck`) is ours.
  Never write a class named `Client` (it would match the TCK include and report a fake pass).
- **No runtime reflection on entities** — no `Field.get/set`, no `Method.invoke`, no
  `setAccessible(true)`. Entity access goes through code generated, in this order of preference:
  1. by APT at compile time (application sources): `mansart-jpa-processor` writes `X$$MansartAccess` and a
     `_MansartJpaAccess` per package, handed over by `ServiceLoader` (`provides
     io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider with <package>._MansartJpaAccess;` written by the
     application, `META-INF/services` on the class path) — no `opens` needed;
  2. by the Maven plugin at build time (dependency jars);
  3. by the **Class-File API** at `EntityManagerFactory` bootstrap (opaque archives such as the
     TCK), reading annotations from class bytes (`ClassFile.of().parse`) and defining accessors as
     hidden classes through `MethodHandles.privateLookupIn` — which requires the application to
     `opens` its entity packages to `io.vidocq.mansart.jpa.core`. Document it; never work around it.
- **No dynamic proxies, no ASM, no Byte Buddy, no `java.lang.instrument`.** `ClassTransformer`
  registration (`PersistenceUnitInfo.addTransformer`) is declined: lazy to-one associations use
  subclasses generated with the Class-File API (entity classes are non-final, §2.1), lazy
  collections use our own collection wrappers, dirty checking uses state snapshots.
- `mansart-jpa-core` must **never import** `jakarta.enterprise.*`, `jakarta.inject.*`,
  `jakarta.transaction.*`, nor any Vauban class. Container integration lives in `mansart-jpa-cdi`.
- **No `synchronized` around blocking I/O** — use `ReentrantLock`, `Semaphore`, atomics.
- **No `ThreadLocal`** — use `ScopedValue` (as `mansart-transactions` does) for any contextual
  propagation (current transaction, current persistence context).
- **No inline SQL in the core**: every statement is built as a `mansart-jpa-dialect-spi` AST and
  rendered by the dialect (H2, PostgreSQL). The TCK DDL scripts are the only hand-written SQL, and they are not ours.
- The persistence context (`EntityManager`) is **not thread-safe** by spec; the
  `EntityManagerFactory` is. Do not add locking to the EM, do make the EMF and its caches safe.
- Any `<scope>compile|runtime</scope>` dependency addition requires the `dependency-gatekeeper`
  agent and an explicit justification in the PR.

## Java Modules Conventions

- `module-info.java` lives in `src/main/java/`, as in `mansart-data-core` and
  `mansart-transactions-core`.
- `jakarta.persistence-api` 3.2 ships a `module-info.class` (`jakarta.persistence`); require it by
  that name. The provider is declared with
  `provides jakarta.persistence.spi.PersistenceProvider with io.vidocq.mansart.jpa.core.…`
  **and** `META-INF/services/jakarta.persistence.spi.PersistenceProvider` — the TCK runs on the
  classpath, production on the module path; both must work.
- Dialects are found with `uses io.vidocq.mansart.jpa.dialect.DialectFactory` (`ServiceLoader`).
- `persistence.xml` and `orm.xml` are parsed with StAX (`java.xml`, JDK) — no JAXB.
- Run the `java-modules-guardian` agent after every `module-info.java` or package change.

## Build & Test

- Build from the repository root with the Maven wrapper:
  `./mvnw -ntp -pl mansart-persistence/<module> -am test`. Red = not done.
- TCK (out of reactor, from its own directory):
  `mansart-persistence/mansart-jpa-tck/run-official-tck-persistence-3.2.sh`.
- **TDD is mandatory**: Red → Green → Refactor. No production line before a failing test
  justifies it. Cite the Jakarta Persistence 3.2 section in test comments (e.g. `// §3.3.4`).
- Unit tests sit in the same package as the class under test, named `<Class>Test`; JUnit Jupiter
  as managed by the Mansart root POM; AssertJ allowed; **no Mockito** — hand-written test doubles.
- Unit tests run on **H2** (in-memory); PostgreSQL tests use **Testcontainers** in
  `<scope>test</scope>` only. The TCK reference database is PostgreSQL (official DDL shipped in the
  TCK bundle under `sql/postgresql/`).
- Use `virtual-threads-reviewer` for concurrent code, `classfile-codegen` for any APT /
  Class-File API generator, `tck-runner` to triage TCK failures.

## Traceability

- Reproducible bugs → `mansart-persistence/BUG.md` (skill `/log-bug`): id, date, symptom,
  minimal repro, hypothesis, status.
- Performance figures → `mansart-persistence/BENCH.md` (skill `/log-bench`). No number in a
  README or a commit without a `BENCH.md` entry.
- TCK score → `mansart-persistence/TCK.md`, updated with every run that changes it: raw counters
  (tests / pass / fail / error / skip), database, date, and the list of excluded or
  not-applicable tests with the reason for each.
- Architectural decisions → `ROADMAP.md`, section "Decisions".

## Commits

- **English**, Conventional Commits, scope = module without the `mansart-` prefix
  (e.g. `feat(jpa-core): flush orders inserts before deletes (§3.3.4)`).
- DCO sign-off (`git commit -s`); author and committer are the human; AI assistance recorded with
  a `Co-Authored-By:` trailer naming the tool (workspace `AI-POLICY.md`).

## Documentation (Antora) conventions

The project documentation lives in `docs/en` as an Antora component and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, versioned per branch (`dev` prerelease on `main`, `'<version>'` on `docs/<version>`), `project-version` attribute, `nav:`, `lang: en`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **English-only** (ADR 0004 in vidocq-docs): no French mirror — do not reintroduce one.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
