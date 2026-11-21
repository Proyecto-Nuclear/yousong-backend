package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.RelojDePrueba;
import cue.edu.co.yousongbackend.support.RelojDePrueba.RelojMovible;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/public/tendencias}: YouTube's most popular music videos in Colombia, one videos.list unit per
 * refresh charged to the system, cached for 6 hours, refreshed by a single flight and served stale (up to 48 hours)
 * when a refresh fails. The clock is movable; every test starts on a new quota day with an empty cache.
 */
@Import(RelojDePrueba.class)
@TestPropertySource(properties = {"app.youtube.daily-quota-limit=1000037", "app.trends.per-minute-per-address=100000"})
class TendenciasApiTest extends YoutubeApiTest {

    private static final String TENDENCIAS = "/api/public/tendencias";
    private static final String WIKI = "https://en.wikipedia.org/wiki/";

    @Autowired
    private RelojMovible reloj;

    @Autowired
    private TendenciasService tendencias;

    @Autowired
    private CuotaYoutube cuota;

    @BeforeEach
    void diaNuevo() {
        // 01:00 two quota days later: a fresh quota day (no units used, no exhaustion latched) with room for a
        // few hours before the next reset, and nothing cached is usable.
        ZoneId pacifico = ZoneId.of("America/Los_Angeles");
        reloj.fijar(LocalDate.now(reloj.withZone(pacifico)).plusDays(2).atTime(1, 0).atZone(pacifico).toInstant());
        tendencias.reiniciar();
    }

    private HttpResponse<String> pedir() {
        return get(TENDENCIAS, null);
    }

    private int llamadasAlGrafico() {
        return (int) YOUTUBE.requests("/videos").stream().filter(r -> r.param("chart") != null).count();
    }

    private static List<String> videos(JsonNode canciones) {
        List<String> ids = new ArrayList<>();
        canciones.forEach(c -> ids.add(c.get("videoId").asString()));
        return ids;
    }

    private String[] grafico() {
        String a = videoIdAleatorio();
        String b = videoIdAleatorio();
        YOUTUBE.chart(LocalYoutubeServer.video(a).titulo("Tusa").canal("KAROLGVEVO").duracion("PT3M21S")
                        .topicos(WIKI + "Music", WIKI + "Pop_music", WIKI + "Reggaeton"),
                LocalYoutubeServer.video(b).titulo("Sin temas"));
        return new String[] {a, b};
    }

    @Test
    void answersTheMostPopularMusicOfColombiaAsCatalogSongs() {
        String a = videoIdAleatorio();
        String noIncrustable = videoIdAleatorio();
        String privado = videoIdAleatorio();
        String b = videoIdAleatorio();
        YOUTUBE.chart(LocalYoutubeServer.video(a).titulo("Tusa").canal("KAROLGVEVO").duracion("PT3M21S")
                        .topicos(WIKI + "Pop_music", WIKI + "Reggaeton"),
                LocalYoutubeServer.video(noIncrustable).incrustable(false),
                LocalYoutubeServer.video(privado).privacidad("private"),
                LocalYoutubeServer.video(b).titulo("Otra").canal("Canal - Topic"));

        HttpResponse<String> response = pedir();

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode cuerpo = json(response);
        assertThat(cuerpo.propertyNames()).containsExactlyInAnyOrder("region", "actualizadoAt", "canciones");
        assertThat(cuerpo.get("region").asString()).isEqualTo("CO");
        assertThat(Instant.parse(cuerpo.get("actualizadoAt").asString())).isEqualTo(reloj.instant());
        assertThat(videos(cuerpo.get("canciones"))).as("only songs the player can play, in chart order")
                .containsExactly(a, b);
        JsonNode primera = cuerpo.get("canciones").get(0);
        assertThat(primera.propertyNames()).containsExactlyInAnyOrder("videoId", "titulo", "artista",
                "artistaVerificado", "canal", "duracionSegundos", "miniaturaUrl", "genero");
        assertThat(primera.get("titulo").asString()).isEqualTo("Tusa");
        assertThat(primera.get("artista").asString()).isEqualTo("KAROLG");
        assertThat(primera.get("artistaVerificado").asBoolean()).isFalse();
        assertThat(primera.get("canal").asString()).isEqualTo("KAROLGVEVO");
        assertThat(primera.get("duracionSegundos").asInt()).isEqualTo(201);
        assertThat(primera.get("miniaturaUrl").asString()).isEqualTo("https://img.test/" + a + "/mqdefault.jpg");
        assertThat(primera.get("genero").asString()).isEqualTo("Reggaetón");
        assertThat(cuerpo.get("canciones").get(1).get("genero").isNull()).isTrue();
        assertThat(cuerpo.get("canciones").get(1).get("artista").asString()).isEqualTo("Canal");
    }

    @Test
    void sendsExactlyTheChartParametersToTheProvider() {
        grafico();

        assertThat(pedir().statusCode()).isEqualTo(200);

        assertThat(YOUTUBE.count()).isEqualTo(1);
        LocalYoutubeServer.Recibida llamada = YOUTUBE.last("/videos");
        assertThat(llamada.query()).containsOnlyKeys("key", "part", "chart", "regionCode", "videoCategoryId", "maxResults");
        assertThat(llamada.param("key")).isEqualTo(CLAVE_PRUEBA);
        assertThat(llamada.param("part")).isEqualTo("snippet,contentDetails,status,topicDetails");
        assertThat(llamada.param("chart")).isEqualTo("mostPopular");
        assertThat(llamada.param("regionCode")).isEqualTo("CO");
        assertThat(llamada.param("videoCategoryId")).isEqualTo("10");
        assertThat(llamada.param("maxResults")).isEqualTo("25");
    }

    @Test
    void theSongsAreStoredWithTheirGenre() {
        String[] ids = grafico();
        String noIncrustable = videoIdAleatorio();
        YOUTUBE.chart(LocalYoutubeServer.video(ids[0]).titulo("Tusa").topicos(WIKI + "Reggaeton"),
                LocalYoutubeServer.video(noIncrustable).incrustable(false));

        assertThat(pedir().statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT titulo || '|' || genero_propio || '|' || fuente_clasificacion || '|' "
                + "|| disponibilidad FROM operacion.cancion WHERE youtube_video_id = ?", String.class, ids[0]))
                .isEqualTo("Tusa|Reggaetón|youtube_topic|DISPONIBLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?",
                Integer.class, noIncrustable)).as("a song the player cannot play is not stored").isZero();
    }

    @Test
    void aCacheHitMakesNoProviderCallForSixHours() {
        grafico();
        JsonNode primera = json(pedir());

        reloj.avanzar(Duration.ofHours(6).minusSeconds(1));
        JsonNode segunda = json(pedir());

        assertThat(llamadasAlGrafico()).isEqualTo(1);
        assertThat(segunda).isEqualTo(primera);

        reloj.avanzar(Duration.ofSeconds(1));
        JsonNode tercera = json(pedir());

        assertThat(llamadasAlGrafico()).as("six hours later the chart is refreshed").isEqualTo(2);
        assertThat(Instant.parse(tercera.get("actualizadoAt").asString())).isEqualTo(reloj.instant());
    }

    @Test
    void concurrentRequestsShareOneProviderCall() throws Exception {
        grafico();
        YOUTUBE.delay("/videos", 400);
        ExecutorService hilos = Executors.newFixedThreadPool(20);
        CountDownLatch salida = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> respuestas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                respuestas.add(hilos.submit(() -> {
                    salida.await();
                    return pedir();
                }));
            }
            salida.countDown();
            for (Future<HttpResponse<String>> respuesta : respuestas) {
                assertThat(respuesta.get().statusCode()).as(respuesta.get().body()).isEqualTo(200);
                assertThat(json(respuesta.get()).get("canciones")).hasSize(2);
            }
        } finally {
            hilos.shutdownNow();
        }

        assertThat(llamadasAlGrafico()).isEqualTo(1);
        assertThat(cuota.usadas()).isEqualTo(1);
    }

    @Test
    void aFailedRefreshServesTheStaleChart() {
        String[] ids = grafico();
        JsonNode fresca = json(pedir());
        reloj.avanzar(Duration.ofHours(7));
        YOUTUBE.script("/videos", 500, LocalYoutubeServer.errorJson(500, "backendError"));

        HttpResponse<String> response = pedir();

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(llamadasAlGrafico()).isEqualTo(2);
        assertThat(json(response).get("actualizadoAt")).isEqualTo(fresca.get("actualizadoAt"));
        assertThat(videos(json(response).get("canciones"))).containsExactly(ids);
    }

    @Test
    void afterAFailureTheProviderIsNotRetriedForAMinute() {
        grafico();
        pedir();
        reloj.avanzar(Duration.ofHours(7));
        YOUTUBE.script("/videos", 500, LocalYoutubeServer.errorJson(500, "backendError"));
        assertThat(pedir().statusCode()).isEqualTo(200);

        reloj.avanzar(Duration.ofSeconds(59));
        assertThat(pedir().statusCode()).isEqualTo(200);
        assertThat(llamadasAlGrafico()).isEqualTo(2);

        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(pedir().statusCode()).isEqualTo(200);
        assertThat(llamadasAlGrafico()).isEqualTo(3);
    }

    @Test
    void aStaleChartIsServedForAtMostFortyEightHours() {
        grafico();
        pedir();
        YOUTUBE.script("/videos", 500, LocalYoutubeServer.errorJson(500, "backendError"));

        reloj.avanzar(Duration.ofHours(48));
        assertThat(pedir().statusCode()).as("exactly 48 hours old is still served").isEqualTo(200);

        reloj.avanzar(Duration.ofMinutes(2));
        HttpResponse<String> response = pedir();

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(json(response).get("code").asString()).isEqualTo("CATALOG_UNAVAILABLE");
    }

    @Test
    void withoutStaleDataAProviderFailureIsUnavailable() {
        YOUTUBE.script("/videos", 500, LocalYoutubeServer.errorJson(500, "backendError"));

        HttpResponse<String> response = pedir();

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response).get("code").asString()).isEqualTo("CATALOG_UNAVAILABLE");
    }

    @Test
    void withoutStaleDataTheProviderQuotaIsReported() {
        YOUTUBE.script("/videos", 403, LocalYoutubeServer.errorJson(403, "quotaExceeded"));

        HttpResponse<String> response = pedir();

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response).get("code").asString()).isEqualTo("CATALOG_QUOTA_EXCEEDED");
    }

    @Test
    void eachRefreshChargesOneSystemUnitAndNothingToOwnersOrPatrons() {
        grafico();
        int antes = cuota.usadas();

        pedir();
        pedir();

        assertThat(cuota.usadas()).isEqualTo(antes + 1);
        assertThat(cuota.usadasPor(OrigenBusqueda.Quien.PARTICIPANTE)).isZero();
        assertThat(cuota.usadasPor(OrigenBusqueda.Quien.PROPIETARIO)).isZero();

        reloj.avanzar(Duration.ofHours(6));
        pedir();

        assertThat(cuota.usadas()).isEqualTo(antes + 2);
        assertThat(cuota.usadasPor(OrigenBusqueda.Quien.PARTICIPANTE)).isZero();
        assertThat(cuota.usadasPor(OrigenBusqueda.Quien.PROPIETARIO)).isZero();
    }

    @Test
    void anEmptyChartIsAnEmptyList() {
        YOUTUBE.chart();

        HttpResponse<String> response = pedir();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("canciones").isArray()).isTrue();
        assertThat(json(response).get("canciones")).isEmpty();
    }
}
