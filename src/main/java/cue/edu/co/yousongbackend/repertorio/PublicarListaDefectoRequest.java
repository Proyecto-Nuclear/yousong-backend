package cue.edu.co.yousongbackend.repertorio;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Publishes a new version of the default list. {@code canciones} are YouTube video ids in playing order
 * (position 1 is the first). Name and rotation policy are optional: "General" and SECUENCIAL by default.
 */
public record PublicarListaDefectoRequest(
        @Size(max = 100) String nombre,
        PoliticaRotacion politicaRotacion,
        @NotNull @Size(min = 1, max = 200, message = "must contain between 1 and 200 songs") List<String> canciones) {
}
