package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.participante.ParticipacionDtos.MiSesionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for a participant holding a credential (see {@code ParticipanteSecurityConfig}). Every call
 * answers 401 {@code SESSION_ENDED} once the session closes, which tells the client to forget everything.
 */
@RestController
class ParticipanteController {

    private final ParticipacionService service;

    ParticipanteController(ParticipacionService service) {
        this.service = service;
    }

    /** Clients poll this to notice the end of the session. */
    @GetMapping("/api/participante/sesion")
    MiSesionResponse miSesion(@AuthenticationPrincipal ParticipanteAutenticado participante) {
        return service.miSesion(participante);
    }

    /** Leave voluntarily; the credential stops working (401 INVALID_CREDENTIAL afterwards). */
    @DeleteMapping("/api/participante/sesion")
    ResponseEntity<Void> salir(@AuthenticationPrincipal ParticipanteAutenticado participante) {
        service.salir(participante);
        return ResponseEntity.noContent().build();
    }
}
