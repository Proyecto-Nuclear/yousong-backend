package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Per-minute limits of the owner catalog routes (per establishment) and the hourly limit of establishment
 * creation (per client address). The production defaults are set back here, because the shared test
 * configuration raises them; the windows live in the application context, so this class has one of its own.
 */
@TestPropertySource(properties = {
        "app.youtube.daily-quota-limit=980000",
        "app.propietario.search-per-minute=10",
        "app.propietario.suggestions-per-minute=60",
        "app.propietario.create-per-hour-per-address=5"})
class PropietarioLimitesApiTest extends YoutubeApiTest {

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    @Test
    void theEleventhSearchInAMinuteIsRateLimited() {
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));
        String token = propietarioToken();
        String consulta = "/api/mi-establecimiento/catalogo/busqueda?q=" + consultaNueva().replace(' ', '+');

        for (int i = 0; i < 10; i++) {
            assertThat(get(consulta, token).statusCode()).as("search " + (i + 1)).isEqualTo(200);
        }
        HttpResponse<String> limited = get(consulta, token);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(get(consulta, propietarioToken()).statusCode()).as("another owner").isEqualTo(200);
        assertThat(get("/api/mi-establecimiento", token).statusCode()).as("configuration routes are not limited")
                .isEqualTo(200);
    }

    @Test
    void theSixtyFirstSuggestionInAMinuteIsRateLimited() {
        String token = propietarioToken();

        for (int i = 0; i < 60; i++) {
            HttpResponse<String> response = get("/api/mi-establecimiento/catalogo/sugerencias?q=ca", token);
            assertThat(response.statusCode()).as("suggestion " + (i + 1) + ": " + response.body()).isEqualTo(200);
        }
        HttpResponse<String> limited = get("/api/mi-establecimiento/catalogo/sugerencias?q=ca", token);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
    }

    @Test
    void theSixthEstablishmentCreationFromOneAddressInAnHourIsRateLimited() {
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = post("/api/mi-establecimiento", tokenSinEstablecimiento(),
                    "{\"nombre\":\"Bar limitado " + i + "\"}");
            assertThat(response.statusCode()).as("creation " + (i + 1) + ": " + response.body()).isEqualTo(201);
        }
        String sub = UUID.randomUUID().toString();

        HttpResponse<String> limited = post("/api/mi-establecimiento", tokenDe(sub), "{\"nombre\":\"Bar de mas\"}");

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(limited.body()).doesNotContain("127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.establecimiento WHERE propietario_sub = ?",
                Integer.class, sub)).isZero();
    }
}
