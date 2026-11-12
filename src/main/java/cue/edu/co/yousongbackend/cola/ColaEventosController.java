package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The anonymous public queue as Server-Sent Events, so the screen behind the QR updates without reloading. Events
 * are named {@code cola} and carry the same snapshot as {@code GET .../cola}; see {@link SuscripcionesCola} for when
 * they are sent, keep-alives, limits and why {@code Last-Event-ID} is ignored. The snapshot includes the
 * default-list preview ({@code proximasDefecto}), so a default song starting or a block placed or lifted while the
 * session runs sends a new event too (only when the snapshot actually changed).
 */
@RestController
class ColaEventosController {

    private final EstablecimientoService establecimientos;
    private final SuscripcionesCola suscripciones;

    ColaEventosController(EstablecimientoService establecimientos, SuscripcionesCola suscripciones) {
        this.establecimientos = establecimientos;
        this.suscripciones = suscripciones;
    }

    /**
     * An unknown slug answers 404 and a reached cap 429 before any stream starts. {@code X-Accel-Buffering: no}
     * keeps an nginx in front from buffering the stream.
     */
    @GetMapping(path = "/api/public/establecimientos/{qrSlug}/cola/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> eventos(@PathVariable String qrSlug, HttpServletRequest http) {
        UUID establecimientoId = establecimientos.obtenerPorSlug(qrSlug).getId();
        SseEmitter emitter = suscripciones.suscribir(establecimientoId, http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).header("X-Accel-Buffering", "no").body(emitter);
    }
}
