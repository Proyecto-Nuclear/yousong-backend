package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The daily search budget of an establishment. Joining is public, so the per-participant limit alone does not
 * bound what one establishment's participants spend: every fresh participation would get a fresh window. The
 * budget (303 units here: three searches of 100 + 1 units) caps the establishment as a whole. Every test uses
 * an establishment of its own, so the budgets do not leak between tests.
 */
@TestPropertySource(properties = {
        "app.youtube.daily-quota-limit=1000003",
        "app.participante.search-per-minute=1000",
        "app.participante.search-units-per-establishment-per-day=303"})
class ParticipantePresupuestoBusquedaApiTest extends ParticipanteApiTestSupport {

    private static final LocalYoutubeServer YOUTUBE = new LocalYoutubeServer();

    @DynamicPropertySource
    static void youtube(DynamicPropertyRegistry registry) {
        registry.add("app.youtube.base-url", YOUTUBE::baseUrl);
        registry.add("app.youtube.api-key", () -> YoutubeApiTest.CLAVE_PRUEBA);
        registry.add("app.youtube.timeout-seconds", () -> "1");
    }

    @BeforeEach
    void resetYoutube() {
        YOUTUBE.reset();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));
    }

    private static String consultaNueva() {
        return "cancion " + videoIdAleatorio();
    }

    private HttpResponse<String> buscar(String consulta, String token) {
        return get("/api/participante/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), token);
    }

    private HttpResponse<String> buscarComoStaff(String consulta) {
        return get("/api/staff/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), operatorToken());
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    /** Spends the whole budget of the establishment with three unique searches. */
    private void agotarPresupuesto(Local local) {
        for (int i = 0; i < 3; i++) {
            assertThat(buscar(consultaNueva(), unirse(local)).statusCode()).as("search " + (i + 1)).isEqualTo(200);
        }
    }

    @Test
    void freshParticipationsWithUniqueSearchesAreStoppedAtTheEstablishmentBudget() {
        Local local = localAbierto();

        int aceptadas = 0;
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> response = buscar(consultaNueva(), unirse(local));
            if (response.statusCode() == 200) {
                aceptadas++;
            } else {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
                assertThat(code(response)).isEqualTo("SEARCH_BUDGET_EXHAUSTED");
            }
        }

        assertThat(aceptadas).isEqualTo(3);
        assertThat(YOUTUBE.count("/search")).as("no more provider searches than the budget allows").isEqualTo(3);
        assertThat(buscarComoStaff(consultaNueva()).statusCode()).as("staff still searches").isEqualTo(200);
    }

    @Test
    void aRefusedSearchNeverReachesTheProvider() {
        Local local = localAbierto();
        agotarPresupuesto(local);
        int callsBefore = YOUTUBE.count();

        HttpResponse<String> refused = buscar(consultaNueva(), unirse(local));

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(YOUTUBE.count()).as("neither search.list nor videos.list").isEqualTo(callsBefore);
    }

    @Test
    void anotherEstablishmentIsNotAffected() {
        agotarPresupuesto(localAbierto());

        assertThat(buscar(consultaNueva(), unirse(localAbierto())).statusCode()).isEqualTo(200);
    }

    @Test
    void cachedSearchesAreFree() {
        Local local = localAbierto();
        String token = unirse(local);
        String repetida = consultaNueva();

        for (int i = 0; i < 10; i++) {
            assertThat(buscar(repetida, token).statusCode()).as("repeat " + (i + 1)).isEqualTo(200);
        }
        assertThat(YOUTUBE.count("/search")).isEqualTo(1);

        assertThat(buscar(consultaNueva(), token).statusCode()).as("the repeats spent no budget").isEqualTo(200);
        assertThat(buscar(consultaNueva(), token).statusCode()).isEqualTo(200);
        assertThat(buscar(consultaNueva(), token).statusCode()).isEqualTo(429);
    }
    @Test
    void searchesThatFailAtTheProviderDoNotConsumeTheEstablishmentBudget() {
        Local local = localAbierto();
        YOUTUBE.script("/search", 500, LocalYoutubeServer.errorJson(500, "backendError"));
        for (int i = 0; i < 6; i++) {
            assertThat(buscar(consultaNueva(), unirse(local)).statusCode()).as("failed search " + i).isEqualTo(503);
        }
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));

        agotarPresupuesto(local);
    }
}
