package cue.edu.co.yousongbackend.catalogo;

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
}
