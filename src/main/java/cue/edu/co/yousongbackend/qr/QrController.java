package cue.edu.co.yousongbackend.qr;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The QR of the session in operation, for the admin portal. Administrators only (see {@code SecurityConfig}). */
@RestController
class QrController {

    private final QrService service;

    QrController(QrService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/establecimientos/{establecimientoId}/qr")
    QrResponse qr(@PathVariable UUID establecimientoId) {
        return service.qrDeLaSesionActual(establecimientoId);
    }
}
