# Noether

**Jakarta Persistence 3.2** for the Vidocq ecosystem, with the provisional
**Jakarta Validation 3.1** provider used by its tests and local TCK harness.

The name honours **Emmy Noether (1882–1935)**, the German mathematician whose
work transformed abstract algebra and connected symmetries with conservation laws.

## Modules

| Directory | Maven coordinates | Role |
| --- | --- | --- |
| `.` | `io.vidocq.noether:noether-root` | Independent reactor, parent `io.vidocq:vidocq-parent:0.4.0-SNAPSHOT` |
| `noether-persistence` | `io.vidocq.noether:noether-jpa` | Persistence provider, dialects, APT, CDI/JTA integration and Java module integration tests |
| `noether-validation` | `io.vidocq.noether:noether-validation` | Local Validation stopgap, not a complete implementation and **not published** |

All implementation packages and Java modules use `io.vidocq.noether`.
The provider is `io.vidocq.noether.jpa.core.NoetherPersistenceProvider`.
Pool and transaction integration retain their external `io.vidocq.mansart`
dependencies. Jakarta Data remains in Mansart; no Data/JPA bridge is introduced.

## Build

Java 25 (Temurin) and Maven 3.9.16 are pinned in `.sdkmanrc`. From this repository:

```sh
sdk env
./mvnw -ntp test
./mvnw -ntp install
./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test
```

Install the required Mansart pool/transaction and Vauban snapshots first when
working locally. `mansart.version` pins the independent Mansart dependency version.
The Vauban integration baseline uses the local snapshot carrying the pending
upstream injection fix. Production remains on the Java module path; the official
TCK uses its separate classpath harness.

## TCK and performance

The standalone runners are deliberately outside the reactor:

```sh
noether-persistence/noether-jpa-tck/run-official-tck-persistence-3.2.sh
noether-validation/noether-validation-tck/run-official-tck-validation-3.1.sh
noether-persistence/check-validation-neutrality.sh
```

The fixture tool retains the pinned official checksums and the documented
TCK-BUG-001 derived-jar patch. Official artifacts are never modified.
No formal Persistence certification is sought; the Web Profile is not a target.
Validation is a test/TCK stopgap, not a conformant Validation claim.

Current comparative harness and modular Leyden AOT smoke commands are:

```sh
cd noether-persistence/noether-jpa-bench
./run-bench.sh noether       # alternatives: hibernate, eclipselink
cd ../noether-jpa-processor-module-it
./run-aot-smoke.sh
```

The runtime consumer is `vidocq-runtime-noether-persistence-extension`,
with its `-codegen` companion and `vidocq-runtime-it-noether-persistence` vehicle.

## Extraction and evidence

On 2026-10-11 the tracked Persistence and Validation implementations were extracted
from Mansart (source branch `persistence-work`, revision `c91d72c`), preserving their
filtered Git history. Root build, licensing and CI infrastructure were adapted
from Mansart. The source repository is <https://codefloe.com/Vidocq/noether>.

The original `BENCH.md`, `TCK.md` and `BUG.md` under the extracted modules retain
their pre-extraction names, commands, source revisions and raw measurements,
explicitly labelled **historical**. They are not fresh measurements of Noether.
Use the commands above and the current runner/benchmark READMEs for the renamed
project. See [ROADMAP.md](ROADMAP.md), [Persistence roadmap](noether-persistence/ROADMAP.md)
and [Validation roadmap](noether-validation/ROADMAP.md) for scope and limitations.

Persistence supports `SINGLE_TABLE`, `JOINED` and, since decision D5 on 2026-10-15,
`TABLE_PER_CLASS` (annotations and ORM XML). Concrete tables contain inherited state;
polymorphic JPQL/Criteria execute against SQL `UNION ALL` ranges with SQL filtering,
aggregation, ordering and pagination. Hierarchy identities, relationships, versions,
secondary tables and bulk mutations use the existing generated-access paths.
`TABLE_PER_CLASS` requires hierarchy-wide unique identifiers: assigned keys or shared
`AUTO`/`SEQUENCE`/`TABLE`/`UUID` generation, not `IDENTITY` (rejected at bootstrap).
Polymorphic foreign keys cannot have a single database referential constraint.
Pessimistic entity queries lock concrete result rows after selection; purely scalar
pessimistic queries over these ranges are explicitly refused. This is a bounded mapping
extension, not broader lazy-loading work or a new certification claim.

LAZY collections (`@OneToMany`, `@ManyToMany`, `@ElementCollection` declared as
`Collection`, `List`, `Set`, `SortedSet`/`NavigableSet` or `Map`/`SortedMap`/`NavigableMap`)
load on first access inside their persistence context; unloaded collections of detached
or closed-context instances throw `PersistenceException`, and `PersistenceUnitUtil.isLoaded`
reports their state. EAGER mappings, JOIN FETCH and fetch/load graphs initialize them.
Concrete collection field types and collections inside embeddables stay eager.

LAZY owning `@ManyToOne`/`@OneToOne` foreign-key properties can now hold a generated
entity subclass: a simple identifier getter needs no SQL; the first other getter or
setter initializes the **same managed instance** once. `find`, queries, EAGER mappings,
JOIN FETCH, Criteria fetches and fetch/load graphs preserve identity and initialize
their required targets. `PersistenceUnitUtil` reports the reference's exact load state
and canonical entity class. Missing rows throw `EntityNotFoundException`; accessing
unloaded state after detach/clear/close/rollback throws `PersistenceException`.

The deliberately narrow target scope is a public, non-final, non-sealed concrete class,
directly extending `Object`, with no implemented interfaces, non-private instance fields
or non-interceptable instance methods; persistent state must use properties and the
identifier must be one annotated basic primitive, boxed integral/character, or `String` property. An owning
property getter and its owner class must also be non-final. Field-access associations,
final classes/methods, inverse one-to-one, join tables, embedded paths, inheritance,
composite/derived identifiers and XML-only/remapped target identifiers stay safely eager.
Final entity classes are accepted as an explicitly non-portable, eager-only extension.
APT emits subclasses with the JDK Class-File API in the application's closed Java module;
opaque archives use bootstrap generation and need the same documented package `opens`
as their accessors and a read edge to the provider SPI (`requires io.vidocq.noether.jpa.core`).
An opened module without that read edge stays eager; no module reads are injected into it.
No instrumentation, dynamic proxy or reflective entity access is used.
Cache graphs containing unloaded to-one references are not stored (no forced load).
Batching was added separately by D13 below. See the detailed Persistence
roadmap and `docs/en` for the exact boundaries.

Lazy first access now batches compatible pending collections or eligible D10 to-one
references in the same persistence context (D13, 2026-10-15). Configure
`io.vidocq.noether.jpa.fetch.batch-size` on the factory: **16** by default,
an integer **1–256**, with **1** disabling grouping. A batch never exceeds **512
key parameters**; composite collection-owner keys reduce its group size accordingly.
Collection rows preserve null basic values, list positions, duplicate bags, map keys
and empty owners (BUG-20261015-09 fixed). Association collections read rows and then
bounded full-state target batches, not one `find` per element; already managed dirty
targets are preserved and unloaded references hydrate in place.

Read batching is intentionally limited to single-table, non-polymorphic owners/targets:
element collections, owning join-table/foreign-key collections, and inverse collections
mapped by a target foreign key. Entity-valued map keys, inverse join tables, secondary
tables, inheritance and unsupported mapping shapes keep the complete ordinary loader.
D10's narrow lazy-reference eligibility is unchanged. Replaced snapshot wrappers use
ordinary loading for flush/orphan baselines. Batches use the active transaction's
connection (otherwise a read connection), do not auto-flush, and retain the existing
lazy-loader cache policy: database reads, no forced cache publication, no overwrite of
managed state. Detached/cleared/closed contexts still fail explicitly. EAGER/fetch/graph
contracts and SQL fetch eligibility are unchanged; batching is not a graph-query rewrite.

JPQL `JOIN FETCH` / `LEFT JOIN FETCH` and Criteria `fetch` of single-valued relationships
read the owner and its fetched to-one targets from the same SQL row (decision D11), with no
follow-up select per target. Hydration reuses the loader: one managed instance per identity,
existing unloaded references hydrated in place, managed (possibly modified) instances never
overwritten, snapshots and `PostLoad` as for `find`. Eligible: non-`DISTINCT` and
non-grouped top-level selects without lock mode, a selected root variable and fetch paths
`var.attribute` chained from it, targets of one concrete type (no subclass, no joined or
table-per-class inheritance). Other shapes, entity graph hints and `find` keep the existing
correct follow-up selects; collection fetching has its own bounded D12 path below.

One collection `JOIN FETCH` / `LEFT JOIN FETCH` (JPQL or Criteria, decision D12,
2026-10-15) reads the owner and all fetched elements in the same SQL statement.
Eligible queries select one concrete root, with just that collection join and no grouping
or lock mode. Entity collections and basic/embeddable element collections reuse existing
generated access and mapping; ordered lists, basic map keys and derived `@MapKey` keys
retain their positions/keys. Non-`DISTINCT` results retain repeated owner references;
`DISTINCT` deduplicates owners without discarding duplicate bag elements. Existing dirty
state is preserved, unloaded wrappers initialize in place, and snapshots/callbacks/cache
publication follow complete assembly. Other mapped eager relationships may still require reads.

Multiple/nested fetches, extra joins, restricted collection aliases, polymorphic or
`JOINED`/`TABLE_PER_CLASS` owners/targets, embedded fetch paths and entity/embeddable map
keys retain complete secondary loading. Fetch/load graph hints and `find` are not rewritten.
Pagination (`setFirstResult` above zero or finite `setMaxResults`) of a single-root entity
selection uses a safe fallback: **page distinct owner identities in SQL, then load their
complete collections through ordinary secondary selects**. Only scalar root ordering is
admitted; grouped/locked queries, complex projections and element ordering throw
`PersistenceException` before SQL. No partial collection or silent in-memory pagination.
Unbounded `getSingleResult` reads all joined rows.
Null collection elements retain the existing assembler limitation (BUG-20261015-09);
batching and broader SQL fetch shapes remain in the prioritized roadmap.

The project uses the Vidocq disjunctive triple licence:
EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later. See `LICENSE`, `licenses/` and `NOTICE`;
the bundled Jakarta ORM schema retains its original upstream licence.

Noether has no independent published release yet. `0.4.0-SNAPSHOT` is the
current development version; older Mansart versions in historical evidence
are not Noether releases.
