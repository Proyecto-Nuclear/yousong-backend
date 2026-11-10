package cue.edu.co.yousongbackend.sesion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** What clients see of a session. */
public record SesionResponse(
        UUID id,
        UUID establecimientoId,
        UUID listaId,
        LocalDate fechaOperativa,
        EstadoSesion estado,
        Instant abiertaAt,
        Instant cerradaAt) {

    static SesionResponse de(Sesion sesion) {
        return new SesionResponse(sesion.getId(), sesion.getEstablecimientoId(), sesion.getListaId(),
                sesion.getFechaOperativa(), sesion.getEstado(), sesion.getAbiertaAt(), sesion.getCerradaAt());
    }
}
