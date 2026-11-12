package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Establishment owners search the YouTube catalog. Only accounts that own an establishment may search: the others
 * get 404 NO_ESTABLISHMENT before any work, like every other owner route. Owning one costs nothing (creation is
 * self-service), so both routes are rate limited per establishment ({@link LimitadorPropietario}) before any
 * work, and provider calls are charged to the owner's daily budget and share ({@link OrigenBusqueda#propietario}).
 */
@RestController
class CatalogoController {

    private final CatalogoService service;
    private final SugerenciasService sugerencias;
    private final PropietarioActual propietario;
    private final LimitadorPropietario limitador;

    CatalogoController(CatalogoService service, SugerenciasService sugerencias, PropietarioActual propietario,
                       LimitadorPropietario limitador) {
        this.service = service;
        this.sugerencias = sugerencias;
        this.propietario = propietario;
        this.limitador = limitador;
    }

    /** Search-as-you-type: known songs and search strings, without spending YouTube Data API quota. */
    @GetMapping("/api/mi-establecimiento/catalogo/sugerencias")
    SugerenciasResponse sugerir(@RequestParam(name = "q", required = false) String q) {
        limitador.registrarSugerencia(propietario.establecimientoId());
        return sugerencias.sugerir(q);
    }

    @GetMapping("/api/mi-establecimiento/catalogo/busqueda")
    BusquedaResponse buscar(@RequestParam(name = "q", required = false) String q,
                            @RequestParam(name = "pagina", required = false) String pagina) {
        UUID establecimientoId = propietario.establecimientoId();
        limitador.registrarBusqueda(establecimientoId);
        return service.buscar(q, pagina, OrigenBusqueda.propietario(establecimientoId));
    }
}
