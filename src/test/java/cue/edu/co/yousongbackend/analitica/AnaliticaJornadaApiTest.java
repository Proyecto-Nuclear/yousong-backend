package cue.edu.co.yousongbackend.analitica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/mi-establecimiento/analitica/jornada[?sesionId=]}: one session of the caller's establishment
 * computed live from {@code operacion.*}, so its charts move while the session is open. Fixtures are plain SQL
 * with chosen instants, states and results.
 */
class AnaliticaJornadaApiTest extends ColaApiTestSupport {

    private static final String RUTA = "/api/mi-establecimiento/analitica/jornada";
    private static final List<String> ESTADOS = List.of("PENDIENTE", "RESERVADA", "REPRODUCIENDO", "ATENDIDA",
            "SALTADA", "FALLIDA", "BLOQUEADA", "NO_ATENDIDA_CIERRE");

    // ---------- fixtures ----------

    /** A fixture establishment with its default list and playback station. */
    private record Bar(Local local, UUID lista, UUID estacion) {
    }

    private Bar bar(String zona) {
        Local local = establecimiento(zona, true);
        UUID lista = listaDefecto(local.id());
        UUID estacion = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.estacion (id, establecimiento_id, nombre) VALUES (?, ?, 'Cabina')",
                estacion, local.id());
        return new Bar(local, lista, estacion);
    }

    private Bar bar() {
        return bar("America/Bogota");
    }

    /** A session in the given state; CERRADA ones close at {@code cerrada}. */
    private UUID sesion(Bar bar, String estado, String fecha, Instant abierta, Instant cerrada) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.sesion (id, establecimiento_id, lista_id, fecha_operativa, estado, abierta_at, "
                        + "cerrada_at) VALUES (?, ?, ?, ?::date, ?, ?, ?)", id, bar.local().id(), bar.lista(), fecha, estado,
                Timestamp.from(abierta), cerrada == null ? null : Timestamp.from(cerrada));
        return id;
    }

    private UUID abierta(Bar bar) {
        return sesion(bar, "ABIERTA", "2026-11-21", Instant.now().minus(3, ChronoUnit.HOURS), null);
    }

    private UUID cerrada(Bar bar, String fecha, Instant cierre) {
        return sesion(bar, "CERRADA", fecha, cierre.minus(5, ChronoUnit.HOURS), cierre);
    }

    /** A participation whose device registered through {@code dispositivo} and whose join consent says {@code consentimiento}. */
    private UUID participante(UUID sesion, String dispositivo, String consentimiento) {
        UUID disp = null;
        if (dispositivo != null) {
            disp = UUID.randomUUID();
            jdbc.update("INSERT INTO operacion.dispositivo (id, canal, expira_at) VALUES (?, ?, now() + interval '1 day')",
                    disp, dispositivo);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.participacion (id, sesion_id, dispositivo_id, seudonimo) VALUES (?, ?, ?, ?)",
                id, sesion, disp, "p-" + id.toString().substring(0, 8));
        if (consentimiento != null) {
            jdbc.update("INSERT INTO operacion.consentimiento (id, participacion_id, politica_version, finalidad, accion, "
                    + "canal) VALUES (?, ?, '1.0', 'F1', 'AUTORIZA', ?)", UUID.randomUUID(), id, consentimiento);
        }
        return id;
    }

    private UUID participante(UUID sesion) {
        return participante(sesion, "WEB", "WEB");
    }

    private UUID cancionConGenero(String genero) {
        String video = videoIdAleatorio();
        UUID id = cancionGuardada(video, "Titulo " + video, "Canal", 200);
        jdbc.update("UPDATE operacion.cancion SET genero_propio = ? WHERE id = ?", genero, id);
        return id;
    }

    /** A request by {@code creador} created at {@code creada}, with its creator's initial vote at the same instant. */
    private UUID solicitud(UUID sesion, UUID cancion, UUID creador, String estado, Instant creada, Instant finalizada) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, estado, creada_at, "
                        + "finalizada_at) VALUES (?, ?, ?, ?, ?, ?, ?)", id, sesion, cancion, creador, estado,
                Timestamp.from(creada), finalizada == null ? null : Timestamp.from(finalizada));
        voto(id, sesion, cancion, creador, creada, true);
        return id;
    }

    private UUID solicitud(UUID sesion, String estado, Instant creada) {
        return solicitud(sesion, cancionConGenero("Salsa"), participante(sesion), estado, creada, null);
    }

    private void voto(UUID solicitud, UUID sesion, UUID cancion, UUID participacion, Instant creado, boolean inicial) {
        jdbc.update("INSERT INTO operacion.voto (id, solicitud_id, participacion_id, sesion_id, cancion_id, es_inicial, "
                        + "creado_at) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), solicitud, participacion, sesion,
                cancion, inicial, Timestamp.from(creado));
    }

    private void votoExtra(UUID solicitud, Instant creado) {
        Map<String, Object> fila = jdbc.queryForMap("SELECT sesion_id, cancion_id FROM operacion.solicitud WHERE id = ?",
                solicitud);
        UUID sesion = (UUID) fila.get("sesion_id");
        voto(solicitud, sesion, (UUID) fila.get("cancion_id"), participante(sesion), creado, false);
    }

    /**
     * A playback attempt of a request ({@code solicitud} not null) or of the default list. {@code iniciada} null
     * means it never reached PLAYING; {@code resultado} null means it is still open.
     */
    private void reproduccion(Bar bar, UUID sesion, UUID solicitud, Instant preparada, Instant iniciada,
                              String resultado) {
        UUID cancion;
        UUID item = null;
        if (solicitud == null) {
            cancion = cancionConGenero("Salsa");
            item = UUID.randomUUID();
            Integer posicion = jdbc.queryForObject("SELECT coalesce(max(posicion), 0) + 1 FROM operacion.item_lista "
                    + "WHERE lista_id = ?", Integer.class, bar.lista());
            jdbc.update("INSERT INTO operacion.item_lista (id, lista_id, cancion_id, posicion) VALUES (?, ?, ?, ?)",
                    item, bar.lista(), cancion, posicion);
        } else {
            cancion = jdbc.queryForObject("SELECT cancion_id FROM operacion.solicitud WHERE id = ?", UUID.class,
                    solicitud);
        }
        Instant terminada = resultado == null ? null : (iniciada == null ? preparada : iniciada).plusSeconds(60);
        jdbc.update("INSERT INTO operacion.reproduccion (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id, "
                        + "item_lista_id, preparada_at, iniciada_at, terminada_at, resultado) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), sesion, cancion, bar.estacion(),
                solicitud == null ? "DEFECTO" : "SOLICITUD", solicitud, item, Timestamp.from(preparada),
                iniciada == null ? null : Timestamp.from(iniciada), terminada == null ? null : Timestamp.from(terminada),
                resultado);
    }

    /** A request that started {@code espera} seconds after it was created and ended with the given results. */
    private UUID iniciada(Bar bar, UUID sesion, Instant creada, long espera, String estado, String resultado) {
        UUID id = solicitud(sesion, cancionConGenero("Salsa"), participante(sesion), estado, creada,
                creada.plusSeconds(espera + 60));
        reproduccion(bar, sesion, id, creada.plusSeconds(espera - 1), creada.plusSeconds(espera), resultado);
        return id;
    }

    private HttpResponse<String> jornada(String bearer, String sesionId) {
        return get(RUTA + (sesionId == null ? "" : "?sesionId=" + sesionId), bearer);
    }

    private JsonNode jornada(Bar bar, Object sesionId) {
        HttpResponse<String> response = jornada(bar.local().token(), sesionId == null ? null : sesionId.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static Set<String> campos(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }

    private static long n(JsonNode node, String campo) {
        return node.get(campo).asLong();
    }

    private static List<String> tramos(JsonNode espera) {
        List<String> tramos = new ArrayList<>();
        espera.get("tramos").forEach(t -> tramos.add(t.get("desdeMin").asInt() + "-"
                + (t.get("hastaMin").isNull() ? "null" : t.get("hastaMin").asInt()) + ":" + t.get("total").asLong()));
        return tramos;
    }

    private static void assertOrigen(JsonNode origen, String nombre, long completadas, long saltadas, long fallidas,
                                     long interrumpidas, long enCurso) {
        assertThat(campos(origen)).containsExactly("completadas", "enCurso", "fallidas", "interrumpidas", "origen",
                "saltadas");
        assertThat(origen.get("origen").asString()).isEqualTo(nombre);
        assertThat(List.of(n(origen, "completadas"), n(origen, "saltadas"), n(origen, "fallidas"),
                n(origen, "interrumpidas"), n(origen, "enCurso")))
                .containsExactly(completadas, saltadas, fallidas, interrumpidas, enCurso);
    }

    // ---------- tests ----------

    @Test
    void withoutAnySessionEverythingIsZeroAndBothOriginsArePresent() {
        Bar bar = bar();

        JsonNode body = jornada(bar, null);

        assertThat(campos(body)).containsExactly("canales", "corteAt", "embudo", "enVivo", "espera", "estados",
                "generosPorHora", "participacion", "porHora", "reproducciones", "sesion");
        assertThat(body.get("sesion").isNull()).isTrue();
        assertThat(body.get("enVivo").asBoolean()).isFalse();
        assertThat(Instant.parse(body.get("corteAt").asString())).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(n(body.get("participacion"), "ingresaron")).isZero();
        assertThat(n(body.get("participacion"), "activas")).isZero();
        assertThat(campos(body.get("canales"))).containsExactly("ANDROID", "WEB");
        assertThat(n(body.get("canales"), "WEB") + n(body.get("canales"), "ANDROID")).isZero();
        assertThat(body.get("porHora")).isEmpty();
        assertThat(campos(body.get("embudo"))).containsExactly("completadas", "creadas", "iniciadas");
        assertThat(n(body.get("embudo"), "creadas") + n(body.get("embudo"), "iniciadas")
                + n(body.get("embudo"), "completadas")).isZero();
        assertThat(new ArrayList<>(body.get("estados").propertyNames())).containsExactlyElementsOf(ESTADOS);
        ESTADOS.forEach(e -> assertThat(n(body.get("estados"), e)).isZero());
        JsonNode espera = body.get("espera");
        assertThat(campos(espera)).containsExactly("antiguedadMedianaPendientesS", "iniciadas", "medianaS", "p95S",
                "pendientes", "tramos");
        assertThat(espera.get("medianaS").isNull()).isTrue();
        assertThat(espera.get("p95S").isNull()).isTrue();
        assertThat(espera.get("antiguedadMedianaPendientesS").isNull()).isTrue();
        assertThat(n(espera, "iniciadas") + n(espera, "pendientes")).isZero();
        assertThat(espera.get("tramos")).isEmpty();
        assertThat(body.get("reproducciones")).hasSize(2);
        assertOrigen(body.get("reproducciones").get(0), "SOLICITUD", 0, 0, 0, 0, 0);
        assertOrigen(body.get("reproducciones").get(1), "DEFECTO", 0, 0, 0, 0, 0);
        assertThat(body.get("generosPorHora")).isEmpty();
    }

    @Test
    void everyRequestStateAndEveryPlaybackResultPerOriginAreCounted() {
        Bar bar = bar();
        UUID sesion = abierta(bar);
        Instant t = Instant.now().minus(2, ChronoUnit.HOURS);
        int[] cuantas = {2, 1, 1, 3, 1, 2, 1, 1};
        for (int i = 0; i < ESTADOS.size(); i++) {
            for (int k = 0; k < cuantas[i]; k++) {
                solicitud(sesion, ESTADOS.get(i), t);
            }
        }
        // request playbacks: completed, skipped after starting, failed before PLAYING (twice, retried), interrupted
        UUID una = solicitud(sesion, "ATENDIDA", t);
        reproduccion(bar, sesion, una, t, t.plusSeconds(10), "COMPLETADA");
        UUID otra = solicitud(sesion, "SALTADA", t);
        reproduccion(bar, sesion, otra, t, t.plusSeconds(10), "SALTADA");
        UUID falla = solicitud(sesion, "FALLIDA", t);
        reproduccion(bar, sesion, falla, t, null, "FALLIDA");
        reproduccion(bar, sesion, falla, t.plusSeconds(5), null, "FALLIDA");
        UUID corte = solicitud(sesion, "NO_ATENDIDA_CIERRE", t);
        reproduccion(bar, sesion, corte, t, t.plusSeconds(20), "INTERRUMPIDA_CIERRE");
        // default list: two completed, one skipped, one failed, one interrupted, and the one playing now
        reproduccion(bar, sesion, null, t, t.plusSeconds(1), "COMPLETADA");
        reproduccion(bar, sesion, null, t, t.plusSeconds(1), "COMPLETADA");
        reproduccion(bar, sesion, null, t, t.plusSeconds(1), "SALTADA");
        reproduccion(bar, sesion, null, t, null, "FALLIDA");
        reproduccion(bar, sesion, null, t, t.plusSeconds(1), "INTERRUMPIDA_CIERRE");
        reproduccion(bar, sesion, null, t, t.plusSeconds(1), null);

        JsonNode body = jornada(bar, null);

        JsonNode estados = body.get("estados");
        assertThat(new ArrayList<>(estados.propertyNames())).containsExactlyElementsOf(ESTADOS);
        assertThat(ESTADOS.stream().map(e -> n(estados, e)).toList())
                .containsExactly(2L, 1L, 1L, 4L, 2L, 3L, 1L, 2L);
        assertThat(body.get("reproducciones")).hasSize(2);
        assertOrigen(body.get("reproducciones").get(0), "SOLICITUD", 1, 1, 2, 1, 0);
        assertOrigen(body.get("reproducciones").get(1), "DEFECTO", 2, 1, 1, 1, 1);
    }

    @Test
    void aLiveSessionShowsItsStateFunnelParticipationAndChannels() {
        Bar bar = bar();
        UUID sesion = abierta(bar);
        Instant t = Instant.now().minus(90, ChronoUnit.MINUTES);
        // channels: the join consent wins over the device's first channel; device only when no consent;
        // a participation whose device was unlinked and has no consent is in no channel
        UUID web = participante(sesion, "WEB", "WEB");
        UUID android = participante(sesion, "WEB", "ANDROID");
        UUID soloDispositivo = participante(sesion, "ANDROID", null);
        participante(sesion, null, null);
        // web requested; android only voted; the device-only one did nothing
        UUID pedida = solicitud(sesion, cancionConGenero("Salsa"), web, "ATENDIDA", t, t.plusSeconds(400));
        voto(pedida, sesion, jdbc.queryForObject("SELECT cancion_id FROM operacion.solicitud WHERE id = ?", UUID.class,
                pedida), android, t.plusSeconds(30), false);
        reproduccion(bar, sesion, pedida, t.plusSeconds(100), t.plusSeconds(120), "COMPLETADA");
        // started but skipped: initiated, not completed
        iniciada(bar, sesion, t, 60, "SALTADA", "SALTADA");
        // never started
        solicitud(sesion, "PENDIENTE", t);

        JsonNode body = jornada(bar, null);

        JsonNode s = body.get("sesion");
        assertThat(campos(s)).containsExactly("estado", "fechaOperativa", "id");
        assertThat(s.get("id").asString()).isEqualTo(sesion.toString());
        assertThat(s.get("fechaOperativa").asString()).isEqualTo("2026-11-21");
        assertThat(s.get("estado").asString()).isEqualTo("ABIERTA");
        assertThat(body.get("enVivo").asBoolean()).isTrue();
        assertThat(campos(body.get("participacion"))).containsExactly("activas", "ingresaron");
        // the 4 above plus the creators of the skipped and of the pending request (both WEB)
        assertThat(n(body.get("participacion"), "ingresaron")).isEqualTo(6);
        assertThat(n(body.get("participacion"), "activas")).isEqualTo(4); // web, android and the two creators
        assertThat(n(body.get("canales"), "WEB")).isEqualTo(3);
        assertThat(n(body.get("canales"), "ANDROID")).isEqualTo(2);
        JsonNode embudo = body.get("embudo");
        assertThat(List.of(n(embudo, "creadas"), n(embudo, "iniciadas"), n(embudo, "completadas")))
                .containsExactly(3L, 2L, 1L);
    }

    @Test
    void waitingTimesUseOnlyStartedRequestsWithInterpolatedPercentilesAndFiveMinuteBins() {
        Bar bar = bar();
        Instant cierre = Instant.parse("2026-11-22T06:00:00Z");
        UUID sesion = cerrada(bar, "2026-11-21", cierre);
        Instant t = Instant.parse("2026-11-22T02:00:00Z");
        for (long espera : new long[] {60, 120, 300, 600, 1500}) {
            iniciada(bar, sesion, t, espera, "ATENDIDA", "COMPLETADA");
        }
        // the first start counts even after a failed attempt that never reached PLAYING
        UUID reintentada = solicitud(sesion, cancionConGenero("Salsa"), participante(sesion), "ATENDIDA", t,
                t.plusSeconds(900));
        reproduccion(bar, sesion, reintentada, t.plusSeconds(10), null, "FALLIDA");
        reproduccion(bar, sesion, reintentada, t.plusSeconds(200), t.plusSeconds(240), "COMPLETADA");
        // left waiting at the close: 20 and 30 minutes old when the session ended
        solicitud(sesion, cancionConGenero("Salsa"), participante(sesion), "NO_ATENDIDA_CIERRE",
                cierre.minus(20, ChronoUnit.MINUTES), cierre);
        solicitud(sesion, cancionConGenero("Salsa"), participante(sesion), "NO_ATENDIDA_CIERRE",
                cierre.minus(30, ChronoUnit.MINUTES), cierre);
        // never started for other reasons: neither started nor pending
        solicitud(sesion, "BLOQUEADA", t);

        JsonNode body = jornada(bar, sesion);

        assertThat(body.get("enVivo").asBoolean()).isFalse();
        assertThat(body.get("sesion").get("estado").asString()).isEqualTo("CERRADA");
        JsonNode espera = body.get("espera");
        // started waits 60, 120, 240, 300, 600, 1500: median (240+300)/2 = 270, p95 = 600 + 0.75 * 900 = 1275
        assertThat(n(espera, "iniciadas")).isEqualTo(6);
        assertThat(n(espera, "medianaS")).isEqualTo(270);
        assertThat(n(espera, "p95S")).isEqualTo(1275);
        assertThat(tramos(espera)).containsExactly("0-5:3", "5-10:1", "10-15:1", "15-20:0", "20-25:0", "25-null:1");
        assertThat(n(espera, "pendientes")).isEqualTo(2);
        assertThat(n(espera, "antiguedadMedianaPendientesS")).isEqualTo(1500);
        assertThat(n(body.get("embudo"), "iniciadas")).isEqualTo(6);
        assertThat(n(body.get("embudo"), "completadas")).isEqualTo(6);
    }

    @Test
    void aSingleStartedRequestIsItsOwnMedianAndPercentileAndLivePendingAgeRunsToNow() {
        Bar bar = bar();
        UUID sesion = abierta(bar);
        Instant ahora = Instant.now();
        iniciada(bar, sesion, ahora.minus(1, ChronoUnit.HOURS), 301, "ATENDIDA", "COMPLETADA");
        solicitud(sesion, "PENDIENTE", ahora.minus(10, ChronoUnit.MINUTES));
        UUID reservada = solicitud(sesion, "RESERVADA", ahora.minus(10, ChronoUnit.MINUTES));
        reproduccion(bar, sesion, reservada, ahora.minusSeconds(5), null, null); // prepared, not playing yet

        JsonNode espera = jornada(bar, null).get("espera");

        assertThat(n(espera, "medianaS")).isEqualTo(301);
        assertThat(n(espera, "p95S")).isEqualTo(301);
        assertThat(tramos(espera)).containsExactly("0-5:0", "5-10:1", "10-15:0", "15-20:0", "20-25:0", "25-null:0");
        assertThat(n(espera, "pendientes")).isEqualTo(2);
        assertThat(n(espera, "antiguedadMedianaPendientesS")).isCloseTo(600, within(30L));
    }

    @Test
    void activityPerHourFollowsTheNightAcrossMidnightInTheEstablishmentTimeZone() {
        Bar bar = bar("America/Bogota"); // UTC-5
        Instant cierre = Instant.parse("2026-11-22T08:00:00Z");
        UUID sesion = cerrada(bar, "2026-11-21", cierre);
        // 22:10, 22:50 and 00:05 Bogota; nothing at 23:00, which still shows with zeros
        UUID a = solicitud(sesion, "ATENDIDA", Instant.parse("2026-11-22T03:10:00Z"));
        solicitud(sesion, "ATENDIDA", Instant.parse("2026-11-22T03:50:00Z"));
        UUID c = solicitud(sesion, "ATENDIDA", Instant.parse("2026-11-22T05:05:00Z"));
        votoExtra(a, Instant.parse("2026-11-22T05:30:00Z")); // a vote at 00:30 for a request of 22:10
        votoExtra(c, Instant.parse("2026-11-22T06:59:00Z")); // 01:59

        JsonNode porHora = jornada(bar, sesion).get("porHora");

        List<String> horas = new ArrayList<>();
        porHora.forEach(h -> {
            assertThat(campos(h)).containsExactly("hora", "solicitudes", "votos");
            horas.add(h.get("hora").asInt() + ":" + n(h, "solicitudes") + "/" + n(h, "votos"));
        });
        assertThat(horas).containsExactly("22:2/2", "23:0/0", "0:1/2", "1:0/1");
    }

    @Test
    void hoursAreLocalToEachEstablishment() {
        Bar tokio = bar("Asia/Tokyo"); // UTC+9
        Instant cierre = Instant.parse("2026-11-21T16:00:00Z");
        UUID sesion = cerrada(tokio, "2026-11-21", cierre);
        for (int i = 0; i < 5; i++) {
            solicitud(sesion, cancionConGenero("J-Pop"), participante(sesion), "ATENDIDA",
                    Instant.parse("2026-11-21T12:30:00Z"), null);
        }

        JsonNode body = jornada(tokio, sesion);

        assertThat(body.get("porHora").get(0).get("hora").asInt()).isEqualTo(21);
        assertThat(body.get("generosPorHora").get(0).get("hora").asInt()).isEqualTo(21);
    }

    @Test
    void genreCellsShareOfTheHourSuppressedUnderFiveParticipants() {
        Bar bar = bar();
        Instant cierre = Instant.parse("2026-11-22T08:00:00Z");
        UUID sesion = cerrada(bar, "2026-11-21", cierre);
        Instant once = Instant.parse("2026-11-22T04:15:00Z"); // 23:15 Bogota
        Instant media = Instant.parse("2026-11-22T05:15:00Z"); // 00:15
        for (int i = 0; i < 6; i++) {
            solicitud(sesion, cancionConGenero("Reggaetón"), participante(sesion), "ATENDIDA", once, null);
        }
        UUID mismo = participante(sesion);
        for (int i = 0; i < 4; i++) {
            solicitud(sesion, cancionConGenero("Vallenato"), mismo, "ATENDIDA", once, null);
        }
        for (int i = 0; i < 5; i++) {
            solicitud(sesion, cancionConGenero(null), participante(sesion), "ATENDIDA", media, null);
        }

        JsonNode celdas = jornada(bar, sesion).get("generosPorHora");

        List<String> filas = new ArrayList<>();
        celdas.forEach(c -> {
            assertThat(campos(c)).containsExactly("genero", "hora", "porcentaje", "suprimida");
            filas.add(c.get("hora").asInt() + "|" + c.get("genero").asString() + "|"
                    + (c.get("porcentaje").isNull() ? "null" : c.get("porcentaje").asDouble()) + "|"
                    + c.get("suprimida").asBoolean());
        });
        assertThat(filas).containsExactly("23|Reggaetón|60.0|false", "23|Vallenato|null|true",
                "0|Sin género|100.0|false");
    }

    @Test
    void theSessionInOperationIsTheDefaultOtherwiseTheLatestClosedOne() {
        Bar bar = bar();
        UUID antigua = cerrada(bar, "2026-11-14", Instant.parse("2026-11-15T06:00:00Z"));
        UUID reciente = cerrada(bar, "2026-11-21", Instant.parse("2026-11-22T06:00:00Z"));

        assertThat(jornada(bar, null).get("sesion").get("id").asString()).isEqualTo(reciente.toString());
        assertThat(jornada(bar, antigua).get("sesion").get("id").asString()).isEqualTo(antigua.toString());

        for (String estado : List.of("PAUSADA", "CERRANDO", "ABIERTA")) {
            UUID enOperacion = sesion(bar, estado, "2026-11-25", Instant.now().minusSeconds(600), null);
            JsonNode body = jornada(bar, null);
            assertThat(body.get("sesion").get("id").asString()).isEqualTo(enOperacion.toString());
            assertThat(body.get("sesion").get("estado").asString()).isEqualTo(estado);
            assertThat(body.get("enVivo").asBoolean()).isTrue();
            jdbc.update("UPDATE operacion.sesion SET estado = 'CERRADA', cerrada_at = abierta_at WHERE id = ?",
                    enOperacion);
        }
    }

    @Test
    void anotherOwnersOrAnUnknownSessionIsNotFound() {
        Bar mio = bar();
        Bar ajeno = bar();
        UUID suya = cerrada(ajeno, "2026-11-21", Instant.parse("2026-11-22T06:00:00Z"));
        solicitud(suya, "ATENDIDA", Instant.parse("2026-11-22T03:00:00Z"));

        assertProblema(jornada(mio.local().token(), suya.toString()), 404, "SESSION_NOT_FOUND");
        assertProblema(jornada(mio.local().token(), UUID.randomUUID().toString()), 404, "SESSION_NOT_FOUND");
        assertProblema(jornada(mio.local().token(), "no-es-un-uuid"), 404, "SESSION_NOT_FOUND");
        assertThat(jornada(mio, null).get("sesion").isNull()).isTrue();
        assertThat(jornada(ajeno, null).get("embudo").get("creadas").asLong()).isEqualTo(1);
    }

    @Test
    void requiresAnOwnerTokenAndAnEstablishment() {
        assertThat(jornada((String) null, null).statusCode()).isEqualTo(401);
        assertProblema(jornada(tokenSinEstablecimiento(), null), 404, "NO_ESTABLISHMENT");
    }

    @Test
    void responsesCarryNoIdentityOfAnyParticipant() {
        Bar bar = bar();
        UUID sesion = abierta(bar);
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 5; i++) {
            UUID id = solicitud(sesion, "PENDIENTE", t);
            votoExtra(id, t);
        }

        String body = jornada(bar.local().token(), null).body();

        List<String> secretos = new ArrayList<>();
        jdbc.queryForList("SELECT id::text FROM operacion.participacion WHERE sesion_id = ? UNION ALL "
                + "SELECT seudonimo FROM operacion.participacion WHERE sesion_id = ? UNION ALL "
                + "SELECT id::text FROM operacion.solicitud WHERE sesion_id = ? UNION ALL "
                + "SELECT id::text FROM operacion.voto WHERE sesion_id = ? UNION ALL "
                + "SELECT dispositivo_id::text FROM operacion.participacion WHERE sesion_id = ? "
                + "AND dispositivo_id IS NOT NULL", String.class, sesion, sesion, sesion, sesion, sesion)
                .forEach(secretos::add);
        assertThat(secretos).hasSizeGreaterThan(20);
        secretos.forEach(secreto -> assertThat(body).doesNotContain(secreto));
        assertThat(body).doesNotContain("seudonimo").doesNotContain("dispositivo").doesNotContain("participante");
    }
}
