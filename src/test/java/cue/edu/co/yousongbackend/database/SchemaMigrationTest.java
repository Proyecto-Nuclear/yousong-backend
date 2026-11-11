package cue.edu.co.yousongbackend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cue.edu.co.yousongbackend.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Verifies the Flyway-built schema against the data dictionaries (33 tables, 253 fields). */
@Transactional
class SchemaMigrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    // ---------- structure ----------

    @Test
    void createsThirtyThreeTablesAcrossThreeSchemas() {
        assertThat(tableCount("operacion")).isEqualTo(20);
        assertThat(tableCount("integracion")).isEqualTo(3);
        assertThat(tableCount("analitica")).isEqualTo(10);
    }

    @Test
    void createsTwoHundredFiftyThreeColumns() {
        Integer columns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE t.table_type = 'BASE TABLE'
                  AND c.table_schema IN ('operacion', 'integracion', 'analitica')
                """, Integer.class);
        assertThat(columns).isEqualTo(253);
    }

    // ---------- integrity rules ----------

    @Test
    void rejectsSecondOpenSessionForTheSameEstablishment() {
        Fixture f = fixture();
        assertThatThrownBy(() -> insertSesion(f.establecimiento, f.lista, "ABIERTA"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsNewSessionAfterTheCurrentOneIsClosed() {
        Fixture f = fixture();
        jdbc.update("UPDATE operacion.sesion SET estado = 'CERRADA', cerrada_at = now() WHERE id = ?", f.sesion);
        insertSesion(f.establecimiento, f.lista, "ABIERTA");
    }

    @Test
    void rejectsDuplicateVoteFromSameParticipationForSameSong() {
        Fixture f = fixture();
        assertThatThrownBy(() -> insertVoto(f.solicitud, f.participacion, f.sesion, f.cancion, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondActiveRequestForSameSongInSession() {
        Fixture f = fixture();
        assertThatThrownBy(() -> insertSolicitud(f.sesion, f.cancion, f.participacion, "PENDIENTE", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsRequestingAgainOnceThePreviousCycleIsFinished() {
        Fixture f = fixture();
        jdbc.update("UPDATE operacion.solicitud SET estado = 'ATENDIDA', finalizada_at = now() WHERE id = ?", f.solicitud);
        insertSolicitud(f.sesion, f.cancion, f.participacion, "PENDIENTE", 2);
    }

    @Test
    void rejectsVoteWhoseSessionDiffersFromTheRequestSession() {
        Fixture f = fixture();
        jdbc.update("UPDATE operacion.sesion SET estado = 'CERRADA', cerrada_at = now() WHERE id = ?", f.sesion);
        UUID otraSesion = insertSesion(f.establecimiento, f.lista, "ABIERTA");
        UUID otraParticipacion = insertParticipacion(otraSesion, "p-other");
        assertThatThrownBy(() -> insertVoto(f.solicitud, otraParticipacion, otraSesion, f.cancion, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsRequestPlaybackWithoutRequest() {
        Fixture f = fixture();
        UUID estacion = insertEstacion(f.establecimiento);
        assertThatThrownBy(() -> insertReproduccion(f, estacion, "SOLICITUD", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDefaultPlaybackLinkedToARequest() {
        Fixture f = fixture();
        UUID estacion = insertEstacion(f.establecimiento);
        assertThatThrownBy(() -> insertReproduccion(f, estacion, "DEFECTO", f.solicitud))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondOpenPlaybackAttemptInSession() {
        Fixture f = fixture();
        UUID estacion = insertEstacion(f.establecimiento);
        insertReproduccion(f, estacion, "SOLICITUD", f.solicitud);
        assertThatThrownBy(() -> insertReproduccion(f, estacion, "SOLICITUD", f.solicitud))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCompletedPlaybackWithoutStart() {
        Fixture f = fixture();
        UUID estacion = insertEstacion(f.establecimiento);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO operacion.reproduccion
                  (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id, preparada_at, terminada_at, resultado)
                VALUES (gen_random_uuid(), ?, ?, ?, 'SOLICITUD', ?, now(), now(), 'COMPLETADA')
                """, f.sesion, f.cancion, estacion, f.solicitud))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsBatchWhoseCountersDoNotReconcile() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO integracion.lote_analitico
                  (id, rango_desde, rango_hasta, leidos, aceptados, duplicados, rechazados, pendientes)
                VALUES (gen_random_uuid(), now() - interval '5 minutes', now(), 10, 5, 1, 1, 1)
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsFinalBatchStateWithPendingRecords() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO integracion.lote_analitico
                  (id, rango_desde, rango_hasta, estado, leidos, aceptados, pendientes)
                VALUES (gen_random_uuid(), now() - interval '5 minutes', now(), 'PUBLICADO', 3, 2, 1)
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSessionAccessCredentialWithBothOrNeitherOwner() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO operacion.sesion_acceso (id, familia_id, refresh_hash, expira_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), repeat('a', 64), now() + interval '1 day')
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- helpers ----------

    private int tableCount(String schema) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                """, Integer.class, schema);
        return n == null ? 0 : n;
    }

    private record Fixture(UUID establecimiento, UUID lista, UUID sesion, UUID participacion,
                           UUID cancion, UUID solicitud) {}

    private Fixture fixture() {
        UUID est = jdbc.queryForObject("""
                INSERT INTO operacion.establecimiento (id, nombre, qr_slug)
                VALUES (gen_random_uuid(), 'Local', 'local-' || substr(gen_random_uuid()::text, 1, 8))
                RETURNING id
                """, UUID.class);
        UUID lista = jdbc.queryForObject("""
                INSERT INTO operacion.lista_defecto (id, establecimiento_id, nombre, version)
                VALUES (gen_random_uuid(), ?, 'General', 1) RETURNING id
                """, UUID.class, est);
        UUID sesion = insertSesion(est, lista, "ABIERTA");
        UUID part = insertParticipacion(sesion, "p-one");
        UUID cancion = jdbc.queryForObject("""
                INSERT INTO operacion.cancion (id, youtube_video_id)
                VALUES (gen_random_uuid(), substr(gen_random_uuid()::text, 1, 11)) RETURNING id
                """, UUID.class);
        UUID solicitud = insertSolicitud(sesion, cancion, part, "PENDIENTE", 1);
        insertVoto(solicitud, part, sesion, cancion, true);
        return new Fixture(est, lista, sesion, part, cancion, solicitud);
    }

    private UUID insertSesion(UUID est, UUID lista, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO operacion.sesion (id, establecimiento_id, lista_id, fecha_operativa, estado)
                VALUES (gen_random_uuid(), ?, ?, current_date, ?) RETURNING id
                """, UUID.class, est, lista, estado);
    }

    private UUID insertParticipacion(UUID sesion, String seudonimo) {
        return jdbc.queryForObject("""
                INSERT INTO operacion.participacion (id, sesion_id, seudonimo)
                VALUES (gen_random_uuid(), ?, ?) RETURNING id
                """, UUID.class, sesion, seudonimo);
    }

    private UUID insertSolicitud(UUID sesion, UUID cancion, UUID creador, String estado, int ciclo) {
        return jdbc.queryForObject("""
                INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, estado, ciclo)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, sesion, cancion, creador, estado, ciclo);
    }

    private void insertVoto(UUID solicitud, UUID participacion, UUID sesion, UUID cancion, boolean inicial) {
        jdbc.update("""
                INSERT INTO operacion.voto (id, solicitud_id, participacion_id, sesion_id, cancion_id, es_inicial)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)
                """, solicitud, participacion, sesion, cancion, inicial);
    }

    private UUID insertEstacion(UUID establecimiento) {
        return jdbc.queryForObject("""
                INSERT INTO operacion.estacion (id, establecimiento_id, nombre)
                VALUES (gen_random_uuid(), ?, 'Cabina') RETURNING id
                """, UUID.class, establecimiento);
    }

    private void insertReproduccion(Fixture f, UUID estacion, String origen, UUID solicitud) {
        jdbc.update("""
                INSERT INTO operacion.reproduccion
                  (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)
                """, f.sesion, f.cancion, estacion, origen, solicitud);
    }
}
