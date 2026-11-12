package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.cola.ColaConsultas.Fila;
import cue.edu.co.yousongbackend.cola.ColaConsultas.Sonando;
import cue.edu.co.yousongbackend.cola.ColaDtos.ColaResponse;
import cue.edu.co.yousongbackend.cola.ColaDtos.EnReproduccion;
import cue.edu.co.yousongbackend.cola.ColaDtos.ItemCola;
import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.participante.EstadoServicio;
import cue.edu.co.yousongbackend.participante.ParticipanteAutenticado;
import cue.edu.co.yousongbackend.participante.VerificacionCredencial;
import cue.edu.co.yousongbackend.sesion.Sesion;
import cue.edu.co.yousongbackend.sesion.SesionRepository;
import cue.edu.co.yousongbackend.sesion.SesionService;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshots of the queue. Each one is read in a single REPEATABLE READ transaction, so its {@code colaVersion},
 * the song playing and the items all belong to the same moment. Estimated waits: see {@link EsperaEstimada}.
 */
@Service
public class ColaService {

    private final EstablecimientoService establecimientos;
    private final SesionService sesiones;
    private final SesionRepository sesionRepository;
    private final ColaConsultas consultas;
    private final Clock clock;

    ColaService(EstablecimientoService establecimientos, SesionService sesiones, SesionRepository sesionRepository,
                ColaConsultas consultas, Clock clock) {
        this.establecimientos = establecimientos;
        this.sesiones = sesiones;
        this.sesionRepository = sesionRepository;
        this.consultas = consultas;
        this.clock = clock;
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
        return sesion.map(enOperacion -> instantanea(enOperacion, null))
                .orElseGet(() -> new ColaResponse(0, EstadoServicio.CERRADO, null, List.of()));
    }

    /** The same snapshot for a participant, saying on every item whether they already supported that song. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ColaResponse deParticipante(ParticipanteAutenticado participante) {
        Sesion sesion = sesionRepository.findById(participante.sesionId())
                .filter(encontrada -> SesionService.enOperacion(encontrada.getEstado()))
                .orElseThrow(() -> ApiException.unauthorized(VerificacionCredencial.SESSION_ENDED,
                        "The session this credential belonged to has ended"));
        return instantanea(sesion, participante.participacionId());
    }

    private ColaResponse instantanea(Sesion sesion, UUID participacionId) {
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
        return new ColaResponse(sesion.getColaVersion(), EstadoServicio.de(sesion.getEstado()), enReproduccion, items);
    }
}
