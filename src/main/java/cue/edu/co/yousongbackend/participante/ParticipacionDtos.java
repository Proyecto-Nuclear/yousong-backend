package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.acceso.CanalAcceso;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request and response bodies of the participant API. None of them exposes an internal id. */
public final class ParticipacionDtos {

    private ParticipacionDtos() {
    }

    /** What a client sees after scanning the QR. */
    public record EstablecimientoPublicoResponse(String nombre, String qrSlug, EstadoServicio servicio,
                                                 boolean puedeParticipar) {
    }

    public record EstablecimientoResumen(String nombre) {
    }

    /**
     * Joining a session. {@code autorizaF1} must be true (purpose F1 is required to participate);
     * {@code autorizaF2} is optional and recorded only when true. Nothing else about the client is collected.
     */
    public record UnirseRequest(
            @NotNull CanalAcceso canal,
            @NotBlank @Size(max = 20) String politicaVersion,
            Boolean autorizaF1,
            Boolean autorizaF2) {
    }

    /** The token is shown only here, once. */
    public record UnirseResponse(String token, Instant expiraAt, String seudonimo, EstadoServicio servicio,
                                 EstablecimientoResumen establecimiento) {
    }

    /** What a participant polls to notice that its session ended. */
    public record MiSesionResponse(EstablecimientoResumen establecimiento, EstadoServicio servicio, String seudonimo) {
    }
}
