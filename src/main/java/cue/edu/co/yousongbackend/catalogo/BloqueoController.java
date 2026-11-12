package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.catalogo.BloqueoDtos.BloqueoResponse;
import cue.edu.co.yousongbackend.catalogo.BloqueoDtos.CrearBloqueoRequest;
import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Content blocks of the caller's own establishment (see {@link PropietarioActual}):
 * <ul>
 *   <li>{@code POST /api/mi-establecimiento/bloqueos} 201: block a song for some minutes or permanently.</li>
 *   <li>{@code GET /api/mi-establecimiento/bloqueos[?todos=true]}: the blocks in force, or the whole history.</li>
 *   <li>{@code DELETE /api/mi-establecimiento/bloqueos/{id}} 200: lift a block early (idempotent).</li>
 * </ul>
 * The only id taken from the client is the id of one of the owner's own blocks; any other is 404.
 */
@RestController
class BloqueoController {

    private final BloqueoContenidoService service;
    private final PropietarioActual propietario;

    BloqueoController(BloqueoContenidoService service, PropietarioActual propietario) {
        this.service = service;
        this.propietario = propietario;
    }

    @PostMapping("/api/mi-establecimiento/bloqueos")
    @ResponseStatus(HttpStatus.CREATED)
    BloqueoResponse bloquear(@RequestBody CrearBloqueoRequest request) {
        return service.bloquear(propietario.establecimientoId(), request);
    }

    @GetMapping("/api/mi-establecimiento/bloqueos")
    List<BloqueoResponse> listar(@RequestParam(defaultValue = "false") boolean todos) {
        return service.listar(propietario.establecimientoId(), todos);
    }

    @DeleteMapping("/api/mi-establecimiento/bloqueos/{bloqueoId}")
    BloqueoResponse levantar(@PathVariable UUID bloqueoId) {
        return service.levantar(propietario.establecimientoId(), bloqueoId);
    }
}
