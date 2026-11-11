package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.acceso.AccesoParticipantes;
import cue.edu.co.yousongbackend.acceso.CanalAcceso;
import cue.edu.co.yousongbackend.acceso.Dispositivo;
import cue.edu.co.yousongbackend.acceso.SesionAcceso;
import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.participante.ParticipacionDtos.EstablecimientoPublicoResponse;
import cue.edu.co.yousongbackend.participante.ParticipacionDtos.EstablecimientoResumen;
import cue.edu.co.yousongbackend.participante.ParticipacionDtos.MiSesionResponse;
import cue.edu.co.yousongbackend.participante.ParticipacionDtos.UnirseRequest;
import cue.edu.co.yousongbackend.participante.ParticipacionDtos.UnirseResponse;
import cue.edu.co.yousongbackend.privacidad.AccionConsentimiento;
import cue.edu.co.yousongbackend.privacidad.Consentimiento;
import cue.edu.co.yousongbackend.privacidad.FinalidadConsentimiento;
import cue.edu.co.yousongbackend.privacidad.PoliticaPrivacidad;
import cue.edu.co.yousongbackend.sesion.EstadoSesion;
import cue.edu.co.yousongbackend.sesion.Sesion;
import cue.edu.co.yousongbackend.sesion.SesionService;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymous clients ("participants") joining the session in operation of an establishment through its QR.
 *
 * <p>Known limitation: the public join endpoint has no rate limiting yet, so one client can register many
 * participations (and fill the database) in an open session. Add a limit per address or per establishment
 * before a public launch.
 */
@Service
public class ParticipacionService {

    /** A device registration outlives a single credential, but is revoked with its session anyway. */
    static final Duration VIGENCIA_DISPOSITIVO = Duration.ofDays(30);

    private static final int INTENTOS_SEUDONIMO = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EstablecimientoService establecimientos;
    private final SesionService sesiones;
    private final AccesoParticipantes accesos;
    private final PoliticaPrivacidad politica;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration vigenciaCredencial;

    ParticipacionService(EstablecimientoService establecimientos, SesionService sesiones, AccesoParticipantes accesos,
                         PoliticaPrivacidad politica, EntityManager entityManager, JdbcTemplate jdbc, Clock clock,
                         @Value("${app.participante.credential-ttl-hours:24}") long horasCredencial) {
        if (horasCredencial <= 0) {
            throw new IllegalStateException("app.participante.credential-ttl-hours must be positive");
        }
        this.establecimientos = establecimientos;
        this.sesiones = sesiones;
        this.accesos = accesos;
        this.politica = politica;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.clock = clock;
        this.vigenciaCredencial = Duration.ofHours(horasCredencial);
    }

    @Transactional(readOnly = true)
    public EstablecimientoPublicoResponse estadoPublico(String qrSlug) {
        Establecimiento establecimiento = establecimientos.obtenerPorSlug(qrSlug);
        EstadoServicio servicio = establecimiento.isActivo()
                ? sesiones.buscarEnOperacion(establecimiento.getId())
                        .map(sesion -> EstadoServicio.de(sesion.getEstado())).orElse(EstadoServicio.CERRADO)
                : EstadoServicio.CERRADO;
        return new EstablecimientoPublicoResponse(establecimiento.getNombre(), establecimiento.getQrSlug(), servicio,
                servicio == EstadoServicio.ABIERTO);
    }

    /**
     * Registers the device, the participation, the consents and the credential in one transaction, while
     * holding a shared lock on the session so a concurrent close either waits for this join (and then
     * revokes its credential) or has already committed (and then the join finds no session).
     *
     * <p>No rate limiting yet (see the class documentation).
     */
    @Transactional
    public UnirseResponse unirse(String qrSlug, UnirseRequest request) {
        if (!Boolean.TRUE.equals(request.autorizaF1())) {
            throw ApiException.badRequest("CONSENT_REQUIRED",
                    "Purpose F1 of the privacy policy must be authorized to participate");
        }
        if (!politica.version().equals(request.politicaVersion())) {
            throw ApiException.conflict("POLICY_VERSION_MISMATCH",
                    "The privacy policy changed; the current version is " + politica.version());
        }
        Establecimiento establecimiento = establecimientos.obtenerPorSlug(qrSlug);
        if (!establecimiento.isActivo()) {
            throw ApiException.conflict("ESTABLISHMENT_INACTIVE", "The establishment is not active");
        }
        Sesion sesion = sesiones.bloquearEnOperacionParaUnirse(establecimiento.getId())
                .filter(enOperacion -> enOperacion.getEstado() != EstadoSesion.CERRANDO)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_SESSION",
                        "The establishment has no session open to participants"));
        if (sesion.getEstado() == EstadoSesion.PAUSADA) {
            throw ApiException.conflict("SESSION_PAUSED", "The session is paused; try again when it resumes");
        }

        Instant ahora = clock.instant().truncatedTo(ChronoUnit.MICROS);
        CanalAcceso canal = request.canal();

        Dispositivo dispositivo = new Dispositivo();
        dispositivo.setId(UUID.randomUUID());
        dispositivo.setCanal(canal);
        dispositivo.setCreadoAt(ahora);
        dispositivo.setUltimoAccesoAt(ahora);
        dispositivo.setExpiraAt(ahora.plus(VIGENCIA_DISPOSITIVO));
        entityManager.persist(dispositivo);
        entityManager.flush();

        UUID participacionId = UUID.randomUUID();
        String seudonimo = registrarParticipacion(participacionId, sesion.getId(), dispositivo.getId(), ahora);

        consentir(participacionId, FinalidadConsentimiento.F1, canal, ahora);
        if (Boolean.TRUE.equals(request.autorizaF2())) {
            consentir(participacionId, FinalidadConsentimiento.F2, canal, ahora);
        }

        String token = TokenParticipante.generar();
        SesionAcceso credencial = new SesionAcceso();
        credencial.setId(UUID.randomUUID());
        credencial.setDispositivoId(dispositivo.getId());
        credencial.setFamiliaId(UUID.randomUUID());
        credencial.setRefreshHash(TokenParticipante.hash(token));
        credencial.setEmitidaAt(ahora);
        credencial.setExpiraAt(ahora.plus(vigenciaCredencial));
        entityManager.persist(credencial);
        entityManager.flush();

        return new UnirseResponse(token, credencial.getExpiraAt(), seudonimo, EstadoServicio.ABIERTO,
                new EstablecimientoResumen(establecimiento.getNombre()));
    }

    @Transactional(readOnly = true)
    public MiSesionResponse miSesion(ParticipanteAutenticado participante) {
        Sesion sesion = entityManager.find(Sesion.class, participante.sesionId());
        // The credential was checked moments ago, but the session may have closed since: say so the same way.
        if (sesion == null || !SesionService.enOperacion(sesion.getEstado())) {
            throw ApiException.unauthorized(VerificacionCredencial.SESSION_ENDED,
                    "The session this credential belonged to has ended");
        }
        Establecimiento establecimiento = establecimientos.obtener(sesion.getEstablecimientoId());
        return new MiSesionResponse(new EstablecimientoResumen(establecimiento.getNombre()),
                EstadoServicio.de(sesion.getEstado()), participante.seudonimo());
    }

    /** Leaving voluntarily: the credential and the device stop working; the participation stays as history. */
    @Transactional
    public void salir(ParticipanteAutenticado participante) {
        accesos.revocarDispositivo(participante.dispositivoId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Inserts the participation with a random pseudonym, retrying on the rare collision inside the session.
     * {@code ON CONFLICT DO NOTHING} reports the collision without aborting the transaction.
     */
    private String registrarParticipacion(UUID id, UUID sesionId, UUID dispositivoId, Instant ahora) {
        Timestamp instante = Timestamp.from(ahora);
        for (int intento = 0; intento < INTENTOS_SEUDONIMO; intento++) {
            String seudonimo = nuevoSeudonimo();
            int insertadas = jdbc.update("INSERT INTO operacion.participacion "
                    + "(id, sesion_id, dispositivo_id, seudonimo, ingreso_at, ultima_actividad_at) VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT ON CONSTRAINT uq_participacion_sesion_seudonimo DO NOTHING",
                    id, sesionId, dispositivoId, seudonimo, instante, instante);
            if (insertadas == 1) {
                return seudonimo;
            }
        }
        throw new IllegalStateException("Could not find a free pseudonym in session " + sesionId);
    }

    private static String nuevoSeudonimo() {
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return "p-" + HexFormat.of().formatHex(bytes);
    }

    /** Only the purpose, the policy version, the channel and the time: no address, user agent or free text. */
    private void consentir(UUID participacionId, FinalidadConsentimiento finalidad, CanalAcceso canal, Instant ahora) {
        Consentimiento consentimiento = new Consentimiento();
        consentimiento.setId(UUID.randomUUID());
        consentimiento.setParticipacionId(participacionId);
        consentimiento.setPoliticaVersion(politica.version());
        consentimiento.setFinalidad(finalidad);
        consentimiento.setAccion(AccionConsentimiento.AUTORIZA);
        consentimiento.setCanal(canal);
        consentimiento.setRegistradoAt(ahora);
        entityManager.persist(consentimiento);
    }
}
