package cue.edu.co.yousongbackend.database;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards S1: every table created by Flyway has exactly one JPA entity.
 * Column-level fidelity is checked by {@code spring.jpa.hibernate.ddl-auto=validate} at context start.
 */
class EntityMappingTest extends PostgresIntegrationTest {

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void mapsExactlyThirtyThreeEntities() {
        assertThat(entityTables()).hasSize(33);
    }

    @Test
    void entityTablesMatchTheFlywayTables() {
        Set<String> databaseTables = new TreeSet<>(jdbc.queryForList("""
                SELECT table_schema || '.' || table_name FROM information_schema.tables
                WHERE table_type = 'BASE TABLE'
                  AND table_schema IN ('operacion', 'integracion', 'analitica')
                """, String.class));

        assertThat(entityTables()).containsExactlyElementsOf(databaseTables);
    }

    private Set<String> entityTables() {
        Set<String> tables = new TreeSet<>();
        entityManagerFactory.getMetamodel().getEntities().forEach(entity -> {
            Table table = entity.getJavaType().getAnnotation(Table.class);
            tables.add(table.schema() + "." + table.name());
        });
        return tables;
    }
}
