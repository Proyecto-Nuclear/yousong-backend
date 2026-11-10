package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.sesion.SesionService;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and updates establishments. Authorization is by role only (see {@code SesionService}).
 * Every change is announced with a {@code configuration.changed} event in the same transaction.
 */
@Service
public class EstablecimientoConfiguracionService {

    private static final String DEFAULT_TIME_ZONE = "America/Bogota";
    private static final String EVENTO = "configuration.changed";
    private static final String AGREGADO = "ESTABLECIMIENTO";
    private static final int MAX_SLUG_ATTEMPTS = 50;

    private final EstablecimientoRepository repository;
    private final EstablecimientoService establecimientos;
    private final SesionService sesiones;
    private final EventoOutboxPublisher eventos;
    private final EntityManager entityManager;
    private final Clock clock;

    EstablecimientoConfiguracionService(EstablecimientoRepository repository, EstablecimientoService establecimientos,
                                        SesionService sesiones, EventoOutboxPublisher eventos,
                                        EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.establecimientos = establecimientos;
        this.sesiones = sesiones;
        this.eventos = eventos;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public EstablecimientoResponse crear(CrearEstablecimientoRequest request) {
        String zona = request.zonaHoraria() == null ? DEFAULT_TIME_ZONE : validarZona(request.zonaHoraria());
        String slug = request.qrSlug() != null ? slugExplicito(request.qrSlug()) : slugGenerado(request.nombre());

        Establecimiento establecimiento = new Establecimiento();
        establecimiento.setId(UUID.randomUUID());
        establecimiento.setNombre(request.nombre().strip());
        establecimiento.setQrSlug(slug);
        establecimiento.setZonaHoraria(zona);
        establecimiento.setCreadoAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        entityManager.persist(establecimiento);
        try {
            entityManager.flush();
        } catch (DataIntegrityViolationException e) {
            // Another request took the slug between the check and the insert.
            throw ApiException.conflict("SLUG_TAKEN", "The QR slug '" + slug + "' is already in use");
        }

        publicarCambio(establecimiento, List.of("nombre", "qr_slug", "zona_horaria", "activo"));
        return EstablecimientoResponse.de(establecimiento);
    }

    @Transactional
    public EstablecimientoResponse actualizar(UUID id, ActualizarEstablecimientoRequest request) {
        // The row lock keeps concurrent updates from interleaving and gives each event its own version.
        Establecimiento establecimiento = establecimientos.obtenerBloqueando(id);
        if (request.nombre() == null && request.zonaHoraria() == null && request.activo() == null) {
            throw ApiException.badRequest("EMPTY_UPDATE", "Send at least one of: nombre, zonaHoraria, activo");
        }
        String zona = request.zonaHoraria() == null ? null : validarZona(request.zonaHoraria());
        if (Boolean.FALSE.equals(request.activo()) && establecimiento.isActivo() && sesiones.hayEnOperacion(id)) {
            throw ApiException.conflict("SESSION_IN_PROGRESS", "Close the session in operation before deactivating the establishment");
        }

        List<String> cambios = new ArrayList<>();
        if (request.nombre() != null && !request.nombre().strip().equals(establecimiento.getNombre())) {
            establecimiento.setNombre(request.nombre().strip());
            cambios.add("nombre");
        }
        if (zona != null && !zona.equals(establecimiento.getZonaHoraria())) {
            establecimiento.setZonaHoraria(zona);
            cambios.add("zona_horaria");
        }
        if (request.activo() != null && request.activo() != establecimiento.isActivo()) {
            establecimiento.setActivo(request.activo());
            cambios.add("activo");
        }
        if (!cambios.isEmpty()) {
            entityManager.flush();
            publicarCambio(establecimiento, cambios);
        }
        return EstablecimientoResponse.de(establecimiento);
    }

    @Transactional(readOnly = true)
    public EstablecimientoResponse obtener(UUID id) {
        return EstablecimientoResponse.de(establecimientos.obtener(id));
    }

    @Transactional(readOnly = true)
    public List<EstablecimientoResponse> listar() {
        return repository.findAll(Sort.by("nombre")).stream().map(EstablecimientoResponse::de).toList();
    }

    private void publicarCambio(Establecimiento establecimiento, List<String> campos) {
        long version = eventos.siguienteVersion(AGREGADO, establecimiento.getId(), EVENTO);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity_type", AGREGADO);
        payload.put("entity_ref", establecimiento.getId());
        payload.put("version", version);
        payload.put("field_codes", campos);
        eventos.publicar(EVENTO, AmbitoEvento.O, "backend.configuracion", UUID.randomUUID(), establecimiento.getId(),
                null, AGREGADO, establecimiento.getId(), version, payload);
    }

    private String slugExplicito(String slug) {
        if (repository.existsByQrSlug(slug)) {
            throw ApiException.conflict("SLUG_TAKEN", "The QR slug '" + slug + "' is already in use");
        }
        return slug;
    }

    private String slugGenerado(String nombre) {
        String base = Slug.de(nombre);
        String candidato = base;
        for (int n = 2; repository.existsByQrSlug(candidato); n++) {
            if (n > MAX_SLUG_ATTEMPTS) {
                throw ApiException.conflict("SLUG_TAKEN", "Could not generate a free QR slug; send an explicit qrSlug");
            }
            candidato = base + "-" + n;
        }
        return candidato;
    }

    private static String validarZona(String zona) {
        if (!ZoneId.getAvailableZoneIds().contains(zona)) {
            throw ApiException.badRequest("INVALID_TIME_ZONE", "'" + zona + "' is not a known IANA time zone");
        }
        return zona;
    }
}
