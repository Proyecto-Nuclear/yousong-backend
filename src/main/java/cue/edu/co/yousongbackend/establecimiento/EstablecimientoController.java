package cue.edu.co.yousongbackend.establecimiento;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The establishment of the calling account ({@code /api/mi-establecimiento}). Any valid access token may call
 * it; which establishment is read or changed is decided by {@link PropietarioActual}, never by the client.
 */
@RestController
class EstablecimientoController {

    static final String MI_ESTABLECIMIENTO = "/api/mi-establecimiento";

    private final EstablecimientoConfiguracionService service;
    private final PropietarioActual propietario;

    EstablecimientoController(EstablecimientoConfiguracionService service, PropietarioActual propietario) {
        this.service = service;
        this.propietario = propietario;
    }

    @PostMapping(MI_ESTABLECIMIENTO)
    ResponseEntity<EstablecimientoResponse> crear(@Valid @RequestBody CrearEstablecimientoRequest request) {
        EstablecimientoResponse creado = service.crear(propietario.sub(), request);
        return ResponseEntity.created(URI.create(MI_ESTABLECIMIENTO)).body(creado);
    }

    @GetMapping(MI_ESTABLECIMIENTO)
    EstablecimientoResponse obtener() {
        return service.obtener(propietario.establecimientoId());
    }

    @PatchMapping(MI_ESTABLECIMIENTO)
    EstablecimientoResponse actualizar(@Valid @RequestBody ActualizarEstablecimientoRequest request) {
        return service.actualizar(propietario.establecimientoId(), request);
    }
}
