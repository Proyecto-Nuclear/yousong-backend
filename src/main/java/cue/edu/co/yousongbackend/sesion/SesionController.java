package cue.edu.co.yousongbackend.sesion;

import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The owner opens, reads and closes the session of their own establishment (see {@link PropietarioActual}). */
@RestController
class SesionController {

    private final SesionService service;
    private final PropietarioActual propietario;

    SesionController(SesionService service, PropietarioActual propietario) {
        this.service = service;
        this.propietario = propietario;
    }

    @PostMapping("/api/mi-establecimiento/sesiones")
    ResponseEntity<SesionResponse> abrir() {
        SesionResponse sesion = service.abrir(propietario.establecimientoId());
        return ResponseEntity.created(URI.create("/api/mi-establecimiento/sesion-actual")).body(sesion);
    }

    @PostMapping("/api/mi-establecimiento/sesion/cierre")
    CierreSesionResponse cerrar() {
        return service.cerrarActual(propietario.establecimientoId());
    }

    @GetMapping("/api/mi-establecimiento/sesion-actual")
    SesionResponse sesionActual() {
        return service.sesionActual(propietario.establecimientoId());
    }
}
