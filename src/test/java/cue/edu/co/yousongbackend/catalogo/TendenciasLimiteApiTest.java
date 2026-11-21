package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** At most N trend reads per minute from one client address (every test client calls from 127.0.0.1). */
@TestPropertySource(properties = {"app.youtube.daily-quota-limit=1000041", "app.trends.per-minute-per-address=3"})
class TendenciasLimiteApiTest extends YoutubeApiTest {

    @Autowired
    private TendenciasService tendencias;

    @BeforeEach
    void ventanaNueva() {
        tendencias.reiniciar();
    }

    @Test
    void theReadAfterTheAllowanceIsRateLimitedAndCallsNothing() {
        YOUTUBE.chart(LocalYoutubeServer.video(videoIdAleatorio()));
        for (int i = 0; i < 3; i++) {
            assertThat(get("/api/public/tendencias", null).statusCode()).isEqualTo(200);
        }
        int llamadas = YOUTUBE.count();

        HttpResponse<String> response = get("/api/public/tendencias", null);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(json(response).get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(YOUTUBE.count()).isEqualTo(llamadas);
    }
}
