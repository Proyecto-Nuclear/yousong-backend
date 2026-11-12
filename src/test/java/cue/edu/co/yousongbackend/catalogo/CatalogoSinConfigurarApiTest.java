package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The default test context has no YouTube key: the catalog is off and nothing else is affected. */
class CatalogoSinConfigurarApiTest extends ApiIntegrationTest {

    @Test
    void searchAnswersNotConfigured() {
        HttpResponse<String> response = get("/api/staff/catalogo/busqueda?q=juanes", operatorToken());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response).get("code").asString()).isEqualTo("CATALOG_NOT_CONFIGURED");
    }

    @Test
    void invalidQueriesStillAnswerBadRequest() {
        assertThat(get("/api/staff/catalogo/busqueda?q=a", operatorToken()).statusCode()).isEqualTo(400);
    }

    @Test
    void theRestOfTheApplicationWorks() {
        assertThat(get("/api/staff/establecimientos/" + establecimiento().id() + "/lista-defecto", operatorToken()).statusCode())
                .isEqualTo(404);
    }
}
