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
 * Our own daily counter (100 units per search, 1 per details call). The counter lives in the application
 * context, so this class has a context of its own (a limit no other class uses) and a single test.
 */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=250")
class CatalogoCuotaLocalApiTest extends YoutubeApiTest {

    private HttpResponse<String> buscar(String consulta) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), propietarioToken());
    }

    @Test
    void stopsCallingTheProviderOnceTheDailyLimitIsReached() {
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));

        // 101 units each: 0 -> 101 -> 202 -> 303; the limit (250) is checked before each call.
        assertThat(buscar(consultaNueva()).statusCode()).isEqualTo(200);
        assertThat(buscar(consultaNueva()).statusCode()).isEqualTo(200);
        assertThat(buscar(consultaNueva()).statusCode()).isEqualTo(200);
        int callsBefore = YOUTUBE.count();
        assertThat(callsBefore).isEqualTo(6);

        HttpResponse<String> blocked = buscar(consultaNueva());

        assertThat(blocked.statusCode()).isEqualTo(503);
        assertThat(json(blocked).get("code").asString()).isEqualTo("CATALOG_QUOTA_EXCEEDED");
        assertThat(YOUTUBE.count()).as("no provider call once the limit is reached").isEqualTo(callsBefore);
    }
}
