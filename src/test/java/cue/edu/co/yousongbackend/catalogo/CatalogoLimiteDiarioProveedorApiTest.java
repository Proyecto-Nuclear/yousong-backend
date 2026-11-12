package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** {@code dailyLimitExceeded} latches the exhaustion just like {@code quotaExceeded}. Its own context. */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=920000")
class CatalogoLimiteDiarioProveedorApiTest extends YoutubeApiTest {

    private HttpResponse<String> buscar(String consulta) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), propietarioToken());
    }

    @Test
    void aDailyLimitAnswerExhaustsTheCatalogUntilTheNextReset() {
        YOUTUBE.script("/search", 403, LocalYoutubeServer.errorJson(403, "dailyLimitExceeded"));

        HttpResponse<String> first = buscar(consultaNueva());

        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(json(first).get("code").asString()).isEqualTo("CATALOG_QUOTA_EXCEEDED");
        assertThat(YOUTUBE.count("/search")).isEqualTo(1);

        HttpResponse<String> second = buscar(consultaNueva());

        assertThat(json(second).get("code").asString()).isEqualTo("CATALOG_QUOTA_EXCEEDED");
        assertThat(YOUTUBE.count("/search")).as("the next request does not reach the provider").isEqualTo(1);
    }
}
