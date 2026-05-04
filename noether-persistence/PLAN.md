# mansart-persistence — Plan (esquisse)

Implémentation **Jakarta Persistence 3.2** (JPA). Conception détaillée **différée** jusqu'à la fin du jalon M4 de `mansart-jakarta-data` — on veut d'abord stabiliser la SPI dialecte et le métamodèle.

## Principes (déjà fixés)

- Réutilisation de `mansart-data-dialect-spi`, `mansart-data-dialect-h2`, `mansart-data-dialect-postgresql`.
- Réutilisation du métamodèle statique généré (`Book_` JPA-standard, voir décision ouverte n°2 du plan Data).
- Pas de chargement bytecode runtime — l'entity-enhancement (lazy, dirty tracking) sera fait par APT au compile-time, pas par un agent Java.
- Cache de second niveau optionnel et pluggable (SPI dédiée, Caffeine-compatible mais pas dépendance directe).
- TCK Jakarta Persistence 3.2 hors reactor (même contrainte ShrinkWrap).

## À planifier en détail plus tard

- Stratégie d'enhancement (lazy fields, dirty tracking, cascades).
- Modèle de transaction (intégration `jakarta.transaction.UserTransaction` + JTA).
- Cache L1 par `EntityManager`.
- JPQL parser (sera la version étendue du parser JDQL fait en M5 de Data).
- Critères API (`CriteriaBuilder`).
- Listeners JPA (`@PrePersist`, etc.).

→ Document à reprendre à l'issue de M4. Pour l'instant, le module reste un placeholder.
