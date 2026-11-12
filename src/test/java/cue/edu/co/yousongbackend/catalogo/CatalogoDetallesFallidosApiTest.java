package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * A failing videos.list must not make every retry pay search.list (100 units) again: the search step is
 * cached on its own. The quota counter lives in the context, so this class has its own limit and one test.
 */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=930000")
class CatalogoDetallesFallidosApiTest extends YoutubeApiTest {

    @Autowired
    private CuotaYoutube cuota;

    private HttpResponse<String> buscar(String consulta) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), propietarioToken());
    }

    @Test
    void afterADetailsFailureTheRetryRepeatsOnlyTheDetailsCall() {
        String id = videoIdAleatorio();
        String consulta = consultaNueva();
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));
        YOUTUBE.script("/videos", 500, "{}");
        int antes = cuota.usadas();

        HttpResponse<String> failed = buscar(consulta);

        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(json(failed).get("code").asString()).isEqualTo("CATALOG_UNAVAILABLE");

        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/videos", 200, "{\"items\":[" + LocalYoutubeServer.video(id).json() + "]}");
        HttpResponse<String> retry = buscar(consulta);

        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        assertThat(json(retry).get("resultados").get(0).get("videoId").asString()).isEqualTo(id);
        assertThat(YOUTUBE.count("/search")).as("search.list ran once in total").isEqualTo(1);
        assertThat(YOUTUBE.count("/videos")).isEqualTo(2);
        assertThat(cuota.usadas() - antes)
                .as("100 for the search once plus 1 for the videos call that answered; the failed one is refunded")
                .isEqualTo(101);
    }
}
