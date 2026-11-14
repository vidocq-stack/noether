# Noether contributor guidance

Read `README.md`, `ROADMAP.md` and `noether-persistence/AGENTS.md` before changes.
The same guidance applies to human and AI contributors; `CLAUDE.md` mirrors it.

Noether owns Jakarta Persistence 3.2 and the provisional Validation provider.
Mansart owns Data, Pool and Transactions; keep their external coordinates and
packages unchanged. Validation and integration vehicles are not release artifacts.
Do not remove their Maven deploy/Central skip flags or release workflow exclusions.

Use Java 25, Maven 3.9.16, strict Java Modules and virtual threads for I/O.
Entity access uses APT and the JDK Class-File API; no reflection-based entity
access, dynamic proxies or bytecode libraries. Write failing tests before
behavioral changes. Use the wrapper from the root for the unit reactor; invoke
official TCKs only through the standalone runner scripts.

Generate at compile time whatever can be: reflection at start time is only the
fallback for archives `noether-jpa-processor` did not process. The test-only
module `noether-architecture-tests` runs the shared `vidocq-archrules` library of
`vidocq-parent` (test scope, brings ArchUnit, Apache-2.0) on the classes of every
shipped module (Vidocq/vidocq#289): allowed dependencies per module, layers, no
foundational brick, Mansart's pool through its adapter only, no reflection outside
the documented fallbacks (nor its `java.lang.invoke` form: a private lookup, a
member found by name), no platform thread, no `ThreadLocal`. Each entry of
`ArchitectureRules.REFLECTIVE_FALLBACKS` and `DEEP_LOOKUP_ADAPTERS` carries its
reason; never add one without it, and drop it once its class stops reflecting
(the self-test fails otherwise).

Keep production dependencies limited to specification APIs and the justified
Vidocq integrations. Preserve official TCK inputs and fixture checksum guards.
Historical Mansart evidence is labelled and preserved verbatim; record new
measurements and reproducible bugs in the appropriate module's traceability files.

Code, comments and documentation are English. Use the term Java Modules.
Commits require human authorship, DCO sign-off and an AI-assistance provenance
trailer; do not commit or push without an explicit request.

Documentation is the English-only Antora `noether` component under `docs/en`.
Use the shared Vidocq documentation UI, with navigation ordered as index,
getting-started, usage, concepts, internals, tck, performance, reference, migration.
Do not reuse the Mansart logo as a Noether logo; new artwork is not part of this extraction.
