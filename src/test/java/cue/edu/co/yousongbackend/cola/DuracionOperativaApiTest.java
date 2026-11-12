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
import tools.jackson.databind.JsonNode;

/**
 * The operating duration of a song starts from what the provider says it lasts, so estimated waits follow the real
 * songs. A song the provider gives no duration for keeps the 240-second reference, and a duration someone already
 * set (anything but the untouched 240) is never overwritten by a refresh.
 */
@TestPropertySource(properties = {"app.youtube.daily-quota-limit=1000000",
        "app.participante.search-units-per-establishment-per-day=100"})
class DuracionOperativaApiTest extends ColaApiTestSupport {

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

    private int duracionOperativa(String videoId) {
        Integer duracion = jdbc.queryForObject("SELECT duracion_operativa_s FROM operacion.cancion "
                + "WHERE youtube_video_id = ?", Integer.class, videoId);
        return duracion == null ? -1 : duracion;
    }

    private HttpResponse<String> publicar(Local local, String... videoIds) {
        String ids = String.join("\",\"", videoIds);
        return put("/api/mi-establecimiento/lista-defecto", local.token(), "{\"canciones\":[\"" + ids + "\"]}");
    }

    /** A stored song whose metadata expired, so the next publication refreshes it from the provider. */
    private void guardadaVencida(String videoId, int duracionOperativa) {
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id, titulo, duracion_operativa_s, disponibilidad, "
                        + "validado_at, metadatos_expiran_at) VALUES (?, ?, 'Viejo', ?, 'DISPONIBLE', "
                        + "now() - interval '8 days', now() - interval '1 day')",
                UUID.randomUUID(), videoId, duracionOperativa);
    }

    @Test
    void aNewSongTakesTheProviderDurationAsItsOperatingDuration() {
        Local local = establecimiento();
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video).duracion("PT3M47S"));

        assertThat(publicar(local, video).statusCode()).isEqualTo(201);

        assertThat(duracionOperativa(video)).isEqualTo(227);
    }

    @Test
    void aSongWithoutAProviderDurationKeepsTheReference() {
        Local local = establecimiento();
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video).duracion(null));

        assertThat(publicar(local, video).statusCode()).isEqualTo(201);

        assertThat(duracionOperativa(video)).isEqualTo(240);
    }

    @Test
    void aRefreshUpdatesAnUntouchedDurationButNeverOneAlreadySet() {
        Local local = establecimiento();
        String intacta = videoIdAleatorio();
        String ajustada = videoIdAleatorio();
        guardadaVencida(intacta, 240);
        guardadaVencida(ajustada, 200);
        YOUTUBE.register(LocalYoutubeServer.video(intacta).duracion("PT3M47S"));
        YOUTUBE.register(LocalYoutubeServer.video(ajustada).duracion("PT3M47S"));

        assertThat(publicar(local, intacta, ajustada).statusCode()).isEqualTo(201);

        assertThat(YOUTUBE.count("/videos")).as("both were refreshed").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id IN (?, ?) "
                + "AND titulo <> 'Viejo'", Integer.class, intacta, ajustada)).isEqualTo(2);
        assertThat(duracionOperativa(intacta)).isEqualTo(227);
        assertThat(duracionOperativa(ajustada)).isEqualTo(200);
    }

    @Test
    void theSecondRequestWaitsForTheRealDurationOfTheFirst() {
        Local local = localAbierto();
        String primera = videoIdAleatorio();
        String segunda = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(primera).duracion("PT3M47S"));
        YOUTUBE.register(LocalYoutubeServer.video(segunda).duracion("PT2M30S"));
        solicitada(unirse(local), primera);
        solicitada(unirse(local), segunda);

        JsonNode cola = json(colaPublica(local.slug())).get("cola");

        assertThat(cola).hasSize(2);
        assertThat(cola.get(0).get("cancion").get("videoId").asString()).isEqualTo(primera);
        assertThat(cola.get(0).get("esperaEstimadaSegundos").asLong()).isZero();
        assertThat(cola.get(1).get("esperaEstimadaSegundos").asLong()).isEqualTo(227);
    }
}
