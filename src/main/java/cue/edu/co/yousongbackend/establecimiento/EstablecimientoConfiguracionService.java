package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.sesion.SesionService;
import cue.edu.co.yousongbackend.shared.persistence.BloqueoTransaccional;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and updates establishments. Authorization is ownership: callers pass the establishment of the
 * current owner (see {@link PropietarioActual}), never an id chosen by a client. One account owns at most one
 * establishment. Every change is announced with a {@code configuration.changed} event in the same transaction.
 */
@Service
public class EstablecimientoConfiguracionService {

    private static final String DEFAULT_TIME_ZONE = "America/Bogota";
    private static final int MAX_SLUG_ATTEMPTS = 20;
    private static final int SLUG_SUFFIX_LENGTH = 6;
    private static final String SLUG_SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    /** Arbitrates two creations by one account; the advisory lock below normally prevents reaching it. */
    private static final String OWNER_UNIQUE_INDEX = "ux_establecimiento_propietario_sub";

    private final SecureRandom random = new SecureRandom();

    private final EstablecimientoRepository repository;
    private final EstablecimientoService establecimientos;
    private final SesionService sesiones;
    private final CambiosConfiguracion cambios;
    private final EntityManager entityManager;
    private final BloqueoTransaccional bloqueos;
    private final Clock clock;

    EstablecimientoConfiguracionService(EstablecimientoRepository repository, EstablecimientoService establecimientos,
                                        SesionService sesiones, CambiosConfiguracion cambios,
                                        EntityManager entityManager, BloqueoTransaccional bloqueos, Clock clock) {
        this.repository = repository;
        this.establecimientos = establecimientos;
        this.sesiones = sesiones;
        this.cambios = cambios;
        this.entityManager = entityManager;
        this.bloqueos = bloqueos;
        this.clock = clock;
    }

    /** Creates the establishment of the account {@code propietarioSub}, which becomes its owner. */
    @Transactional
    public EstablecimientoResponse crear(String propietarioSub, CrearEstablecimientoRequest request) {
        String zona = request.zonaHoraria() == null ? DEFAULT_TIME_ZONE : validarZona(request.zonaHoraria());
        // "This slug is free" and "this account has no establishment" are rules about rows that do not exist
        // yet, so a row lock cannot protect them. Creating an establishment is rare, so serializing it is cheap
        // and removes both check-then-insert races (a double click of one owner included).
        bloqueos.tomar(BloqueoTransaccional.Clave.CREACION_DE_ESTABLECIMIENTO);
        if (repository.existsByPropietarioSub(propietarioSub)) {
            throw yaTieneEstablecimiento();
        }
        String slug = request.qrSlug() != null ? slugExplicito(request.qrSlug()) : slugGenerado(request.nombre());

        Establecimiento establecimiento = new Establecimiento();
        establecimiento.setId(UUID.randomUUID());
        establecimiento.setNombre(request.nombre().strip());
        establecimiento.setQrSlug(slug);
        establecimiento.setZonaHoraria(zona);
        establecimiento.setPropietarioSub(propietarioSub);
        establecimiento.setCreadoAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        try {
            entityManager.persist(establecimiento);
            entityManager.flush();
        } catch (PersistenceException e) {
            // The injected EntityManager does not translate exceptions: recognise the owner index by name.
            if (violaIndice(e, OWNER_UNIQUE_INDEX)) {
                throw yaTieneEstablecimiento();
            }
            throw e;
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

    private static ApiException yaTieneEstablecimiento() {
        return ApiException.conflict("ESTABLISHMENT_ALREADY_EXISTS", "This account already has an establishment");
    }

    private static boolean violaIndice(Throwable error, String indice) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violacion && indice.equals(violacion.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private void publicarCambio(Establecimiento establecimiento, List<String> campos) {
        cambios.publicar(establecimiento.getId(), campos);
    }

    private String slugExplicito(String slug) {
        if (repository.existsByQrSlug(slug)) {
            throw ApiException.conflict("SLUG_TAKEN", "The QR slug '" + slug + "' is already in use");
        }
        return slug;
    }

    /** The name as a slug plus a short random suffix, so a public QR address cannot be guessed from the name. */
    private String slugGenerado(String nombre) {
        String base = Slug.de(nombre);
        for (int intento = 0; intento < MAX_SLUG_ATTEMPTS; intento++) {
            String candidato = base + "-" + sufijoAleatorio();
            if (!repository.existsByQrSlug(candidato)) {
                return candidato;
            }
        }
        throw ApiException.conflict("SLUG_TAKEN", "Could not generate a free QR slug; send an explicit qrSlug");
    }

    private String sufijoAleatorio() {
        StringBuilder sufijo = new StringBuilder(SLUG_SUFFIX_LENGTH);
        for (int i = 0; i < SLUG_SUFFIX_LENGTH; i++) {
            sufijo.append(SLUG_SUFFIX_ALPHABET.charAt(random.nextInt(SLUG_SUFFIX_ALPHABET.length())));
        }
        return sufijo.toString();
    }

    private static String validarZona(String zona) {
        if (!ZoneId.getAvailableZoneIds().contains(zona)) {
            throw ApiException.badRequest("INVALID_TIME_ZONE", "'" + zona + "' is not a known IANA time zone");
        }
        return zona;
    }
}
