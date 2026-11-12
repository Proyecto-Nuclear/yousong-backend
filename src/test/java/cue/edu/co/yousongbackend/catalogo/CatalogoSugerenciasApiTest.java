package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cue.edu.co.yousongbackend.support.LocalSugerenciasServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Search-as-you-type for staff: songs the system already knows first, then the unofficial YouTube suggest
 * strings. Never touches the YouTube Data API (checked after every test), and a broken suggest source never
 * fails the endpoint.
 */
@TestPropertySource(properties = {"app.youtube.daily-quota-limit=960000",
        "app.suggestions.youtube.failure-threshold=100000", "app.suggestions.youtube.max-calls-per-second=100000"})
class CatalogoSugerenciasApiTest extends YoutubeApiTest {

    static final LocalSugerenciasServer SUGERENCIAS = new LocalSugerenciasServer();
    private static final SecureRandom RANDOM = new SecureRandom();

    @DynamicPropertySource
    static void sugerencias(DynamicPropertyRegistry registry) {
        registry.add("app.suggestions.youtube.base-url", SUGERENCIAS::baseUrl);
        registry.add("app.suggestions.youtube.timeout-millis", () -> "400");
    }

    @BeforeEach
    void resetSugerencias() {
        SUGERENCIAS.reset();
    }

    @AfterEach
    void neverCallsTheDataApi() {
        assertThat(YOUTUBE.count()).as("suggestions must never call the YouTube Data API").isZero();
    }

    // ---------- helpers ----------

    /** A lowercase word no other test (or other class) uses, so shared songs never interfere. */
    static String marca() {
        StringBuilder texto = new StringBuilder("qz");
        for (int i = 0; i < 8; i++) {
            texto.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return texto.toString();
    }

    private HttpResponse<String> sugerir(String consulta) {
        return get("/api/mi-establecimiento/catalogo/sugerencias?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8),
                propietarioToken());
    }

    private JsonNode items(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response).get("sugerencias");
    }

    private static List<String> textos(JsonNode items) {
        List<String> textos = new ArrayList<>();
        items.forEach(item -> textos.add(item.get("tipo").asString() + ":" + item.get("texto").asString()));
        return textos;
    }

    private String cancion(String titulo, String artistaVerificado, String canal, String disponibilidad) {
        String videoId = videoIdAleatorio();
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id, titulo, artista_verificado, canal_titulo, "
                        + "disponibilidad, duracion_proveedor_s, miniatura_url) VALUES (?, ?, ?, ?, ?, ?, 215, ?)",
                UUID.randomUUID(), videoId, titulo, artistaVerificado, canal, disponibilidad,
                "https://img.test/" + videoId + "/mqdefault.jpg");
        return videoId;
    }

    private String cancion(String titulo) {
        return cancion(titulo, null, "Canal cualquiera", "DISPONIBLE");
    }

    // ---------- local songs ----------

    @Test
    void findsAKnownSongByTitleWithEverythingNeededToPickIt() {
        String m = marca();
        String id = cancion("Amor " + m, null, "Juanes - Topic", "DISPONIBLE");

        JsonNode items = items(sugerir(m));

        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.get("tipo").asString()).isEqualTo("CANCION");
        assertThat(item.get("texto").asString()).isEqualTo("Amor " + m);
        JsonNode cancion = item.get("cancion");
        assertThat(cancion.get("videoId").asString()).isEqualTo(id);
        assertThat(cancion.get("titulo").asString()).isEqualTo("Amor " + m);
        assertThat(cancion.get("artista").asString()).isEqualTo("Juanes");
        assertThat(cancion.get("artistaVerificado").asBoolean()).isFalse();
        assertThat(cancion.get("canal").asString()).isEqualTo("Juanes - Topic");
        assertThat(cancion.get("duracionSegundos").asInt()).isEqualTo(215);
        assertThat(cancion.get("miniaturaUrl").asString()).isEqualTo("https://img.test/" + id + "/mqdefault.jpg");
    }

    @Test
    void findsASongByItsVerifiedArtist() {
        String m = marca();
        cancion("Sin pistas", "Artista " + m, "Otro canal", "DISPONIBLE");

        JsonNode cancion = items(sugerir(m)).get(0).get("cancion");

        assertThat(cancion.get("artista").asString()).isEqualTo("Artista " + m);
        assertThat(cancion.get("artistaVerificado").asBoolean()).isTrue();
    }

    @Test
    void findsASongByTheArtistDerivedFromATopicChannel() {
        String m = marca();
        cancion("Sin pistas", null, "Banda" + m + " - Topic", "DESCONOCIDA");

        JsonNode cancion = items(sugerir("banda" + m)).get(0).get("cancion");

        assertThat(cancion.get("artista").asString()).isEqualTo("Banda" + m);
        assertThat(cancion.get("artistaVerificado").asBoolean()).isFalse();
    }

    @Test
    void ignoresAccentsAndCase() {
        String m = marca();
        cancion("CANCIÓN Ñandú " + m.toUpperCase());

        assertThat(items(sugerir("cancion nandu " + m))).hasSize(1);
        assertThat(items(sugerir("Canción ÑANDÚ " + m))).hasSize(1);
    }

    @Test
    void everyWordOfTheQueryMustMatch() {
        String m = marca();
        cancion("Rojo " + m);
        cancion("Azul " + m);

        assertThat(textos(items(sugerir(m + " rojo")))).containsExactly("CANCION:Rojo " + m);
        assertThat(items(sugerir(m + " verde"))).isEmpty();
    }

    @Test
    void titlePrefixAndWordStartMatchesComeFirst() {
        String m = marca();
        cancion("aaa x" + m);
        cancion("zzz " + m);
        cancion("bbb " + m);
        cancion(m + " zzz");

        assertThat(textos(items(sugerir(m)))).containsExactly(
                "CANCION:" + m + " zzz", "CANCION:bbb " + m, "CANCION:zzz " + m, "CANCION:aaa x" + m);
    }

    @Test
    void likeWildcardsInTheQueryMatchLiterally() {
        String m = marca();
        cancion("100% " + m);
        cancion("100 " + m + "x");
        cancion("a_b" + m);
        cancion("axb" + m);
        cancion("back\\slash" + m);

        assertThat(textos(items(sugerir("100% " + m)))).containsExactly("CANCION:100% " + m);
        assertThat(textos(items(sugerir("a_b" + m)))).containsExactly("CANCION:a_b" + m);
        assertThat(textos(items(sugerir("k\\s" + "lash" + m)))).containsExactly("CANCION:back\\slash" + m);
        assertThat(items(sugerir("%" + m))).isEmpty();
    }

    @Test
    void unavailableAndUntitledSongsAreNeverSuggested() {
        String m = marca();
        cancion("Fuera " + m, null, "Canal", "NO_DISPONIBLE");
        cancion(null, "Artista " + m, "Canal " + m, "DISPONIBLE");
        cancion("Pendiente " + m, null, "Canal", "DESCONOCIDA");

        assertThat(textos(items(sugerir(m)))).containsExactly("CANCION:Pendiente " + m);
    }

    @Test
    void atMostFiveLocalSongs() {
        String m = marca();
        for (int i = 0; i < 7; i++) {
            cancion("Tema " + i + " " + m);
        }

        assertThat(items(sugerir(m))).hasSize(5);
    }

    // ---------- remote suggestions ----------

    @Test
    void remoteSuggestionsFollowTheSongsWithoutRepeatingThem() {
        String m = marca();
        cancion("Hola " + m);
        SUGERENCIAS.suggestions("hola " + m, "HÓLA " + m, "otra " + m, "otra " + m, "Otra " + m);

        assertThat(textos(items(sugerir(m)))).containsExactly("CANCION:Hola " + m, "BUSQUEDA:otra " + m);
        JsonNode busqueda = items(sugerir(m)).get(1);
        assertThat(busqueda.has("cancion")).isFalse();
    }

    @Test
    void theRemoteReceivesTheNormalizedQueryAndNothingElse() {
        SUGERENCIAS.suggestions("x");
        String m = marca();

        sugerir("  Shakira    " + m + "  ");

        LocalSugerenciasServer.Recibida recibida = SUGERENCIAS.last();
        assertThat(recibida.param("q")).isEqualTo("Shakira " + m);
        assertThat(recibida.hasHeader("Cookie")).isFalse();
        assertThat(recibida.hasHeader("Authorization")).isFalse();
    }

    @Test
    void atMostFiveRemoteSuggestionsAndEightInTotal() {
        String m = marca();
        SUGERENCIAS.suggestions("r1 " + m, "r2 " + m, "r3 " + m, "r4 " + m, "r5 " + m, "r6 " + m, "r7 " + m);
        assertThat(textos(items(sugerir(m)))).containsExactly(
                "BUSQUEDA:r1 " + m, "BUSQUEDA:r2 " + m, "BUSQUEDA:r3 " + m, "BUSQUEDA:r4 " + m, "BUSQUEDA:r5 " + m);

        String n = marca();
        for (int i = 0; i < 6; i++) {
            cancion("Tema " + i + " " + n);
        }
        SUGERENCIAS.suggestions("r1 " + n, "r2 " + n, "r3 " + n, "r4 " + n, "r5 " + n);
        List<String> todas = textos(items(sugerir(n)));
        assertThat(todas).hasSize(8);
        assertThat(todas.subList(0, 5)).allMatch(t -> t.startsWith("CANCION:"));
        assertThat(todas.subList(5, 8)).containsExactly("BUSQUEDA:r1 " + n, "BUSQUEDA:r2 " + n, "BUSQUEDA:r3 " + n);
    }

    @Test
    void aBrokenRemoteNeverFailsTheEndpoint() {
        String m = marca();
        cancion("Solo local " + m);

        SUGERENCIAS.script(500, "boom");
        assertThat(textos(items(sugerir(m + " solo")))).containsExactly("CANCION:Solo local " + m);

        SUGERENCIAS.script(200, "<html>captcha</html>");
        assertThat(textos(items(sugerir(m + " local")))).containsExactly("CANCION:Solo local " + m);

        SUGERENCIAS.script(200, "{\"unexpected\":true}");
        assertThat(textos(items(sugerir("solo local " + m)))).containsExactly("CANCION:Solo local " + m);

        SUGERENCIAS.suggestions("tarde " + m);
        SUGERENCIAS.delay(1_500);
        assertThat(textos(items(sugerir("local " + m)))).containsExactly("CANCION:Solo local " + m);
    }

    @Test
    void anIdenticalQueryIsServedFromTheCache() {
        String m = marca();
        SUGERENCIAS.suggestions("una " + m);

        sugerir(m);
        HttpResponse<String> segunda = sugerir(m.toUpperCase());

        assertThat(textos(items(segunda))).containsExactly("BUSQUEDA:una " + m);
        assertThat(SUGERENCIAS.count()).isEqualTo(1);
    }

    // ---------- validation and access ----------

    @Test
    void rejectsQueriesThatAreTooShortTooLongOrBlank() {
        for (String consulta : new String[]{"x", "  y  ", "   ", "a".repeat(101)}) {
            HttpResponse<String> response = sugerir(consulta);
            assertThat(response.statusCode()).as(consulta).isEqualTo(400);
            assertThat(json(response).get("code").asString()).isEqualTo("INVALID_QUERY");
        }
        HttpResponse<String> sinConsulta = get("/api/mi-establecimiento/catalogo/sugerencias", propietarioToken());
        assertThat(json(sinConsulta).get("code").asString()).isEqualTo("INVALID_QUERY");
        assertThat(sugerir("a".repeat(100)).statusCode()).isEqualTo(200);
        assertThat(sugerir("ab").statusCode()).isEqualTo(200);
        assertThat(SUGERENCIAS.count()).as("an invalid query never reaches the remote").isEqualTo(2);
    }

    @Test
    void onlyOwnersOfAnEstablishmentMayAskForSuggestions() {
        String path = "/api/mi-establecimiento/catalogo/sugerencias?q=" + marca();

        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, "pt_not-a-real-credential").statusCode()).isEqualTo(401);
        assertThat(get(path, propietarioToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> sinLocal = get(path, tokenSinEstablecimiento());
        assertThat(sinLocal.statusCode()).isEqualTo(404);
        assertThat(json(sinLocal).get("code").asString()).isEqualTo("NO_ESTABLISHMENT");
    }

    // ---------- privacy ----------

    @Test
    void theQueryNeverAppearsInTheLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> capturados = new ListAppender<>();
        capturados.start();
        root.addAppender(capturados);
        String m = marca();
        try {
            cancion("Privado " + m);
            SUGERENCIAS.suggestions("privado " + m + " live");
            sugerir("privado " + m);
            SUGERENCIAS.script(500, "boom");
            sugerir(m + " privado");
            SUGERENCIAS.script(200, "<html>" + m + "</html>");
            sugerir(m + " uno");
            SUGERENCIAS.suggestions("x");
            SUGERENCIAS.delay(1_000);
            sugerir(m + " dos");
            sugerir(m.substring(0, 1));
        } finally {
            root.detachAppender(capturados);
        }

        assertThat(capturados.list).as("the failures were logged").anySatisfy(evento ->
                assertThat(evento.getFormattedMessage()).contains("suggest"));
        assertThat(capturados.list).allSatisfy(evento -> {
            assertThat(evento.getFormattedMessage()).doesNotContain(m);
            if (evento.getThrowableProxy() != null) {
                assertThat(evento.getThrowableProxy().getMessage()).doesNotContain(m);
            }
        });
    }
}
