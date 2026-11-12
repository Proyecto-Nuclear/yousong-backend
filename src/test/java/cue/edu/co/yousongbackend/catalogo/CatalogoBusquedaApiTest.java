package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** Staff search of the YouTube catalog against a fake provider. */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=1000000")
class CatalogoBusquedaApiTest extends YoutubeApiTest {

    private HttpResponse<String> buscar(String consulta) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), propietarioToken());
    }

    private HttpResponse<String> buscar(String consulta, String pagina) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8)
                + "&pagina=" + URLEncoder.encode(pagina, StandardCharsets.UTF_8), propietarioToken());
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    private void proveedorDevuelve(String siguiente, LocalYoutubeServer.Video... videos) {
        String[] ids = new String[videos.length];
        for (int i = 0; i < videos.length; i++) {
            ids[i] = videos[i].id();
            YOUTUBE.register(videos[i]);
        }
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(siguiente, ids));
    }

    // ---------- mapping ----------

    @Test
    void showsImageTitleArtistAndDuration() {
        String id = videoIdAleatorio();
        proveedorDevuelve(null, LocalYoutubeServer.video(id).titulo("La camisa negra").canal("Juanes - Topic").duracion("PT3M47S"));

        HttpResponse<String> response = buscar(consultaNueva());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode first = json(response).get("resultados").get(0);
        assertThat(first.get("videoId").asString()).isEqualTo(id);
        assertThat(first.get("titulo").asString()).isEqualTo("La camisa negra");
        assertThat(first.get("artista").asString()).isEqualTo("Juanes");
        assertThat(first.get("artistaVerificado").asBoolean()).isFalse();
        assertThat(first.get("canal").asString()).isEqualTo("Juanes - Topic");
        assertThat(first.get("duracionSegundos").asInt()).isEqualTo(227);
        assertThat(first.get("miniaturaUrl").asString()).isEqualTo("https://img.test/" + id + "/mqdefault.jpg");
        assertThat(json(response).get("siguientePagina").isNull()).isTrue();
    }

    @Test
    void keepsTheRelevanceOrderOfTheProvider() {
        String a = videoIdAleatorio();
        String b = videoIdAleatorio();
        String c = videoIdAleatorio();
        proveedorDevuelve(null, LocalYoutubeServer.video(c), LocalYoutubeServer.video(a), LocalYoutubeServer.video(b));

        JsonNode results = json(buscar(consultaNueva())).get("resultados");

        assertThat(results.get(0).get("videoId").asString()).isEqualTo(c);
        assertThat(results.get(1).get("videoId").asString()).isEqualTo(a);
        assertThat(results.get(2).get("videoId").asString()).isEqualTo(b);
    }

    @Test
    void aSongWithAVerifiedArtistShowsItAsVerified() {
        String id = videoIdAleatorio();
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id, artista_verificado) VALUES (gen_random_uuid(), ?, 'Juanes')", id);
        proveedorDevuelve(null, LocalYoutubeServer.video(id).canal("Algun canal"));

        JsonNode first = json(buscar(consultaNueva())).get("resultados").get(0);

        assertThat(first.get("artista").asString()).isEqualTo("Juanes");
        assertThat(first.get("artistaVerificado").asBoolean()).isTrue();
    }

    @Test
    void unavailableVideosAreFilteredOut() {
        String bueno = videoIdAleatorio();
        proveedorDevuelve(null,
                LocalYoutubeServer.video(videoIdAleatorio()).incrustable(false),
                LocalYoutubeServer.video(bueno),
                LocalYoutubeServer.video(videoIdAleatorio()).privacidad("private"),
                LocalYoutubeServer.video(videoIdAleatorio()).envivo("live"),
                LocalYoutubeServer.video(videoIdAleatorio()).restriccion("{\"blocked\":[\"CO\"]}"));

        JsonNode results = json(buscar(consultaNueva())).get("resultados");

        assertThat(results.size()).isEqualTo(1);
        assertThat(results.get(0).get("videoId").asString()).isEqualTo(bueno);
    }

    @Test
    void anEmptySearchAnswersAnEmptyListWithoutAskingForDetails() {
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null));

        HttpResponse<String> response = buscar(consultaNueva());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("resultados").size()).isZero();
        assertThat(YOUTUBE.count("/videos")).isZero();
    }

    // ---------- what is sent to the provider ----------

    @Test
    void sendsTheExpectedParametersToTheProvider() {
        proveedorDevuelve("TOKEN_2", LocalYoutubeServer.video(videoIdAleatorio()));
        String consulta = "  Juanes   la camisa NEGRA & más  ";

        HttpResponse<String> response = buscar(consulta, "TOKEN_1");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        LocalYoutubeServer.Recibida search = YOUTUBE.last("/search");
        assertThat(search.param("key")).isEqualTo(CLAVE_PRUEBA);
        assertThat(search.param("part")).isEqualTo("snippet");
        assertThat(search.param("type")).isEqualTo("video");
        assertThat(search.param("q")).isEqualTo("Juanes la camisa NEGRA & más");
        assertThat(search.param("maxResults")).isEqualTo("5");
        assertThat(search.param("videoCategoryId")).isEqualTo("10");
        assertThat(search.param("videoEmbeddable")).isEqualTo("true");
        assertThat(search.param("safeSearch")).isEqualTo("moderate");
        assertThat(search.param("regionCode")).isEqualTo("CO");
        assertThat(search.param("relevanceLanguage")).isEqualTo("es");
        assertThat(search.param("pageToken")).isEqualTo("TOKEN_1");

        LocalYoutubeServer.Recibida videos = YOUTUBE.last("/videos");
        assertThat(videos.param("key")).isEqualTo(CLAVE_PRUEBA);
        assertThat(videos.param("part")).isEqualTo("snippet,contentDetails,status");
        assertThat(videos.param("id")).isNotBlank();
    }

    @Test
    void omitsThePageTokenOnTheFirstPage() {
        proveedorDevuelve(null, LocalYoutubeServer.video(videoIdAleatorio()));

        buscar(consultaNueva());

        assertThat(YOUTUBE.last("/search").query()).doesNotContainKey("pageToken");
    }

    @Test
    void passesTheNextPageTokenThrough() {
        proveedorDevuelve("CAUQAA", LocalYoutubeServer.video(videoIdAleatorio()));

        JsonNode body = json(buscar(consultaNueva()));

        assertThat(body.get("siguientePagina").asString()).isEqualTo("CAUQAA");
    }

    @Test
    void askingForDetailsJoinsTheIdsWithCommas() {
        String a = videoIdAleatorio();
        String b = videoIdAleatorio();
        proveedorDevuelve(null, LocalYoutubeServer.video(a), LocalYoutubeServer.video(b));

        buscar(consultaNueva());

        assertThat(YOUTUBE.last("/videos").param("id")).isEqualTo(a + "," + b);
    }

    @Test
    void theKeyNeverAppearsInAResponse() {
        proveedorDevuelve("T", LocalYoutubeServer.video(videoIdAleatorio()));
        assertThat(buscar(consultaNueva()).body()).doesNotContain(CLAVE_PRUEBA);

        YOUTUBE.script("/search", 403, LocalYoutubeServer.errorJson(403, "keyInvalid"));
        HttpResponse<String> failure = buscar(consultaNueva());
        assertThat(failure.statusCode()).isEqualTo(503);
        assertThat(failure.body()).doesNotContain(CLAVE_PRUEBA).doesNotContain("key=");
    }

    // ---------- cache ----------

    @Test
    void anIdenticalSearchIsAnsweredFromTheCache() {
        proveedorDevuelve(null, LocalYoutubeServer.video(videoIdAleatorio()));
        String consulta = consultaNueva();

        buscar(consulta);
        HttpResponse<String> second = buscar("  " + consulta.toUpperCase() + "  ");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(YOUTUBE.count("/search")).isEqualTo(1);
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);
    }

    @Test
    void theCacheDistinguishesPages() {
        proveedorDevuelve("P2", LocalYoutubeServer.video(videoIdAleatorio()));
        String consulta = consultaNueva();

        buscar(consulta);
        buscar(consulta, "P2");
        buscar(consulta, "P2");

        assertThat(YOUTUBE.count("/search")).isEqualTo(2);
    }

    @Test
    void failuresAreNotCached() {
        String consulta = consultaNueva();
        YOUTUBE.script("/search", 500, "{}");
        assertThat(buscar(consulta).statusCode()).isEqualTo(503);

        proveedorDevuelve(null, LocalYoutubeServer.video(videoIdAleatorio()));
        assertThat(buscar(consulta).statusCode()).isEqualTo(200);
    }

    // ---------- provider failures ----------

    @Test
    void anInvalidKeyIsReportedAsUnavailable() {
        YOUTUBE.script("/search", 400, LocalYoutubeServer.errorJson(400, "keyInvalid"));

        HttpResponse<String> response = buscar(consultaNueva());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("CATALOG_UNAVAILABLE");
    }

    @Test
    void aForbiddenKeyRestrictionIsReportedAsUnavailable() {
        YOUTUBE.script("/search", 403, LocalYoutubeServer.errorJson(403, "ipRefererBlocked"));

        HttpResponse<String> response = buscar(consultaNueva());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("CATALOG_UNAVAILABLE");
    }

    @Test
    void aProviderServerErrorIsReportedAsUnavailable() {
        YOUTUBE.script("/search", 500, "<html>boom</html>");

        HttpResponse<String> response = buscar(consultaNueva());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("CATALOG_UNAVAILABLE");
    }

    @Test
    void aMalformedProviderAnswerIsReportedAsUnavailable() {
        YOUTUBE.script("/search", 200, "this is not json");

        assertThat(code(buscar(consultaNueva()))).isEqualTo("CATALOG_UNAVAILABLE");
    }

    @Test
    void aFailingDetailsCallIsReportedAsUnavailable() {
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, videoIdAleatorio()));
        YOUTUBE.script("/videos", 500, "{}");

        assertThat(code(buscar(consultaNueva()))).isEqualTo("CATALOG_UNAVAILABLE");
    }

    @Test
    void aSlowProviderTimesOutAsUnavailable() {
        proveedorDevuelve(null, LocalYoutubeServer.video(videoIdAleatorio()));
        YOUTUBE.delay("/search", 2500);

        HttpResponse<String> response = buscar(consultaNueva());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("CATALOG_UNAVAILABLE");
    }

    // ---------- query validation ----------

    @Test
    void rejectsQueriesThatAreTooShortOrTooLongOrBlank() {
        for (String invalid : new String[] {"a", "   a   ", "x".repeat(101), "   ", ""}) {
            HttpResponse<String> response = buscar(invalid);
            assertThat(response.statusCode()).as("'%s'", invalid).isEqualTo(400);
            assertThat(code(response)).isEqualTo("INVALID_QUERY");
        }
        assertThat(code(get("/api/mi-establecimiento/catalogo/busqueda", propietarioToken()))).isEqualTo("INVALID_QUERY");
        assertThat(YOUTUBE.count()).isZero();
    }

    @Test
    void acceptsTheBoundaryLengths() {
        proveedorDevuelve(null, LocalYoutubeServer.video(videoIdAleatorio()));
        assertThat(buscar("ab").statusCode()).isEqualTo(200);
        assertThat(buscar(("y".repeat(98) + videoIdAleatorio()).substring(0, 100)).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsAMalformedPageToken() {
        HttpResponse<String> response = buscar(consultaNueva(), "bad token!");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_PAGE");
        assertThat(YOUTUBE.count()).isZero();
    }

    // ---------- access ----------

    @Test
    void anonymousCallersAreRejectedOwnersAreServedAndAccountsWithoutAnEstablishmentAreNot() {
        proveedorDevuelve(null, LocalYoutubeServer.video(videoIdAleatorio()));
        String path = "/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consultaNueva(), StandardCharsets.UTF_8);

        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, propietarioToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> sinLocal = get(path, tokenSinEstablecimiento());
        assertThat(sinLocal.statusCode()).isEqualTo(404);
        assertThat(code(sinLocal)).isEqualTo("NO_ESTABLISHMENT");
    }
}
