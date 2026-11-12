package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Establishment owners search the YouTube catalog (origin STAFF). Only accounts that own an establishment may
 * search: the others get 404 NO_ESTABLISHMENT before any work, like every other owner route.
 */
@RestController
class CatalogoController {

    private final CatalogoService service;
    private final SugerenciasService sugerencias;
    private final PropietarioActual propietario;

    CatalogoController(CatalogoService service, SugerenciasService sugerencias, PropietarioActual propietario) {
        this.service = service;
        this.sugerencias = sugerencias;
        this.propietario = propietario;
    }

    /** Search-as-you-type: known songs and search strings, without spending YouTube Data API quota. */
    @GetMapping("/api/mi-establecimiento/catalogo/sugerencias")
    SugerenciasResponse sugerir(@RequestParam(name = "q", required = false) String q) {
        propietario.establecimientoId();
        return sugerencias.sugerir(q);
    }

    @GetMapping("/api/mi-establecimiento/catalogo/busqueda")
    BusquedaResponse buscar(@RequestParam(name = "q", required = false) String q,
                            @RequestParam(name = "pagina", required = false) String pagina) {
        propietario.establecimientoId();
        return service.buscar(q, pagina);
    }
}
