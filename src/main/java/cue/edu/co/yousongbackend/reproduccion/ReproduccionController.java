package cue.edu.co.yousongbackend.reproduccion;

import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.EliminacionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.FinReproduccionRequest;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.FinReproduccionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.InicioReproduccionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.SaltoRequest;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.SaltoResponse;
import cue.edu.co.yousongbackend.sesion.SesionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The minimal playback driver of the owner: it acts on the session in operation of the caller's own
 * establishment (see {@link PropietarioActual}); no session id is taken from the client.
 * <ul>
 *   <li>{@code POST /api/mi-establecimiento/reproduccion/siguiente}: start the head of the queue, else the
 *       default list.</li>
 *   <li>{@code POST /api/mi-establecimiento/reproduccion/fin}: end the playing song (COMPLETADA, SALTADA with an
 *       optional {@code motivoCodigo}, FALLIDA).</li>
 *   <li>{@code POST /api/mi-establecimiento/reproduccion/saltar}: skip the playing song with a reason and start
 *       the next one.</li>
 *   <li>{@code DELETE /api/mi-establecimiento/cola/{solicitudId}}: take a waiting request out of the queue (only
 *       requests of the owner's session in operation; any other id is 404).</li>
 * </ul>
 */
@RestController
class ReproduccionController {

    private final ReproduccionService service;
    private final SesionService sesiones;
    private final PropietarioActual propietario;

    ReproduccionController(ReproduccionService service, SesionService sesiones, PropietarioActual propietario) {
        this.service = service;
        this.sesiones = sesiones;
        this.propietario = propietario;
    }

    @PostMapping("/api/mi-establecimiento/reproduccion/siguiente")
    InicioReproduccionResponse siguiente() {
        return service.siguiente(sesionEnOperacion());
    }

    @PostMapping("/api/mi-establecimiento/reproduccion/fin")
    FinReproduccionResponse fin(@Valid @RequestBody FinReproduccionRequest request) {
        return service.terminar(sesionEnOperacion(), request.resultado(), request.motivoCodigo());
    }

    @PostMapping("/api/mi-establecimiento/reproduccion/saltar")
    SaltoResponse saltar(@RequestBody(required = false) SaltoRequest request) {
        return service.saltar(sesionEnOperacion(), request == null ? null : request.motivoCodigo());
    }

    @DeleteMapping("/api/mi-establecimiento/cola/{solicitudId}")
    EliminacionResponse eliminarDeCola(@PathVariable UUID solicitudId) {
        return service.eliminarDeCola(sesionEnOperacion(), solicitudId);
    }

    private UUID sesionEnOperacion() {
        return sesiones.idEnOperacion(propietario.establecimientoId());
    }
}
