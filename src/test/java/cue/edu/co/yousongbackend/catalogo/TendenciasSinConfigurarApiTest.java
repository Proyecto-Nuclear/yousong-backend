package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The default test context has no YouTube key: the trends answer that the catalog is not configured. */
class TendenciasSinConfigurarApiTest extends ApiIntegrationTest {

    @Test
    void trendsAnswerNotConfigured() {
        HttpResponse<String> response = get("/api/public/tendencias", null);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response).get("code").asString()).isEqualTo("CATALOG_NOT_CONFIGURED");
    }
}
