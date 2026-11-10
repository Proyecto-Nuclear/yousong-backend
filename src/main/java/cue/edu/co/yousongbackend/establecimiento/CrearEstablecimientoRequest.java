package cue.edu.co.yousongbackend.establecimiento;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code qrSlug} and {@code zonaHoraria} are optional: the slug is generated from the name and the zone
 * defaults to America/Bogota. The slug cannot be changed later because it is printed in the QR codes.
 */
public record CrearEstablecimientoRequest(
        @NotBlank @Size(max = 120) String nombre,
        @Pattern(regexp = "[a-z0-9-]{3,80}", message = "must be 3-80 characters: lowercase letters, digits and hyphens") String qrSlug,
        @Size(max = 64) String zonaHoraria) {
}
