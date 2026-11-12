package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.establecimiento.HorarioDtos.VentanaRequest;
import cue.edu.co.yousongbackend.establecimiento.HorarioDtos.VentanaResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The weekly windows of the caller's establishment. {@code PUT} replaces the whole set (at most 21 windows):
 * {@code [{"diaSemana":1-7,"inicio":"HH:mm","fin":"HH:mm"}]}, 1 = Monday, wall-clock times in the establishment's
 * time zone. A window whose {@code fin} is not after {@code inicio} ends the next day.
 */
@RestController
class HorarioController {

    static final String HORARIOS = "/api/mi-establecimiento/horarios";

    private final HorarioService service;
    private final PropietarioActual propietario;

    HorarioController(HorarioService service, PropietarioActual propietario) {
        this.service = service;
        this.propietario = propietario;
    }

    @GetMapping(HORARIOS)
    List<VentanaResponse> listar() {
        return service.listar(propietario.establecimientoId());
    }

    @PutMapping(HORARIOS)
    List<VentanaResponse> reemplazar(@RequestBody List<VentanaRequest> ventanas) {
        return service.reemplazar(propietario.establecimientoId(), ventanas);
    }
}
