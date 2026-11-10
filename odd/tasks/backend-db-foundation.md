Objective: backend foundation (Spring Boot deps + PostgreSQL schema via Flyway) for Proyecto Nuclear VII.
Branch: feat/backend-db-foundation | Delivery: ask-on-risk | Test runner: `./gradlew test` (JUnit 5, embedded PostgreSQL via zonky; Docker not usable on this machine)

## Specs
- S1: Traditional code (no low-code). Teams of 3. Stack per team docs (Spring Boot, PostgreSQL, Flyway, Spring Security + JWT, WebSocket).
- S2: Database `nuclear_vii` (UTF8) with three schemas: `operacion` (20 tables), `integracion` (3), `analitica` (10) = 33 tables.
- S3: Tables, columns, types, defaults, PK/FK/UNIQUE/CHECK and indexes follow the four dictionaries in `documentation/` (operativo y reproducción, acceso control y privacidad, eventos y procesamiento, modelo analítico).
- S4: Integrity rules from the report: one active session per establishment, one active request per song+session (partial unique indexes), composite FKs (vote/request/participation share session and song), playback origin exclusive (request XOR default item), one open playback attempt per session, lote reconciliation CHECK.
- S5: Create tables with Flyway migrations. Finish Spring Boot dependencies needed for the project.
- S6 (assumption): Java toolchain stays 25 (scaffold default) although docs mention Java 21.

## Tasks
- T1 (S5): Dependencies + application config + docker-compose for local PostgreSQL | inline | done | a258f59
- T2 (S2-S4): Flyway migrations V1..V5 + schema integration tests (RED first) | inline | done | 8f7a2e2 (RED: 15/16 failed; GREEN: 16/16 pass, 5 migrations applied, 33 tables, 252 columns)

## Log
- L1 (2026-11-10): "Y vamos a iniciar a configurar el proyecto iniciando por el backend creando la base de datos y configuracion basica de dependencias ... crea las tablas con flyway, tambien termina de configurar todas las dependencias de springboot necesarias para el proyecto"
- L3: Removed scaffold plugin `org.hibernate.orm` 7.4.13.Final (marker not published; its enhancement block was empty).
- L4: Tests run on zonky embedded PostgreSQL 14.15, docs reference PG 18; nothing used is version-specific. Not yet run against a real PG 18 (docker-compose.yml provided).
- L5: Pending (not requested yet): DB roles/privileges per schema, JPA entities, security config, JWT secret management.
- L2: Docker socket is permission-denied and no local PostgreSQL server exists; verification uses embedded PostgreSQL (zonky) in tests.
