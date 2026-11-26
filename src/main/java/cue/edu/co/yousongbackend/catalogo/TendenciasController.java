package cue.edu.co.yousongbackend.catalogo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/public/tendencias}: the most popular music in Colombia, anonymous (see {@link TendenciasService}).
 * The remote address feeds the per-minute limit only.
 */
@RestController
class TendenciasController {

    private final TendenciasService service;

    TendenciasController(TendenciasService service) {
        this.service = service;
    }

    @GetMapping("/api/public/tendencias")
    TendenciasResponse tendencias(HttpServletRequest http) {
        return service.obtener(http.getRemoteAddr());
    }
}
