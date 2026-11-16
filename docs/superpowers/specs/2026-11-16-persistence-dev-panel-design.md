# Persistence panel of the Vidocq dev console: design

Date: 2026-11-16. Requested by Yann (brainstorm of 2026-11-11, answers relayed by the VIDOCQ session on 2026-11-16).

## Intent

A developer running an application on Noether under `vidocq:dev` wants to see and use their persistence units without
leaving the dev console. They want to see the entity model as a graph, run JPQL with completion, page through the rows
of an entity, compare the indexes they declared with the ones in the database, and get the DDL that Noether would run.
The panel does for Noether what the Mansart Data panel does for Jakarta Data.

## Layout (layout A, chosen by Yann)

A single **Persistence** panel. A *Persistence unit* dropdown at its top selects the unit every sub-tab works on.

| Sub-tab | Shows |
|---|---|
| Monitoring | The unit's boot facts (data source or pool, JTA or resource-local, schema action), and counts kept in memory: entities, embeddables, declared named queries, declared indexes. Nothing touches the database. |
| Model | The entities, embeddables and mapped superclasses as a graph. Relations show their cardinality, their owning side and the inheritance. Clicking an entity shows its table, columns and attributes. |
| JPQL | A query editor with completion of entities, attributes, paths, keywords and `:parameters` (the editor of Mansart Data's JDQL tab). Parameters are given as JSON. Results come as rows, with a CSV export. SELECT, UPDATE and DELETE are all allowed, and the writes run in a transaction, as Mansart Data's writes do. |
| Explore | Pick an entity and page through its rows: 20, 50 or 100 per page, sorted by id, read-only, with a CSV export of the page. A right-click menu on the selected rows (multi-select) pre-fills the JPQL tab with an UPDATE or a DELETE, `WHERE e.id IN (...)`. The user reviews and runs it there, so Explore itself writes nothing. |
| Indexes | The declared indexes (`@Table(indexes)`, unique constraints, unique columns, foreign keys), beside the indexes read from the database (JDBC metadata, on a click), and the diff between the two: missing in the database, extra in the database, different columns or uniqueness. |
| DDL | The create script, and on demand the drop script, that Noether's schema generation would run for the unit. Generated on a click, never executed, with a Download button. |

"Number of queries" means the declared named queries, read from memory. Noether gets no counter of executed queries
for now.

## Rules inherited from the console (Vidocq `dev-console-panels.adoc`)

- `sample()` reads memory only: every value that needs the database is an action, run on a click.
- Nothing of the application outlives a reload: the panel keeps names (strings), never an application `Class`.
- Actions run in a `dev` launch only.
- The panel lives in a `-dev` module (`vidocq-runtime-noether-persistence-extension-dev`), which only `vidocq:dev`
  adds and which is never packaged.

## Parts, in delivery order

1. **Noether: a description of a running unit** (this repository).
   - `UnitDescription` is a read-only snapshot of the unit's entities, embeddables, named queries and the tables that
     schema generation maps, with their columns, keys, indexes, unique constraints and foreign keys.
   - `SchemaScripts` holds the create and drop scripts, rendered for the unit's database and never executed.
   - Both are obtained with `EntityManagerFactory.unwrap`, the standard way to reach what a provider offers beyond
     the specification. They live in the exported package `io.vidocq.noether.jpa.core.spi` and hold strings only.
   - The JPA `Metamodel` lacks tables, columns, owning sides and declared indexes, and Noether exports no other
     package.
2. **Vidocq: the `noether-persistence` startup-report section.** The Noether runtime extension writes the boot facts
   of each unit. A live panel needs a section of the same id, and the extension writes none today.
3. **Vidocq console: a panel-level selector.** A panel declares one choice, rendered once above its sub-tabs. Every
   action receives the selected value, and the Monitoring values follow it. Today a unit can only be a field repeated
   in each form.
4. **Vidocq console: a graph view** for an action result (the Model sub-tab), and multi-row selection with a context
   menu that fills another action's form (Explore to JPQL, the way a replay cell already fills a form). Today the page
   draws charts and tables only.
5. **Vidocq: the `-dev` module** with the six sub-tabs, on top of parts 1 to 4.

Each part is one pull request, merged in this order. The `<qualifier>` / `<scope>` binding of `persistence.xml` is out
of scope.

## Non-goals

- No counter of executed queries in Noether.
- No write from Explore, and no write from the Model sub-tab.
- No schema change applied from the Indexes or DDL sub-tabs: they show and download only.
