# ADR-0013: Migration numbers are reserved per module, with Flyway out-of-order enabled

- Status: Accepted
- Date: 2026-11-25

## Context

The database schema is built from versioned Flyway migrations. Several modules own part of the schema, and the modules are delivered in different phases. Numbers were reserved up front so that each module keeps a stable, recognisable file name: V1 catalog, V2 identity and plans, V3 notifications and outbox, V4 FX rates, V5 form registry and upper-limit notes and V6 alert ordering.

V5 was delivered before V3 and V4. A database that is already at V5 would reject V3 and V4 as out of order under Flyway's default behaviour.

## Decision

- Migration numbers are reserved per module: V1 catalog, V2 identity and plans, V3 notifications and outbox, V4 FX rates, V5 form registry and upper-limit notes, V6 alert ordering columns and subscription boundary version, V7 billing provider and V8 paid horizon per provider subscription (Phase 8, ADR-0014; V7 depends on V2 only, V8 on V7; both delivered after V6). A module never uses a number reserved for another module, and a released migration is never edited.
- Flyway runs with `spring.flyway.out-of-order: true`, so a database at V5 applies V3 and V4 when they arrive.
- Validation stays on (`validate-on-migrate: true`): checksums, missing and failed migrations are still errors on every start. Only the version order of pending migrations is relaxed.

## Consequences

- File names stay tied to their module, independent of delivery order.
- The V3 and V4 migrations must not depend on objects created by V5, and V5 must not depend on them, because their relative order differs between fresh and existing databases.
- A fresh database applies V1 to V6 in numeric order; an existing database at V5 applies V3 and V4 afterwards. Both end in the same schema, which a migration test checks once V3 and V4 exist.

## Alternatives considered

- **Renumber V5 to the next free number after V2.** Rejected: it breaks the module-to-number mapping and changes the history of a delivered migration.
- **Hold V5 back until V3 and V4 are delivered.** Rejected: it blocks the catalog work on unrelated modules.
- **Create V3 and V4 as empty placeholders.** Rejected: placeholder migrations carry no meaning and would have to be replaced, which changes checksums.
