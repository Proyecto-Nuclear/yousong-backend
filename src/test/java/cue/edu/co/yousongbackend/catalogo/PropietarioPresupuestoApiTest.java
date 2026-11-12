package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
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
 * The daily provider budget of an establishment owner. Creating an establishment is self-service, so owning one
 * costs an attacker nothing: every provider call an owner route triggers (catalog search, default-list publish,
 * content-block lookup) is charged to a budget per establishment (303 units here: three searches of 100 + 1),
 * separate from the budget of that establishment's patrons. Every test uses establishments of its own.
 */
@TestPropertySource(properties = {
        "app.youtube.daily-quota-limit=1000006",
        "app.propietario.search-units-per-establishment-per-day=303",
        "app.participante.search-units-per-establishment-per-day=303",
        "app.participante.search-per-minute=1000"})
class PropietarioPresupuestoApiTest extends ColaApiTestSupport {

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

    private HttpResponse<String> buscarComoPropietario(String consulta, String token) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), token);
    }

    private HttpResponse<String> buscarComoParticipante(String consulta, String token) {
        return get("/api/participante/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), token);
    }

    private HttpResponse<String> publicar(Local local, String... videoIds) {
        StringBuilder ids = new StringBuilder();
        for (String id : videoIds) {
            ids.append(ids.length() == 0 ? "" : ",").append('"').append(id).append('"');
        }
        return put("/api/mi-establecimiento/lista-defecto", local.token(), "{\"canciones\":[" + ids + "]}");
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    /** Unique searches until the owner budget refuses one; returns how many were accepted. */
    private int agotarPresupuesto(Local local) {
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> response = buscarComoPropietario(consultaNueva(), local.token());
            if (response.statusCode() != 200) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
                assertThat(code(response)).isEqualTo("SEARCH_BUDGET_EXHAUSTED");
                return i;
            }
        }
        throw new AssertionError("the owner budget never stopped the searches");
    }

    @Test
    void anOwnerWithUniqueSearchesIsStoppedAtTheBudgetAndPatronsAndOtherOwnersAreNotAffected() {
        Local local = localAbierto();

        int aceptadas = 0;
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> response = buscarComoPropietario(consultaNueva(), local.token());
            if (response.statusCode() == 200) {
                aceptadas++;
            } else {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
                assertThat(code(response)).isEqualTo("SEARCH_BUDGET_EXHAUSTED");
            }
        }

        assertThat(aceptadas).isEqualTo(3);
        assertThat(YOUTUBE.count("/search")).as("no more provider searches than the budget allows").isEqualTo(3);
        assertThat(buscarComoParticipante(consultaNueva(), unirse(local)).statusCode())
                .as("the patrons of that place have a budget of their own").isEqualTo(200);
        assertThat(buscarComoPropietario(consultaNueva(), propietarioToken()).statusCode())
                .as("another owner").isEqualTo(200);
    }

    @Test
    void aRefusedSearchNeverReachesTheProvider() {
        Local local = establecimiento();
        agotarPresupuesto(local);
        int callsBefore = YOUTUBE.count();

        HttpResponse<String> refused = buscarComoPropietario(consultaNueva(), local.token());

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(YOUTUBE.count()).as("neither search.list nor videos.list").isEqualTo(callsBefore);
    }

    @Test
    void cachedSearchesAreFree() {
        Local local = establecimiento();
        String repetida = consultaNueva();

        for (int i = 0; i < 10; i++) {
            assertThat(buscarComoPropietario(repetida, local.token()).statusCode()).as("repeat " + (i + 1)).isEqualTo(200);
        }
        assertThat(YOUTUBE.count("/search")).isEqualTo(1);

        assertThat(agotarPresupuesto(local)).as("the repeats spent no budget").isEqualTo(2);
    }

    @Test
    void searchesThatFailAtTheProviderAreRefunded() {
        Local local = establecimiento();
        YOUTUBE.script("/search", 500, LocalYoutubeServer.errorJson(500, "backendError"));
        for (int i = 0; i < 6; i++) {
            assertThat(buscarComoPropietario(consultaNueva(), local.token()).statusCode()).as("failed " + i).isEqualTo(503);
        }
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));

        assertThat(agotarPresupuesto(local)).isEqualTo(3);
    }

    @Test
    void publishingADefaultListSpendsFromTheOwnerBudget() {
        Local local = establecimiento();
        String guardada = videoGuardado();
        assertThat(agotarPresupuesto(local)).as("303 units: exactly three searches").isEqualTo(3);
        String nueva = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(nueva));
        int detalles = YOUTUBE.count("/videos");

        HttpResponse<String> refused = publicar(local, guardada, nueva);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(429);
        assertThat(code(refused)).isEqualTo("SEARCH_BUDGET_EXHAUSTED");
        assertThat(YOUTUBE.count("/videos")).as("no lookup of the unknown video").isEqualTo(detalles);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?",
                Integer.class, nueva)).isZero();
        HttpResponse<String> stored = publicar(local, guardada);
        assertThat(stored.statusCode()).as("songs already stored need no lookup: " + stored.body()).isEqualTo(201);
    }

    @Test
    void blockingAnUnknownSongSpendsFromTheOwnerBudget() {
        Local local = localAbierto();
        agotarPresupuesto(local);
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video));

        HttpResponse<String> refused = bloquear(local.token(), video, 15, "INAPROPIADO");

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(429);
        assertThat(code(refused)).isEqualTo("SEARCH_BUDGET_EXHAUSTED");
        assertThat(YOUTUBE.count("/videos")).as("only the details of the accepted searches").isEqualTo(3);
        assertThat(bloquear(propietarioToken(), video, 15, "INAPROPIADO").statusCode())
                .as("another owner still looks it up").isEqualTo(201);
    }
}
