package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Blocking a song the catalog never stored: it is looked up for staff (one videos.list unit) and stored first. */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=1000000")
class BloqueoCancionNuevaApiTest extends ColaApiTestSupport {

    private static final LocalYoutubeServer YOUTUBE = new LocalYoutubeServer();

    @DynamicPropertySource
    static void youtube(DynamicPropertyRegistry registry) {
        registry.add("app.youtube.base-url", YOUTUBE::baseUrl);
        registry.add("app.youtube.api-key", () -> YoutubeApiTest.CLAVE_PRUEBA);
        registry.add("app.youtube.timeout-seconds", () -> "1");
    }

    @BeforeEach
    void resetYoutube() {
        YOUTUBE.reset();
    }

    @Test
    void anUnknownSongIsLookedUpStoredAndBlocked() {
        Local local = localAbierto();
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video).titulo("Cancion vetada"));

        HttpResponse<String> response = bloquear(local.token(), video, 15, "INAPROPIADO");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);
        assertThat(json(response).get("cancion").get("titulo").asString()).isEqualTo("Cancion vetada");
        assertThat(contar("SELECT count(*) FROM operacion.bloqueo_contenido b JOIN operacion.cancion c "
                + "ON c.id = b.cancion_id WHERE c.youtube_video_id = ?", video)).isEqualTo(1);
        assertProblema(solicitar(unirse(local), video), 409, "SONG_BLOCKED");
    }

    @Test
    void aSongTheProviderDoesNotKnowIsNotFound() {
        Local local = localAbierto();
        String video = videoIdAleatorio();

        assertProblema(bloquear(local.token(), video, 15, "INAPROPIADO"), 404, "SONG_NOT_FOUND");
        assertThat(contar("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?", video)).isZero();
    }
}
