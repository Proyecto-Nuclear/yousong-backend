package cue.edu.co.yousongbackend.catalogo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff (administrators and operators) search the YouTube catalog. Role checks live in SecurityConfig. */
@RestController
class CatalogoController {

    private final CatalogoService service;

    CatalogoController(CatalogoService service) {
        this.service = service;
    }

    @GetMapping("/api/staff/catalogo/busqueda")
    BusquedaResponse buscar(@RequestParam(name = "q", required = false) String q,
                            @RequestParam(name = "pagina", required = false) String pagina) {
        return service.buscar(q, pagina);
    }
}
