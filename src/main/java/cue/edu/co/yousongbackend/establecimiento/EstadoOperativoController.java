package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.establecimiento.EstadoOperativoService.MantenimientoResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System pause and maintenance of the caller's establishment (see {@link EstadoOperativoService}). Like every
 * owner route, the establishment comes from the token ({@link PropietarioActual}), never from the request.
 */
@RestController
class EstadoOperativoController {

    private static final String MI = EstablecimientoController.MI_ESTABLECIMIENTO;

    private final EstadoOperativoService service;
    private final PropietarioActual propietario;

    EstadoOperativoController(EstadoOperativoService service, PropietarioActual propietario) {
        this.service = service;
        this.propietario = propietario;
    }

    @PostMapping(MI + "/pausa")
    EstablecimientoResponse pausar() {
        return service.pausar(propietario.establecimientoId());
    }

    @PostMapping(MI + "/reanudacion")
    EstablecimientoResponse reanudar() {
        return service.reanudar(propietario.establecimientoId());
    }

    @PostMapping(MI + "/mantenimiento/inicio")
    MantenimientoResponse iniciarMantenimiento() {
        return service.iniciarMantenimiento(propietario.establecimientoId());
    }

    @PostMapping(MI + "/mantenimiento/fin")
    EstablecimientoResponse finalizarMantenimiento() {
        return service.finalizarMantenimiento(propietario.establecimientoId());
    }
}
