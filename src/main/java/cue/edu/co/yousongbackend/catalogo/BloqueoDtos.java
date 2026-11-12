package cue.edu.co.yousongbackend.catalogo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** Wire shapes of the owner's content blocks ({@code /api/mi-establecimiento/bloqueos}). */
public final class BloqueoDtos {

    private BloqueoDtos() {
    }

    /** Why a song is blocked. The codes are stored in {@code bloqueo_contenido.motivo_codigo}. */
    public enum MotivoBloqueo {
        /** Offensive or unsuitable for the venue. */
        INAPROPIADO,
        /** Rights or licensing problem. */
        DERECHOS,
        /** Any other reason. */
        OTRO
    }

    /**
     * Body of {@code POST /api/mi-establecimiento/bloqueos}: exactly one of {@code duracionMinutos} (1..10080, a
     * week) or {@code permanente: true}, and a {@link MotivoBloqueo} code.
     */
    public record CrearBloqueoRequest(String videoId, Integer duracionMinutos, Boolean permanente, String motivoCodigo) {
    }

    /** The blocked song as the owner sees it. */
    public record CancionBloqueada(String titulo, String artista, String miniaturaUrl) {
    }

    /**
     * A block. {@code hastaAt} is null for a permanent block; {@code vigente} says whether it applies now.
     * {@code solicitudesBloqueadas} (waiting requests of the song taken out of the queue) and
     * {@code reproduciendoActualmente} (the song is playing: it is not interrupted, skip it if needed) only come
     * in the answer of the creation.
     */
    public record BloqueoResponse(UUID id, String videoId, CancionBloqueada cancion, String motivoCodigo,
                                  Instant desdeAt, Instant hastaAt, boolean permanente, Instant levantadoAt,
                                  boolean vigente,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Integer solicitudesBloqueadas,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Boolean reproduciendoActualmente) {

        BloqueoResponse conEfectos(int solicitudes, boolean reproduciendo) {
            return new BloqueoResponse(id, videoId, cancion, motivoCodigo, desdeAt, hastaAt, permanente, levantadoAt,
                    vigente, solicitudes, reproduciendo);
        }
    }
}
