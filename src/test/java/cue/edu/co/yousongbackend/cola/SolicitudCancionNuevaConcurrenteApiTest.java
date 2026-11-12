package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Many participants requesting the same unknown song at once share one YouTube lookup: one videos.list call and
 * one quota unit, charged to the establishment's daily budget (2 units here, so the budget shows what was spent).
 */
@TestPropertySource(properties = {"app.youtube.daily-quota-limit=1000000",
        "app.participante.search-units-per-establishment-per-day=2"})
class SolicitudCancionNuevaConcurrenteApiTest extends ColaApiTestSupport {

    private static final LocalYoutubeServer YOUTUBE = new LocalYoutubeServer();
    private static final int PARTICIPANTES = 30;

    @DynamicPropertySource
    static void youtube(DynamicPropertyRegistry registry) {
        registry.add("app.youtube.base-url", YOUTUBE::baseUrl);
        registry.add("app.youtube.api-key", () -> YoutubeApiTest.CLAVE_PRUEBA);
        registry.add("app.youtube.timeout-seconds", () -> "2");
    }

    @BeforeEach
    void resetYoutube() {
        YOUTUBE.reset();
    }

    /** Every participant requests the video at the same moment; the answers in submission order. */
    private List<HttpResponse<String>> todosAlaVez(List<String> tokens, String video) throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
        try {
            List<Future<HttpResponse<String>>> futuras = new ArrayList<>();
            for (String token : tokens) {
                futuras.add(pool.submit(() -> {
                    salida.await();
                    return solicitar(token, video);
                }));
            }
            salida.countDown();
            List<HttpResponse<String>> respuestas = new ArrayList<>();
            for (Future<HttpResponse<String>> futura : futuras) {
                respuestas.add(futura.get());
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<String> participantes(Local local) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < PARTICIPANTES; i++) {
            tokens.add(unirse(local));
        }
        return tokens;
    }

    /** Requests a fresh unknown song, which spends one more unit of the establishment budget. */
    private HttpResponse<String> solicitarOtraNueva(Local local) {
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video));
        return solicitar(unirse(local), video);
    }

    @Test
    void concurrentRequestsOfTheSameUnknownSongShareOneLookup() throws Exception {
        Local local = localAbierto();
        List<String> tokens = participantes(local);
        String video = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(video));
        YOUTUBE.delay("/videos", 500);

        List<HttpResponse<String>> respuestas = todosAlaVez(tokens, video);

        for (HttpResponse<String> response : respuestas) {
            assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        }
        assertThat(respuestas).filteredOn(r -> r.statusCode() == 201).hasSize(1);
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);
        UUID solicitud = cancionSolicitada(local, video);
        assertThat(votos(solicitud)).isEqualTo(PARTICIPANTES);
        // One unit spent out of two: one more lookup fits, the next one does not.
        YOUTUBE.reset();
        assertThat(solicitarOtraNueva(local).statusCode()).isEqualTo(201);
        assertProblema(solicitarOtraNueva(local), 429, "SEARCH_BUDGET_EXHAUSTED");
    }

    @Test
    void aFailedSharedLookupFailsEveryCallerIsGivenBackOnceAndIsNotRemembered() throws Exception {
        Local local = localAbierto();
        List<String> tokens = participantes(local);
        String video = videoIdAleatorio();
        YOUTUBE.script("/videos", 500, "{}");
        YOUTUBE.delay("/videos", 500);

        List<HttpResponse<String>> respuestas = todosAlaVez(tokens, video);

        for (HttpResponse<String> response : respuestas) {
            assertProblema(response, 503, "CATALOG_UNAVAILABLE");
        }
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?", video)).isZero();

        // The failed unit came back and nothing was cached: a later attempt looks the song up again.
        YOUTUBE.reset();
        YOUTUBE.register(LocalYoutubeServer.video(video));
        assertThat(solicitar(unirse(local), video).statusCode()).isEqualTo(201);
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);
        assertThat(solicitarOtraNueva(local).statusCode()).isEqualTo(201);
        assertProblema(solicitarOtraNueva(local), 429, "SEARCH_BUDGET_EXHAUSTED");
    }

    @Test
    void aSongTheProviderDoesNotKnowIsNotFoundForEveryConcurrentCaller() throws Exception {
        Local local = localAbierto();
        List<String> tokens = participantes(local);
        YOUTUBE.delay("/videos", 300);

        List<HttpResponse<String>> respuestas = todosAlaVez(tokens, videoIdAleatorio());

        for (HttpResponse<String> response : respuestas) {
            assertProblema(response, 404, "SONG_NOT_FOUND");
        }
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);
    }

    private UUID cancionSolicitada(Local local, String video) {
        return jdbc.queryForObject("SELECT s.id FROM operacion.solicitud s JOIN operacion.cancion c ON c.id = s.cancion_id "
                + "WHERE s.sesion_id = ? AND c.youtube_video_id = ?", UUID.class, sesionDe(local), video);
    }
}
