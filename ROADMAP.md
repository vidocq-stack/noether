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

On 2026-10-15, decision D5 approves the optional `TABLE_PER_CLASS` strategy.
The implementation adds concrete inherited tables and SQL-union polymorphic ranges
to the existing metadata, generated access, schema, loading, JPQL/Criteria and bulk
mutation paths. Shared identifiers exclude `IDENTITY`; scalar pessimistic union queries
are refused, while entity queries use concrete-row follow-on locking. Existing
unsupported mapping shapes and broader lazy loading remain outside this follow-up.

Also on 2026-10-15 (decision D9), LAZY collections are delivered: `@OneToMany`,
`@ManyToMany` and `@ElementCollection` load on first access through typed wrappers
assigned by the generated access, keep persistence-context, flush, orphan-removal,
merge and cache semantics, report exact `isLoaded` state and fail with
`PersistenceException` once detached or closed. EAGER, fetch joins and entity graphs
initialize them. Lazy to-one relationships, batching and SQL fetch joins remain open;
see the detailed roadmap.
