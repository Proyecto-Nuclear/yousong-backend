package cue.edu.co.yousongbackend.establecimiento;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Only what is sent is changed. The QR slug is deliberately not editable. */
public record ActualizarEstablecimientoRequest(
        @Size(max = 120) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String nombre,
        @Size(max = 64) String zonaHoraria,
        Boolean activo) {
}
