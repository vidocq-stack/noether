# Noether — Roadmap

Noether owns the implementations extracted from Mansart on 2026-10-11.

| Building block | Status | Detailed roadmap |
| --- | --- | --- |
| Jakarta Persistence 3.2 | P0–P12 delivered under the historical scope-based engineering gate; no formal certification | [Persistence](noether-persistence/ROADMAP.md) |
| Jakarta Validation 3.1 | V0–V2 provisional local test/TCK stopgap; not published | [Validation](noether-validation/ROADMAP.md) |

The extraction changes repository, artifact, package and provider names, not
persistence semantics or the documented conformance scope. Pool, Transactions
and Data remain owned by Mansart. Data/JPA integration and generation
mutualisation remain deferred. Original evidence is preserved in the extracted
modules, labelled historical; current commands are in `README.md`.

The 2026-10-14 Persistence follow-up adds to-one join-table mappings and
unidirectional one-to-many target-table foreign keys (annotation and XML), including
lifecycle and query execution. Other relational mapping boundaries remain documented
in the detailed roadmap; this does not extend the certification claim.
