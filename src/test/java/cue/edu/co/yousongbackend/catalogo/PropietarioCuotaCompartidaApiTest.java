package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.participante.ParticipanteApiTestSupport;
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
 * All owners together may spend only their share of the daily quota (here 0.3 of 1010, so three searches of
 * 101 units), even when each stays within its own budget (two searches). Many self-registered owners can use up
 * that share, but never the participants' share. The counters live in the application context, so this class
 * has a context of its own and a single test.
 */
@TestPropertySource(properties = {
        "app.youtube.daily-quota-limit=1010",
        "app.youtube.participant-quota-share=0.5",
        "app.youtube.owner-quota-share=0.3",
        "app.propietario.search-units-per-establishment-per-day=202",
        "app.participante.search-per-minute=1000",
        "app.participante.search-units-per-establishment-per-day=303"})
class PropietarioCuotaCompartidaApiTest extends ParticipanteApiTestSupport {

    private static final LocalYoutubeServer YOUTUBE = new LocalYoutubeServer();

    @DynamicPropertySource
    static void youtube(DynamicPropertyRegistry registry) {
        registry.add("app.youtube.base-url", YOUTUBE::baseUrl);
        registry.add("app.youtube.api-key", () -> YoutubeApiTest.CLAVE_PRUEBA);
        registry.add("app.youtube.timeout-seconds", () -> "1");
    }

    private static String consultaNueva() {
        return URLEncoder.encode("cancion " + videoIdAleatorio(), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> buscarComoPropietario(String token) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + consultaNueva(), token);
    }

    private HttpResponse<String> buscarComoParticipante(String token) {
        return get("/api/participante/catalogo/busqueda?q=" + consultaNueva(), token);
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    @Test
    void ownersWithinTheirBudgetsCannotPassTheOwnerShareAndParticipantsKeepSearching() {
        YOUTUBE.reset();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));
        String primero = propietarioToken();
        String segundo = propietarioToken();

        assertThat(buscarComoPropietario(primero).statusCode()).isEqualTo(200);
        assertThat(buscarComoPropietario(primero).statusCode()).isEqualTo(200);
        HttpResponse<String> budget = buscarComoPropietario(primero);
        assertThat(budget.statusCode()).as("the first owner's own budget").isEqualTo(429);
        assertThat(code(budget)).isEqualTo("SEARCH_BUDGET_EXHAUSTED");
        assertThat(buscarComoPropietario(segundo).statusCode()).as("a second owner").isEqualTo(200);

        HttpResponse<String> share = buscarComoPropietario(segundo);
        assertThat(share.statusCode()).as("within its own budget, but the owner share is used").isEqualTo(503);
        assertThat(code(share)).isEqualTo("CATALOG_QUOTA_EXCEEDED");
        assertThat(buscarComoPropietario(propietarioToken()).statusCode()).as("a third owner too").isEqualTo(503);
        assertThat(YOUTUBE.count("/search")).isEqualTo(3);

        Local bar = localAbierto();
        assertThat(buscarComoParticipante(unirse(bar)).statusCode()).as("patrons keep their share").isEqualTo(200);
        assertThat(buscarComoParticipante(unirse(bar)).statusCode()).isEqualTo(200);
        assertThat(YOUTUBE.count("/search")).isEqualTo(5);
    }
}
