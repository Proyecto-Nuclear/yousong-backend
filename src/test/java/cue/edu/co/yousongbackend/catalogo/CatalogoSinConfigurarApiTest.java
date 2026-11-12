package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The default test context has no YouTube key: the catalog is off and nothing else is affected. */
class CatalogoSinConfigurarApiTest extends ApiIntegrationTest {

    @Test
    void searchAnswersNotConfigured() {
        HttpResponse<String> response = get("/api/mi-establecimiento/catalogo/busqueda?q=juanes", propietarioToken());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response).get("code").asString()).isEqualTo("CATALOG_NOT_CONFIGURED");
    }

    @Test
    void invalidQueriesStillAnswerBadRequest() {
        assertThat(get("/api/mi-establecimiento/catalogo/busqueda?q=a", propietarioToken()).statusCode()).isEqualTo(400);
    }

    @Test
    void theRestOfTheApplicationWorks() {
        assertThat(get("/api/mi-establecimiento/lista-defecto", propietarioToken()).statusCode())
                .isEqualTo(404);
    }
}
