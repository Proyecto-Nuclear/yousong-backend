package cue.edu.co.yousongbackend.reproduccion;

import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.FinReproduccionRequest;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.FinReproduccionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.InicioReproduccionResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The minimal staff playback driver ({@code /api/staff/**}: administrators and operators, enforced by
 * {@code SecurityConfig}). Authorization is by role only, like the rest of the staff API (see {@code SesionService}).
 */
@RestController
class ReproduccionStaffController {

    private final ReproduccionService service;

    ReproduccionStaffController(ReproduccionService service) {
        this.service = service;
    }

    @PostMapping("/api/staff/sesiones/{sesionId}/reproduccion/siguiente")
    InicioReproduccionResponse siguiente(@PathVariable UUID sesionId) {
        return service.siguiente(sesionId);
    }

    @PostMapping("/api/staff/sesiones/{sesionId}/reproduccion/fin")
    FinReproduccionResponse fin(@PathVariable UUID sesionId, @Valid @RequestBody FinReproduccionRequest request) {
        return service.terminar(sesionId, request.resultado());
    }
}
