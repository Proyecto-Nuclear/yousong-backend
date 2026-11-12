package cue.edu.co.yousongbackend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cue.edu.co.yousongbackend.support.PostgresIntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V8 is additive: establishments created before it keep a NULL owner (no account can reach them), several
 * NULL owners coexist, and one account owns at most one establishment.
 */
class EstablecimientoPropietarioMigrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String url;

    @Test
    void existingEstablishmentsKeepANullOwnerAndOneAccountOwnsAtMostOne() {
        String nombre = "mig_v8_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + nombre);
        try {
            DriverManagerDataSource fuente = new DriverManagerDataSource(
                    url.replaceFirst("/postgres(\\?|$)", "/" + nombre + "$1"), "postgres", "postgres");
            Flyway.configure().dataSource(fuente).locations("classpath:db/migration").target("7").load().migrate();
            JdbcTemplate antigua = new JdbcTemplate(fuente);
            UUID previo = antigua.queryForObject("INSERT INTO operacion.establecimiento (id, nombre, qr_slug) "
                    + "VALUES (gen_random_uuid(), 'Previo', 'previo-v8') RETURNING id", UUID.class);

            Flyway.configure().dataSource(fuente).locations("classpath:db/migration").load().migrate();

            assertThat(antigua.queryForObject("SELECT propietario_sub FROM operacion.establecimiento WHERE id = ?",
                    String.class, previo)).isNull();
            antigua.update("INSERT INTO operacion.establecimiento (id, nombre, qr_slug) "
                    + "VALUES (gen_random_uuid(), 'Otro sin dueno', 'otro-v8')");
            antigua.update("INSERT INTO operacion.establecimiento (id, nombre, qr_slug, propietario_sub) "
                    + "VALUES (gen_random_uuid(), 'De Ana', 'ana-v8', 'sub-ana')");
            assertThatThrownBy(() -> antigua.update("INSERT INTO operacion.establecimiento (id, nombre, qr_slug, "
                    + "propietario_sub) VALUES (gen_random_uuid(), 'Otro de Ana', 'ana2-v8', 'sub-ana')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(antigua.queryForObject("SELECT count(*) FROM operacion.establecimiento "
                    + "WHERE propietario_sub IS NULL", Integer.class)).isEqualTo(2);
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS " + nombre + " WITH (FORCE)");
        }
    }
}
