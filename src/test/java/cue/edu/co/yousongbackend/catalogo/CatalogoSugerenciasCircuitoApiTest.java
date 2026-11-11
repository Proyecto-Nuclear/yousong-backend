package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import cue.edu.co.yousongbackend.support.LocalSugerenciasServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * After {@code failure-threshold} consecutive failures the unofficial suggest source is left alone for the
 * cooldown, and the endpoint keeps answering with local songs. Its own context: the circuit state lives in it.
 * (Closing after the cooldown is covered with a movable clock in {@link SugerenciasRemotasResguardadasTest}.)
 */
@TestPropertySource(properties = {"app.suggestions.youtube.failure-threshold=2",
        "app.suggestions.youtube.cooldown-minutes=10"})
class CatalogoSugerenciasCircuitoApiTest extends ApiIntegrationTest {

    private static final LocalSugerenciasServer SUGERENCIAS = new LocalSugerenciasServer();

    @DynamicPropertySource
    static void sugerencias(DynamicPropertyRegistry registry) {
        registry.add("app.suggestions.youtube.base-url", SUGERENCIAS::baseUrl);
    }

    @Test
    void theCircuitOpensAfterConsecutiveFailuresAndStopsCallingTheRemote() {
        SUGERENCIAS.script(500, "boom");

        for (int i = 0; i < 4; i++) {
            HttpResponse<String> response = get("/api/staff/catalogo/sugerencias?q=" + CatalogoSugerenciasApiTest.marca(),
                    operatorToken());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(json(response).get("sugerencias")).isEmpty();
        }

        assertThat(SUGERENCIAS.count()).isEqualTo(2);
    }
}
