package cue.edu.co.yousongbackend.database;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V9 is additive: the system pause and maintenance flags are NOT NULL with a false default, so establishments
 * created before it keep working exactly as before (not paused, not in maintenance).
 */
class EstablecimientoEstadoOperativoMigrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String url;

    @Test
    void existingEstablishmentsAreNeitherPausedNorInMaintenance() {
        String nombre = "mig_v9_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + nombre);
        try {
            DriverManagerDataSource fuente = new DriverManagerDataSource(
                    url.replaceFirst("/postgres(\\?|$)", "/" + nombre + "$1"), "postgres", "postgres");
            Flyway.configure().dataSource(fuente).locations("classpath:db/migration").target("8").load().migrate();
            JdbcTemplate antigua = new JdbcTemplate(fuente);
            UUID previo = antigua.queryForObject("INSERT INTO operacion.establecimiento (id, nombre, qr_slug) "
                    + "VALUES (gen_random_uuid(), 'Previo', 'previo-v9') RETURNING id", UUID.class);

            Flyway.configure().dataSource(fuente).locations("classpath:db/migration").load().migrate();

            Map<String, Object> fila = antigua.queryForMap("SELECT pausado, en_mantenimiento "
                    + "FROM operacion.establecimiento WHERE id = ?", previo);
            assertThat(fila).containsEntry("pausado", false).containsEntry("en_mantenimiento", false);
            UUID nuevo = antigua.queryForObject("INSERT INTO operacion.establecimiento (id, nombre, qr_slug) "
                    + "VALUES (gen_random_uuid(), 'Nuevo', 'nuevo-v9') RETURNING id", UUID.class);
            assertThat(antigua.queryForMap("SELECT pausado, en_mantenimiento FROM operacion.establecimiento "
                    + "WHERE id = ?", nuevo)).containsEntry("pausado", false).containsEntry("en_mantenimiento", false);
            assertThat(antigua.queryForList("SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'operacion' AND table_name = 'establecimiento' "
                    + "AND column_name IN ('pausado', 'en_mantenimiento')", String.class)).containsExactly("NO", "NO");
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS " + nombre + " WITH (FORCE)");
        }
    }
}
