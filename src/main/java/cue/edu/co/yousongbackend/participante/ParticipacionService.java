package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.acceso.AccesoParticipantes;
import cue.edu.co.yousongbackend.acceso.CanalAcceso;
import cue.edu.co.yousongbackend.acceso.Dispositivo;
import cue.edu.co.yousongbackend.acceso.DispositivoRepository;
import cue.edu.co.yousongbackend.acceso.SesionAcceso;
import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.establecimiento.HorarioSemanal;
import cue.edu.co.yousongbackend.establecimiento.HorarioService;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymous clients ("participants") joining the session in operation of an establishment through its QR.
 *
 * <p>Joining is anonymous, so it is bounded three ways: joins per minute per client address and per
 * establishment ({@link LimitadorUniones}), and participations per session ({@code app.participante.max-per-session}).
 *
 * <p>Weekly windows ({@link HorarioService}): with at least one, the QR only admits participants inside a window.
 * Outside every window the public status is CERRADO (with the next opening) and joining answers 409
 * OUTSIDE_SCHEDULE, even when the owner opened a session manually; participants already inside keep their access
 * until the session closes. Without windows the session state alone decides, as before.
 *
 * <p>Owner switches, checked first: maintenance (MANTENIMIENTO, 409 ESTABLISHMENT_IN_MAINTENANCE) and the system
 * pause (PAUSADO, 409 ESTABLISHMENT_PAUSED) stop every join regardless of windows and session.
 *
 * <p>One device, one participation per session ({@code uq_participacion_sesion_dispositivo}): the first join
 * mints a device and returns its secret ({@link TokenDispositivo}) once; a later join that presents it gets the
 * same participation back (with a fresh credential) instead of a second one, also after leaving. The device is
 * a technical context only: no registration, no personal data, no hardware identifier or fingerprint.
 */
@Service
public class ParticipacionService {

    /**
     * A device outlives its credentials and sessions; every successful identification renews it (sliding), so
     * a phone that keeps coming back keeps its identity, and one that stops coming is forgotten after this.
     */
    static final Duration VIGENCIA_DISPOSITIVO = Duration.ofDays(30);

    private static final int INTENTOS_SEUDONIMO = 10;

    private final EstablecimientoService establecimientos;
    private final HorarioService horarios;
    private final EstadoPublicoService estados;
    private final SesionService sesiones;
    private final AccesoParticipantes accesos;
    private final DispositivoRepository dispositivos;
    private final PoliticaPrivacidad politica;
    private final GeneradorSeudonimos seudonimos;
    private final LimitadorUniones limitador;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration vigenciaCredencial;
    private final int maximoPorSesion;

    ParticipacionService(EstablecimientoService establecimientos, HorarioService horarios, EstadoPublicoService estados,
                         SesionService sesiones,
                         AccesoParticipantes accesos,
                         DispositivoRepository dispositivos, PoliticaPrivacidad politica,
                         GeneradorSeudonimos seudonimos, LimitadorUniones limitador,
                         EntityManager entityManager, JdbcTemplate jdbc, Clock clock,
                         @Value("${app.participante.credential-ttl-hours:24}") long horasCredencial,
                         @Value("${app.participante.max-per-session:5000}") int maximoPorSesion) {
        if (horasCredencial <= 0) {
            throw new IllegalStateException("app.participante.credential-ttl-hours must be positive");
        }
        if (maximoPorSesion <= 0) {
            throw new IllegalStateException("app.participante.max-per-session must be positive");
        }
        this.establecimientos = establecimientos;
        this.horarios = horarios;
        this.estados = estados;
        this.sesiones = sesiones;
        this.accesos = accesos;
        this.dispositivos = dispositivos;
        this.politica = politica;
        this.seudonimos = seudonimos;
        this.limitador = limitador;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.clock = clock;
        this.vigenciaCredencial = Duration.ofHours(horasCredencial);
        this.maximoPorSesion = maximoPorSesion;
    }

    /**
     * What the QR shows. {@code proximaApertura} is only present when the establishment is closed because it is
     * outside every weekly window: the start of the next window.
     */
    @Transactional(readOnly = true)
    public EstablecimientoPublicoResponse estadoPublico(String qrSlug) {
        Establecimiento establecimiento = establecimientos.obtenerPorSlug(qrSlug);
        EstadoPublicoService.EstadoPublico estado = estados.resolver(establecimiento,
                () -> sesiones.buscarEnOperacion(establecimiento.getId()));
        EstadoServicio servicio = estado.servicio();
        Instant proximaApertura = estado.proximaApertura();
        return new EstablecimientoPublicoResponse(establecimiento.getNombre(), establecimiento.getQrSlug(), servicio,
                servicio == EstadoServicio.ABIERTO, proximaApertura);
    }

    /**
     * Identifies or registers the device, finds or registers its participation, records the consents and
     * issues a credential in one transaction, while holding a shared lock on the session so a concurrent close
     * either waits for this join (and then revokes its credential) or has already committed (and then the
     * join finds no session).
     *
     * <p>A device that already holds a participation in this session gets it back: its previous credentials
     * are revoked and a fresh one is issued, and no second participation (so no second vote identity) exists.
     * When two joins of the same device race, {@code uq_participacion_sesion_dispositivo} decides: the loser
     * waits for the winner and then takes the existing participation.
     *
     * <p>The per-minute limits are checked before the session lock is requested, so a limited client
     * neither creates rows nor delays a close; they apply to rejoins too. The per-session cap is counted under
     * the shared lock and only when a participation is about to be created; joins running at the same moment
     * may overshoot it by a few, which is fine for a bound against floods.
     *
     * @param direccion the client address, used only as a key of the in-memory limiter (never stored)
     */
    @Transactional
    public UnirseResponse unirse(String qrSlug, UnirseRequest request, String direccion) {
        if (!Boolean.TRUE.equals(request.autorizaF1())) {
            throw ApiException.badRequest("CONSENT_REQUIRED",
                    "Purpose F1 of the privacy policy must be authorized to participate");
        }
        if (!politica.version().equals(request.politicaVersion())) {
            throw ApiException.conflict("POLICY_VERSION_MISMATCH",
                    "The privacy policy changed; the current version is " + politica.version());
        }
        Establecimiento establecimiento = establecimientos.obtenerPorSlug(qrSlug);
        limitador.registrar(direccion, establecimiento.getId());
        if (!establecimiento.isActivo()) {
            throw ApiException.conflict("ESTABLISHMENT_INACTIVE", "The establishment is not active");
        }
        // Like the limits above, checked before the session lock: a refused join writes nothing and delays no close.
        if (establecimiento.isEnMantenimiento()) {
            throw ApiException.conflict("ESTABLISHMENT_IN_MAINTENANCE", "The establishment is in maintenance");
        }
        if (establecimiento.isPausado()) {
            throw ApiException.conflict("ESTABLISHMENT_PAUSED", "The establishment is paused; try again when it resumes");
        }
        if (!horarios.horarioDe(establecimiento).abiertoEn(clock.instant())) {
            throw ApiException.conflict("OUTSIDE_SCHEDULE", "The establishment is outside its opening hours");
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

        Optional<Dispositivo> conocido = identificar(request.dispositivoToken(), ahora);
        String dispositivoTokenNuevo = null;
        Dispositivo dispositivo;
        if (conocido.isPresent()) {
            dispositivo = conocido.get();
        } else {
            dispositivoTokenNuevo = TokenDispositivo.generar();
            dispositivo = registrarDispositivo(canal, TokenDispositivo.hash(dispositivoTokenNuevo), ahora);
        }

        Ingreso ingreso = conocido.isPresent()
                ? participacionDe(sesion.getId(), dispositivo.getId()).orElse(null)
                : null;
        if (ingreso == null) {
            verificarCupo(sesion.getId());
            ingreso = registrarParticipacion(sesion.getId(), dispositivo.getId(), ahora);
        }

        if (ingreso.existente()) {
            accesos.revocarCredencialesDeLaParticipacion(ingreso.id(), ahora);
        }
        // F1 was just checked against the current policy version, so it is recorded again whenever the last
        // record of the participation is not an authorization under that same version.
        if (!(ingreso.existente() && autorizadaVigente(ingreso.id(), FinalidadConsentimiento.F1))) {
            consentir(ingreso.id(), FinalidadConsentimiento.F1, canal, ahora);
        }
        if (Boolean.TRUE.equals(request.autorizaF2())
                && !(ingreso.existente() && autorizadaVigente(ingreso.id(), FinalidadConsentimiento.F2))) {
            consentir(ingreso.id(), FinalidadConsentimiento.F2, canal, ahora);
        }

        if (conocido.isPresent()) {
            // Sliding expiry: being identified keeps the device alive. Done last, so racing joins of the
            // same device are decided by the participation constraint and not by this row lock.
            dispositivo.setUltimoAccesoAt(ahora);
            dispositivo.setExpiraAt(ahora.plus(VIGENCIA_DISPOSITIVO));
        }

        String token = TokenParticipante.generar();
        SesionAcceso credencial = new SesionAcceso();
        credencial.setId(UUID.randomUUID());
        credencial.setDispositivoId(dispositivo.getId());
        credencial.setParticipacionId(ingreso.id());
        credencial.setFamiliaId(UUID.randomUUID());
        credencial.setRefreshHash(TokenParticipante.hash(token));
        credencial.setEmitidaAt(ahora);
        credencial.setExpiraAt(ahora.plus(vigenciaCredencial));
        entityManager.persist(credencial);
        entityManager.flush();

        return new UnirseResponse(token, credencial.getExpiraAt(), ingreso.seudonimo(), EstadoServicio.ABIERTO,
                new EstablecimientoResumen(establecimiento.getNombre()), dispositivoTokenNuevo);
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
        // The same state the public status reports (pause, maintenance, schedule), so the views agree.
        EstadoServicio servicio = estados.resolver(establecimiento, () -> Optional.of(sesion)).servicio();
        return new MiSesionResponse(new EstablecimientoResumen(establecimiento.getNombre()), servicio,
                participante.seudonimo());
    }

    /**
     * Leaving voluntarily: the credentials of the participation stop working; the participation stays as
     * history and the device stays valid, so the same phone coming back gets this participation again.
     */
    @Transactional
    public void salir(ParticipanteAutenticado participante) {
        accesos.revocarCredencialesDeLaParticipacion(participante.participacionId(),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** The participation of a join: found again for a known device, or just created. */
    private record Ingreso(UUID id, String seudonimo, boolean existente) {
    }

    /**
     * The device a secret identifies, if any. Anything unusable (malformed, unknown, expired, revoked) is
     * simply "no device", with no error and no hint of why, so the secret cannot be probed.
     */
    private Optional<Dispositivo> identificar(String dispositivoToken, Instant ahora) {
        if (!TokenDispositivo.formatoValido(dispositivoToken)) {
            return Optional.empty();
        }
        return dispositivos.buscarVigentePorHash(TokenDispositivo.hash(dispositivoToken), ahora);
    }

    private Dispositivo registrarDispositivo(CanalAcceso canal, String credencialHash, Instant ahora) {
        Dispositivo dispositivo = new Dispositivo();
        dispositivo.setId(UUID.randomUUID());
        dispositivo.setCanal(canal);
        dispositivo.setCreadoAt(ahora);
        dispositivo.setUltimoAccesoAt(ahora);
        dispositivo.setExpiraAt(ahora.plus(VIGENCIA_DISPOSITIVO));
        dispositivo.setCredencialHash(credencialHash);
        entityManager.persist(dispositivo);
        entityManager.flush();
        return dispositivo;
    }

    /**
     * The participation of the device in the session, locked, so rejoins of one device take turns revoking
     * and issuing credentials (and exactly one credential stays live).
     */
    private Optional<Ingreso> participacionDe(UUID sesionId, UUID dispositivoId) {
        List<Ingreso> encontradas = jdbc.query("SELECT id, seudonimo FROM operacion.participacion "
                        + "WHERE sesion_id = ? AND dispositivo_id = ? FOR UPDATE",
                (fila, n) -> new Ingreso(fila.getObject("id", UUID.class), fila.getString("seudonimo"), true),
                sesionId, dispositivoId);
        return encontradas.stream().findFirst();
    }

    private void verificarCupo(UUID sesionId) {
        Integer participaciones = jdbc.queryForObject(
                "SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", Integer.class, sesionId);
        if (participaciones != null && participaciones >= maximoPorSesion) {
            throw ApiException.conflict("SESSION_FULL", "The session has reached its maximum number of participants");
        }
    }

    /**
     * Inserts the participation with a random pseudonym, retrying on the rare pseudonym collision inside the
     * session. {@code ON CONFLICT DO NOTHING} reports any conflict without aborting the transaction; when the
     * conflict is the device's own participation (a racing join of the same device won), that participation
     * is returned instead. Running out of retries is a controlled 503: the caller's transaction rolls back, so
     * nothing of the join is kept.
     */
    private Ingreso registrarParticipacion(UUID sesionId, UUID dispositivoId, Instant ahora) {
        Timestamp instante = Timestamp.from(ahora);
        for (int intento = 0; intento < INTENTOS_SEUDONIMO; intento++) {
            UUID id = UUID.randomUUID();
            String seudonimo = seudonimos.nuevo();
            int insertadas = jdbc.update("INSERT INTO operacion.participacion "
                    + "(id, sesion_id, dispositivo_id, seudonimo, ingreso_at, ultima_actividad_at) VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT DO NOTHING",
                    id, sesionId, dispositivoId, seudonimo, instante, instante);
            if (insertadas == 1) {
                return new Ingreso(id, seudonimo, false);
            }
            Optional<Ingreso> existente = participacionDe(sesionId, dispositivoId);
            if (existente.isPresent()) {
                return existente.get();
            }
        }
        throw ApiException.serviceUnavailable("JOIN_UNAVAILABLE",
                "Could not register the participation right now. Try again in a moment");
    }

    /** Whether the latest decision on that purpose is an authorization under the current policy version. */
    private boolean autorizadaVigente(UUID participacionId, FinalidadConsentimiento finalidad) {
        return !jdbc.queryForList("SELECT 1 FROM (SELECT accion, politica_version FROM operacion.consentimiento "
                        + "WHERE participacion_id = ? AND finalidad = ? ORDER BY registrado_at DESC LIMIT 1) ultimo "
                        + "WHERE accion = ? AND politica_version = ?",
                participacionId, finalidad.name(), AccionConsentimiento.AUTORIZA.name(), politica.version()).isEmpty();
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
