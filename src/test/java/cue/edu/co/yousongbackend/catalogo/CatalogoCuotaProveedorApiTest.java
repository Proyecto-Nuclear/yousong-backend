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
 * The provider says the quota is gone: the application remembers it (until the next reset) and stops calling.
 * Its own context, because the "exhausted" state lives in the application context.
 */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=900000")
class CatalogoCuotaProveedorApiTest extends YoutubeApiTest {

    private HttpResponse<String> buscar(String consulta) {
        return get("/api/staff/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), operatorToken());
    }

    @Test
    void aQuotaExceededAnswerExhaustsTheCatalogUntilTheNextReset() {
        YOUTUBE.script("/search", 403, LocalYoutubeServer.errorJson(403, "quotaExceeded"));

        HttpResponse<String> first = buscar(consultaNueva());

        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(json(first).get("code").asString()).isEqualTo("CATALOG_QUOTA_EXCEEDED");
        assertThat(first.body()).doesNotContain(CLAVE_PRUEBA);
        assertThat(YOUTUBE.count("/search")).isEqualTo(1);

        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));
        HttpResponse<String> second = buscar(consultaNueva());

        assertThat(second.statusCode()).isEqualTo(503);
        assertThat(json(second).get("code").asString()).isEqualTo("CATALOG_QUOTA_EXCEEDED");
        assertThat(YOUTUBE.count("/search")).as("the next request does not reach the provider").isEqualTo(1);
    }
}
