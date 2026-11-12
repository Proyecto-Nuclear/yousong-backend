package cue.edu.co.yousongbackend.reproduccion;

import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.FinReproduccionRequest;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.FinReproduccionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.InicioReproduccionResponse;
import cue.edu.co.yousongbackend.sesion.SesionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The minimal playback driver of the owner: it acts on the session in operation of the caller's own
 * establishment (see {@link PropietarioActual}); no session id is taken from the client.
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
        return service.siguiente(sesiones.idEnOperacion(propietario.establecimientoId()));
    }

    @PostMapping("/api/mi-establecimiento/reproduccion/fin")
    FinReproduccionResponse fin(@Valid @RequestBody FinReproduccionRequest request) {
        return service.terminar(sesiones.idEnOperacion(propietario.establecimientoId()), request.resultado());
    }
}
