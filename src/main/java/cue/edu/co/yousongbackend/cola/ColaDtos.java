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

    /**
     * The song playing now. {@code origen} is {@code SOLICITUD} (a request of the queue) or {@code DEFECTO} (the
     * default list, which covers an empty queue); {@code solicitudId} is null for a default-list song.
     */
    public record EnReproduccion(UUID solicitudId, String origen, CancionCola cancion, Instant iniciadaAt,
                                 long restanteSegundos,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Boolean votado) {
    }

    /**
     * An upcoming song of the default list. Shaped like {@link ItemCola} without {@code solicitudId} and
     * {@code votos}: it is not a request, so it cannot be supported, and it carries no {@code votado} either, in
     * the participant's view too. {@code posicion} continues after the requests of {@code cola} (2 requests: the
     * first default song is position 3); {@code esperaEstimadaSegundos} counts what is left of the song playing,
     * every request and the default songs ahead of it ({@link EsperaEstimada}).
     */
    public record ItemDefecto(int posicion, CancionCola cancion, long esperaEstimadaSegundos) {
    }

    /**
     * A snapshot of the queue. {@code colaVersion} is {@code sesion.cola_version} read in the same snapshot as the
     * items (0 when there is no session in operation), so a client can drop an older snapshot or event.
     *
     * <p>{@code proximasDefecto} previews the default list that plays once the requests run out (at most
     * {@code app.cola.default-preview-size}, 5 by default), in the order the rotation will pick it, skipping
     * blocked and unavailable songs; the requests of {@code cola} always play first. {@code rotacionDefecto} is the
     * policy of the list the session pinned: {@code SECUENCIAL}, or {@code ALEATORIA}, whose next song cannot be
     * predicted, so its preview is always empty. Without a session in operation, or when nothing of the list is
     * playable, the preview is empty and {@code rotacionDefecto} is null. A list republished during a session
     * applies from the next session, so it does not change the preview of the running one.
     */
    public record ColaResponse(long colaVersion, EstadoServicio estado, EnReproduccion enReproduccion,
                               List<ItemCola> cola, List<ItemDefecto> proximasDefecto, String rotacionDefecto) {
    }
}
