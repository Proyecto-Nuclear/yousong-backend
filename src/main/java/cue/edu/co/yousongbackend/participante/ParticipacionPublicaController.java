package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.participante.ParticipacionDtos.EstablecimientoPublicoResponse;
import cue.edu.co.yousongbackend.participante.ParticipacionDtos.UnirseRequest;
import cue.edu.co.yousongbackend.participante.ParticipacionDtos.UnirseResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous endpoints behind the QR {@code /s/{qrSlug}}: see the service, then join it. */
@RestController
class ParticipacionPublicaController {

    private final ParticipacionService service;

    ParticipacionPublicaController(ParticipacionService service) {
        this.service = service;
    }

    @GetMapping("/api/public/establecimientos/{qrSlug}")
    EstablecimientoPublicoResponse estado(@PathVariable String qrSlug) {
        return service.estadoPublico(qrSlug);
    }

    /**
     * The response carries the credential, so no cache (browser or proxy) may keep it. The remote address
     * feeds the join limiter only (see {@link LimitadorUniones} for running behind a reverse proxy).
     */
    @PostMapping("/api/public/establecimientos/{qrSlug}/participaciones")
    ResponseEntity<UnirseResponse> unirse(@PathVariable String qrSlug, @Valid @RequestBody UnirseRequest request,
                                          HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.unirse(qrSlug, request, http.getRemoteAddr()));
    }
}
