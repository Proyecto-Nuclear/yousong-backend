package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A slow provider call for one query must not block a different query. The old code ran the provider call
 * inside Caffeine's compute, which holds a ConcurrentHashMap bin lock, so a second query hashing to the same
 * bin waited. The test makes that collision deterministic: it picks two queries whose cache keys fall in the
 * same bin of a table of up to 64 bins (a cache of a handful of entries never grows past that), so the same
 * bin is shared in every smaller table too. Timing still matters (the slow call takes 900 ms, the other one
 * must answer in well under 500 ms), so the margins are wide; it cannot be made fully deterministic.
 */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=940000")
class CatalogoProveedorLentoApiTest extends YoutubeApiTest {

    private HttpResponse<String> buscar(String consulta) {
        return get("/api/mi-establecimiento/catalogo/busqueda?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8), propietarioToken());
    }

    /** Same shape as the service's cache key (lower-cased query, NUL, empty page token) and ConcurrentHashMap's spread. */
    private static int bin(String consulta) {
        int h = (consulta.toLowerCase() + "\u0000").hashCode();
        return (h ^ (h >>> 16)) & 63;
    }

    @Test
    void aSlowQueryDoesNotBlockADifferentQueryInTheSameCacheBin() throws Exception {
        String lenta = "lenta " + videoIdAleatorio();
        String rapida = null;
        for (int i = 0; rapida == null; i++) {
            String candidata = "rapida " + i;
            if (bin(candidata) == bin(lenta)) {
                rapida = candidata;
            }
        }
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.script("/search", 200, LocalYoutubeServer.searchJson(null, id));
        YOUTUBE.delay("/search", lenta, 900);

        CompletableFuture<HttpResponse<String>> enCurso = CompletableFuture.supplyAsync(() -> buscar(lenta));
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (YOUTUBE.count("/search") == 0) {
            assertThat(System.nanoTime()).as("the slow search reached the provider").isLessThan(limite);
            Thread.sleep(10);
        }

        long inicio = System.nanoTime();
        HttpResponse<String> otra = buscar(rapida);
        Duration tardanza = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(otra.statusCode()).as(otra.body()).isEqualTo(200);
        assertThat(tardanza).as("the other query is not held back by the slow one").isLessThan(Duration.ofMillis(500));
        assertThat(enCurso.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
}
