package cue.edu.co.yousongbackend.catalogo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CancionRepository extends JpaRepository<Cancion, UUID> {

    List<Cancion> findByYoutubeVideoIdIn(Collection<String> youtubeVideoIds);

    /**
     * Registers a video the first time it is seen. {@code on conflict do nothing} makes it safe when two
     * requests register the same new video at once; the other columns take their database defaults
     * (availability unknown, 240 s operating duration) until the catalog enriches them.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into operacion.cancion (id, youtube_video_id) values (:id, :videoId) "
            + "on conflict (youtube_video_id) do nothing", nativeQuery = true)
    void registrarSiNoExiste(@Param("id") UUID id, @Param("videoId") String videoId);

    /**
     * Stores what the provider said about a video. A bulk update on purpose: callers run it for ids in
     * sorted order so concurrent publications lock the rows in the same order (song has no {@code @Version}).
     */
    @Modifying(flushAutomatically = true)
    @Query("update Cancion c set c.titulo = :titulo, c.miniaturaUrl = :miniatura, c.duracionProveedorS = :duracion, "
            + "c.canalTitulo = :canal, c.disponibilidad = :disponibilidad, c.validadoAt = :validadoAt, "
            + "c.metadatosExpiranAt = :expiraAt where c.youtubeVideoId = :videoId")
    int actualizarMetadatos(@Param("videoId") String videoId, @Param("titulo") String titulo,
                            @Param("miniatura") String miniatura, @Param("duracion") Integer duracion,
                            @Param("canal") String canal, @Param("disponibilidad") Disponibilidad disponibilidad,
                            @Param("validadoAt") Instant validadoAt, @Param("expiraAt") Instant expiraAt);
}
