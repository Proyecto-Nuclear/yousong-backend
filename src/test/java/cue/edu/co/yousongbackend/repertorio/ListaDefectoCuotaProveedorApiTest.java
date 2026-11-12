package cue.edu.co.yousongbackend.repertorio;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * When the PROVIDER itself answers quotaExceeded, a default list is still published without metadata (best
 * effort), unlike an exhausted owner budget or owner share. Its own context: the exhaustion is latched there.
 */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=950000")
class ListaDefectoCuotaProveedorApiTest extends YoutubeApiTest {

    @Test
    void aProviderQuotaAnswerStillPublishesTheListWithoutMetadata() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.script("/videos", 403, LocalYoutubeServer.errorJson(403, "quotaExceeded"));

        HttpResponse<String> response = put("/api/mi-establecimiento/lista-defecto", local.token(),
                "{\"canciones\":[\"" + id + "\"]}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json(response).get("canciones").get(0).get("titulo").isNull()).isTrue();
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);

        String otro = videoIdAleatorio();
        HttpResponse<String> latched = put("/api/mi-establecimiento/lista-defecto", local.token(),
                "{\"canciones\":[\"" + otro + "\"]}");
        assertThat(latched.statusCode()).as("the latched exhaustion is best effort too").isEqualTo(201);
        assertThat(YOUTUBE.count("/videos")).as("no call once the provider said the quota is gone").isEqualTo(1);
    }
}
