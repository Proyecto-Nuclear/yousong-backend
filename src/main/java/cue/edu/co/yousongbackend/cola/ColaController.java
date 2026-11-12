package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.cola.ColaDtos.ApoyoResponse;
import cue.edu.co.yousongbackend.cola.ColaDtos.ColaResponse;
import cue.edu.co.yousongbackend.cola.ColaDtos.SolicitarRequest;
import cue.edu.co.yousongbackend.participante.ParticipanteAutenticado;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The queue: the anonymous view behind the QR, and the participant's own view, requests and votes. Participant
 * endpoints answer 401 {@code SESSION_ENDED} once the session closes (see {@code ParticipanteSecurityConfig}).
 */
@RestController
class ColaController {

    private final ColaService cola;
    private final SolicitudesService solicitudes;

    ColaController(ColaService cola, SolicitudesService solicitudes) {
        this.cola = cola;
        this.solicitudes = solicitudes;
    }

    @GetMapping("/api/public/establecimientos/{qrSlug}/cola")
    ColaResponse publica(@PathVariable String qrSlug) {
        return cola.publica(qrSlug);
    }

    @GetMapping("/api/participante/cola")
    ColaResponse deParticipante(@AuthenticationPrincipal ParticipanteAutenticado participante) {
        return cola.deParticipante(participante);
    }

    /** 201 when this call created the request (with the caller's first vote), 200 when it was a vote. */
    @PostMapping("/api/participante/solicitudes")
    ResponseEntity<ApoyoResponse> solicitar(@AuthenticationPrincipal ParticipanteAutenticado participante,
                                            @RequestBody SolicitarRequest request) {
        ApoyoResponse apoyo = solicitudes.solicitar(participante, request.videoId());
        return ResponseEntity.status(apoyo.creada() ? HttpStatus.CREATED : HttpStatus.OK).body(apoyo);
    }

    @PostMapping("/api/participante/solicitudes/{solicitudId}/votos")
    ApoyoResponse votar(@AuthenticationPrincipal ParticipanteAutenticado participante,
                        @PathVariable UUID solicitudId) {
        return solicitudes.votar(participante, solicitudId);
    }
}
