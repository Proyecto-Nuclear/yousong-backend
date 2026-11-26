package cue.edu.co.yousongbackend.destacadas;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** At most N reads of the rankings per minute from one client address (every test client calls from 127.0.0.1). */
@TestPropertySource(properties = "app.destacadas.per-minute-per-address=3")
class DestacadasLimiteApiTest extends ApiIntegrationTest {

    @Autowired
    private DestacadasService destacadas;

    @BeforeEach
    void ventanaNueva() {
        destacadas.reiniciar();
    }

    @Test
    void theReadAfterTheAllowanceIsRateLimitedEvenForAnUnknownSlug() {
        Local local = establecimiento();
        for (int i = 0; i < 3; i++) {
            assertThat(get("/api/public/establecimientos/" + local.slug() + "/destacadas", null).statusCode())
                    .isEqualTo(200);
        }

        HttpResponse<String> limitada = get("/api/public/establecimientos/" + local.slug() + "/destacadas", null);
        HttpResponse<String> desconocida = get("/api/public/establecimientos/no-existe/destacadas", null);

        assertThat(limitada.statusCode()).isEqualTo(429);
        assertThat(json(limitada).get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(desconocida.statusCode()).as("checked before the lookup").isEqualTo(429);
    }
}
