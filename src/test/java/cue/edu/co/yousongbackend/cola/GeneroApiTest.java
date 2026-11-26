package cue.edu.co.yousongbackend.cola;

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
 * The music genre ({@code genero}) every song object carries: live from YouTube's topics in search results,
 * stored in {@code cancion.genero_propio} (source {@code youtube_topic}) by the single metadata write, and read
 * back by the queue, the default list and the suggestions. A genre of another source is never overwritten.
 */
@TestPropertySource(properties = {"app.youtube.daily-quota-limit=1000000", "app.suggestions.youtube.enabled=false"})
class GeneroApiTest extends ColaApiTestSupport {

    private static final LocalYoutubeServer YOUTUBE = new LocalYoutubeServer();
    private static final String WIKI = "https://en.wikipedia.org/wiki/";

    @DynamicPropertySource
    static void youtube(DynamicPropertyRegistry registry) {
        registry.add("app.youtube.base-url", YOUTUBE::baseUrl);
        registry.add("app.youtube.api-key", () -> YoutubeApiTest.CLAVE_PRUEBA);
        registry.add("app.youtube.timeout-seconds", () -> "1");
        registry.add("app.youtube.max-results", () -> "5");
    }

    @BeforeEach
    void resetYoutube() {
        YOUTUBE.reset();
    }

    private static String q(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8);
    }

    private void proveedorDevuelve(LocalYoutubeServer.Video... videos) {
        String[] ids = new String[videos.length];
        for (int i = 0; i < videos.length; i++) {
            ids[i] = videos[i].id();
            YOUTUBE.register(videos[i]);
        }
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, ids));
    }

    private String generoGuardado(String videoId) {
        return jdbc.queryForObject("SELECT coalesce(genero_propio, '<null>') || '|' || coalesce(fuente_clasificacion, "
                + "'<null>') FROM operacion.cancion WHERE youtube_video_id = ?", String.class, videoId);
    }

    /** A stored song with this genre and source whose metadata already expired (so the next write refreshes it). */
    private void cancionVencidaConGenero(String videoId, String genero, String fuente) {
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id, titulo, genero_propio, fuente_clasificacion, "
                + "disponibilidad, metadatos_expiran_at) VALUES (?, ?, 'Vieja', ?, ?, 'DISPONIBLE', now() - interval '1 day')",
                UUID.randomUUID(), videoId, genero, fuente);
    }

    private HttpResponse<String> publicarLista(String token, String... videoIds) {
        StringBuilder canciones = new StringBuilder();
        for (String id : videoIds) {
            canciones.append(canciones.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        return put("/api/mi-establecimiento/lista-defecto", token, "{\"canciones\":[" + canciones + "]}");
    }

    private void conGenero(String videoId, String genero) {
        jdbc.update("UPDATE operacion.cancion SET genero_propio = ?, fuente_clasificacion = 'youtube_topic' "
                + "WHERE youtube_video_id = ?", genero, videoId);
    }

    // ---------- search ----------

    @Test
    void ownerSearchResultsCarryTheLiveGenreOrNull() {
        String conTemas = videoIdAleatorio();
        String sinTemas = videoIdAleatorio();
        proveedorDevuelve(LocalYoutubeServer.video(conTemas).topicos(WIKI + "Music", WIKI + "Pop_music", WIKI + "Reggaeton"),
                LocalYoutubeServer.video(sinTemas));

        HttpResponse<String> response = get("/api/mi-establecimiento/catalogo/busqueda?q=" + q("genero " + conTemas),
                propietarioToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode resultados = json(response).get("resultados");
        assertThat(resultados.get(0).get("genero").asString()).isEqualTo("Reggaetón");
        assertThat(resultados.get(1).has("genero")).as("the field is always present").isTrue();
        assertThat(resultados.get(1).get("genero").isNull()).isTrue();
        assertThat(YOUTUBE.last("/videos").param("part")).isEqualTo("snippet,contentDetails,status,topicDetails");
    }

    @Test
    void aCuratedStoredGenreWinsOverTheLiveOneInSearch() {
        String video = videoIdAleatorio();
        cancionVencidaConGenero(video, "Bolero", "curaduria");
        proveedorDevuelve(LocalYoutubeServer.video(video).topicos(WIKI + "Pop_music"));

        JsonNode primero = json(get("/api/mi-establecimiento/catalogo/busqueda?q=" + q("curado " + video),
                propietarioToken())).get("resultados").get(0);

        assertThat(primero.get("genero").asString()).isEqualTo("Bolero");
    }

    @Test
    void participantSearchResultsCarryTheGenre() {
        Local local = localAbierto();
        String video = videoIdAleatorio();
        proveedorDevuelve(LocalYoutubeServer.video(video).topicos(WIKI + "Salsa_music"));

        HttpResponse<String> response = get("/api/participante/catalogo/busqueda?q=" + q("salsa " + video), unirse(local));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("resultados").get(0).get("genero").asString()).isEqualTo("Salsa");
        assertThat(contar("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?", video))
                .as("a search alone stores nothing").isZero();
    }

    // ---------- persistence ----------

    @Test
    void requestingANewSongStoresItsGenreWithTheYoutubeSourceAndTheQueueShowsIt() {
        Local local = localAbierto();
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video).titulo("Vivir mi vida").topicos(WIKI + "Salsa_music", WIKI + "Pop_music"));
        String participante = unirse(local);

        assertThat(solicitar(participante, video).statusCode()).isEqualTo(201);

        assertThat(YOUTUBE.last("/videos").param("part")).isEqualTo("snippet,contentDetails,status,topicDetails");
        assertThat(generoGuardado(video)).isEqualTo("Salsa|youtube_topic");
        assertThat(json(colaPublica(local.slug())).get("cola").get(0).get("cancion").get("genero").asString())
                .isEqualTo("Salsa");
        assertThat(json(colaParticipante(participante)).get("cola").get(0).get("cancion").get("genero").asString())
                .isEqualTo("Salsa");
    }

    @Test
    void aSongWithoutTopicsIsStoredWithoutGenre() {
        Local local = localAbierto();
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video).topicos(WIKI + "Entertainment"));

        assertThat(solicitar(unirse(local), video).statusCode()).isEqualTo(201);

        assertThat(generoGuardado(video)).isEqualTo("<null>|<null>");
        JsonNode cancion = json(colaPublica(local.slug())).get("cola").get(0).get("cancion");
        assertThat(cancion.has("genero")).isTrue();
        assertThat(cancion.get("genero").isNull()).isTrue();
    }

    @Test
    void aGenreOfAnotherSourceIsNeverOverwritten() {
        Local local = establecimiento();
        String video = videoIdAleatorio();
        cancionVencidaConGenero(video, "Bolero", "curaduria");
        YOUTUBE.register(LocalYoutubeServer.video(video).titulo("Nueva").topicos(WIKI + "Pop_music"));

        HttpResponse<String> response = publicarLista(local.token(), video);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(YOUTUBE.count("/videos")).as("the metadata was refreshed").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT titulo FROM operacion.cancion WHERE youtube_video_id = ?", String.class,
                video)).isEqualTo("Nueva");
        assertThat(generoGuardado(video)).isEqualTo("Bolero|curaduria");
        assertThat(json(response).get("canciones").get(0).get("genero").asString()).isEqualTo("Bolero");
    }

    @Test
    void aSourcelessEmptyGenreIsFilled() {
        Local local = establecimiento();
        String video = videoIdAleatorio();
        cancionVencidaConGenero(video, null, "curaduria");
        YOUTUBE.register(LocalYoutubeServer.video(video).topicos(WIKI + "Rock_music"));

        assertThat(publicarLista(local.token(), video).statusCode()).isEqualTo(201);

        assertThat(generoGuardado(video)).isEqualTo("Rock|youtube_topic");
    }

    @Test
    void aYoutubeGenreIsRefreshedButNeverErased() {
        Local local = establecimiento();
        String video = videoIdAleatorio();
        cancionVencidaConGenero(video, "Rock", "youtube_topic");
        YOUTUBE.register(LocalYoutubeServer.video(video).topicos(WIKI + "Pop_music"));

        assertThat(publicarLista(local.token(), video).statusCode()).isEqualTo(201);
        assertThat(generoGuardado(video)).isEqualTo("Pop|youtube_topic");

        jdbc.update("UPDATE operacion.cancion SET metadatos_expiran_at = now() - interval '1 day' WHERE youtube_video_id = ?",
                video);
        YOUTUBE.register(LocalYoutubeServer.video(video));
        assertThat(publicarLista(local.token(), video).statusCode()).isEqualTo(201);

        assertThat(YOUTUBE.count("/videos")).isEqualTo(2);
        assertThat(generoGuardado(video)).as("a video that lost its topics keeps the last known genre")
                .isEqualTo("Pop|youtube_topic");
    }

    // ---------- reads of stored songs ----------

    @Test
    void theSongPlayingAndTheDefaultPreviewCarryTheStoredGenre() {
        String primera = videoGuardado();
        String segunda = videoGuardado();
        String tercera = videoGuardado();
        conGenero(primera, "Vallenato");
        conGenero(segunda, "Cumbia");
        Local local = localConLista("SECUENCIAL", primera, segunda, tercera);

        HttpResponse<String> inicio = siguiente(local.token());
        assertThat(inicio.statusCode()).as(inicio.body()).isEqualTo(200);
        assertThat(json(inicio).get("cancion").get("genero").asString()).isEqualTo("Vallenato");

        JsonNode cola = json(colaPublica(local.slug()));
        assertThat(cola.get("enReproduccion").get("cancion").get("genero").asString()).isEqualTo("Vallenato");
        assertThat(cola.get("proximasDefecto").get(0).get("cancion").get("genero").asString()).isEqualTo("Cumbia");
        assertThat(cola.get("proximasDefecto").get(1).get("cancion").get("genero").isNull()).isTrue();
    }

    @Test
    void theDefaultListReadsCarryTheStoredGenre() {
        Local local = establecimiento();
        String video = videoGuardado();
        conGenero(video, "Merengue");

        assertThat(publicarLista(local.token(), video).statusCode()).isEqualTo(201);

        JsonNode lista = json(get("/api/mi-establecimiento/lista-defecto", local.token()));
        assertThat(lista.get("canciones").get(0).get("genero").asString()).isEqualTo("Merengue");
        assertThat(YOUTUBE.count()).as("fresh metadata: no provider call").isZero();
    }

    @Test
    void knownSongSuggestionsCarryTheStoredGenre() {
        String video = videoIdAleatorio();
        String titulo = "Ojala que llueva " + video;
        cancionGuardada(video, titulo, "JuanLuisGuerraVEVO", 250);
        conGenero(video, "Merengue");

        HttpResponse<String> response = get("/api/mi-establecimiento/catalogo/sugerencias?q=" + q(titulo),
                propietarioToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode sugerencia = json(response).get("sugerencias").get(0);
        assertThat(sugerencia.get("cancion").get("videoId").asString()).isEqualTo(video);
        assertThat(sugerencia.get("cancion").get("genero").asString()).isEqualTo("Merengue");
    }
}
