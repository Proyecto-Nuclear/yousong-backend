package cue.edu.co.yousongbackend.database;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.PostgresIntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V7 on a database that already has participants: their credentials get the participation of their device
 * (each legacy device had exactly one), and their devices stay unidentifiable (no device secret).
 */
class DispositivoCredencialMigrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String url;

    @Test
    void existingParticipantCredentialsAreLinkedToTheParticipationOfTheirDevice() {
        String nombre = "mig_v7_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + nombre);
        try {
            DriverManagerDataSource fuente = new DriverManagerDataSource(
                    url.replaceFirst("/postgres(\\?|$)", "/" + nombre + "$1"), "postgres", "postgres");
            Flyway.configure().dataSource(fuente).locations("classpath:db/migration").target("6").load().migrate();
            JdbcTemplate antigua = new JdbcTemplate(fuente);

            UUID est = antigua.queryForObject("INSERT INTO operacion.establecimiento (id, nombre, qr_slug) "
                    + "VALUES (gen_random_uuid(), 'Local', 'local-v7') RETURNING id", UUID.class);
            UUID lista = antigua.queryForObject("INSERT INTO operacion.lista_defecto (id, establecimiento_id, nombre, "
                    + "version) VALUES (gen_random_uuid(), ?, 'General', 1) RETURNING id", UUID.class, est);
            UUID sesion = antigua.queryForObject("INSERT INTO operacion.sesion (id, establecimiento_id, lista_id, "
                    + "fecha_operativa, estado) VALUES (gen_random_uuid(), ?, ?, current_date, 'ABIERTA') RETURNING id",
                    UUID.class, est, lista);
            UUID dispositivo = antigua.queryForObject("INSERT INTO operacion.dispositivo (id, canal, expira_at) "
                    + "VALUES (gen_random_uuid(), 'WEB', now() + interval '30 days') RETURNING id", UUID.class);
            UUID participacion = antigua.queryForObject("INSERT INTO operacion.participacion (id, sesion_id, "
                    + "dispositivo_id, seudonimo) VALUES (gen_random_uuid(), ?, ?, 'p-aaaaaa') RETURNING id",
                    UUID.class, sesion, dispositivo);
            UUID credencial = antigua.queryForObject("INSERT INTO operacion.sesion_acceso (id, dispositivo_id, "
                    + "familia_id, refresh_hash, expira_at) VALUES (gen_random_uuid(), ?, gen_random_uuid(), "
                    + "repeat('b', 64), now() + interval '1 day') RETURNING id", UUID.class, dispositivo);

            Flyway.configure().dataSource(fuente).locations("classpath:db/migration").load().migrate();

            assertThat(antigua.queryForObject("SELECT participacion_id FROM operacion.sesion_acceso WHERE id = ?",
                    UUID.class, credencial)).isEqualTo(participacion);
            assertThat(antigua.queryForObject("SELECT credencial_hash FROM operacion.dispositivo WHERE id = ?",
                    String.class, dispositivo)).isNull();
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS " + nombre + " WITH (FORCE)");
        }
    }
}
