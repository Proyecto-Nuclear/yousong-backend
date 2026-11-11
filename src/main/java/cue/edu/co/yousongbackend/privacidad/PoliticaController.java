package cue.edu.co.yousongbackend.privacidad;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The current privacy policy, public: a client shows it before joining a session. */
@RestController
class PoliticaController {

    record PoliticaResponse(String version, List<PoliticaPrivacidad.Finalidad> finalidades) {
    }

    private final PoliticaPrivacidad politica;

    PoliticaController(PoliticaPrivacidad politica) {
        this.politica = politica;
    }

    @GetMapping("/api/public/politica")
    PoliticaResponse politica() {
        return new PoliticaResponse(politica.version(), politica.finalidades());
    }
}
