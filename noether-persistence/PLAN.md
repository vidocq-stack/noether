# mansart-persistence — Plan (outline)

Implementation of **Jakarta Persistence 3.2** (JPA). Detailed design **deferred** until the end of milestone M4 of `mansart-jakarta-data` — we first want to stabilize the dialect SPI and metamodel.

## Principles (already fixed)

- Reuse of `mansart-data-dialect-spi`, `mansart-data-dialect-h2`, `mansart-data-dialect-postgresql`.
- Reuse of generated static metamodel (`Book_` JPA-standard, see open decision #2 of the Data plan).
- No runtime bytecode loading — entity-enhancement (lazy, dirty tracking) will be done by APT at compile-time, not by a Java agent.
- Optional and pluggable second-level cache (dedicated SPI, Caffeine-compatible but not a direct dependency).
- Jakarta Persistence 3.2 TCK outside reactor (same ShrinkWrap constraint).

## To plan in detail later

- Enhancement strategy (lazy fields, dirty tracking, cascades).
- Transaction model (integration `jakarta.transaction.UserTransaction` + JTA).
- L1 cache per `EntityManager`.
- JPQL parser (will be the extended version of the JDQL parser made in M5 of Data).
- Criteria API (`CriteriaBuilder`).
- JPA listeners (`@PrePersist`, etc.).

→ Document to revisit after M4 completion. For now, the module remains a placeholder.
