package cue.edu.co.yousongbackend.catalogo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff (administrators and operators) search the YouTube catalog. Role checks live in SecurityConfig. */
@RestController
class CatalogoController {

    private final CatalogoService service;
    private final SugerenciasService sugerencias;

    CatalogoController(CatalogoService service, SugerenciasService sugerencias) {
        this.service = service;
        this.sugerencias = sugerencias;
    }

    /** Search-as-you-type: known songs and search strings, without spending YouTube Data API quota. */
    @GetMapping("/api/staff/catalogo/sugerencias")
    SugerenciasResponse sugerir(@RequestParam(name = "q", required = false) String q) {
        return sugerencias.sugerir(q);
    }

    @GetMapping("/api/staff/catalogo/busqueda")
    BusquedaResponse buscar(@RequestParam(name = "q", required = false) String q,
                            @RequestParam(name = "pagina", required = false) String pagina) {
        return service.buscar(q, pagina);
    }
}
