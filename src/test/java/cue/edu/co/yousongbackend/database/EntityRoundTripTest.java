package cue.edu.co.yousongbackend.database;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.analitica.DimTiempo;
import cue.edu.co.yousongbackend.catalogo.Cancion;
import cue.edu.co.yousongbackend.catalogo.Disponibilidad;
import cue.edu.co.yousongbackend.control.Comando;
import cue.edu.co.yousongbackend.control.EstadoComando;
import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutbox;
import cue.edu.co.yousongbackend.repertorio.ListaDefecto;
import cue.edu.co.yousongbackend.repertorio.PoliticaRotacion;
import cue.edu.co.yousongbackend.sesion.EstadoSesion;
import cue.edu.co.yousongbackend.sesion.Sesion;
import cue.edu.co.yousongbackend.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Persists entities and checks what really lands in PostgreSQL (and what comes back). */
@Transactional
class EntityRoundTripTest extends PostgresIntegrationTest {

    @PersistenceContext
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void storesJsonColumnsAsJsonObjectsNotEscapedStrings() {
        Comando comando = new Comando();
        comando.setId(UUID.randomUUID());
        comando.setAmbito("participacion:uuid:VOTAR");
        comando.setClave(UUID.randomUUID());
        comando.setPeticionHash("a".repeat(64));
        comando.setRespuesta("{\"request_id\":\"abc\",\"queue_version\":2}");
        comando.setExpiraAt(Instant.parse("2026-11-16T01:00:00Z"));

        flushAndClear(comando);

        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(respuesta) FROM operacion.comando WHERE id = ?",
                String.class, comando.getId())).isEqualTo("object");
        assertThat(jdbc.queryForObject("SELECT (respuesta ->> 'queue_version')::int FROM operacion.comando WHERE id = ?",
                Integer.class, comando.getId())).isEqualTo(2);
        assertThat(em.find(Comando.class, comando.getId()).getRespuesta()).contains("queue_version");
    }

    @Test
    void outboxEventKeepsScopeEnumAndJsonPayload() {
        EventoOutbox evento = new EventoOutbox();
        evento.setEventId(UUID.randomUUID());
        evento.setEventType("vote.accepted");
        evento.setOccurredAt(Instant.parse("2026-11-09T01:00:00Z"));
        evento.setAgregadoTipo("VOTO");
        evento.setAgregadoId(UUID.randomUUID());
        evento.setCorrelacionId(UUID.randomUUID());
        evento.setProductor("backend.solicitudes");
        evento.setAmbito(AmbitoEvento.B);
        evento.setPayload("{\"is_initial\":false,\"votes_after\":3}");

        flushAndClear(evento);

        assertThat(jdbc.queryForObject("SELECT ambito FROM integracion.evento_outbox WHERE event_id = ?",
                String.class, evento.getEventId())).isEqualTo("B");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(payload) FROM integracion.evento_outbox WHERE event_id = ?",
                String.class, evento.getEventId())).isEqualTo("object");

        EventoOutbox leido = em.find(EventoOutbox.class, evento.getEventId());
        assertThat(leido.getAmbito()).isEqualTo(AmbitoEvento.B);
        assertThat(leido.getSchemaVersion()).isEqualTo(1);
        assertThat(leido.getIntentosEntrega()).isZero();
        assertThat(leido.getOccurredAt()).isEqualTo(Instant.parse("2026-11-09T01:00:00Z"));
    }

    @Test
    void sessionVersionIncrementsOnEveryUpdateAndEnumsAreStoredAsText() {
        Sesion sesion = nuevaSesion();
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?",
                String.class, sesion.getId())).isEqualTo("ABIERTA");
        assertThat(sesion.getVersion()).isZero();

        Sesion gestionada = em.find(Sesion.class, sesion.getId());
        gestionada.setEstado(EstadoSesion.PAUSADA);
        em.flush();

        assertThat(gestionada.getVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM operacion.sesion WHERE id = ?",
                Long.class, sesion.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?",
                String.class, sesion.getId())).isEqualTo("PAUSADA");
    }

    @Test
    void newEntitiesBehaveLikeRowsCreatedWithDatabaseDefaults() {
        Cancion cancion = new Cancion();
        cancion.setId(UUID.randomUUID());
        cancion.setYoutubeVideoId("dQw4w9WgXcQ");

        flushAndClear(cancion);

        Cancion leida = em.find(Cancion.class, cancion.getId());
        assertThat(leida.getDuracionOperativaS()).isEqualTo(240);
        assertThat(leida.getDisponibilidad()).isEqualTo(Disponibilidad.DESCONOCIDA);

        Comando comando = new Comando();
        assertThat(comando.getEstado()).isEqualTo(EstadoComando.EN_PROCESO);
        assertThat(new Establecimiento().getZonaHoraria()).isEqualTo("America/Bogota");
    }

    @Test
    void integerPrimaryKeyOfTheTimeDimensionRoundTrips() {
        DimTiempo tiempo = new DimTiempo();
        tiempo.setId(2026110820);
        tiempo.setFechaLocal(LocalDate.of(2026, 11, 8));
        tiempo.setHora((short) 20);
        tiempo.setDiaSemana((short) 7);

        flushAndClear(tiempo);

        DimTiempo leido = em.find(DimTiempo.class, 2026110820);
        assertThat(leido.getFechaLocal()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(leido.getHora()).isEqualTo((short) 20);
    }

    // ---------- helpers ----------

    private void flushAndClear(Object entity) {
        em.persist(entity);
        em.flush();
        em.clear();
    }

    private Sesion nuevaSesion() {
        Establecimiento est = new Establecimiento();
        est.setId(UUID.randomUUID());
        est.setNombre("Local");
        est.setQrSlug("local-" + est.getId().toString().substring(0, 8));
        em.persist(est);

        ListaDefecto lista = new ListaDefecto();
        lista.setId(UUID.randomUUID());
        lista.setEstablecimientoId(est.getId());
        lista.setNombre("General");
        lista.setVersion(1);
        lista.setPoliticaRotacion(PoliticaRotacion.SECUENCIAL);
        em.persist(lista);

        Sesion sesion = new Sesion();
        sesion.setId(UUID.randomUUID());
        sesion.setEstablecimientoId(est.getId());
        sesion.setListaId(lista.getId());
        sesion.setFechaOperativa(LocalDate.of(2026, 11, 8));
        em.persist(sesion);
        em.flush();
        return sesion;
    }
}
