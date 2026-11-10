package cue.edu.co.yousongbackend.sesion;

import cue.edu.co.yousongbackend.cola.EstadoSolicitud;
import cue.edu.co.yousongbackend.cola.SolicitudRepository;
import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionRepository;
import cue.edu.co.yousongbackend.reproduccion.ResultadoReproduccion;
import cue.edu.co.yousongbackend.repertorio.ListaDefecto;
import cue.edu.co.yousongbackend.repertorio.ListaDefectoRepository;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens and closes music sessions. A session is one night of service of one establishment.
 *
 * <p>Known limitation, accepted for the one-establishment pilot: authorization is by role only. Any
 * authenticated administrator can operate any establishment; per-establishment membership is not checked yet.
 */
@Service
public class SesionService {

    private static final String PRODUCTOR = "backend.sesiones";

    /** A session in one of these states blocks opening another one for the same establishment. */
    static final Set<EstadoSesion> EN_OPERACION = EnumSet.of(EstadoSesion.ABIERTA, EstadoSesion.PAUSADA, EstadoSesion.CERRANDO);

    /** Requests that were still waiting or playing when the session closed. */
    private static final Set<EstadoSolicitud> SOLICITUDES_ABIERTAS =
            EnumSet.of(EstadoSolicitud.PENDIENTE, EstadoSolicitud.RESERVADA, EstadoSolicitud.REPRODUCIENDO);

    private final SesionRepository sesiones;
    private final EstablecimientoService establecimientos;
    private final ListaDefectoRepository listas;
    private final SolicitudRepository solicitudes;
    private final ReproduccionRepository reproducciones;
    private final EstacionRepository estaciones;
    private final EventoOutboxPublisher eventos;
    private final EntityManager entityManager;
    private final Clock clock;

    SesionService(SesionRepository sesiones, EstablecimientoService establecimientos, ListaDefectoRepository listas,
                  SolicitudRepository solicitudes, ReproduccionRepository reproducciones,
                  EstacionRepository estaciones, EventoOutboxPublisher eventos, EntityManager entityManager,
                  Clock clock) {
        this.sesiones = sesiones;
        this.establecimientos = establecimientos;
        this.listas = listas;
        this.solicitudes = solicitudes;
        this.reproducciones = reproducciones;
        this.estaciones = estaciones;
        this.eventos = eventos;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public SesionResponse abrir(UUID establecimientoId) {
        // Locking the establishment serializes concurrent opens: the second one waits, then sees the first.
        Establecimiento establecimiento = establecimientos.obtenerBloqueando(establecimientoId);
        if (!establecimiento.isActivo()) {
            throw ApiException.conflict("ESTABLISHMENT_INACTIVE", "The establishment is not active");
        }
        if (sesiones.findFirstByEstablecimientoIdAndEstadoIn(establecimientoId, EN_OPERACION).isPresent()) {
            throw ApiException.conflict("SESSION_ALREADY_OPEN", "The establishment already has a session in operation");
        }
        ListaDefecto lista = listas.findFirstByEstablecimientoIdOrderByCreadoAtDescVersionDesc(establecimientoId)
                .orElseThrow(() -> ApiException.conflict("NO_DEFAULT_LIST",
                        "Configure a default list before opening a session"));

        Instant ahora = ahora();
        Sesion sesion = new Sesion();
        sesion.setId(UUID.randomUUID());
        sesion.setEstablecimientoId(establecimientoId);
        sesion.setListaId(lista.getId());
        sesion.setFechaOperativa(FechaOperativa.de(ahora, establecimiento.getZonaHoraria()));
        sesion.setAbiertaAt(ahora);
        entityManager.persist(sesion);
        entityManager.flush();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sesion.getId());
        payload.put("fecha_operativa", sesion.getFechaOperativa());
        payload.put("lista_version", lista.getVersion());
        eventos.publicar("session.opened", AmbitoEvento.B, PRODUCTOR, UUID.randomUUID(), establecimientoId,
                sesion.getId(), "SESION", sesion.getId(), sesion.getVersion(), payload);

        return SesionResponse.de(sesion);
    }

    @Transactional
    public CierreSesionResponse cerrar(UUID sesionId) {
        Sesion sesion = sesiones.findByIdForUpdate(sesionId)
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "Session " + sesionId + " does not exist"));
        if (sesion.getEstado() == EstadoSesion.CERRADA) {
            throw ApiException.conflict("SESSION_ALREADY_CLOSED", "The session is already closed");
        }

        Instant ahora = ahora();
        // What was still open is finalized, never deleted: the history of the night stays queryable.
        int pendientes = solicitudes.finalizarAbiertas(sesionId, SOLICITUDES_ABIERTAS,
                EstadoSolicitud.NO_ATENDIDA_CIERRE, ahora);
        reproducciones.terminarAbiertas(sesionId, ResultadoReproduccion.INTERRUMPIDA_CIERRE, ahora);
        estaciones.deshabilitarDeLaSesion(sesionId);

        sesion.setEstado(EstadoSesion.CERRADA);
        sesion.setCerradaAt(ahora);
        entityManager.flush();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("closed_at", ahora);
        payload.put("pendientes_al_cierre", pendientes);
        payload.put("cierre_version", sesion.getVersion());
        eventos.publicar("session.closed", AmbitoEvento.B, PRODUCTOR, UUID.randomUUID(), sesion.getEstablecimientoId(),
                sesion.getId(), "SESION", sesion.getId(), sesion.getVersion(), payload);

        return new CierreSesionResponse(SesionResponse.de(sesion), pendientes);
    }

    @Transactional(readOnly = true)
    public SesionResponse sesionActual(UUID establecimientoId) {
        return SesionResponse.de(sesionEnOperacion(establecimientoId));
    }

    /** The session currently in operation for the establishment (404 if the establishment or the session is missing). */
    @Transactional(readOnly = true)
    public Sesion sesionEnOperacion(UUID establecimientoId) {
        establecimientos.obtener(establecimientoId);
        return sesiones.findFirstByEstablecimientoIdAndEstadoIn(establecimientoId, EN_OPERACION)
                .orElseThrow(() -> ApiException.notFound("NO_ACTIVE_SESSION", "The establishment has no session in operation"));
    }

    /** Microsecond precision, like the database, so what we return equals what is stored. */
    private Instant ahora() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
