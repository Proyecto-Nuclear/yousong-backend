package cue.edu.co.yousongbackend.analitica;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's analytics dashboard: one session computed live ({@link AnaliticaJornadaService}). The establishment
 * is always the caller's own; parameters are read as text so every malformed value gets the documented error.
 */
@RestController
class AnaliticaController {

    private final AnaliticaJornadaService jornada;

    AnaliticaController(AnaliticaJornadaService jornada) {
        this.jornada = jornada;
    }

    @GetMapping("/api/mi-establecimiento/analitica/jornada")
    JornadaResponse jornada(@RequestParam(name = "sesionId", required = false) String sesionId) {
        return jornada.jornada(sesionId);
    }
}
