package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.cola.ColaDtos.ColaResponse;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live subscribers of the public queue ({@code GET /api/public/establecimientos/{qrSlug}/cola/eventos}), keyed
 * by establishment id, and the fan-out of a new snapshot after every committed queue change.
 *
 * <p><b>When.</b> Writers publish {@link ColaCambiada} inside their transaction ({@link VersionCola}); this class
 * handles it {@code AFTER_COMMIT}, so subscribers only ever see committed state and a rolled-back action notifies
 * nobody. The listener only flips a flag and hands the work to an executor: it never throws into the committing
 * thread, never touches the database there and never writes to a client there.
 *
 * <p><b>What.</b> Every event is a full public snapshot ({@link ColaService#publicaDe}, the same JSON as
 * {@code GET .../cola}, no identities) named {@code cola} with {@code id:} = {@code colaVersion}. A reconnecting
 * client may send {@code Last-Event-ID}; it is ignored on purpose, because the first event of every connection is
 * already a full snapshot. Two identical consecutive snapshots are sent to a subscriber once.
 *
 * <p><b>Ordering and slow clients.</b> Per establishment, one drain at a time reads the snapshot (one database
 * read per change, whatever the number of subscribers) and hands it to every subscriber's mailbox, in commit order.
 * A mailbox holds only the latest snapshot plus a pending keep-alive (latest wins: every event is a full snapshot,
 * so an intermediate one a slow client never got is not needed), and each subscriber is written by its own task,
 * one at a time. Handing over never blocks, so a slow or stuck client only delays itself and holds at most one
 * pending snapshot; a blocked write is bounded by the container's socket write timeout and the stream's lifetime.
 * Tasks run on virtual threads; their number is bounded by the subscriber caps (one drain per establishment with
 * subscribers, one writer per subscriber).
 *
 * <p><b>Limits.</b> At most {@code app.cola.sse-max-per-establishment} streams per establishment and
 * {@code app.cola.sse-max-per-address} per client address (429 {@code TOO_MANY_STREAMS}); a stream lives at most
 * {@code app.cola.sse-timeout-minutes} and the client reconnects. The address is the servlet remote address, as in
 * {@code LimitadorUniones} (no {@code X-Forwarded-For}; behind a proxy enable {@code server.forward-headers-strategy}).
 * A subscriber is removed on completion, timeout, error or a failed write; the keep-alive comment every
 * {@code app.cola.sse-heartbeat-millis} also reveals clients that left without closing.
 *
 * <p><b>Single instance.</b> The registry lives in memory: a change committed by one instance only reaches the
 * subscribers connected to that instance. Running several instances needs a shared broadcast (for example relaying
 * {@code queue.changed} from the outbox to every instance).
 */
@Component
public class SuscripcionesCola implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SuscripcionesCola.class);

    static final String EVENTO = "cola";
    static final String LATIDO = " keep-alive";

    /** A snapshot ready to send: its version and its JSON, serialized once for every subscriber. */
    private record Instantanea(long colaVersion, String json) {
    }

    private final ColaService cola;
    private final JsonMapper json;
    private final long duracionMillis;
    private final int maximoPorEstablecimiento;
    private final int maximoPorDireccion;

    private final Map<UUID, Canal> canales = new ConcurrentHashMap<>();
    /** Open streams per client address; guarded by {@code this}, like the membership of the channels. */
    private final Map<String, Integer> porDireccion = new HashMap<>();
    private final ExecutorService envios = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cola-sse-", 0).factory());
    private final ScheduledExecutorService latidos = Executors.newSingleThreadScheduledExecutor(tarea -> {
        Thread hilo = new Thread(tarea, "cola-sse-latido");
        hilo.setDaemon(true);
        return hilo;
    });
    private volatile boolean cerrado;

    SuscripcionesCola(ColaService cola, JsonMapper json,
                      @Value("${app.cola.sse-timeout-minutes:30}") long duracionMinutos,
                      @Value("${app.cola.sse-heartbeat-millis:15000}") long latidoMillis,
                      @Value("${app.cola.sse-max-per-establishment:500}") int maximoPorEstablecimiento,
                      @Value("${app.cola.sse-max-per-address:10}") int maximoPorDireccion) {
        if (duracionMinutos <= 0 || latidoMillis <= 0 || maximoPorEstablecimiento <= 0 || maximoPorDireccion <= 0) {
            throw new IllegalStateException("app.cola.sse-* settings must be positive");
        }
        this.cola = cola;
        this.json = json;
        this.duracionMillis = TimeUnit.MINUTES.toMillis(duracionMinutos);
        this.maximoPorEstablecimiento = maximoPorEstablecimiento;
        this.maximoPorDireccion = maximoPorDireccion;
        latidos.scheduleAtFixedRate(this::latir, latidoMillis, latidoMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a stream of the establishment's public queue for a client address. The first event (the current
     * snapshot) follows right after; throws 429 {@code TOO_MANY_STREAMS} when a cap is reached.
     */
    SseEmitter suscribir(UUID establecimientoId, String direccion) {
        Suscriptor suscriptor;
        synchronized (this) {
            if (cerrado) {
                throw ApiException.serviceUnavailable("SHUTTING_DOWN", "The server is restarting. Try again in a moment");
            }
            Canal existente = canales.get(establecimientoId);
            if (existente != null && existente.suscriptores.size() >= maximoPorEstablecimiento) {
                throw ApiException.tooManyRequests("TOO_MANY_STREAMS",
                        "Too many screens are following this queue right now. Reload the page in a moment");
            }
            int deLaDireccion = porDireccion.getOrDefault(direccion, 0);
            if (deLaDireccion >= maximoPorDireccion) {
                throw ApiException.tooManyRequests("TOO_MANY_STREAMS",
                        "Too many open queue streams from this device: at most " + maximoPorDireccion);
            }
            porDireccion.put(direccion, deLaDireccion + 1);
            Canal canal = existente != null ? existente : canales.computeIfAbsent(establecimientoId, Canal::new);
            suscriptor = new Suscriptor(canal, direccion, new SseEmitter(duracionMillis));
            canal.suscriptores.add(suscriptor);
        }
        SseEmitter emitter = suscriptor.emitter;
        emitter.onCompletion(() -> quitar(suscriptor));
        emitter.onError(error -> quitar(suscriptor));
        emitter.onTimeout(() -> {
            quitar(suscriptor);
            emitter.complete();
        });
        suscriptor.canal.pedirInicial(suscriptor);
        return emitter;
    }

    /**
     * After the commit of a queue change: schedules a fresh snapshot for the establishment's subscribers. Never
     * throws (it runs inside the commit of the writer), and does nothing when nobody is subscribed.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void alConfirmar(ColaCambiada cambio) {
        try {
            Canal canal = canales.get(cambio.establecimientoId());
            if (canal != null) {
                canal.notificar();
            }
        } catch (RuntimeException e) {
            log.warn("Could not schedule the queue snapshot for establishment {}", cambio.establecimientoId(), e);
        }
    }

    /** Ends every stream before the web server's graceful shutdown waits for in-flight requests. */
    @EventListener(ContextClosedEvent.class)
    void alCerrarContexto() {
        List<Suscriptor> todos;
        synchronized (this) {
            cerrado = true;
            todos = new ArrayList<>();
            canales.values().forEach(canal -> todos.addAll(canal.suscriptores));
        }
        for (Suscriptor suscriptor : todos) {
            quitar(suscriptor);
            try {
                suscriptor.emitter.complete();
            } catch (RuntimeException ignored) {
                // Already completed by the container.
            }
        }
    }

    @Override
    public void destroy() {
        latidos.shutdownNow();
        envios.shutdownNow();
    }

    /** Open streams, all establishments together (package-visible for tests). */
    synchronized int suscriptores() {
        return porDireccion.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Open streams of one establishment (package-visible for tests). */
    int suscriptores(UUID establecimientoId) {
        Canal canal = canales.get(establecimientoId);
        return canal == null ? 0 : canal.suscriptores.size();
    }

    /** Snapshots read and handed to every subscriber of the establishment since its channel opened (for tests). */
    long difusiones(UUID establecimientoId) {
        Canal canal = canales.get(establecimientoId);
        return canal == null ? 0 : canal.difusiones.get();
    }

    private void quitar(Suscriptor suscriptor) {
        if (!suscriptor.activo.compareAndSet(true, false)) {
            return;
        }
        synchronized (this) {
            Canal canal = suscriptor.canal;
            canal.suscriptores.remove(suscriptor);
            porDireccion.computeIfPresent(suscriptor.direccion, (direccion, abiertos) -> abiertos <= 1 ? null : abiertos - 1);
            if (canal.suscriptores.isEmpty()) {
                canales.remove(canal.establecimientoId, canal);
            }
        }
    }

    private void latir() {
        try {
            canales.values().forEach(canal -> canal.suscriptores.forEach(Suscriptor::latir));
        } catch (RuntimeException e) {
            log.warn("Could not schedule the queue keep-alives", e);
        }
    }

    /** Runs the task on its own virtual thread; false when the application is shutting down. */
    private boolean ejecutar(Runnable tarea) {
        try {
            envios.execute(tarea);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** The subscribers of one establishment and its serialized drain of snapshots. */
    private final class Canal {

        private final UUID establecimientoId;
        private final Set<Suscriptor> suscriptores = ConcurrentHashMap.newKeySet();
        private final Queue<Suscriptor> nuevos = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean cambio = new AtomicBoolean();
        private final AtomicBoolean drenando = new AtomicBoolean();
        private final AtomicLong difusiones = new AtomicLong();

        private Canal(UUID establecimientoId) {
            this.establecimientoId = establecimientoId;
        }

        void notificar() {
            cambio.set(true);
            programar();
        }

        void pedirInicial(Suscriptor suscriptor) {
            nuevos.add(suscriptor);
            programar();
        }

        private void programar() {
            if (drenando.compareAndSet(false, true) && !ejecutar(this::drenar)) {
                drenando.set(false);
            }
        }

        /**
         * One at a time per establishment: reads the snapshot once and hands it over, either to everybody (after a
         * change) or only to the new subscribers (their first event). Changes that arrive meanwhile are coalesced
         * into the next round, which reads a newer committed snapshot.
         */
        private void drenar() {
            try {
                while (true) {
                    boolean paraTodos = cambio.getAndSet(false);
                    List<Suscriptor> iniciales = new ArrayList<>();
                    for (Suscriptor nuevo; (nuevo = nuevos.poll()) != null; ) {
                        iniciales.add(nuevo);
                    }
                    if (!paraTodos && iniciales.isEmpty()) {
                        return;
                    }
                    Instantanea instantanea;
                    try {
                        ColaResponse snapshot = cola.publicaDe(establecimientoId);
                        instantanea = new Instantanea(snapshot.colaVersion(), json.writeValueAsString(snapshot));
                    } catch (RuntimeException e) {
                        // The new subscribers cannot get their first event: end them so their client reconnects.
                        log.warn("Could not read the public queue of establishment {}", establecimientoId, e);
                        iniciales.forEach(Suscriptor::abandonar);
                        continue;
                    }
                    if (paraTodos) {
                        difusiones.incrementAndGet();
                        suscriptores.forEach(suscriptor -> suscriptor.entregar(instantanea));
                    } else {
                        iniciales.forEach(suscriptor -> suscriptor.entregar(instantanea));
                    }
                }
            } finally {
                drenando.set(false);
                if (cambio.get() || !nuevos.isEmpty()) {
                    programar();
                }
            }
        }
    }

    /** One open stream: a mailbox (latest snapshot, pending keep-alive) written by one task at a time. */
    private final class Suscriptor {

        private final Canal canal;
        private final String direccion;
        private final SseEmitter emitter;
        private final AtomicBoolean activo = new AtomicBoolean(true);
        private final AtomicReference<Instantanea> pendiente = new AtomicReference<>();
        private final AtomicBoolean latido = new AtomicBoolean();
        private final AtomicBoolean enviando = new AtomicBoolean();
        /** JSON of the last snapshot written; only the (one at a time) writer task touches it. */
        private volatile String ultimo;

        private Suscriptor(Canal canal, String direccion, SseEmitter emitter) {
            this.canal = canal;
            this.direccion = direccion;
            this.emitter = emitter;
        }

        void entregar(Instantanea instantanea) {
            pendiente.set(instantanea);
            programar();
        }

        void latir() {
            latido.set(true);
            programar();
        }

        void abandonar() {
            quitar(this);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Already completed.
            }
        }

        private void programar() {
            if (activo.get() && enviando.compareAndSet(false, true) && !ejecutar(this::enviar)) {
                enviando.set(false);
            }
        }

        private void enviar() {
            try {
                while (activo.get()) {
                    Instantanea instantanea = pendiente.getAndSet(null);
                    if (instantanea != null) {
                        if (!instantanea.json().equals(ultimo)) {
                            emitter.send(SseEmitter.event().name(EVENTO).id(Long.toString(instantanea.colaVersion()))
                                    .data(instantanea.json()));
                            ultimo = instantanea.json();
                        }
                    } else if (latido.getAndSet(false)) {
                        emitter.send(SseEmitter.event().comment(LATIDO));
                    } else {
                        break;
                    }
                }
            } catch (IOException e) {
                // The client left: the container completes the request; only this subscriber is dropped.
                quitar(this);
            } catch (RuntimeException e) {
                // Already completed, or the payload could not be written: drop only this subscriber.
                quitar(this);
                try {
                    emitter.completeWithError(e);
                } catch (RuntimeException ignored) {
                    // Already completed.
                }
            } finally {
                enviando.set(false);
                if (activo.get() && (pendiente.get() != null || latido.get())) {
                    programar();
                }
            }
        }
    }
}
