package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.sesion.SesionService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The participant credential: what it opens, what it never opens, and how it dies (leaving, expiring, or
 * the establishment closing its session).
 */
class AccesoParticipanteApiTest extends ParticipanteApiTestSupport {

    @Autowired
    private SesionService sesiones;

    @Autowired
    private PlatformTransactionManager transacciones;

    // ---------- a valid credential ----------

    @Test
    void aParticipantSeesItsSession() {
        Local local = localAbierto();
        HttpResponse<String> join = unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false));
        String token = json(join).get("token").asString();

        HttpResponse<String> response = get(MI_SESION, token);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("establecimiento").get("nombre").asString()).isEqualTo("Local de prueba");
        assertThat(body.get("servicio").asString()).isEqualTo("ABIERTO");
        assertThat(body.get("seudonimo").asString()).isEqualTo(json(join).get("seudonimo").asString());
        assertThat(response.body()).doesNotContain(local.id().toString());
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void aPausedSessionKeepsTheParticipantInsideAndSaysSo() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        String token = unirse(local);
        jdbc.update("UPDATE operacion.sesion SET estado = 'PAUSADA' WHERE id = ?", sesionId);

        HttpResponse<String> response = get(MI_SESION, token);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("servicio").asString()).isEqualTo("PAUSADO");
    }

    // ---------- invalid credentials ----------

    @Test
    void missingMalformedAndUnknownCredentialsAreRejected() {
        assertCredencialRechazada(get(MI_SESION, null), "INVALID_CREDENTIAL");
        assertCredencialRechazada(get(MI_SESION, "garbage"), "INVALID_CREDENTIAL");
        assertCredencialRechazada(get(MI_SESION, "pt_" + "A".repeat(43)), "INVALID_CREDENTIAL");
        assertCredencialRechazada(getConAutorizacion(MI_SESION, "Basic dXNlcjpwYXNz"), "INVALID_CREDENTIAL");
        assertCredencialRechazada(getConAutorizacion(MI_SESION, "Bearer"), "INVALID_CREDENTIAL");
    }

    @Test
    void aStaffTokenDoesNotOpenParticipantEndpoints() {
        assertCredencialRechazada(get(MI_SESION, propietarioToken()), "INVALID_CREDENTIAL");
        assertCredencialRechazada(get(MI_SESION, tokenSinEstablecimiento()), "INVALID_CREDENTIAL");
    }

    @Test
    void aParticipantTokenDoesNotOpenStaffOrAdminEndpoints() {
        Local local = localAbierto();
        String token = unirse(local);

        assertThat(get("/api/mi-establecimiento/sesion-actual", token).statusCode()).isEqualTo(401);
        assertThat(get("/api/mi-establecimiento/qr", token).statusCode()).isEqualTo(401);
        assertThat(post(CIERRE, token).statusCode()).isEqualTo(401);
    }

    @Test
    void anExpiredCredentialIsRejected() {
        Local local = localAbierto();
        String token = unirse(local);
        jdbc.update("UPDATE operacion.sesion_acceso SET emitida_at = now() - interval '2 days', "
                + "expira_at = now() - interval '1 second' WHERE revocada_at IS NULL AND dispositivo_id IN "
                + "(SELECT p.dispositivo_id FROM operacion.participacion p JOIN operacion.sesion s ON s.id = p.sesion_id "
                + "WHERE s.establecimiento_id = ?)", local.id());

        assertCredencialRechazada(get(MI_SESION, token), "INVALID_CREDENTIAL");
    }

    @Test
    void anExpiredDeviceIsRejected() {
        Local local = localAbierto();
        String token = unirse(local);
        jdbc.update("UPDATE operacion.dispositivo SET expira_at = now() - interval '1 second' WHERE id IN "
                + "(SELECT p.dispositivo_id FROM operacion.participacion p JOIN operacion.sesion s ON s.id = p.sesion_id "
                + "WHERE s.establecimiento_id = ?)", local.id());

        assertCredencialRechazada(get(MI_SESION, token), "INVALID_CREDENTIAL");
    }

    // ---------- leaving ----------

    @Test
    void leavingRevokesTheCredentialButKeepsTheDevice() {
        Local local = localAbierto();
        String token = unirse(local);
        String otro = unirse(local);

        HttpResponse<String> leave = delete(MI_SESION, token);

        assertThat(leave.statusCode()).as(leave.body()).isEqualTo(204);
        assertCredencialRechazada(get(MI_SESION, token), "INVALID_CREDENTIAL");
        assertCredencialRechazada(delete(MI_SESION, token), "INVALID_CREDENTIAL");
        assertThat(get(MI_SESION, otro).statusCode()).isEqualTo(200);
        // The device survives leaving, so the same phone gets its participation back if it returns.
        assertThat(contar("SELECT count(*) FROM operacion.dispositivo d JOIN operacion.participacion p "
                + "ON p.dispositivo_id = d.id JOIN operacion.sesion s ON s.id = p.sesion_id "
                + "WHERE s.establecimiento_id = ? AND d.revocado_at IS NOT NULL", local.id())).isZero();
    }

    // ---------- the session ends ----------

    @Test
    void closingTheSessionEndsEveryParticipantOfThatSessionOnly() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        String primero = unirse(local);
        String segundo = unirse(local);
        Local vecino = localAbierto();
        String delVecino = unirse(vecino);

        cerrarSesion(sesionId);

        assertCredencialRechazada(get(MI_SESION, primero), "SESSION_ENDED");
        assertCredencialRechazada(get(MI_SESION, segundo), "SESSION_ENDED");
        assertCredencialRechazada(delete(MI_SESION, segundo), "SESSION_ENDED");
        assertThat(get(MI_SESION, delVecino).statusCode()).isEqualTo(200);
        assertThat(contar("SELECT count(*) FROM operacion.sesion_acceso a JOIN operacion.participacion p "
                + "ON p.dispositivo_id = a.dispositivo_id WHERE p.sesion_id = ? AND a.revocada_at IS NULL", sesionId))
                .isZero();
        // Devices survive the close: only the credentials end, so the phones are recognized next night.
        assertThat(contar("SELECT count(*) FROM operacion.dispositivo d JOIN operacion.participacion p "
                + "ON p.dispositivo_id = d.id WHERE p.sesion_id = ? AND d.revocado_at IS NULL", sesionId)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM operacion.sesion_acceso a JOIN operacion.participacion p "
                + "ON p.dispositivo_id = a.dispositivo_id JOIN operacion.sesion s ON s.id = p.sesion_id "
                + "WHERE s.establecimiento_id = ? AND a.revocada_at IS NULL", vecino.id())).isEqualTo(1);
    }

    @Test
    void aNewSessionDoesNotReviveCredentialsOfThePreviousOne() {
        Local local = localListoParaAbrir();
        UUID primera = abrirSesion(local);
        String token = unirse(local);
        cerrarSesion(primera);

        abrirSesion(local);

        assertCredencialRechazada(get(MI_SESION, token), "SESSION_ENDED");
        String nuevo = unirse(local);
        assertThat(get(MI_SESION, nuevo).statusCode()).isEqualTo(200);
    }

    @Test
    void joinsRacingACloseNeverFailAndNeverOutliveTheSession() throws Exception {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        int joins = 12;
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(joins + 1);
        try {
            List<Future<HttpResponse<String>>> uniones = new ArrayList<>();
            for (int i = 0; i < joins; i++) {
                uniones.add(pool.submit(enCuantoSalga(salida,
                        () -> unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)))));
            }
            Future<HttpResponse<String>> cierre = pool.submit(enCuantoSalga(salida,
                    () -> post(CIERRE, local.token())));
            salida.countDown();

            assertThat(cierre.get().statusCode()).as(cierre.get().body()).isEqualTo(200);
            for (Future<HttpResponse<String>> union : uniones) {
                HttpResponse<String> response = union.get();
                assertThat(response.statusCode()).as(response.body()).isIn(201, 409);
                if (response.statusCode() == 201) {
                    assertCredencialRechazada(get(MI_SESION, json(response).get("token").asString()), "SESSION_ENDED");
                } else {
                    assertThat(json(response).get("code").asString()).isEqualTo("NO_ACTIVE_SESSION");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(contar("SELECT count(*) FROM operacion.sesion_acceso a JOIN operacion.participacion p "
                + "ON p.dispositivo_id = a.dispositivo_id WHERE p.sesion_id = ? AND a.revocada_at IS NULL", sesionId))
                .isZero();
    }

    @Test
    void aCloseWaitsForAJoinThatHoldsTheSessionLock() throws Exception {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        CountDownLatch bloqueada = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> union = pool.submit(() -> new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                assertThat(sesiones.bloquearEnOperacionParaUnirse(local.id())).isPresent();
                bloqueada.countDown();
                try {
                    soltar.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            bloqueada.await();
            Future<HttpResponse<String>> cierre = pool.submit(
                    () -> post(CIERRE, local.token()));

            Thread.sleep(700);
            assertThat(cierre.isDone()).as("the close must wait for the join holding the shared lock").isFalse();

            soltar.countDown();
            union.get();
            assertThat(cierre.get().statusCode()).isEqualTo(200);
        } finally {
            soltar.countDown();
            pool.shutdownNow();
        }
    }

    private static <T> Callable<T> enCuantoSalga(CountDownLatch salida, Callable<T> accion) {
        return () -> {
            salida.await();
            return accion.call();
        };
    }
}
