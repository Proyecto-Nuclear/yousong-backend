package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Requesting a song the catalog has never stored: it is looked up with one videos.list call (1 quota unit),
 * charged like a participant search to the establishment's daily budget (2 units here), then stored.
 */
@TestPropertySource(properties = {"app.youtube.daily-quota-limit=1000000",
        "app.participante.search-units-per-establishment-per-day=2"})
class SolicitudCancionNuevaApiTest extends ColaApiTestSupport {

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
    void anUnknownSongIsLookedUpStoredAndRequested() {
        Local local = localAbierto();
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video).titulo("Hips don't lie").canal("ShakiraVEVO").duracion("PT3M38S"));

        HttpResponse<String> response = solicitar(unirse(local), video);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);
        assertThat(YOUTUBE.count("/search")).isZero();
        assertThat(jdbc.queryForObject("SELECT titulo || '|' || canal_titulo || '|' || duracion_proveedor_s || '|' "
                + "|| disponibilidad FROM operacion.cancion WHERE youtube_video_id = ?", String.class, video))
                .isEqualTo("Hips don't lie|ShakiraVEVO|218|DISPONIBLE");
        assertThat(json(colaPublica(local.slug())).get("cola").get(0).get("cancion").get("artista").asString())
                .isEqualTo("Shakira");
    }

    @Test
    void aStoredSongIsNotLookedUpAgain() {
        Local local = localAbierto();
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video));
        solicitada(unirse(local), video);

        assertThat(solicitar(unirse(local), video).statusCode()).isEqualTo(200);
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);
    }

    @Test
    void aSongTheProviderDoesNotKnowIsNotFound() {
        Local local = localAbierto();
        String video = videoIdAleatorio();

        assertProblema(solicitar(unirse(local), video), 404, "SONG_NOT_FOUND");
        assertThat(contar("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?", video)).isZero();
    }

    @Test
    void aSongThatCannotBePlayedHereIsUnavailable() {
        Local local = localAbierto();
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video).incrustable(false));

        assertProblema(solicitar(unirse(local), video), 409, "SONG_UNAVAILABLE");
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ?", sesionDe(local))).isZero();
    }

    @Test
    void aProviderOutageIsUnavailableNotAServerError() {
        Local local = localAbierto();
        YOUTUBE.script("/videos", 500, "{}");

        HttpResponse<String> response = solicitar(unirse(local), videoIdAleatorio());

        assertProblema(response, 503, "CATALOG_UNAVAILABLE");
    }

    @Test
    void lookupsAreChargedToTheEstablishmentBudgetAndAFailedOneIsGivenBack() {
        Local local = localAbierto();
        YOUTUBE.script("/videos", 500, "{}");
        solicitar(unirse(local), videoIdAleatorio());
        YOUTUBE.reset();
        for (int i = 0; i < 2; i++) {
            String video = videoIdAleatorio();
            YOUTUBE.register(LocalYoutubeServer.video(video));
            assertThat(solicitar(unirse(local), video).statusCode()).isEqualTo(201);
        }
        String tercera = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(tercera));

        assertProblema(solicitar(unirse(local), tercera), 429, "SEARCH_BUDGET_EXHAUSTED");
        assertThat(YOUTUBE.count("/videos")).isEqualTo(2);
        // Another establishment has its own budget.
        assertThat(solicitar(unirse(localAbierto()), tercera).statusCode()).isEqualTo(201);
        assertThat(contar("SELECT count(*) FROM operacion.solicitud s JOIN operacion.cancion c ON c.id = s.cancion_id "
                + "WHERE c.youtube_video_id = ?", tercera)).isEqualTo(1);
        assertThat(UUID.fromString(json(colaPublica(local.slug())).get("cola").get(0).get("solicitudId").asString()))
                .isNotNull();
    }
}
