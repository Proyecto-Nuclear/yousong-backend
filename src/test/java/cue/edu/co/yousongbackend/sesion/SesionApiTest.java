package cue.edu.co.yousongbackend.sesion;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Opening, closing and reading music sessions through the HTTP API, with real security and PostgreSQL. */
class SesionApiTest extends ApiIntegrationTest {

    private static final String ABRIR = "/api/mi-establecimiento/sesiones";
    private static final String CERRAR = "/api/mi-establecimiento/sesion/cierre";
    private static final String ACTUAL = "/api/mi-establecimiento/sesion-actual";

    private String abrirSesion(Local local) {
        HttpResponse<String> response = post(ABRIR, local.token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    // ---------- open ----------

    @Test
    void theOwnerOpensASessionForTheirEstablishment() {
        Local local = localListoParaAbrir();

        HttpResponse<String> response = post(ABRIR, local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.get("estado").asString()).isEqualTo("ABIERTA");
        assertThat(body.get("establecimientoId").asString()).isEqualTo(local.id().toString());
        assertThat(body.get("cerradaAt").isNull()).isTrue();

        String sesionId = body.get("id").asString();
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?::uuid", String.class, sesionId))
                .isEqualTo("ABIERTA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.sesion WHERE establecimiento_id = ?",
                Integer.class, local.id())).isEqualTo(1);
    }

    @Test
    void theOperatingDateIsTheLocalDateOfTheEstablishment() {
        Local local = establecimiento("Pacific/Kiritimati", true); // UTC+14: often a different day than UTC
        listaDefecto(local.id());

        JsonNode body = json(post(ABRIR, local.token()));

        Instant openedAt = Instant.parse(body.get("abiertaAt").asString());
        assertThat(body.get("fechaOperativa").asString())
                .isEqualTo(FechaOperativa.de(openedAt, "Pacific/Kiritimati").toString());
    }

    @Test
    void theSessionKeepsTheLatestDefaultListOfTheEstablishment() {
        Local local = establecimiento();
        listaDefecto(local.id(), "Vieja", "2026-01-01T00:00:00Z");
        UUID newest = listaDefecto(local.id(), "Nueva", "2026-02-01T00:00:00Z");

        JsonNode body = json(post(ABRIR, local.token()));

        assertThat(body.get("listaId").asString()).isEqualTo(newest.toString());
    }

    @Test
    void openingRecordsTheSessionOpenedEventInTheSameTransaction() {
        Local local = localListoParaAbrir();
        String sesionId = abrirSesion(local);

        assertThat(jdbc.queryForObject("""
                SELECT ambito || '|' || agregado_tipo || '|' || agregado_version || '|' || jsonb_typeof(payload)
                FROM integracion.evento_outbox WHERE sesion_id = ?::uuid AND event_type = 'session.opened'
                """, String.class, sesionId)).isEqualTo("B|SESION|0|object");
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'session_id' FROM integracion.evento_outbox
                WHERE sesion_id = ?::uuid AND event_type = 'session.opened'
                """, String.class, sesionId)).isEqualTo(sesionId);
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'lista_version')::int FROM integracion.evento_outbox
                WHERE sesion_id = ?::uuid AND event_type = 'session.opened'
                """, Integer.class, sesionId)).isEqualTo(1);
    }

    @Test
    void aSecondOpenSessionIsRejected() {
        Local local = localListoParaAbrir();
        abrirSesion(local);

        HttpResponse<String> second = post(ABRIR, local.token());

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(json(second).get("code").asString()).isEqualTo("SESSION_ALREADY_OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.sesion WHERE establecimiento_id = ?",
                Integer.class, local.id())).isEqualTo(1);
    }

    @Test
    void aNewSessionCanOpenOnceTheCurrentOneIsClosed() {
        Local local = localListoParaAbrir();
        String first = abrirSesion(local);
        assertThat(post(CERRAR, local.token()).statusCode()).isEqualTo(200);

        String second = abrirSesion(local);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void concurrentOpensCreateExactlyOneSession() throws Exception {
        Local local = localListoParaAbrir();
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Callable<Integer> call = () -> {
                    ready.countDown();
                    go.await();
                    return post(ABRIR, local.token()).statusCode();
                };
                results.add(pool.submit(call));
            }
            ready.await();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(attempts - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.sesion WHERE establecimiento_id = ?",
                Integer.class, local.id())).isEqualTo(1);
    }

    @Test
    void anEstablishmentWithoutADefaultListCannotOpenASession() {
        Local local = establecimiento();

        HttpResponse<String> response = post(ABRIR, local.token());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("NO_DEFAULT_LIST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.sesion WHERE establecimiento_id = ?",
                Integer.class, local.id())).isZero();
    }

    @Test
    void anAccountWithoutAnEstablishmentCannotOpenASession() {
        HttpResponse<String> response = post(ABRIR, tokenSinEstablecimiento());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NO_ESTABLISHMENT");
    }

    @Test
    void anInactiveEstablishmentCannotOpenASession() {
        Local local = establecimiento("America/Bogota", false);
        listaDefecto(local.id());

        HttpResponse<String> response = post(ABRIR, local.token());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("ESTABLISHMENT_INACTIVE");
    }

    @Test
    void openingRequiresAToken() {
        Local local = localListoParaAbrir();

        assertThat(post(ABRIR, null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.sesion WHERE establecimiento_id = ?",
                Integer.class, local.id())).isZero();
    }

    // ---------- current session ----------

    @Test
    void theOwnerReadsTheCurrentSession() {
        Local local = localListoParaAbrir();
        String sesionId = abrirSesion(local);

        HttpResponse<String> response = get(ACTUAL, local.token());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("id").asString()).isEqualTo(sesionId);
        assertThat(json(response).get("estado").asString()).isEqualTo("ABIERTA");
    }

    @Test
    void thereIsNoCurrentSessionBeforeOpeningOrAfterClosing() {
        Local local = localListoParaAbrir();
        HttpResponse<String> none = get(ACTUAL, local.token());
        assertThat(none.statusCode()).isEqualTo(404);
        assertThat(json(none).get("code").asString()).isEqualTo("NO_ACTIVE_SESSION");

        String sesionId = abrirSesion(local);
        post(CERRAR, local.token());

        assertThat(get(ACTUAL, local.token()).statusCode()).isEqualTo(404);
    }

    @Test
    void currentSessionRequiresAuthentication() {
        assertThat(get(ACTUAL, null).statusCode()).isEqualTo(401);
        HttpResponse<String> sinLocal = get(ACTUAL, tokenSinEstablecimiento());
        assertThat(sinLocal.statusCode()).isEqualTo(404);
        assertThat(json(sinLocal).get("code").asString()).isEqualTo("NO_ESTABLISHMENT");
    }

    // ---------- close ----------

    @Test
    void closingFinalizesWhatWasLeftOpenAndKeepsWhatWasAlreadyDone() {
        Local local = localListoParaAbrir();
        String sesionId = abrirSesion(local);
        UUID sesion = UUID.fromString(sesionId);
        UUID creador = participacion(sesion);
        UUID pendiente = solicitud(sesion, cancion(), creador, "PENDIENTE", null);
        UUID reservada = solicitud(sesion, cancion(), creador, "RESERVADA", null);
        UUID cancionSonando = cancion();
        UUID sonando = solicitud(sesion, cancionSonando, creador, "REPRODUCIENDO", null);
        UUID atendida = solicitud(sesion, cancion(), creador, "ATENDIDA", "2026-11-01T01:00:00Z");
        UUID estacion = estacionHabilitada(local.id(), sesion);
        UUID reproduccion = reproduccionEnCurso(sesion, cancionSonando, estacion, sonando);

        HttpResponse<String> response = post(CERRAR, local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("sesion").get("estado").asString()).isEqualTo("CERRADA");
        assertThat(body.get("sesion").get("cerradaAt").isNull()).isFalse();
        assertThat(body.get("pendientesAlCierre").asInt()).isEqualTo(3);

        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?", String.class, sesion))
                .isEqualTo("CERRADA");
        for (UUID s : List.of(pendiente, reservada, sonando)) {
            assertThat(jdbc.queryForObject("SELECT estado || '|' || (finalizada_at IS NOT NULL) FROM operacion.solicitud WHERE id = ?",
                    String.class, s)).isEqualTo("NO_ATENDIDA_CIERRE|true");
        }
        // Same instant as before closing (compared as an instant, not as text in the session time zone).
        assertThat(jdbc.queryForObject(
                "SELECT estado || '|' || (finalizada_at = '2026-11-01T01:00:00Z'::timestamptz) FROM operacion.solicitud WHERE id = ?",
                String.class, atendida)).isEqualTo("ATENDIDA|true");
        assertThat(jdbc.queryForObject("SELECT resultado || '|' || (terminada_at IS NOT NULL) FROM operacion.reproduccion WHERE id = ?",
                String.class, reproduccion)).isEqualTo("INTERRUMPIDA_CIERRE|true");
        assertThat(jdbc.queryForObject("SELECT habilitada FROM operacion.estacion WHERE id = ?", Boolean.class, estacion))
                .isFalse();
    }

    @Test
    void closingRecordsTheSessionClosedEventWithTheNumberOfPendingRequests() {
        Local local = localListoParaAbrir();
        String sesionId = abrirSesion(local);
        UUID sesion = UUID.fromString(sesionId);
        UUID creador = participacion(sesion);
        solicitud(sesion, cancion(), creador, "PENDIENTE", null);
        solicitud(sesion, cancion(), creador, "PENDIENTE", null);

        post(CERRAR, local.token());

        assertThat(jdbc.queryForObject("""
                SELECT ambito || '|' || (payload ->> 'pendientes_al_cierre') || '|' || (agregado_version >= 1)
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'session.closed'
                """, String.class, sesion)).isEqualTo("B|2|true");
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'closed_at' IS NOT NULL FROM integracion.evento_outbox
                WHERE sesion_id = ? AND event_type = 'session.closed'
                """, Boolean.class, sesion)).isTrue();
    }

    @Test
    void closingTwiceFindsNoSessionToClose() {
        Local local = localListoParaAbrir();
        String sesionId = abrirSesion(local);
        assertThat(post(CERRAR, local.token()).statusCode()).isEqualTo(200);

        HttpResponse<String> again = post(CERRAR, local.token());

        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("code").asString()).isEqualTo("NO_ACTIVE_SESSION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integracion.evento_outbox WHERE sesion_id = ?::uuid "
                + "AND event_type = 'session.closed'", Integer.class, sesionId)).isEqualTo(1);
    }

    @Test
    void closingWithoutASessionInOperationIsNotFound() {
        HttpResponse<String> response = post(CERRAR, localListoParaAbrir().token());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NO_ACTIVE_SESSION");

        HttpResponse<String> sinLocal = post(CERRAR, tokenSinEstablecimiento());
        assertThat(sinLocal.statusCode()).isEqualTo(404);
        assertThat(json(sinLocal).get("code").asString()).isEqualTo("NO_ESTABLISHMENT");
    }

    @Test
    void closingRequiresAToken() {
        Local local = localListoParaAbrir();
        String sesionId = abrirSesion(local);

        assertThat(post(CERRAR, null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?::uuid", String.class, sesionId))
                .isEqualTo("ABIERTA");
    }

    @Test
    void closingASessionNeverTouchesAnotherEstablishment() {
        Local mine = localListoParaAbrir();
        Local other = localListoParaAbrir();
        String mineId = abrirSesion(mine);
        String otherId = abrirSesion(other);
        UUID otherSolicitud = solicitud(UUID.fromString(otherId), cancion(), participacion(UUID.fromString(otherId)),
                "PENDIENTE", null);

        post(CERRAR, mine.token());

        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?::uuid", String.class, otherId))
                .isEqualTo("ABIERTA");
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, otherSolicitud))
                .isEqualTo("PENDIENTE");
    }
}
