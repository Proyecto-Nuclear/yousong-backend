package cue.edu.co.yousongbackend.establecimiento;

import java.time.Instant;
import java.util.UUID;

/** What clients see of an establishment. The owner's identity-provider subject is never part of it. */
public record EstablecimientoResponse(
        UUID id,
        String nombre,
        String qrSlug,
        String zonaHoraria,
        boolean activo,
        boolean pausado,
        boolean enMantenimiento,
        Instant creadoAt) {

    static EstablecimientoResponse de(Establecimiento e) {
        return new EstablecimientoResponse(e.getId(), e.getNombre(), e.getQrSlug(), e.getZonaHoraria(),
                e.isActivo(), e.isPausado(), e.isEnMantenimiento(), e.getCreadoAt());
    }
}
