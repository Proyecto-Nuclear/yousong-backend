package cue.edu.co.yousongbackend.cola;

import com.fasterxml.jackson.annotation.JsonInclude;
import cue.edu.co.yousongbackend.catalogo.ArtistaDesdeCanal;
import cue.edu.co.yousongbackend.participante.EstadoServicio;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the queue. None of them carries an identity: no participation, device or pseudonym, so the
 * same snapshot can be shown on a public screen. {@code votado} only exists in the participant's own view.
 */
public final class ColaDtos {

    private ColaDtos() {
    }

    /** Body of {@code POST /api/participante/solicitudes}: the {@code videoId} of a catalog search result. */
    public record SolicitarRequest(String videoId) {
    }

    /**
     * Confirmation of a request or a vote. {@code creada} is true when this call created the request (and its
     * first vote); {@code posicion} is the 1-based place in the queue, null while the request is playing.
     */
    public record ApoyoResponse(UUID solicitudId, long votos, boolean votado, Integer posicion, boolean creada) {
    }

    /** A song as the queue shows it; the artist label follows the catalog rule ({@link ArtistaDesdeCanal}). */
    public record CancionCola(String videoId, String titulo, String artista, boolean artistaVerificado,
                              Integer duracionSegundos, String miniaturaUrl) {

        static CancionCola de(String videoId, String titulo, String artistaVerificado, String canal,
                              Integer duracionSegundos, String miniaturaUrl) {
            return new CancionCola(videoId, titulo, ArtistaDesdeCanal.etiqueta(artistaVerificado, canal),
                    ArtistaDesdeCanal.verificado(artistaVerificado), duracionSegundos, miniaturaUrl);
        }
    }

    public record ItemCola(UUID solicitudId, int posicion, long votos, CancionCola cancion, long esperaEstimadaSegundos,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean votado) {
    }

    /** The song playing now; {@code solicitudId} is null when it comes from the default list. */
    public record EnReproduccion(UUID solicitudId, CancionCola cancion, Instant iniciadaAt, long restanteSegundos,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Boolean votado) {
    }

    /**
     * A snapshot of the queue. {@code colaVersion} is {@code sesion.cola_version} read in the same snapshot as the
     * items (0 when there is no session in operation), so a client can drop an older snapshot or event.
     */
    public record ColaResponse(long colaVersion, EstadoServicio estado, EnReproduccion enReproduccion,
                               List<ItemCola> cola) {
    }
}
