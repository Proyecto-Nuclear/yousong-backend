package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Participants of all establishments together may spend only a share of the daily quota (here half of 1010,
 * so five searches of 101 units), and the rest stays for the staff. The counters live in the application
 * context, so this class has a context of its own and a single test.
 */
@TestPropertySource(properties = {
        "app.youtube.daily-quota-limit=1010",
        "app.youtube.participant-quota-share=0.5",
        "app.participante.search-per-minute=1000",
        "app.participante.search-units-per-establishment-per-day=303"})
class ParticipanteCuotaCompartidaApiTest extends ParticipanteApiTestSupport {

    private static final LocalYoutubeServer YOUTUBE = new LocalYoutubeServer();

    @DynamicPropertySource
    static void youtube(DynamicPropertyRegistry registry) {
        registry.add("app.youtube.base-url", YOUTUBE::baseUrl);
        registry.add("app.youtube.api-key", () -> YoutubeApiTest.CLAVE_PRUEBA);
        registry.add("app.youtube.timeout-seconds", () -> "1");
    }

    private static String consultaNueva() {
        return "cancion " + videoIdAleatorio();
    }

    private HttpResponse<String> buscar(String token) {
        return get("/api/participante/catalogo/busqueda?q=" + URLEncoder.encode(consultaNueva(), StandardCharsets.UTF_8), token);
    }

    @Test
    void establishmentsWithinTheirBudgetsCannotPushParticipantsPastTheShareAndStaffKeepsWorking() {
        YOUTUBE.reset();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));
        Local primero = localAbierto();
        Local segundo = localAbierto();

        for (int i = 0; i < 3; i++) {
            assertThat(buscar(unirse(primero)).statusCode()).as("first establishment, search " + (i + 1)).isEqualTo(200);
        }
        assertThat(buscar(unirse(segundo)).statusCode()).as("second establishment is not affected").isEqualTo(200);
        assertThat(buscar(unirse(segundo)).statusCode()).isEqualTo(200);

        HttpResponse<String> share = buscar(unirse(segundo));
        assertThat(share.statusCode()).as("within its own budget, but the participant share is used").isEqualTo(503);
        assertThat(json(share).get("code").asString()).isEqualTo("CATALOG_QUOTA_EXCEEDED");
        assertThat(buscar(unirse(localAbierto())).statusCode()).as("a third establishment too").isEqualTo(503);
        assertThat(YOUTUBE.count("/search")).isEqualTo(5);

        HttpResponse<String> staff = get("/api/staff/catalogo/busqueda?q="
                + URLEncoder.encode(consultaNueva(), StandardCharsets.UTF_8), operatorToken());
        assertThat(staff.statusCode()).as(staff.body()).isEqualTo(200);
    }
}
