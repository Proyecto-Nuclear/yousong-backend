package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.catalogo.BloqueosVigentes;
import cue.edu.co.yousongbackend.cola.ColaConsultas.CancionDefecto;
import cue.edu.co.yousongbackend.cola.ColaConsultas.Fila;
import cue.edu.co.yousongbackend.cola.ColaConsultas.Sonando;
import cue.edu.co.yousongbackend.cola.ColaDtos.ColaResponse;
import cue.edu.co.yousongbackend.cola.ColaDtos.EnReproduccion;
import cue.edu.co.yousongbackend.cola.ColaDtos.ItemCola;
import cue.edu.co.yousongbackend.cola.ColaDtos.ItemDefecto;
import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.participante.EstadoPublicoService;
import cue.edu.co.yousongbackend.participante.EstadoServicio;
import cue.edu.co.yousongbackend.participante.ParticipanteAutenticado;
import cue.edu.co.yousongbackend.participante.VerificacionCredencial;
import cue.edu.co.yousongbackend.reproduccion.RotacionListaDefecto;
import cue.edu.co.yousongbackend.reproduccion.RotacionListaDefecto.ProximasDefecto;
import cue.edu.co.yousongbackend.sesion.Sesion;
import cue.edu.co.yousongbackend.sesion.SesionRepository;
import cue.edu.co.yousongbackend.sesion.SesionService;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshots of the queue. Each one is read in a single REPEATABLE READ transaction, so its {@code colaVersion},
 * the song playing, the items and the default-list preview all belong to the same moment. Estimated waits: see
 * {@link EsperaEstimada}.
 *
 * <p>The default-list preview ({@code proximasDefecto}) comes from {@link RotacionListaDefecto#proximas}, the same
 * rule that picks the next default song, so it never guesses differently from the player.
 */
@Service
public class ColaService {

    private final EstablecimientoService establecimientos;
    private final SesionService sesiones;
    private final SesionRepository sesionRepository;
    private final ColaConsultas consultas;
    private final Clock clock;
    private final EstadoPublicoService estados;
    private final RotacionListaDefecto rotacion;
    private final BloqueosVigentes bloqueos;
    private final int tamanoPrevia;

    ColaService(EstablecimientoService establecimientos, SesionService sesiones, SesionRepository sesionRepository,
                ColaConsultas consultas, Clock clock, EstadoPublicoService estados, RotacionListaDefecto rotacion,
                BloqueosVigentes bloqueos, @Value("${app.cola.default-preview-size:5}") int tamanoPrevia) {
        this.establecimientos = establecimientos;
        this.sesiones = sesiones;
        this.sesionRepository = sesionRepository;
        this.consultas = consultas;
        this.clock = clock;
        this.estados = estados;
        this.rotacion = rotacion;
        this.bloqueos = bloqueos;
        this.tamanoPrevia = Math.max(0, tamanoPrevia);
    }

    /**
     * The anonymous queue behind the QR: songs, votes and waits, no identity. An inactive establishment or one
     * with no session in operation shows an empty, CERRADO queue.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ColaResponse publica(String qrSlug) {
        return publica(establecimientos.obtenerPorSlug(qrSlug));
    }

    /** The same anonymous snapshot, by establishment id (the live stream keys its subscribers by id, not slug). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ColaResponse publicaDe(UUID establecimientoId) {
        return publica(establecimientos.obtener(establecimientoId));
    }

    private ColaResponse publica(Establecimiento establecimiento) {
        Optional<Sesion> sesion = establecimiento.isActivo()
                ? sesiones.buscarEnOperacion(establecimiento.getId())
                : Optional.empty();
        // The state is the one the QR status endpoint reports (pause, maintenance, schedule), so both agree.
        EstadoServicio estado = estados.resolver(establecimiento, () -> sesion).servicio();
        return sesion.map(enOperacion -> instantanea(enOperacion, null, estado))
                .orElseGet(() -> new ColaResponse(0, estado, null, List.of(), List.of(), null));
    }

    /** The same snapshot for a participant, saying on every item whether they already supported that song. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ColaResponse deParticipante(ParticipanteAutenticado participante) {
        Sesion sesion = sesionRepository.findById(participante.sesionId())
                .filter(encontrada -> SesionService.enOperacion(encontrada.getEstado()))
                .orElseThrow(() -> ApiException.unauthorized(VerificacionCredencial.SESSION_ENDED,
                        "The session this credential belonged to has ended"));
        return instantanea(sesion, participante.participacionId(), EstadoServicio.de(sesion.getEstado()));
    }

    private ColaResponse instantanea(Sesion sesion, UUID participacionId, EstadoServicio estado) {
        List<Fila> filas = consultas.enEspera(sesion.getId());
        Optional<Sonando> sonando = consultas.sonando(sesion.getId());
        Set<UUID> apoyadas = participacionId == null ? null : consultas.cancionesApoyadas(participacionId, sesion.getId());
        Instant ahora = clock.instant();

        long restante = sonando.map(s -> EsperaEstimada.restante(s.iniciadaAt(), s.duracionOperativa(), ahora)).orElse(0L);
        List<Long> esperas = EsperaEstimada.esperas(restante, filas.stream().map(Fila::duracionOperativa).toList());
        List<ItemCola> items = new ArrayList<>(filas.size());
        for (int i = 0; i < filas.size(); i++) {
            Fila fila = filas.get(i);
            items.add(new ItemCola(fila.solicitudId(), i + 1, fila.votos(), fila.cancion(), esperas.get(i),
                    apoyadas == null ? null : apoyadas.contains(fila.cancionId())));
        }
        EnReproduccion enReproduccion = sonando.map(s -> new EnReproduccion(s.solicitudId(), s.origen(), s.cancion(),
                s.iniciadaAt(), restante, apoyadas == null ? null : apoyadas.contains(s.cancionId()))).orElse(null);
        long tras = esperas.isEmpty() ? restante
                : esperas.get(esperas.size() - 1) + EsperaEstimada.duracion(filas.get(filas.size() - 1).duracionOperativa());
        Optional<ProximasDefecto> proximas = rotacion.proximas(sesion.getId(),
                bloqueos.bloqueadas(sesion.getEstablecimientoId(), ahora), tamanoPrevia);
        return new ColaResponse(sesion.getColaVersion(), estado, enReproduccion, items,
                proximas.map(previa -> previa(previa.cancionIds(), items.size(), tras)).orElse(List.of()),
                proximas.map(previa -> previa.politica().name()).orElse(null));
    }

    /**
     * The default songs, numbered after the {@code delante} requests and waiting {@code espera} (the playing song and
     * every request) plus the default songs ahead of them.
     */
    private List<ItemDefecto> previa(List<UUID> cancionIds, int delante, long espera) {
        Map<UUID, CancionDefecto> canciones = consultas.canciones(cancionIds);
        List<CancionDefecto> enOrden = cancionIds.stream().map(canciones::get).filter(Objects::nonNull).toList();
        List<Long> esperas = EsperaEstimada.esperas(espera, enOrden.stream().map(CancionDefecto::duracionOperativa).toList());
        List<ItemDefecto> items = new ArrayList<>(enOrden.size());
        for (int i = 0; i < enOrden.size(); i++) {
            items.add(new ItemDefecto(delante + i + 1, enOrden.get(i).cancion(), esperas.get(i)));
        }
        return items;
    }
}
