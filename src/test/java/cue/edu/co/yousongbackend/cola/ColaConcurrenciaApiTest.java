package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Queue actions arriving at the same moment. Every queue change of a session takes the session row lock (the
 * {@code cola_version} bump) first, so they apply one after another: no lost vote, no lost version, and a vote
 * never lands on a request that a concurrent playback change just finished.
 */
class ColaConcurrenciaApiTest extends ColaApiTestSupport {

    private static <T> Callable<T> enCuantoSalga(CountDownLatch salida, Callable<T> accion) {
        return () -> {
            salida.await();
            return accion.call();
        };
    }

    private static List<HttpResponse<String>> todas(List<Future<HttpResponse<String>>> futuras) throws Exception {
        List<HttpResponse<String>> respuestas = new ArrayList<>();
        for (Future<HttpResponse<String>> futura : futuras) {
            respuestas.add(futura.get());
        }
        return respuestas;
    }

    @Test
    void parallelVotesOfDifferentParticipantsAreAllCounted() throws Exception {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        int participantes = 12;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < participantes; i++) {
            tokens.add(unirse(local));
        }
        long antes = colaVersion(sesion);
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(participantes);
        try {
            List<Future<HttpResponse<String>>> votos = new ArrayList<>();
            for (String token : tokens) {
                votos.add(pool.submit(enCuantoSalga(salida, () -> votar(token, solicitud))));
            }
            salida.countDown();

            for (HttpResponse<String> response : todas(votos)) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(votos(solicitud)).isEqualTo(participantes + 1);
        assertThat(colaVersion(sesion)).isEqualTo(antes + participantes);
        assertThat(eventos(sesion, "vote.accepted")).isEqualTo(participantes + 1);
        assertThat(json(colaPublica(local.slug())).get("cola").get(0).get("votos").asInt()).isEqualTo(participantes + 1);
    }

    @Test
    void theSameParticipantVotingTwiceAtOnceGetsOneVoteAndOneRejection() throws Exception {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        String token = unirse(local);
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> respuestas;
        try {
            List<Future<HttpResponse<String>>> votos = List.of(
                    pool.submit(enCuantoSalga(salida, () -> votar(token, solicitud))),
                    pool.submit(enCuantoSalga(salida, () -> votar(token, solicitud))));
            salida.countDown();
            respuestas = todas(votos);
        } finally {
            pool.shutdownNow();
        }

        assertThat(respuestas).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        HttpResponse<String> rechazo = respuestas.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
        assertThat(json(rechazo).get("code").asString()).isEqualTo("ALREADY_VOTED");
        assertThat(votos(solicitud)).isEqualTo(2);
    }

    @Test
    void twoParticipantsRequestingTheSameNewSongAtOnceShareOneRequest() throws Exception {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String video = videoGuardado();
        int participantes = 6;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < participantes; i++) {
            tokens.add(unirse(local));
        }
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(participantes);
        List<HttpResponse<String>> respuestas;
        try {
            List<Future<HttpResponse<String>>> futuras = new ArrayList<>();
            for (String token : tokens) {
                futuras.add(pool.submit(enCuantoSalga(salida, () -> solicitar(token, video))));
            }
            salida.countDown();
            respuestas = todas(futuras);
        } finally {
            pool.shutdownNow();
        }

        assertThat(respuestas).extracting(HttpResponse::statusCode).containsOnly(200, 201);
        assertThat(respuestas.stream().filter(r -> r.statusCode() == 201).count()).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ?", sesion)).isEqualTo(1);
        UUID solicitud = jdbc.queryForObject("SELECT id FROM operacion.solicitud WHERE sesion_id = ?", UUID.class, sesion);
        assertThat(votos(solicitud)).isEqualTo(participantes);
        assertThat(contar("SELECT count(*) FROM operacion.voto WHERE solicitud_id = ? AND es_inicial", solicitud))
                .isEqualTo(1);
        assertThat(eventos(sesion, "request.created")).isEqualTo(1);
    }

    @Test
    void votesRacingAPlaybackStartAndFinishNeverLandOnAFinishedRequest() throws Exception {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        int participantes = 10;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < participantes; i++) {
            tokens.add(unirse(local));
        }
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(participantes + 1);
        List<HttpResponse<String>> respuestas;
        try {
            List<Future<HttpResponse<String>>> futuras = new ArrayList<>();
            Future<Integer> driver = pool.submit(() -> {
                salida.await();
                int inicio = siguiente(sesion, operatorToken()).statusCode();
                int cierre = fin(sesion, "COMPLETADA", operatorToken()).statusCode();
                return inicio * 1000 + cierre;
            });
            for (String token : tokens) {
                futuras.add(pool.submit(enCuantoSalga(salida, () -> votar(token, solicitud))));
            }
            salida.countDown();
            respuestas = todas(futuras);
            assertThat(driver.get()).isEqualTo(200_200);
        } finally {
            pool.shutdownNow();
        }

        for (HttpResponse<String> response : respuestas) {
            assertThat(response.statusCode()).as(response.body()).isIn(200, 409);
            if (response.statusCode() == 409) {
                assertThat(json(response).get("code").asString()).isEqualTo("REQUEST_NOT_ACTIVE");
            }
        }
        long aceptados = respuestas.stream().filter(r -> r.statusCode() == 200).count();
        assertThat(votos(solicitud)).isEqualTo(1 + (int) aceptados);
        assertThat(contar("SELECT count(*) FROM operacion.voto v JOIN operacion.solicitud s ON s.id = v.solicitud_id "
                + "WHERE s.id = ? AND v.creado_at > s.finalizada_at", solicitud)).isZero();
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, solicitud))
                .isEqualTo("ATENDIDA");
    }
}
