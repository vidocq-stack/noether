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
table-per-class inheritance). Other shapes and `find` keep the existing
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
keys retain complete secondary loading. At D12 delivery, collection graph nodes and
`find` were not SQL-rewritten; D16 below adds the bounded single-collection graph path.
Pagination (`setFirstResult` above zero or finite `setMaxResults`) of a single-root entity
selection uses a safe fallback: **page distinct owner identities in SQL, then load their
complete collections through ordinary secondary selects**. Only scalar root ordering is
admitted; grouped/locked queries, complex projections and element ordering throw
`PersistenceException` before SQL. No partial collection or silent in-memory pagination.
Unbounded `getSingleResult` reads all joined rows.
Null collection elements are preserved since D13 (BUG-20261015-09);
broader SQL fetch shapes remain in the prioritized roadmap.

Query `jakarta.persistence.fetchgraph` / `jakarta.persistence.loadgraph` hints now
drive eligible to-one SQL loading (D14, 2026-10-15): one root entity selection, no
distinct/grouping/locking or explicit collection fetch, supported concrete owner/targets.
Direct and nested to-one graph nodes become LEFT fetch joins using D11's hydration,
including converters and secondary-table state. Existing unconditional fetch joins,
filtering, ordering and SQL pagination are preserved; null targets do not filter roots.
LOAD also includes eligible mapped eager to-ones. FETCH only adds explicit nodes to SQL,
but may overfetch omitted eager state through the existing loader, as permitted by
the specification; it does not promise those attributes remain unloaded.
Hints and mutable graphs are reread on each execution. Identity, dirty managed state,
in-place lazy-reference initialization, callbacks, snapshots and cache safeguards are
unchanged. At D14 delivery collection nodes still used complete follow-up loading; complex queries,
embedded/treated nodes and unsupported polymorphic/inherited types retain existing
fallbacks. Nested/multiple collection SQL fetching remains a separate lot.

Graph-aware `find` now uses the same SQL to-one graph planner (D15, 2026-10-16).
On an eligible cache miss, `find(EntityClass, id, hints)` and the 3.2
`find(EntityGraph, id, options)` read direct/nested to-ones with the root, using LEFT
joins and mapped key binders, including composite root identifiers. LOAD also includes
eligible mapped eager to-ones; FETCH may still overfetch omitted eager state.
Normal managed roots and shared-cache hits retain their existing no-reread fast paths,
then initialize requested graph state. Unloaded root/target references hydrate in place,
and dirty managed state is never overwritten. Unlike a query, find does not auto-flush.
Hints/graphs are validated and reread per call; when both graph hints are present,
find retains its existing merged plan (query FETCH precedence is unchanged).
Plain find, locking finds, unsupported polymorphic/inherited mappings and unsupported
graph paths retain their original loader. At D15 delivery collections still initialized
completely through follow-up reads. Cache retrieve/store/REFRESH policy, lock/timeout hints,
callbacks and snapshots keep the existing find contract; no new timeout semantics
or SQL collection fetching are introduced.

Decision **D16 (2026-10-16)** adds SQL loading for **one leaf root collection** requested
by a fetch/load graph, reusing D12's collector and D15's find routing. Eligible JPQL and
Criteria queries select one concrete root, optionally `DISTINCT`, with no existing joins,
grouping, set operations or locks. A non-locking database find, including an unloaded
managed root, uses the same path. A LEFT join retains empty owners; query results do not
gain duplicate roots. Entity/basic/embeddable collections retain null values, duplicate
bags, list positions/order and supported basic/derived map keys. Managed dirty state and
wrapper/target identity are preserved; collections complete before snapshots, callbacks
and cache publication. Graphs/hints are reread each execution.

Other mapped eager state still loads as required (FETCH may overfetch). Graphs containing
subgraphs, multiple collections, or explicit to-one association nodes keep D14/D15's
existing complete loading; mixed graphs are not degraded by replacing their to-one joins.
Extra/filtering joins, complex/polymorphic shapes, embedded paths and entity/embeddable map
keys also retain the existing loader. Explicit fetch joins are unchanged. Finite owner
pagination uses **SQL distinct-owner paging plus complete secondary loads**, never a
partial collection or silent in-memory page. Managed/cache find hits retain their fast
paths; find gains no auto-flush. Nested-to-one collection subgraphs and multiple
collection SQL fetching remain future lots.

Verification: **34/34** collection-fetch scenarios (14 new), **144/144** focused tests;
full clean reactor and patched PostgreSQL TCK evidence are recorded in
[`noether-persistence/TCK.md`](noether-persistence/TCK.md). The ordinary-loader callback
cycle discovered during this work is tracked separately as BUG-20261016-01.

BUG-20261016-01 is fixed (2026-10-16, follow-up to D16): every `EntityLoader` read runs in a
load scope of the persistence context, and `PostLoad` callbacks are deferred until the
outermost load returns, so a callback that reads a lazy collection (or an element callback
reaching back to its owner's collection) sees it complete. Each freshly loaded entity still
gets exactly one callback before `find`, `refresh` or the query returns; callback
mutations stay dirty and callback exceptions still propagate. Evidence is in
[`noether-persistence/TCK.md`](noether-persistence/TCK.md).

Decision **D17 (2026-10-16)** extends D16 to **one root collection with to-one subgraphs**,
for example `Order -> lines -> product -> supplier`. The single graph-requested entity
collection is LEFT-joined as in D16, and every to-one subgraph node of its elements (direct
or nested chains of concrete, non-polymorphic entities; basic/embedded leaves may coexist)
becomes a LEFT fetch join from the collection alias. Owners, elements and targets are
hydrated from the same rows through D12's collector and D11's generated column access; LOAD
also joins the elements' mapped eager to-ones. It applies to the same JPQL/Criteria shapes
and eligible non-locking database `find` as D16. Null targets never filter elements, empty
owners stay, bag duplicates, list positions and basic map keys are kept, and shared targets
have one managed instance. Dirty managed entities/collections are not overwritten,
unloaded lazy references complete in place, and each fresh entity gets one `PostLoad`
after the whole graph is assembled (BUG-20261016-01 load scope). Graph-collection pagination
pages owners in SQL from the original statement (no synthetic joins), then completes
collections and subgraphs through secondary loads. Nested collection subgraphs (including
element collections of targets), polymorphic/inherited targets, multiple collections, root
to-one nodes beside the collection and unknown/embedded-subgraph nodes keep the complete
existing fallback; explicit fetch joins are unchanged. Evidence is in
[`noether-persistence/TCK.md`](noether-persistence/TCK.md).

Decision **D18 (2026-10-16)** combines D14–D17 for **mixed graphs with exactly one root
collection and direct/nested root to-one branches**, for example
`Order { customer { favorite { supplier } }, lines { product { supplier } } }`.
Concrete hydratable targets on either branch use LEFT joins in the same SQL statement;
shared root/element targets retain one managed identity without multiplying collection
entries. This also applies to a basic/embeddable root element collection with root
to-ones, and LOAD expands mapped eager to-ones on both branches (FETCH may overfetch).
Eligible JPQL/Criteria and non-locking database finds retain D16/D17's query/mapping
boundaries, complete lifecycle/dirty/lazy/cache contracts and execution-time graph/hint
planning. Managed/cache find hits keep their ordinary completion paths without
auto-flush. Pagination still pages distinct owners in SQL, then completely loads their
collections and branches; it is not single-query pagination.

Multiple root collections, collections nested on either branch, unsupported map keys,
complex/polymorphic shapes and existing explicit fetch joins retain their established
complete loading or explicit refusal. Prioritized remaining work: multiple collection
SQL fetching, nested collections, broader fetch eligibility, broader lazy/batch shapes
and missing relational mappings (detailed backlog in the roadmaps). D5/D9–D17 and
BUG-20261016-01 above remain historical records. D18 verification is recorded in
[`noether-persistence/TCK.md`](noether-persistence/TCK.md).

Decision **D19 (2026-10-16)** adds **multiple graph-requested root collections**:
`Order { lines { product { supplier } }, payments }`. Eligible JPQL/Criteria first
select owner identities in SQL, then read each collection independently for bounded
owner groups. Non-locking graph finds use the same collectors directly. This is split
SQL loading, never a join between collections: bags are not Cartesian-amplified,
lists/maps retain their indexes, and shared root/element targets retain managed identity.
Root and element to-one subgraphs reuse D18's LEFT joins; mapped eager to-ones and root
collections are included (permitted FETCH overfetch). Every branch is staged before
relationships, snapshots and callbacks; dirty state and in-place wrappers/references
survive. Fully complete managed/cache find hits need no SELECT and find never auto-flushes.

With `C` collection branches, `N` owners, key width `W` and configured fetch batch size
`S` (default 16, range 1–256), `B = min(S, floor(512 / W))`: an eligible query uses
`1 + C * ceil(N / B)` SELECTs, independent of the number of elements; a database find
uses `C` SELECTs. Size 1 disables owner grouping, not split collection assembly.
Pagination applies the limit to distinct owner identities in SQL, then reads their
complete collections. Mutable graph/hint plans are rebuilt each time.

The D16–D18 query/mapping restrictions still apply: unsupported joins/locking/grouped
or set queries, polymorphic/complex graph targets, unsupported map keys and **nested
collection-valued subgraphs** retain complete ordinary loading or explicit refusal.
Nested eager collections on non-root targets likewise retain fallback. Omitted
unsupported lazy to-one shapes can still require the established eager fallback;
the bounded count is not a universal claim for those shapes or dirty in-memory
association changes. Existing explicit JOIN FETCH and single-collection SQL are unchanged.
Remaining priorities are deeper nested collection graphs, broader fetch eligibility,
broader lazy/batch support and missing mappings. D5/D9–D18 and BUG-20261016-01 remain historical.

Decision **D20 (2026-10-16)** extends D19 to **two collection edges per graph path**,
for example `Order { lines { discounts { supplier }, notes } }`. The first edge
must be a root collection; finite to-one chains may lead from its entity elements
to the second collection's owners (`Order.lines.product.tags` also qualifies).
Independent root branches and second-level entity/element collections reuse the
same D16–D19 collectors, with no collection-to-collection join. Shared nested
owners are deduplicated by mapped identity and requests for the same owner type
and collection are merged before SQL. Basic null rows, duplicate bags, stored list
positions and supported map keys retain their existing assembly.

Queries first select/paginate roots in SQL, then read first-level collections,
then second-level collections in independent owner batches. The count is
`1 + sum(ceil(Nc / Bc))` over all collectors, where `Nc` is that collector's
distinct owner count and `Bc = min(batch-size, floor(512 / owner-key-width))`;
non-locking database finds omit the initial root-selection SELECT. Empty nested
owner sets issue no SELECT. All stages finish before relationships, snapshots,
cache publication or callbacks; loaded dirty state and lazy wrappers/references
remain in place. Complete managed/cache finds need no SELECT, new JDBC connection
or auto-flush (the connection-count regression is tracked as BUG-20261016-03).
BUG-20261016-02, exposed by removing one duplicate from a nested managed bag, is
fixed by reusing whole-owner join-row rewriting when a bag snapshot has duplicates.

At D20, third collection edges, collections under root to-one branches,
unsupported eager closures, polymorphic/complex mappings, entity/embeddable map
keys and broader joins/grouped/locked/set queries retained complete ordinary
loading or explicit refusal. D21, D33 and D34 separately extend nested collection
graphs while preserving those other boundaries. Eager to-one expansion is
cycle-guarded; deeper finite cyclic graphs fall back. Counts exclude ordinary
completion for dirty in-memory relationship changes and omitted unsupported lazy
mappings. Existing explicit fetch joins and unnested single-collection collectors
are unchanged. D5/D9–D19 and BUG-20261016-01 above are historical delivery records.
The remaining priorities are deeper collection graphs, broader fetch/lazy/batch
eligibility and missing mappings. Verification is in
[`noether-persistence/TCK.md`](noether-persistence/TCK.md).

Decision **D21 (2026-10-16)** supersedes D20's root-to-one-collection fallback:
eligible JPQL/Criteria and non-locking `find` FETCH/LOAD graphs now group collections
reached through finite root to-one chains, such as `Order.customer.addresses` or
`Order.customer.favorite.tags`. An initial bounded LEFT to-one seed reuses D14–D18
hydration, then D19/D20 collectors read distinct intermediate owners. Shared
customers/products are not loaded once per order; compatible owner-type requests
shared across collection depths are merged at the later stage.

At D21, the bound was **two collection edges per graph path**. D33 extends nested
paths beginning at a root collection to three edges, and D34 extends the
root-to-one seed path to three edges as well. Independent root collections and
element to-one subgraphs can coexist. Null to-ones retain their roots, empty
collections stay complete, and the existing duplicate/null/list/map semantics
remain unchanged. All graph branches precede callbacks; dirty managed state, lazy
wrappers/references, snapshots, flush, L2 cache and JDBC-free complete find hits
retain their existing contracts. Find never auto-flushes.

For this new path, a query uses `1 + ceil(Nroot / Broot) + sum(ceil(Nc / Bc))`
SELECTs: root identities, root seed batches, then each distinct-owner collection
batch. A database find omits the root-identity query. Each
`B = min(fetch.batch-size, floor(512 / owner-key-width))`; zero owner IDs cause no
collection SELECT. Counts exclude ordinary completion of dirty reassigned
relationships and unsupported lazy mappings. Root pagination remains SQL-only,
followed by complete collection reads, never collection-row limits or in-memory paging.

For both supported staged routes, explicit cyclic paths and a fourth collection
edge, unsupported mapped eager closures, polymorphic/non-hydratable targets,
unsupported keys/embedded paths and the existing complex/joined/grouped/set/locking
query boundaries keep complete ordinary loading or explicit refusal. Explicit
fetch joins and earlier graph eligibility are unchanged.
The remaining priorities are deeper-than-three/cyclic graphs, broader
fetch/lazy/batch eligibility and missing mappings. D5/D9–D21 and BUG records
above are historical; the D21 contract, D33/D34 extensions and verification are in
the detailed roadmap and `TCK.md`.

Decision **D22 (2026-10-16)** pauses further graph optimization in favour of
**owning to-one joins to unique non-ID business columns**, for example
`Order.customer_code -> Customer.code` while `Customer.id` remains its JPA identity.
Single and composite tuples work with annotations, metadata-complete ORM XML and
XML overrides, including reordered referenced columns and attribute converters.

```java
@Entity
class Customer {
    @Id long id;
    @Column(unique = true, nullable = false, updatable = false)
    String code;
}
// On an entity whose own @Id is independent of the association:
@ManyToOne
@JoinColumn(name = "customer_code", referencedColumnName = "code")
Customer customer;
```

Referenced attributes must be unambiguous primary-table BASIC columns, explicitly
non-null, insertable and immutable (`updatable=false`), backed by `unique=true`
for one column or a matching table `uniqueConstraints` tuple. Composite join columns
must all name their target and use uniform nullability. Owning join columns must
be fully writable (`insertable=true`, `updatable=true`); read-only/shared-column
business joins are explicitly outside this lot.
Mixed ID/business tuples, duplicate/missing/nonunique references and mutable keys
are refused at bootstrap. A managed key change is also rejected at flush, using
converter-aware snapshots; detached references must agree with their actual identity.

Writes bind business values; reads resolve the tuple to the actual target ID before
ordinary loading, so `find`, repeated references and L2 cache retain JPA identity.
Many-to-one, owning/inverse one-to-one, reassignment/null, cascade/orphan removal,
required insert/delete dependencies, nullable cycles and same-row references are
covered. Non-null dependency cycles are explicitly refused. Generated foreign keys
reference the real unique columns. JPQL/Criteria joins, navigation, projections,
identity/null comparisons and bulk association SET to an existing entity
parameter/literal or NULL use those mappings. Bulk expression SET and mutation of
referenced key attributes are refused.

Business joins deliberately remain **eager/ordinary-loading fallbacks**, including
graphs and explicit fetch joins; their owners are excluded from optimized row/graph
hydration and lazy batching. No business tuple is treated as a lazy-reference ID.
This lot excludes join tables, secondary tables, inheritance, embedded association
paths, derived/unsupported nested identifiers and inverse collections on business
joins. Further mapping boundaries take priority over expanding D10–D21 optimization;
see the detailed roadmap, `BUG.md` and `TCK.md`. Earlier D5/D9–D21 evidence is historical.

### Simple MapsId derived identity (D23, 2026-10-16)

The existing P5 derived-identity mappings now have a strengthened, bounded
simple-parent contract, for annotations and metadata-complete `orm.xml`:

- An owning `@OneToOne` with unnamed `@MapsId` derives a
  dependent's simple `@Id` from the target's matching simple ID.
- An owning `@ManyToOne` or `@OneToOne` with `@MapsId("parentId")`
  derives one direct BASIC component of an `@EmbeddedId`
  from the matching simple target ID. Other key components remain application
  supplied. Mutable and record embedded identifiers are covered.

```java
@Id long id;
@MapsId
@OneToOne(cascade = CascadeType.PERSIST)
@JoinColumn(name = "parent_id")
Parent parent;
```

The relationship owns the shared PK/FK columns; the corresponding scalar ID
attribute is not a second writable column. Initial derived values are copied
from the relationship. Cascaded sequence IDs are available when persist returns;
IDENTITY-derived values are finalized at insert, before PostPersist and caching.
Generated-parent primitive zero placeholders are never registered as real IDs
(an explicitly assigned zero on a nongenerated parent remains valid).
Once registered, changing or clearing the identity-defining relationship or
non-derived key parts is refused, including PreUpdate changes and JPQL/Criteria
bulk identity assignments. Direct changes to relationship-mapped embedded-ID
components are ignored and rederived, as required by their read-only semantics.
Ordinary non-ID updates and dependent-first deletes remain supported.

Loads, incoming foreign keys, JPQL/Criteria paths and entity comparisons, fetch
joins/graphs, cache identity and PostLoad use the same actual tuple. MapsId
associations retain the existing safe eager fallback rather than gaining lazy
subclass/batch eligibility. Generated DDL references actual derived columns and
preserves their underlying target key types.

Unknown/dotted names, incompatible types, named MapsId on a simple ID, inverse
relationships, whole-ID many-to-one mappings (§2.4.2.1), overlapping mappings,
join tables, independently generated dependent
IDs, noninsertable/secondary-table join columns, chained derived targets, nested
target IDs and unmapped nested key remainders are explicitly refused.
Incomplete remaining embedded key values or missing parent identities fail before
the dependent INSERT. The already-covered P5 flat composite-parent examples remain
supported; this lot does **not** claim arbitrary composite/nested/partial mappings.
See D23 in the detailed roadmap and `noether-persistence/TCK.md` for evidence.

### To-one relationships in element collections (D24, 2026-10-16)

Embeddable `@ElementCollection` values may contain owning `@ManyToOne` and
`@OneToOne` relationships to non-polymorphic, single-table, simple-ID entities.
Annotations and metadata-complete ORM XML share the existing mapping/access
pipeline; collection-member association overrides include `value.target` on maps.
Nested embeddables and records use generated accesses too.

The **collection-table row owns the target FK**, not the target or the owner's
entity row. Its columns use the target ID's JDBC type and a database FK; owning
one-to-one columns are unique. List positions and stored map keys remain separate
columns. Elements have no entity identity: changed collections are deleted by
their owner's complete key and rewritten with every occurrence, position and key.
No update/delete matches an embeddable by `equals`/`hashCode`; duplicate bag/list
values and null rows are retained. The new verified map contract uses stored
basic keys; this lot does not extend entity/embeddable-key mapping support.

Collection loading remains LAZY/EAGER as mapped. On initialization, element
references resolve eagerly to actual managed identities, including shared targets.
Persist/flush, generated target IDs, merge, refresh/detach/remove cascades,
reference reassignment, snapshots, owner versions and shared-cache restoration
are covered. Removing an element is not orphan removal.

Basic JPQL/Criteria collection-join attributes, `KEY` and `INDEX` are covered.
Association navigation and selecting whole relationship-bearing embeddable values
are explicitly refused. Fetch joins and graphs use complete ordinary loading;
these owners are not added to optimized row hydration or lazy batching.
Inverse relationships, orphan removal, to-many/nested collections, business/composite/
derived target joins, MapsId inside values, join tables, read-only/shared FK columns
and polymorphic/`JOINED`/`TABLE_PER_CLASS` targets remain bootstrap errors.
Unsupported owner keys/indexes are refused rather than silently dropping state.
The detailed D24 roadmap and `TCK.md` record the exact tests and remaining backlog.

### Inverse element-collection boundary (D25, 2026-10-17)

This follow-up adds **bootstrap validation, not inverse relationship loading**.
Jakarta Persistence 3.2 §2.7 gives embeddables no entity identity: a relationship
back from a referenced entity targets the containing entity, never a collection
element. §2.8 requires relationships *inside* collection embeddables to be owning
many-to-one/one-to-one foreign-key mappings. `ManyToOne` itself has no `mappedBy`;
its entity inverse would be `OneToMany`. The standard dotted `mappedBy` examples
(§11.1.31, §11.1.42) navigate single embedded attributes, not collection elements.

Noether now rejects entity `mappedBy` paths traversing a list/bag/map element
collection (including one under an embedded attribute) **before schema generation**,
and rejects entity relationships targeting an embeddable. Annotation and
metadata-complete XML inverse declarations have the same diagnostics. Ordinary
single-embedded inverse paths remain supported.

Collection-row uniqueness does not prove one-to-one cardinality between containing
entities: an owner can have several rows referencing different targets; bags can
also repeat a target. Positions/map keys do not turn those values into entities.
No inverse FK/column, invented element identity, inverse lifecycle/query behavior
or orphan removal is introduced. A future inverse **entity-owner** contract needs
separate normative/cardinality analysis and tests; it is not claimed illegal
merely because collection traversal is unsupported. D24 owning-side behavior and
all earlier evidence remain unchanged. See D25 and `TCK.md` for verification.

### Secondary-table business-key to-one joins (D26, 2026-10-17)

D26 extends D22 to **one** immutable, non-null, unique BASIC column in a target's
declared secondary table, for owning `ManyToOne` and `OneToOne`. Annotation
(`SecondaryTable`/`SecondaryTables`) and metadata-complete ORM XML share the
existing model, generated field/property accesses and converters.

Persistence 3.2 §11.1.26 explicitly makes non-primary-key references optional
and nonportable between providers. Table 24 locates the referenced column in
the target entity's table; §11.1.47 allows that entity's state across primary
and secondary tables. Noether resolves an unambiguous BASIC column's declared
table; `JoinColumn.table` still names the **owner's FK table**, never the target.
Ambiguous names are refused rather than guessing a table.

The target has a single-column primary key and a single-column secondary PK
join (explicitly renamed or defaulted). Uniqueness must be declared by
`Column.unique` or that secondary table's exact `uniqueConstraints`; a primary
table constraint cannot substitute. DDL references the actual secondary unique
column. Reads resolve its secondary PK join back to actual JPA identity; writes,
generated identities, reassignment/null, deletion, JPQL/Criteria, bulk association
SET and cache identity retain the ordinary paths. Fetch graphs/joins remain
complete eager fallbacks, not new lazy/batch/SQL-collector eligibility.

Composite secondary business keys/PK joins, owner secondary-table FKs,
inheritance, join tables, embedded/derived business associations, mutable or
ambiguous keys and inverse business collections remain refused. D22's original
primary-table scope and all D5/D9–D25 deliveries remain historical; the current
prioritized backlog and independently checked evidence are in the roadmaps
and `noether-persistence/TCK.md`.

### Multiple IdClass parents in element collections (D35, 2026-10-18)

Following D29–D32's composite/derived target extensions, element-value owning
to-ones can now reference an `@IdClass` target with multiple relationship-valued
identifier parts. Each parent has a simple, non-derived ID and is non-polymorphic;
other identifier parts are BASIC. The collection-row FK preserves the complete
physical tuple and resolves one managed target identity, even when two parent
roles point to the same entity type. Annotation and metadata-complete owner/value
XML share the existing planner; relationship-valued target IDs remain annotation-mapped.

JPQL/Criteria navigation and projections, duplicate/null references, collection
rewrites, merge and owner removal retain ordinary loading semantics. Composite/
chained parents, nested keys, business/join-table/polymorphic mappings and broader
fetch/lazy/batch optimization remain outside this lot. Exact scope and regression
evidence are in the roadmaps and `noether-persistence/TCK.md`.

### Composite IdClass parents in element collections (D36, 2026-10-18)

D36 extends D32/D35 target identifier relationships to non-polymorphic parents
with direct all-BASIC `@EmbeddedId` or `@IdClass` keys. Simple and composite
parents may coexist in the target's IdClass. Its relationship-named fields use
the parent's key type; the collection-row FK flattens every physical parent
key part plus the target's BASIC parts, preserving identity with reordered
explicit joins or default join columns.

Annotation and metadata-complete owner/value XML retain ordinary managed
identity, query projection/navigation, merge/refresh, collection rewrites and
shared-cache behavior. Relationship-valued target identifiers remain
annotation-mapped. Nested/non-BASIC/chained-derived parents and broader
business/join-table/polymorphic or fetch/lazy/batch support are not added.
The roadmaps and `noether-persistence/TCK.md` record the exact regression scope.

The project uses the Vidocq disjunctive triple licence:
EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later. See `LICENSE`, `licenses/` and `NOTICE`;
the bundled Jakarta ORM schema retains its original upstream licence.

Noether has no independent published release yet. `0.4.0-SNAPSHOT` is the
current development version; older Mansart versions in historical evidence
are not Noether releases.
