package cue.edu.co.yousongbackend.sesion;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opening and closing are administrator operations ({@code /api/admin/**}); any staff member can read the
 * current session ({@code /api/staff/**}). The roles are enforced by {@code SecurityConfig}.
 */
@RestController
class SesionController {

    private final SesionService service;

    SesionController(SesionService service) {
        this.service = service;
    }

    @PostMapping("/api/admin/establecimientos/{establecimientoId}/sesiones")
    ResponseEntity<SesionResponse> abrir(@PathVariable UUID establecimientoId) {
        SesionResponse sesion = service.abrir(establecimientoId);
        return ResponseEntity
                .created(URI.create("/api/staff/establecimientos/" + establecimientoId + "/sesion-actual"))
                .body(sesion);
    }

    @PostMapping("/api/admin/sesiones/{sesionId}/cierre")
    CierreSesionResponse cerrar(@PathVariable UUID sesionId) {
        return service.cerrar(sesionId);
    }

    @GetMapping("/api/staff/establecimientos/{establecimientoId}/sesion-actual")
    SesionResponse sesionActual(@PathVariable UUID establecimientoId) {
        return service.sesionActual(establecimientoId);
    }
}
