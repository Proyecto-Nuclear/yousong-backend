package cue.edu.co.yousongbackend.sesion;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ClienteSse;
import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import cue.edu.co.yousongbackend.support.RelojDePrueba;
import cue.edu.co.yousongbackend.support.RelojDePrueba.RelojMovible;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The scheduled close: a session opened inside a weekly window is closed through the normal close path once the
 * establishment is outside every window; a session the owner opened outside the windows is left to the owner.
 * Ticks are run directly ({@link CierrePorHorario#ejecutarCierresPendientes}) with a movable clock.
 */
@Import(RelojDePrueba.class)
class CierrePorHorarioApiTest extends ColaApiTestSupport {

    private static final String HORARIOS = "/api/mi-establecimiento/horarios";
    /** Monday 20:00-22:00 UTC; 2026-11-16 is a Monday. */
    private static final String LUNES = "[{\"diaSemana\":1,\"inicio\":\"20:00\",\"fin\":\"22:00\"}]";
    private static final Instant INICIO = Instant.parse("2026-11-16T20:00:00Z");
    private static final Instant FIN = Instant.parse("2026-11-16T22:00:00Z");

    @Autowired
    private RelojMovible reloj;

    @Autowired
    private CierrePorHorario cierre;

    @Autowired
    private ScheduledTaskHolder tareas;

    private Local conHorario() {
        Local local = establecimiento("UTC", true);
        listaDefecto(local.id());
        assertThat(put(HORARIOS, local.token(), LUNES).statusCode()).isEqualTo(200);
        return local;
    }

    private String estadoSesion(UUID sesion) {
        return jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?", String.class, sesion);
    }

    @Test
    void aSessionOpenedInsideAWindowClosesLikeAManualCloseWhenTheWindowEnds() {
        reloj.fijar(INICIO.plus(Duration.ofMinutes(30)));
        Local local = conHorario();
        UUID sesion = abrirSesion(local);
        String participante = unirse(local);

        try (ClienteSse cliente = ClienteSse.abrir(port, "/api/public/establecimientos/" + local.slug() + "/cola/eventos")) {
            assertThat(cliente.siguiente().json().get("estado").asString()).isEqualTo("ABIERTO");
            reloj.fijar(FIN.minusSeconds(1));
            cierre.ejecutarCierresPendientes();
            assertThat(estadoSesion(sesion)).isEqualTo("ABIERTA");

            reloj.fijar(FIN);
            cierre.ejecutarCierresPendientes();

            assertThat(estadoSesion(sesion)).isEqualTo("CERRADA");
            assertThat(jdbc.queryForObject("SELECT cerrada_at FROM operacion.sesion WHERE id = ?", OffsetDateTime.class,
                    sesion).toInstant()).isEqualTo(FIN);
            assertThat(cliente.siguiente().json().get("estado").asString()).isEqualTo("CERRADO");
        }
        assertCredencialRechazada(get(MI_SESION, participante), "SESSION_ENDED");
        assertThat(eventos(sesion, "session.closed")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT accion_codigo || ':' || entidad_tipo || ':' || entidad_ref || ':' || resultado "
                + "FROM operacion.auditoria WHERE establecimiento_id = ? AND accion_codigo = 'SESION_CERRADA_POR_HORARIO'",
                String.class, local.id())).containsExactly("SESION_CERRADA_POR_HORARIO:SESION:" + sesion + ":EXITO");
    }

    @Test
    void aSessionTheOwnerOpenedOutsideTheWindowsIsNeverClosedAutomatically() {
        reloj.fijar(INICIO.minus(Duration.ofHours(2)));
        Local local = conHorario();
        UUID sesion = abrirSesion(local);

        reloj.fijar(FIN.plus(Duration.ofHours(1)));
        cierre.ejecutarCierresPendientes();

        assertThat(estadoSesion(sesion)).isEqualTo("ABIERTA");
    }

    @Test
    void aSessionOpenedMomentsBeforeTheEndGetsTheMinimumOpenTime() {
        reloj.fijar(FIN.minusSeconds(30));
        Local local = conHorario();
        UUID sesion = abrirSesion(local);

        reloj.fijar(FIN.plusSeconds(10));
        cierre.ejecutarCierresPendientes();
        assertThat(estadoSesion(sesion)).isEqualTo("ABIERTA");

        reloj.fijar(FIN.plusSeconds(31));
        cierre.ejecutarCierresPendientes();
        assertThat(estadoSesion(sesion)).isEqualTo("CERRADA");
    }

    @Test
    void withoutWindowsNothingIsEverClosedAutomatically() {
        reloj.fijar(INICIO.plus(Duration.ofMinutes(30)));
        Local local = localAbierto();
        UUID sesion = sesionDe(local);

        reloj.fijar(FIN.plus(Duration.ofDays(1)));
        cierre.ejecutarCierresPendientes();

        assertThat(estadoSesion(sesion)).isEqualTo("ABIERTA");
    }

    @Test
    void aPausedSessionIsClosedToo() {
        reloj.fijar(INICIO.plus(Duration.ofMinutes(30)));
        Local local = conHorario();
        UUID sesion = abrirSesion(local);
        assertThat(post("/api/mi-establecimiento/sesion/pausa", local.token()).statusCode()).isEqualTo(200);

        reloj.fijar(FIN.plus(Duration.ofMinutes(5)));
        cierre.ejecutarCierresPendientes();

        assertThat(estadoSesion(sesion)).isEqualTo("CERRADA");
    }

    @Test
    void oneFailingEstablishmentDoesNotStopTheOthers() {
        reloj.fijar(INICIO.plus(Duration.ofMinutes(30)));
        Local roto = conHorario();
        UUID sesionRota = abrirSesion(roto);
        Local sano = conHorario();
        UUID sesionSana = abrirSesion(sano);
        // An unusable time zone makes evaluating this establishment's schedule fail.
        jdbc.update("UPDATE operacion.establecimiento SET zona_horaria = 'Not/AZone' WHERE id = ?", roto.id());
        try {
            reloj.fijar(FIN.plus(Duration.ofMinutes(5)));
            cierre.ejecutarCierresPendientes();

            assertThat(estadoSesion(sesionSana)).isEqualTo("CERRADA");
            assertThat(estadoSesion(sesionRota)).isEqualTo("ABIERTA");
        } finally {
            jdbc.update("UPDATE operacion.establecimiento SET zona_horaria = 'UTC' WHERE id = ?", roto.id());
        }
    }

    @Test
    void theTickIsAScheduledTaskWithTheConfiguredInterval() {
        assertThat(tareas.getScheduledTasks()).anySatisfy(tarea -> {
            assertThat(tarea.getTask()).isInstanceOf(FixedDelayTask.class);
            // The runnable is wrapped for observability; its name is the scheduled method.
            assertThat(tarea.getTask().getRunnable().toString()).isEqualTo(CierrePorHorario.class.getName() + ".tick");
            assertThat(((FixedDelayTask) tarea.getTask()).getIntervalDuration()).isEqualTo(Duration.ofDays(1));
        });
    }
}
