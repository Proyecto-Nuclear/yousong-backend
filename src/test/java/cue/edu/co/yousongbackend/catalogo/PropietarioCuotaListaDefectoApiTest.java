package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Publishing a default list once the owner limits are gone. The best-effort publish (without metadata) is only
 * for the provider's own quota; when the owner's budget or the owner share is used, a list that needs a lookup of
 * a never-stored video is refused and nothing is written, while a list of stored songs still publishes. The owner
 * share here is 0.25 of 10100 (25 searches of 101 units) and each owner budget 909 (9 searches). The counters live
 * in the application context, so this class has a context of its own and a single test.
 */
@TestPropertySource(properties = {
        "app.youtube.daily-quota-limit=10100",
        "app.propietario.search-units-per-establishment-per-day=909"})
class PropietarioCuotaListaDefectoApiTest extends ColaApiTestSupport {

    private static final LocalYoutubeServer YOUTUBE = new LocalYoutubeServer();

    @DynamicPropertySource
    static void youtube(DynamicPropertyRegistry registry) {
        registry.add("app.youtube.base-url", YOUTUBE::baseUrl);
        registry.add("app.youtube.api-key", () -> YoutubeApiTest.CLAVE_PRUEBA);
        registry.add("app.youtube.timeout-seconds", () -> "1");
    }

    private HttpResponse<String> buscar(String token) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q="
                + URLEncoder.encode("cancion " + videoIdAleatorio(), StandardCharsets.UTF_8), token);
    }

    private HttpResponse<String> publicar(Local local, String... videoIds) {
        return put("/api/mi-establecimiento/lista-defecto", local.token(),
                "{\"canciones\":[\"" + String.join("\",\"", videoIds) + "\"]}");
    }

    private int listas(Local local) {
        return jdbc.queryForObject("SELECT count(*) FROM operacion.lista_defecto WHERE establecimiento_id = ?",
                Integer.class, local.id());
    }

    private int canciones(String videoId) {
        return jdbc.queryForObject("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?",
                Integer.class, videoId);
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    private void refused(HttpResponse<String> response, int status, String code, Local local, String nueva) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(code(response)).isEqualTo(code);
        assertThat(listas(local)).as("no version created").isZero();
        assertThat(canciones(nueva)).as("no song row written").isZero();
    }

    @Test
    void anUnknownVideoIsRefusedOnceTheOwnerBudgetOrTheOwnerShareIsGoneAndStoredSongsStillPublish() {
        YOUTUBE.reset();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));

        Local agotado = establecimiento();
        for (int i = 0; i < 9; i++) {
            assertThat(buscar(agotado.token()).statusCode()).as("own search " + (i + 1)).isEqualTo(200);
        }
        int aceptadas = 0;
        for (int i = 0; i < 30; i++) {
            HttpResponse<String> response = buscar(propietarioToken());
            if (response.statusCode() == 200) {
                aceptadas++;
            } else {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
                assertThat(code(response)).isEqualTo("CATALOG_QUOTA_EXCEEDED");
            }
        }
        assertThat(aceptadas).as("the owner share holds 25 searches, 9 already spent").isEqualTo(16);

        String guardada = videoGuardado();
        String nueva = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(nueva));
        int detalles = YOUTUBE.count("/videos");

        refused(publicar(agotado, guardada, nueva), 429, "SEARCH_BUDGET_EXHAUSTED", agotado, nueva);
        Local soloCuota = establecimiento();
        refused(publicar(soloCuota, guardada, nueva), 503, "CATALOG_QUOTA_EXCEEDED", soloCuota, nueva);
        assertThat(YOUTUBE.count("/videos")).as("no lookup reached the provider").isEqualTo(detalles);

        assertThat(publicar(agotado, guardada).statusCode()).as("stored songs, budget gone").isEqualTo(201);
        assertThat(publicar(soloCuota, guardada).statusCode()).as("stored songs, share gone").isEqualTo(201);
    }
}
