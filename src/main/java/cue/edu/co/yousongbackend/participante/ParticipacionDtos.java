package cue.edu.co.yousongbackend.participante;

import com.fasterxml.jackson.annotation.JsonInclude;
import cue.edu.co.yousongbackend.acceso.CanalAcceso;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request and response bodies of the participant API. None of them exposes an internal id. */
public final class ParticipacionDtos {

    private ParticipacionDtos() {
    }

    /**
     * What a client sees after scanning the QR. {@code proximaApertura} (optional, omitted when null) is the start
     * of the next weekly window while the establishment is closed because it is outside its opening hours.
     */
    public record EstablecimientoPublicoResponse(String nombre, String qrSlug, EstadoServicio servicio,
                                                 boolean puedeParticipar,
                                                 @JsonInclude(JsonInclude.Include.NON_NULL) Instant proximaApertura) {
    }

    public record EstablecimientoResumen(String nombre) {
    }

    /**
     * Joining a session. {@code autorizaF1} must be true (purpose F1 is required to participate);
     * {@code autorizaF2} is optional and recorded only when true. {@code dispositivoToken} is optional: the
     * device secret a previous join returned, so the same phone gets its participation back instead of a
     * second one. A missing, unknown, expired or revoked one simply yields a new device. Nothing else about
     * the client is collected.
     */
    public record UnirseRequest(
            @NotNull CanalAcceso canal,
            @NotBlank @Size(max = 20) String politicaVersion,
            Boolean autorizaF1,
            Boolean autorizaF2,
            String dispositivoToken) {

        /** The device secret must never reach a log through this record. */
        @Override
        public String toString() {
            return "UnirseRequest[canal=" + canal + ", politicaVersion=" + politicaVersion + ", autorizaF1="
                    + autorizaF1 + ", autorizaF2=" + autorizaF2 + ", dispositivoToken="
                    + (dispositivoToken == null ? "null" : "<redacted>") + "]";
        }
    }

    /**
     * The token is shown only here, once. {@code dispositivoToken} is present only when this join minted a
     * new device: the client stores it and sends it on later joins. A recognized device never gets it again.
     */
    public record UnirseResponse(String token, Instant expiraAt, String seudonimo, EstadoServicio servicio,
                                 EstablecimientoResumen establecimiento,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String dispositivoToken) {

        /** The device secret must never reach a log through this record. */
        @Override
        public String toString() {
            return "UnirseResponse[expiraAt=" + expiraAt + ", seudonimo=" + seudonimo + ", servicio=" + servicio
                    + ", establecimiento=" + establecimiento + "]";
        }
    }

    /** What a participant polls to notice that its session ended. */
    public record MiSesionResponse(EstablecimientoResumen establecimiento, EstadoServicio servicio, String seudonimo) {
    }
}
