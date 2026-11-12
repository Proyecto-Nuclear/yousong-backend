package cue.edu.co.yousongbackend.shared.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction-scoped lock on a named operation, for rules that span rows that do not exist yet and so
 * cannot be protected with a row lock (for example "this slug is free" before inserting it).
 *
 * <p>Backed by PostgreSQL advisory locks: taken on demand, held until the surrounding transaction commits
 * or rolls back, and invisible to everything that does not ask for the same key.
 */
@Component
public class BloqueoTransaccional {

    /** Keys in use. One constant per operation, so two unrelated operations never share a lock by accident. */
    public enum Clave {
        CREACION_DE_ESTABLECIMIENTO(1_001L),
        /** One scheduled-close tick at a time across every instance of the backend. */
        CIERRE_POR_HORARIO(1_002L);

        private final long valor;

        Clave(long valor) {
            this.valor = valor;
        }
    }

    private final JdbcTemplate jdbc;

    BloqueoTransaccional(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void tomar(Clave clave) {
        jdbc.execute("select pg_advisory_xact_lock(" + clave.valor + ")");
    }

    /** Like {@link #tomar} but never waits: false when another transaction holds the lock right now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean intentar(Clave clave) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, clave.valor));
    }
}
