# YouSong analytics pipeline

Fills `analitica.*` from the outbox (`integracion.evento_outbox`) and the operational state (`operacion.*`),
following "Diccionario del modelo analítico" and "Diccionario de eventos y procesamiento".
Python 3.12, PySpark 4.1.1 (local mode) on OpenJDK 17, Parquet, PostgreSQL through psycopg 3.
Spark 4.1.1 documents "Java 17/21, Scala 2.13, Python 3.10+", so the image pins that combination; the host
Python is not used.

## Run

All configuration comes from environment variables. Nothing is read from or written to an env file.

| Variable | Required | Meaning |
| --- | --- | --- |
| `ANALYTICS_DB_URL` | yes | `postgresql://host:port/db` (a `jdbc:postgresql://...` URL is accepted too) |
| `ANALYTICS_DB_USER` | yes | Database user |
| `ANALYTICS_DB_PASSWORD` | yes | Database password |
| `ANALYTICS_HMAC_KEY` | yes | Private key of the participation pseudonym, at least 32 bytes |
| `ANALYTICS_PARQUET_DIR` | yes (image sets `/data/parquet`) | Private Parquet directory |
| `ANALYTICS_INTERVAL_SECONDS` | no, 300 | Loop period |
| `ANALYTICS_LAG_SECONDS` | no, 30 | Events newer than `now - lag` wait for the next cycle (late commits) |

From `yousong-backend/analytics`, with the backend database published on the host:

```bash
export ANALYTICS_DB_URL=postgresql://host.docker.internal:5432/nuclear_vii
export ANALYTICS_DB_USER=...            # a role that can read operacion/integracion and write analitica/integracion
export ANALYTICS_DB_PASSWORD=...
export ANALYTICS_HMAC_KEY="$(openssl rand -hex 32)"   # generate once and keep it in your secret store

docker compose build
docker compose run --rm analytics once   # one cycle, exit code 0 when published or nothing to publish
docker compose up -d analytics           # loop: a cycle every 5 minutes (also picks up closed sessions)
docker compose logs -f analytics
docker compose down                      # keeps the Parquet volume; add -v to delete it
```

Without compose: `docker build --target runtime -t yousong-analytics .` then
`docker run --rm --add-host host.docker.internal:host-gateway -e ANALYTICS_DB_URL -e ANALYTICS_DB_USER -e ANALYTICS_DB_PASSWORD -e ANALYTICS_HMAC_KEY -v yousong_analytics_parquet:/data/parquet yousong-analytics once`.

Keep `ANALYTICS_HMAC_KEY` stable: a new key yields new pseudonyms, so the same participation would be published
again as another row of `dim_participacion`.

## Tests

```bash
analytics/scripts/test.sh          # from the yousong-backend root
```

It builds the test image, starts a throwaway `postgres:18` on a free local port and a private Docker network,
applies the backend's Flyway SQL (`src/main/resources/db/migration`), runs `pytest` (unit tests for contracts,
pseudonyms, validation, transforms and reconciliation, plus the end-to-end suite) and removes the database.
Extra arguments go to pytest (`analytics/scripts/test.sh -k e2e`).

## What one cycle does

1. Takes a PostgreSQL advisory lock (one run at a time) and closes batches left open by a crashed run as `FALLIDO`.
2. In one `REPEATABLE READ READ ONLY` transaction reads the outbox range `[rango_desde, rango_hasta)` with
   `ambito IN ('B','O')` (restricted `R` events never leave the database) and the operational state of the
   sessions to publish: those touched by an event of the range, still in operation, never published, closed after
   their last cut, or with requests/attempts finalized after it. `rango_desde` is the `rango_hasta` of the last
   finished batch; `rango_hasta = now() - ANALYTICS_LAG_SECONDS`.
3. Replaces operational participation ids by `participante_ref` before anything reaches Spark or Parquet, records
   `integracion.lote_analitico` as `EN_CURSO` (`leidos = pendientes = events read`) and writes the raw Parquet.
4. Reads the Parquet back and classifies every record once: duplicate delivery (same `event_id`), contract
   violation, reference to a fact missing from its session, duplicate fact (same business identity, e.g.
   `vote_id`), or accepted. The batch becomes `VALIDADO` with `leidos = aceptados + duplicados + rechazados` and
   `pendientes = 0`; one `integracion.rechazo_ingesta` row per record and rule, without payload values. A sanitized
   copy (identity, rule, path) goes to `quarantine/lote=<id>`.
5. Rebuilds dimensions and facts of each session from the snapshot, writes them to `curated/lote=<id>`, reads them
   back and re-checks nulls, domains and the request/playback state reconciliation. A session that does not
   reconcile is not published in this batch (its previous publication stays) and gets `rechazo_ingesta` rows with
   `event_id` NULL and a `CONCILIACION_*` rule.
6. Publishes in ONE transaction: upserts dimensions, replaces the session's facts, moves
   `analitica.publicacion_sesion` (`version + 1`, `publicada_at`, `corte_eventos_at = rango_hasta`) and sets the batch
   to `PUBLICADO`. Any failure rolls all of it back, marks the batch `FALLIDO` and the previous publication stays
   visible. With nothing to publish the batch ends `VALIDADO`.

Re-running is idempotent: facts keep the operational ids, analytic keys are UUIDv5 of their business `UNIQUE`, and
a session is rebuilt as a whole, so the same state always yields the same rows. A closed session already published
after its close is not published again unless something in it changes.

## Identifiers written (for readers of `analitica.*`)

| Column | Value |
| --- | --- |
| `dim_establecimiento.id` | `operacion.establecimiento.id` |
| `dim_sesion.id`, every `sesion_id`, `publicacion_sesion.sesion_id` | `operacion.sesion.id` |
| `dim_lista.id`, `hecho_reproduccion.lista_id` | `operacion.lista_defecto.id` (of the item that played, origin DEFECTO) |
| `dim_cancion.cancion_ref` | `operacion.cancion.id` (resolve titles with `JOIN operacion.cancion c ON c.id = d.cancion_ref`) |
| `dim_cancion.id`, facts' `cancion_id` | analytic key `uuid5(ns, "dim_cancion:<establecimiento_id>:<cancion_ref>")`, never the operational song id |
| `dim_participacion.participante_ref` | `HMAC-SHA-256(key, "<sesion_id>:<participacion_id>")`, 64 hex |
| `dim_participacion.id`, facts' `participacion_id` | analytic key `uuid5(ns, "dim_participacion:<sesion_id>:<participante_ref>")`, never the operational participation id |
| `hecho_solicitud.solicitud_ref`, `hecho_voto.solicitud_ref`, `hecho_reproduccion.solicitud_ref` | `operacion.solicitud.id` |
| `hecho_voto.voto_ref` | `operacion.voto.id` |
| `hecho_reproduccion.reproduccion_ref` | `operacion.reproduccion.id` |
| `dim_tiempo.id`, facts' `tiempo_id` | `YYYYMMDDHH` of the local hour (establishment time zone) |

Existing rows keep their analytic id: the publisher upserts on the business `UNIQUE` and remaps facts to the stored
id. `ns` is fixed in `yousong_analytics/ids.py`.

## Definitions applied

- `hecho_solicitud.estado` is the operational state (PENDIENTE, RESERVADA, REPRODUCIENDO, ATENDIDA, SALTADA,
  FALLIDA, BLOQUEADA, NO_ATENDIDA_CIERRE). `primer_inicio_at` is the earliest `iniciada_at` among its attempts;
  `espera_real_s = primer_inicio_at - creada_at` (3 decimals), NULL when it never started (never zero).
- `hecho_reproduccion.resultado` is COMPLETADA, SALTADA, FALLIDA, INTERRUMPIDA_CIERRE or NULL while in progress.
  `iniciada_at` is copied as observed: a failure before PLAYING keeps it NULL.
- Reconciliation (a session failing any rule is not published): ATENDIDA needs a COMPLETADA attempt; the latest
  attempt explains the terminal state (COMPLETADA -> ATENDIDA, SALTADA -> SALTADA, INTERRUMPIDA_CIERRE ->
  NO_ATENDIDA_CIERRE, FALLIDA never ATENDIDA); REPRODUCIENDO has an open attempt; a CERRADA session has no open
  attempt; plus domain re-checks (origin exclusivity, COMPLETADA requires a start, result iff end, wait >= 0).
- Genre: `dim_cancion.genero_propio` and `fuente_clasificacion` copy `operacion.cancion`. No title, thumbnail,
  videoId or provider duration is copied.
- Local hour and ISO weekday use the establishment's `zona_horaria`; a night that crosses midnight keeps its
  `dim_sesion.fecha_operativa`.
- Participation counts every `operacion.participacion` of the session; channel from its device, or from its
  consent record when retention already unlinked the device.

## Contracts

`yousong_analytics/contracts.py` validates schema_version 1 of every event type the backend emits, with closed
payloads (unknown properties rejected) and a 64 KiB limit. Field names follow the backend code, which differs from
the dictionary catalogue in places: `playback.started` uses `playback_id`, `origin`, `list_item_id`;
`playback.finished` uses `playback_id` and `resultado` (`COMPLETADA`, ...); `queue.changed` is emitted with scope
B; `moderation.changed` and `request.removed` are not in the catalogue. The backend does not emit E04
`participation.started` nor E12 `playback.prepared`, so participation and attempts come from the operational
reconciliation. Date-times are accepted as ISO-8601 strings or epoch numbers.

## Out of scope

- **90-day retention.** The dictionary requires deleting the linkable detail 90 days after the session closes, also
  from Parquet, quarantine and analytic tables, and keeping only reviewed aggregates up to 12 months. This pipeline
  does not delete anything. A retention job must remove, for sessions closed more than 90 days ago:
  `analitica.hecho_voto`, `hecho_reproduccion`, `hecho_solicitud`, `dim_participacion`, `publicacion_sesion`
  rows; the `raw/` and `curated/` Parquet of the batches that carried them; and `integracion.rechazo_ingesta` with
  its `quarantine/` files.
- Suppressing cells under five participants is the reader's job (the published model keeps detail).
- E24-E26 (`pipeline.run_finished`, `data.quality_evaluated`, `metrics.published`) are not emitted: the pipeline
  does not write to the outbox; `integracion.lote_analitico` and `rechazo_ingesta` carry the same information.
- `evento_outbox.publicado_at` is not modified (the outbox delivery worker owns it); the consumed range is tracked
  by `lote_analitico.rango_desde/rango_hasta`.
