package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code rateLimitExceeded} is YouTube's short-window limit: it fails the current call only and must never
 * latch the daily exhaustion. Its own context (limit no other class uses), because exhaustion lives there.
 */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=910000")
class CatalogoRateLimitApiTest extends YoutubeApiTest {

    private HttpResponse<String> buscar(String consulta) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), propietarioToken());
    }

    @Test
    void aRateLimitAnswerFailsOnlyThatCallAndTheNextRequestReachesTheProvider() {
        YOUTUBE.script("/search", 403, LocalYoutubeServer.errorJson(403, "rateLimitExceeded"));

        HttpResponse<String> first = buscar(consultaNueva());

        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(json(first).get("code").asString()).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(first.body()).doesNotContain(CLAVE_PRUEBA);
        assertThat(YOUTUBE.count("/search")).isEqualTo(1);

        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));
        HttpResponse<String> second = buscar(consultaNueva());

        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(YOUTUBE.count("/search")).as("the next request reaches the provider again").isEqualTo(2);
    }
}
