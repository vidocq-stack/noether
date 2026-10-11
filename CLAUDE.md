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
