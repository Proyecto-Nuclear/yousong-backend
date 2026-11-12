package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cue.edu.co.yousongbackend.cola.ClienteSse.Evento;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The public queue as a stream of Server-Sent Events: a snapshot on connect, a new snapshot after every committed
 * queue change (and only then), keep-alive comments, and the stream survives the session close.
 */
@TestPropertySource(properties = {"app.cola.sse-heartbeat-millis=200", "app.cola.sse-max-per-address=1000",
        "app.cola.sse-max-per-establishment=1000"})
class ColaEventosApiTest extends ColaApiTestSupport {

    private static final Duration SILENCIO = Duration.ofMillis(1200);

    @Autowired
    private SuscripcionesCola suscripciones;

    private static String eventos(String slug) {
        return "/api/public/establecimientos/" + slug + "/cola/eventos";
    }

    private ClienteSse abrir(Local local) {
        ClienteSse cliente = ClienteSse.abrir(port, eventos(local.slug()));
        assertThat(cliente.status()).isEqualTo(200);
        return cliente;
    }

    @Test
    void onConnectTheCurrentSnapshotArrivesAsAColaEventWithTheVersionAsId() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());

        try (ClienteSse cliente = abrir(local)) {
            assertThat(cliente.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/event-stream"));
            assertThat(cliente.headers().firstValue("Cache-Control")).hasValueSatisfying(
                    value -> assertThat(value).contains("no-cache"));
            Evento inicial = cliente.siguiente();

            assertThat(inicial.nombre()).isEqualTo("cola");
            assertThat(inicial.id()).isEqualTo(String.valueOf(colaVersion(sesion)));
            assertThat(inicial.json()).isEqualTo(json(colaPublica(local.slug())));
            assertThat(inicial.json().get("cola")).hasSize(1);
        }
    }

    @Test
    void aVoteSendsTheNewSnapshotWithTheNewVersionAndVotes() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());

        try (ClienteSse cliente = abrir(local)) {
            long antes = cliente.siguiente().colaVersion();
            assertThat(votar(unirse(local), solicitud).statusCode()).isEqualTo(200);

            Evento evento = cliente.siguiente();

            assertThat(evento.colaVersion()).isEqualTo(antes + 1).isEqualTo(colaVersion(sesion));
            assertThat(evento.id()).isEqualTo(String.valueOf(antes + 1));
            assertThat(evento.json().get("cola").get(0).get("votos").asInt()).isEqualTo(2);
        }
    }

    @Test
    void aRequestAndAPlaybackStartEachSendASnapshot() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);

        try (ClienteSse cliente = abrir(local)) {
            assertThat(cliente.siguiente().json().get("cola")).isEmpty();
            UUID solicitud = solicitada(unirse(local), videoGuardado());

            Evento pedida = cliente.hastaVersion(colaVersion(sesion));
            assertThat(pedida.json().get("cola").get(0).get("solicitudId").asString()).isEqualTo(solicitud.toString());

            assertThat(siguiente(local.token()).statusCode()).isEqualTo(200);
            Evento sonando = cliente.hastaVersion(colaVersion(sesion));
            assertThat(sonando.json().get("enReproduccion").get("solicitudId").asString()).isEqualTo(solicitud.toString());
            assertThat(sonando.json().get("cola")).isEmpty();
        }
    }

    @Test
    void eventsCarryNoIdentities() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String token = unirse(local);
        UUID solicitud = solicitada(token, videoGuardado());

        try (ClienteSse cliente = abrir(local)) {
            cliente.siguiente();
            votar(unirse(local), solicitud);
            Evento evento = cliente.siguiente();

            List<String> identidades = new ArrayList<>(List.of(token));
            jdbc.queryForList("SELECT id::text, seudonimo FROM operacion.participacion WHERE sesion_id = ?", sesion)
                    .forEach(fila -> fila.values().forEach(valor -> identidades.add(String.valueOf(valor))));
            jdbc.queryForList("SELECT dispositivo_id::text FROM operacion.participacion WHERE sesion_id = ? "
                    + "AND dispositivo_id IS NOT NULL", String.class, sesion).forEach(identidades::add);
            assertThat(identidades).hasSizeGreaterThan(2);
            assertThat(identidades).allSatisfy(identidad -> assertThat(evento.datos()).doesNotContain(identidad));
            JsonNode item = evento.json().get("cola").get(0);
            assertThat(item.propertyNames()).containsExactlyInAnyOrder("solicitudId", "posicion", "votos", "cancion",
                    "esperaEstimadaSegundos");
        }
    }

    @Test
    void aRejectedActionThatRolledBackSendsNothing() {
        Local local = localAbierto();
        String token = unirse(local);
        UUID solicitud = solicitada(token, videoGuardado());

        try (ClienteSse cliente = abrir(local)) {
            cliente.siguiente();
            long difundidas = suscripciones.difusiones(local.id());

            assertProblema(votar(token, solicitud), 409, "ALREADY_VOTED");

            assertThat(cliente.durante(SILENCIO)).isEmpty();
            assertThat(suscripciones.difusiones(local.id())).isEqualTo(difundidas);
        }
    }

    @Test
    void theSessionCloseSendsAClosedEmptySnapshotAndTheStreamStaysOpenForTheNextSession() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());

        try (ClienteSse cliente = abrir(local)) {
            cliente.siguiente();
            cerrarSesion(sesion);

            JsonNode cerrada = cliente.siguiente().json();
            assertThat(cerrada.get("estado").asString()).isEqualTo("CERRADO");
            assertThat(cerrada.get("cola")).isEmpty();
            assertThat(cerrada.get("enReproduccion").isNull()).isTrue();

            abrirSesion(local);
            JsonNode abierta = cliente.siguiente().json();
            assertThat(abierta.get("estado").asString()).isEqualTo("ABIERTO");
            assertThat(suscripciones.suscriptores(local.id())).isEqualTo(1);
        }
    }

    @Test
    void keepAliveCommentsArriveWithoutAnyQueueChange() {
        Local local = localListoParaAbrir();

        try (ClienteSse cliente = abrir(local)) {
            assertThat(cliente.siguiente().json().get("estado").asString()).isEqualTo("CERRADO");

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(cliente.comentarios()).filteredOn(": keep-alive"::equals).hasSizeGreaterThanOrEqualTo(2));
            assertThat(cliente.durante(Duration.ofMillis(300))).isEmpty();
        }
    }

    @Test
    void anUnknownSlugIsA404ProblemBeforeAnyStream() {
        try (ClienteSse cliente = ClienteSse.abrir(port, eventos("no-existe-" + UUID.randomUUID()))) {
            assertThat(cliente.status()).isEqualTo(404);
            assertThat(cliente.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/problem+json"));
            assertThat(cliente.cuerpo()).contains("\"code\":\"ESTABLISHMENT_NOT_FOUND\"");
        }
    }

    @Test
    void aClientThatDisconnectsIsRemovedFromTheRegistry() {
        Local local = localListoParaAbrir();
        ClienteSse cliente = abrir(local);
        cliente.siguiente();
        assertThat(suscripciones.suscriptores(local.id())).isEqualTo(1);

        cliente.close();

        await().atMost(Duration.ofSeconds(10)).until(() -> suscripciones.suscriptores(local.id()) == 0);
    }

    @Test
    void twoEstablishmentsDoNotReceiveEachOthersEvents() {
        Local uno = localAbierto();
        Local otro = localAbierto();
        UUID solicitud = solicitada(unirse(uno), videoGuardado());

        try (ClienteSse deUno = abrir(uno); ClienteSse deOtro = abrir(otro)) {
            deUno.siguiente();
            deOtro.siguiente();
            long difundidasOtro = suscripciones.difusiones(otro.id());

            votar(unirse(uno), solicitud);

            assertThat(deUno.siguiente().json().get("cola").get(0).get("votos").asInt()).isEqualTo(2);
            assertThat(deOtro.durante(SILENCIO)).isEmpty();
            assertThat(suscripciones.difusiones(otro.id())).isEqualTo(difundidasOtro);
        }
    }

    @Test
    void manyConcurrentSubscribersAllReceiveEveryVersionInOrder() throws Exception {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        List<ClienteSse> clientes = new ArrayList<>();
        try (ExecutorService conexiones = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ClienteSse>> abiertos = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                abiertos.add(CompletableFuture.supplyAsync(() -> abrir(local), conexiones));
            }
            for (CompletableFuture<ClienteSse> abierto : abiertos) {
                clientes.add(abierto.get());
            }
        }
        try {
            long inicial = colaVersion(sesion);
            for (ClienteSse cliente : clientes) {
                assertThat(cliente.siguiente().colaVersion()).isEqualTo(inicial);
            }
            assertThat(suscripciones.suscriptores(local.id())).isEqualTo(50);

            for (int cambio = 1; cambio <= 3; cambio++) {
                votar(unirse(local), solicitud);
                long version = inicial + cambio;
                for (ClienteSse cliente : clientes) {
                    Evento evento = cliente.siguiente();
                    assertThat(evento.colaVersion()).isEqualTo(version);
                    assertThat(evento.json().get("cola").get(0).get("votos").asInt()).isEqualTo(1 + cambio);
                }
            }
        } finally {
            clientes.forEach(ClienteSse::close);
        }
    }

    @Test
    void aBrowserFromAnAllowedOriginCanSubscribe() {
        Local local = localListoParaAbrir();

        try (ClienteSse cliente = ClienteSse.abrir(port, eventos(local.slug()), Map.of("Origin", "http://localhost:3000"))) {
            assertThat(cliente.status()).isEqualTo(200);
            assertThat(cliente.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:3000");
            assertThat(cliente.headers().firstValue("Set-Cookie")).isEmpty();
            assertThat(cliente.siguiente().nombre()).isEqualTo("cola");
        }
    }

    @Test
    void aReconnectingClientWithLastEventIdGetsAFullSnapshot() {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());

        try (ClienteSse cliente = ClienteSse.abrir(port, eventos(local.slug()), Map.of("Last-Event-ID", "0"))) {
            assertThat(cliente.status()).isEqualTo(200);
            assertThat(cliente.siguiente().json().get("cola").get(0).get("solicitudId").asString())
                    .isEqualTo(solicitud.toString());
        }
    }
}
