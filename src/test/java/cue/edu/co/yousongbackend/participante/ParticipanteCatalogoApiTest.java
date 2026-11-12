package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * A participant searching the catalog from the phone. Every search can cost 100 of the 10,000 daily quota
 * units, so it is limited per participant, and it stops working the moment the session closes.
 */
@TestPropertySource(properties = {"app.youtube.daily-quota-limit=1000000", "app.participante.search-per-minute=3"})
class ParticipanteCatalogoApiTest extends ParticipanteApiTestSupport {

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
    }

    private static String consultaNueva() {
        return "cancion " + videoIdAleatorio();
    }

    private HttpResponse<String> buscar(String consulta, String token) {
        return get("/api/participante/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), token);
    }

    private void proveedorDevuelve(LocalYoutubeServer.Video... videos) {
        String[] ids = new String[videos.length];
        for (int i = 0; i < videos.length; i++) {
            ids[i] = videos[i].id();
            YOUTUBE.register(videos[i]);
        }
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, ids));
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    // ---------- the search itself ----------

    @Test
    void aParticipantSeesImageTitleArtistAndDuration() {
        String token = unirse(localAbierto());
        String id = videoIdAleatorio();
        proveedorDevuelve(LocalYoutubeServer.video(id).titulo("La camisa negra").canal("Juanes - Topic").duracion("PT3M47S"));

        HttpResponse<String> response = buscar(consultaNueva(), token);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode first = json(response).get("resultados").get(0);
        assertThat(first.get("videoId").asString()).isEqualTo(id);
        assertThat(first.get("titulo").asString()).isEqualTo("La camisa negra");
        assertThat(first.get("artista").asString()).isEqualTo("Juanes");
        assertThat(first.get("duracionSegundos").asInt()).isEqualTo(227);
        assertThat(first.get("miniaturaUrl").asString()).isEqualTo("https://img.test/" + id + "/mqdefault.jpg");
    }

    @Test
    void anInvalidQueryIsABadRequest() {
        String token = unirse(localAbierto());

        HttpResponse<String> response = buscar("x", token);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_QUERY");
    }

    @Test
    void aProviderOutageIsReportedAsUnavailableNotAsAServerError() {
        String token = unirse(localAbierto());
        YOUTUBE.script("/search", 403, LocalYoutubeServer.errorJson(403, "quotaExceeded"));

        HttpResponse<String> response = buscar(consultaNueva(), token);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("CATALOG_QUOTA_EXCEEDED");
    }

    // ---------- who may search ----------

    @Test
    void searchingRequiresAParticipantCredential() {
        assertThat(buscar(consultaNueva(), null).statusCode()).isEqualTo(401);
        assertCredencialRechazada(buscar(consultaNueva(), "pt_not-a-real-credential"), "INVALID_CREDENTIAL");
        assertCredencialRechazada(buscar(consultaNueva(), adminToken()), "INVALID_CREDENTIAL");
        assertThat(YOUTUBE.count()).as("the provider is never called for an unauthenticated caller").isZero();
    }

    @Test
    void afterTheSessionClosesTheSearchAnswersSessionEndedWithoutCallingTheProvider() {
        Local local = localListoParaAbrir();
        UUID sesion = abrirSesion(local);
        String token = unirse(local);
        cerrarSesion(sesion);

        HttpResponse<String> response = buscar(consultaNueva(), token);

        assertCredencialRechazada(response, "SESSION_ENDED");
        assertThat(YOUTUBE.count()).isZero();
    }

    // ---------- limit per participant ----------

    @Test
    void eachParticipantHasItsOwnSearchesPerMinute() {
        Local local = localAbierto();
        String ana = unirse(local);
        String luis = unirse(local);
        proveedorDevuelve(LocalYoutubeServer.video(videoIdAleatorio()));

        for (int i = 0; i < 3; i++) {
            assertThat(buscar(consultaNueva(), ana).statusCode()).as("search " + (i + 1)).isEqualTo(200);
        }
        HttpResponse<String> limited = buscar(consultaNueva(), ana);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(buscar(consultaNueva(), luis).statusCode()).as("another participant is not affected").isEqualTo(200);
    }

    @Test
    void aLimitedSearchNeverReachesTheProvider() {
        String token = unirse(localAbierto());
        proveedorDevuelve(LocalYoutubeServer.video(videoIdAleatorio()));
        for (int i = 0; i < 3; i++) {
            buscar(consultaNueva(), token);
        }
        int callsBefore = YOUTUBE.count("/search");

        assertThat(buscar(consultaNueva(), token).statusCode()).isEqualTo(429);

        assertThat(YOUTUBE.count("/search")).isEqualTo(callsBefore);
    }
}
