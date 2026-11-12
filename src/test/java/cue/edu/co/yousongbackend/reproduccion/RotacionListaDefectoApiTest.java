package cue.edu.co.yousongbackend.reproduccion;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The default list covers an empty queue and hands back to the requests when they reappear. */
class RotacionListaDefectoApiTest extends ColaApiTestSupport {

    @Test
    void anEmptyQueuePlaysTheDefaultListWithoutCreatingARequest() {
        String a = videoGuardado();
        Local local = localConLista("SECUENCIAL", a, videoGuardado());
        UUID sesion = sesionDe(local);
        long antes = colaVersion(sesion);

        HttpResponse<String> response = siguiente(local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("origen").asString()).isEqualTo("DEFECTO");
        assertThat(body.get("solicitudId").isNull()).isTrue();
        assertThat(body.get("votos").asLong()).isZero();
        assertThat(body.get("cancion").get("videoId").asString()).isEqualTo(a);
        assertThat(body.get("colaVersion").asLong()).isEqualTo(antes + 1);
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ?", sesion)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT r.origen || '|' || (r.solicitud_id IS NULL) || '|' || il.posicion || '|' || (il.lista_id = s.lista_id)
                FROM operacion.reproduccion r JOIN operacion.item_lista il ON il.id = r.item_lista_id
                JOIN operacion.sesion s ON s.id = r.sesion_id WHERE r.sesion_id = ?
                """, String.class, sesion)).isEqualTo("DEFECTO|true|1|true");
        UUID reproduccion = UUID.fromString(body.get("reproduccionId").asString());
        assertThat(cambioDeOrigen(reproduccion)).isEqualTo("null|DEFECTO|" + reproduccion);
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'origin') || '|' || (payload ->> 'request_id' IS NULL)
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'playback.started'
                """, String.class, sesion)).isEqualTo("DEFECTO|true");
    }

    @Test
    void thePublicQueueShowsADefaultSongWithoutIdentities() {
        String a = videoGuardado();
        Local local = localConLista("SECUENCIAL", a);
        siguiente(local.token());

        JsonNode cola = json(colaPublica(local.slug()));

        JsonNode sonando = cola.get("enReproduccion");
        assertThat(sonando.get("origen").asString()).isEqualTo("DEFECTO");
        assertThat(sonando.get("solicitudId").isNull()).isTrue();
        assertThat(sonando.get("cancion").get("videoId").asString()).isEqualTo(a);
        assertThat(sonando.get("restanteSegundos").asLong()).isBetween(195L, 200L);
        assertThat(sonando.has("votado")).isFalse();
        assertThat(sonando.has("participacionId")).isFalse();
        assertThat(cola.get("cola")).isEmpty();
    }

    @Test
    void aRequestedSongShowsItsOriginToo() {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        siguiente(local.token());

        JsonNode sonando = json(colaPublica(local.slug())).get("enReproduccion");

        assertThat(sonando.get("origen").asString()).isEqualTo("SOLICITUD");
        assertThat(sonando.get("solicitudId").asString()).isEqualTo(solicitud.toString());
    }

    @Test
    void sequentialRotationFollowsThePositionsAndWrapsAround() {
        String a = videoGuardado();
        String b = videoGuardado();
        String c = videoGuardado();
        Local local = localConLista("SECUENCIAL", a, b, c);

        assertThat(reproducirVarias(local, 5)).containsExactly(a, b, c, a, b);
    }

    @Test
    void sequentialRotationSkipsBlockedAndUnavailableSongs() {
        String a = videoGuardado();
        String b = videoGuardado();
        String c = videoGuardado();
        String d = videoGuardado();
        Local local = localConLista("SECUENCIAL", a, b, c, d);
        bloqueada(local.token(), b);
        jdbc.update("UPDATE operacion.cancion SET disponibilidad = 'NO_DISPONIBLE' WHERE youtube_video_id = ?", c);

        assertThat(reproducirVarias(local, 3)).containsExactly(a, d, a);
    }

    @Test
    void aListWithNothingPlayableIsAnEmptyQueue() {
        String a = videoGuardado();
        Local local = localConLista("SECUENCIAL", a);
        UUID sesion = sesionDe(local);
        bloqueada(local.token(), a);
        long version = colaVersion(sesion);

        assertProblema(siguiente(local.token()), 409, "QUEUE_EMPTY");
        assertThat(colaVersion(sesion)).isEqualTo(version);
        assertThat(contar("SELECT count(*) FROM operacion.reproduccion WHERE sesion_id = ?", sesion)).isZero();
    }

    @Test
    void randomRotationNeverRepeatsTheLastSongWhenThereAreAlternatives() {
        String a = videoGuardado();
        String b = videoGuardado();
        String c = videoGuardado();
        Local local = localConLista("ALEATORIA", a, b, c);

        List<String> reproducidas = reproducirVarias(local, 40);

        for (int i = 1; i < reproducidas.size(); i++) {
            assertThat(reproducidas.get(i)).as("play %d", i).isNotEqualTo(reproducidas.get(i - 1));
        }
        assertThat(new HashSet<>(reproducidas)).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void randomRotationRepeatsTheOnlyPlayableSong() {
        String a = videoGuardado();
        String b = videoGuardado();
        Local local = localConLista("ALEATORIA", a, b);
        bloqueada(local.token(), b);

        assertThat(reproducirVarias(local, 3)).containsExactly(a, a, a);
    }

    @Test
    void aRequestNeverInterruptsADefaultSongAndPlaysNext() {
        String a = videoGuardado();
        String b = videoGuardado();
        Local local = localConLista("SECUENCIAL", a, b);
        UUID sesion = sesionDe(local);
        UUID primeraDefecto = UUID.fromString(json(siguiente(local.token())).get("reproduccionId").asString());

        UUID solicitud = solicitada(unirse(local), videoGuardado());

        assertThat(videoSonando(sesion)).isEqualTo(a);
        assertProblema(siguiente(local.token()), 409, "PLAYBACK_IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, solicitud))
                .isEqualTo("PENDIENTE");

        assertThat(fin("COMPLETADA", local.token()).statusCode()).isEqualTo(200);
        JsonNode deSolicitud = json(siguiente(local.token()));
        assertThat(deSolicitud.get("origen").asString()).isEqualTo("SOLICITUD");
        assertThat(deSolicitud.get("solicitudId").asString()).isEqualTo(solicitud.toString());
        UUID reproduccionSolicitud = UUID.fromString(deSolicitud.get("reproduccionId").asString());
        assertThat(cambioDeOrigen(reproduccionSolicitud)).isEqualTo("DEFECTO|SOLICITUD|" + reproduccionSolicitud);

        fin("COMPLETADA", local.token());
        JsonNode deVuelta = json(siguiente(local.token()));
        assertThat(deVuelta.get("origen").asString()).isEqualTo("DEFECTO");
        assertThat(deVuelta.get("cancion").get("videoId").asString()).isEqualTo(b);
        UUID reproduccionDefecto = UUID.fromString(deVuelta.get("reproduccionId").asString());
        assertThat(cambioDeOrigen(reproduccionDefecto)).isEqualTo("SOLICITUD|DEFECTO|" + reproduccionDefecto);
        assertThat(eventos(sesion, "playback.source_changed")).isEqualTo(3);
        assertThat(cambioDeOrigen(primeraDefecto)).isEqualTo("null|DEFECTO|" + primeraDefecto);
    }

    @Test
    void twoDefaultSongsInARowDoNotChangeTheSource() {
        Local local = localConLista("SECUENCIAL", videoGuardado(), videoGuardado());
        UUID sesion = sesionDe(local);

        reproducirVarias(local, 2);

        assertThat(eventos(sesion, "playback.source_changed")).isEqualTo(1);
        assertThat(eventos(sesion, "playback.started")).isEqualTo(2);
    }

    @Test
    void finishingADefaultSongNeedsNoRequest() {
        Local local = localConLista("SECUENCIAL", videoGuardado());
        UUID sesion = sesionDe(local);
        siguiente(local.token());

        HttpResponse<String> response = fin("COMPLETADA", local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("solicitudId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT resultado FROM operacion.reproduccion WHERE sesion_id = ?", String.class,
                sesion)).isEqualTo("COMPLETADA");
        assertThat(json(colaPublica(local.slug())).get("enReproduccion").isNull()).isTrue();
    }

    @Test
    void closingTheSessionInterruptsADefaultSong() {
        Local local = localConLista("SECUENCIAL", videoGuardado());
        UUID sesion = sesionDe(local);
        siguiente(local.token());

        cerrarSesion(sesion);

        assertThat(jdbc.queryForObject("SELECT resultado || '|' || origen FROM operacion.reproduccion WHERE sesion_id = ?",
                String.class, sesion)).isEqualTo("INTERRUMPIDA_CIERRE|DEFECTO");
    }

    @Test
    void twoParallelStartsFromTheDefaultListPlayOnlyOneSong() throws Exception {
        Local local = localConLista("SECUENCIAL", videoGuardado(), videoGuardado());
        UUID sesion = sesionDe(local);
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> respuestas;
        try {
            List<Future<HttpResponse<String>>> futuras = List.of(
                    pool.submit(() -> {
                        salida.await();
                        return siguiente(local.token());
                    }),
                    pool.submit(() -> {
                        salida.await();
                        return siguiente(local.token());
                    }));
            salida.countDown();
            respuestas = List.of(futuras.get(0).get(), futuras.get(1).get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(respuestas).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(contar("SELECT count(*) FROM operacion.reproduccion WHERE sesion_id = ?", sesion)).isEqualTo(1);
    }

    /** Plays and completes {@code veces} songs, returning their video ids in order. */
    private List<String> reproducirVarias(Local local, int veces) {
        List<String> videos = new ArrayList<>();
        for (int i = 0; i < veces; i++) {
            HttpResponse<String> response = siguiente(local.token());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            videos.add(json(response).get("cancion").get("videoId").asString());
            assertThat(fin("COMPLETADA", local.token()).statusCode()).isEqualTo(200);
        }
        return videos;
    }

    private String cambioDeOrigen(UUID reproduccionId) {
        return jdbc.queryForObject("""
                SELECT coalesce(payload ->> 'from_source', 'null') || '|' || (payload ->> 'to_source') || '|'
                       || (payload ->> 'reproduction_id')
                FROM integracion.evento_outbox WHERE event_type = 'playback.source_changed' AND agregado_id = ?
                """, String.class, reproduccionId);
    }
}
