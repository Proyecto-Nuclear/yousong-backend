package cue.edu.co.yousongbackend.destacadas;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/public/establecimientos/{qrSlug}/destacadas?dias=30}: the most voted, requested and played songs
 * of an establishment and its last playbacks, anonymous (see {@link DestacadasService}). {@code dias} is read as
 * text so every malformed value gets the same 400 {@code INVALID_PERIOD}. The remote address feeds the per-minute
 * limit only.
 */
@RestController
class DestacadasController {

    private final DestacadasService service;

    DestacadasController(DestacadasService service) {
        this.service = service;
    }

    @GetMapping("/api/public/establecimientos/{qrSlug}/destacadas")
    DestacadasResponse destacadas(@PathVariable String qrSlug, @RequestParam(name = "dias", required = false) String dias,
                                  HttpServletRequest http) {
        return service.destacadas(qrSlug, dias, http.getRemoteAddr());
    }
}
