package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the YouTube metadata of stored songs (title, channel, thumbnail, duration, availability) up to date.
 *
 * <p>Two steps so that no provider call ever happens inside a database transaction: {@link #consultar} talks
 * to YouTube (no transaction), {@link #guardar} writes the answer (call it inside the caller's transaction).
 */
@Slf4j
@Service
public class MetadatosCancionService {

    private static final int MAX_TITULO = 300;
    private static final int MAX_CANAL = 200;
    private static final int MAX_MINIATURA = 2048;

    private final ProveedorYoutube proveedor;
    private final CancionRepository canciones;
    private final YoutubeProperties propiedades;
    private final Clock reloj;

    MetadatosCancionService(ProveedorYoutube proveedor, CancionRepository canciones, YoutubeProperties propiedades,
                            Clock reloj) {
        this.proveedor = proveedor;
        this.canciones = canciones;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    /**
     * Fetches from YouTube the videos whose stored metadata is missing or expired. Best effort: when the
     * catalog is not configured, unavailable or out of quota it answers an empty map (and logs a warning).
     *
     * @return the details of the videos that were fetched, by video id
     * @throws ApiException 400 {@code VIDEO_UNAVAILABLE} when YouTube answers definitively that a video does
     *                      not exist or cannot be played
     */
    public Map<String, VideoYoutube> consultar(Collection<String> videoIds) {
        List<String> vencidos = vencidos(videoIds);
        if (vencidos.isEmpty()) {
            return Map.of();
        }
        List<VideoYoutube> respuesta;
        try {
            respuesta = proveedor.obtener(vencidos);
        } catch (YoutubeException e) {
            log.warn("Song metadata not refreshed ({}): the default list is published without it", e.tipo());
            return Map.of();
        }
        Map<String, VideoYoutube> porId = respuesta.stream()
                .collect(Collectors.toMap(VideoYoutube::videoId, v -> v, (a, b) -> a, LinkedHashMap::new));
        for (String id : vencidos) {
            VideoYoutube video = porId.get(id);
            if (video == null || !video.disponible()) {
                throw ApiException.badRequest("VIDEO_UNAVAILABLE", "The video '" + id
                        + "' does not exist on YouTube or cannot be played here, so it cannot be added");
            }
        }
        return porId;
    }

    /**
     * Asks YouTube about one video a participant wants to request (one videos.list unit, charged to
     * {@code origen}). Unlike {@link #consultar}, this is not best effort: without an answer there is nothing
     * to request. No transaction: call it before opening one.
     *
     * @throws ApiException 404 {@code SONG_NOT_FOUND} when YouTube does not know the video, 409
     *                      {@code SONG_UNAVAILABLE} when it cannot be played here, and the catalog errors of a
     *                      search (503 not configured / unavailable / quota, 429 establishment budget)
     */
    public VideoYoutube consultarParaSolicitud(String videoId, OrigenBusqueda origen) {
        List<VideoYoutube> respuesta;
        try {
            respuesta = proveedor.obtener(List.of(videoId), origen);
        } catch (YoutubeException e) {
            throw e.aApi();
        }
        VideoYoutube video = respuesta.stream().filter(v -> videoId.equals(v.videoId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("SONG_NOT_FOUND", "The video '" + videoId
                        + "' does not exist on YouTube"));
        if (!video.disponible()) {
            throw ApiException.conflict("SONG_UNAVAILABLE", "This song cannot be played here");
        }
        return video;
    }

    /** Registers the video if it is new and stores what YouTube said about it, in its own short transaction. */
    @Transactional
    public void registrar(VideoYoutube video) {
        canciones.registrarSiNoExiste(UUID.randomUUID(), video.videoId());
        guardar(Map.of(video.videoId(), video));
    }

    /** Stores the fetched metadata. Rows are written in video id order, so concurrent writers cannot deadlock. */
    public void guardar(Map<String, VideoYoutube> videos) {
        Instant ahora = reloj.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expira = ahora.plus(Duration.ofDays(propiedades.metadataTtlDays()));
        videos.keySet().stream().sorted().forEach(id -> {
            VideoYoutube v = videos.get(id);
            canciones.actualizarMetadatos(id, recortar(v.titulo(), MAX_TITULO), recortar(v.miniaturaUrl(), MAX_MINIATURA),
                    v.duracionSegundos() != null && v.duracionSegundos() > 0 ? v.duracionSegundos() : null,
                    recortar(v.canal(), MAX_CANAL), Disponibilidad.DISPONIBLE, ahora, expira);
        });
    }

    private List<String> vencidos(Collection<String> videoIds) {
        Instant ahora = reloj.instant();
        Map<String, Cancion> guardadas = canciones.findByYoutubeVideoIdIn(videoIds).stream()
                .collect(Collectors.toMap(Cancion::getYoutubeVideoId, c -> c));
        return videoIds.stream().filter(id -> {
            Cancion c = guardadas.get(id);
            return c == null || c.getMetadatosExpiranAt() == null || !c.getMetadatosExpiranAt().isAfter(ahora);
        }).toList();
    }

    private static String recortar(String valor, int maximo) {
        return valor == null || valor.length() <= maximo ? valor : valor.substring(0, maximo);
    }
}
