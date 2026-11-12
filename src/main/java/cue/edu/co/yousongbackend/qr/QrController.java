package cue.edu.co.yousongbackend.qr;

import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The printable access QR of the caller's own establishment, for the admin portal (see {@link PropietarioActual}). */
@RestController
class QrController {

    private final QrService service;
    private final PropietarioActual propietario;

    QrController(QrService service, PropietarioActual propietario) {
        this.service = service;
        this.propietario = propietario;
    }

    @GetMapping("/api/mi-establecimiento/qr")
    QrResponse qr() {
        return service.qrDelEstablecimiento(propietario.establecimientoId());
    }
}
