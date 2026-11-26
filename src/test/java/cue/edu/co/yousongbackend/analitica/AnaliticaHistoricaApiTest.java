package cue.edu.co.yousongbackend.analitica;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/mi-establecimiento/analitica?jornadas=8}: the published history of the caller's establishment,
 * read only from {@code analitica.*}. Fixtures are plain SQL written the way the pipeline publishes: analytic
 * dimensions whose ids copy the operational ones, facts tied to a published batch and one
 * {@code publicacion_sesion} row per published session.
 */
class AnaliticaHistoricaApiTest extends ColaApiTestSupport {

    private static final String RUTA = "/api/mi-establecimiento/analitica";

    // ---------- fixtures (the pipeline's output) ----------

    /** The analytic copy of a fresh owned establishment and a published batch to tie facts to. */
    private record Bodega(Local local, UUID lote) {
    }

    private Bodega bodega() {
        Local local = establecimiento();
        jdbc.update("INSERT INTO analitica.dim_establecimiento (id, nombre, zona_horaria) VALUES (?, 'Local', "
                + "'America/Bogota')", local.id());
        UUID lote = UUID.randomUUID();
        jdbc.update("INSERT INTO integracion.lote_analitico (id, rango_desde, rango_hasta, finalizado_at, estado) "
                + "VALUES (?, now() - interval '1 day', now(), now(), 'PUBLICADO')", lote);
        return new Bodega(local, lote);
    }

    /** A session of the establishment in the analytic model; published with the given version when not null. */
    private UUID jornada(Bodega b, String fecha, Long version, Instant publicada) {
        UUID id = UUID.randomUUID();
        Instant abierta = LocalDate.parse(fecha).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
                .plus(25, ChronoUnit.HOURS);
        jdbc.update("INSERT INTO analitica.dim_sesion (id, establecimiento_id, fecha_operativa, estado, abierta_at, "
                        + "cerrada_at) VALUES (?, ?, ?::date, 'CERRADA', ?, ?)", id, b.local().id(), fecha,
                Timestamp.from(abierta), Timestamp.from(abierta.plus(6, ChronoUnit.HOURS)));
        if (version != null) {
            jdbc.update("INSERT INTO analitica.publicacion_sesion (sesion_id, lote_id, version, publicada_at, "
                            + "corte_eventos_at) VALUES (?, ?, ?, ?, ?)", id, b.lote(), version, Timestamp.from(publicada),
                    Timestamp.from(publicada.minusSeconds(60)));
        }
        return id;
    }

    private UUID jornada(Bodega b, String fecha) {
        return jornada(b, fecha, 1L, Instant.parse(fecha + "T12:00:00Z"));
    }

    /** A participation of the session with the given channel; returns its analytic id. */
    private UUID participante(UUID sesion, String canal) {
        UUID id = UUID.randomUUID();
        byte[] ref = new byte[32];
        new java.security.SecureRandom().nextBytes(ref);
        jdbc.update("INSERT INTO analitica.dim_participacion (id, sesion_id, participante_ref, canal, ingreso_at) "
                + "VALUES (?, ?, ?, ?, now())", id, sesion, HexFormat.of().formatHex(ref), canal);
        return id;
    }

    /** The analytic classification of a fresh catalog song; returns {dim id, video id}. */
    private Object[] cancionAnalitica(Bodega b, String genero) {
        String video = videoIdAleatorio();
        UUID ref = cancionGuardada(video, "Titulo " + video, "Canal " + video, 200);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO analitica.dim_cancion (id, establecimiento_id, cancion_ref, genero_propio) "
                + "VALUES (?, ?, ?, ?)", id, b.local().id(), ref, genero);
        return new Object[] {id, video};
    }

    /** The local calendar slot of a fact; shared by every test, so inserted only once. */
    private int tiempo(String fechaLocal, int hora) {
        LocalDate fecha = LocalDate.parse(fechaLocal);
        int id = Integer.parseInt(fechaLocal.replace("-", "")) * 100 + hora;
        jdbc.update("INSERT INTO analitica.dim_tiempo (id, fecha_local, hora, dia_semana) VALUES (?, ?::date, ?, ?) "
                + "ON CONFLICT DO NOTHING", id, fechaLocal, hora, fecha.getDayOfWeek().getValue());
        return id;
    }

    private UUID solicitud(Bodega b, UUID sesion, Object[] cancion, UUID participacion, String fechaLocal, int hora) {
        UUID ref = UUID.randomUUID();
        jdbc.update("INSERT INTO analitica.hecho_solicitud (solicitud_ref, sesion_id, cancion_id, participacion_id, "
                        + "tiempo_id, creada_at, estado, lote_id) VALUES (?, ?, ?, ?, ?, now(), 'ATENDIDA', ?)", ref, sesion,
                cancion[0], participacion, tiempo(fechaLocal, hora), b.lote());
        return ref;
    }

    private void voto(Bodega b, UUID sesion, UUID solicitud, Object[] cancion, UUID participacion, boolean inicial) {
        jdbc.update("INSERT INTO analitica.hecho_voto (voto_ref, solicitud_ref, sesion_id, cancion_id, participacion_id, "
                        + "tiempo_id, creado_at, es_inicial, lote_id) VALUES (?, ?, ?, ?, ?, ?, now(), ?, ?)",
                UUID.randomUUID(), solicitud, sesion, cancion[0], participacion, tiempo("2026-11-21", 21), inicial,
                b.lote());
    }

    /** A request with its initial vote plus {@code extra} votes from new participants. */
    private void pedidaConVotos(Bodega b, UUID sesion, Object[] cancion, int extra) {
        UUID creador = participante(sesion, "WEB");
        UUID ref = solicitud(b, sesion, cancion, creador, "2026-11-21", 21);
        voto(b, sesion, ref, cancion, creador, true);
        for (int i = 0; i < extra; i++) {
            voto(b, sesion, ref, cancion, participante(sesion, "WEB"), false);
        }
    }

    private void reproduccion(Bodega b, UUID sesion, Object[] cancion, UUID solicitud, String resultado) {
        UUID lista = null;
        if (solicitud == null) {
            lista = UUID.randomUUID();
            jdbc.update("INSERT INTO analitica.dim_lista (id, establecimiento_id, nombre, version) VALUES (?, ?, 'G', 1)",
                    lista, b.local().id());
        }
        boolean inicio = !"FALLIDA".equals(resultado);
        jdbc.update("INSERT INTO analitica.hecho_reproduccion (reproduccion_ref, sesion_id, cancion_id, origen, "
                        + "solicitud_ref, lista_id, preparada_at, iniciada_at, terminada_at, resultado, lote_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), " + (inicio ? "now()" : "NULL") + ", now(), ?, ?)",
                UUID.randomUUID(), sesion, cancion[0], solicitud == null ? "DEFECTO" : "SOLICITUD", solicitud, lista,
                resultado, b.lote());
    }

    private HttpResponse<String> analitica(String bearer, String jornadas) {
        return get(RUTA + (jornadas == null ? "" : "?jornadas=" + jornadas), bearer);
    }

    private JsonNode analitica(Bodega b, String jornadas) {
        HttpResponse<String> response = analitica(b.local().token(), jornadas);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static Set<String> campos(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }

    private static List<String> sesiones(JsonNode lista) {
        List<String> ids = new ArrayList<>();
        lista.forEach(e -> ids.add(e.get("sesionId").asString()));
        return ids;
    }

    // ---------- tests ----------

    @Test
    void withoutPublishedSessionsEverythingIsEmptyAndBothChannelsAreZero() {
        Bodega b = bodega();
        jornada(b, "2026-11-20", null, null); // in the model but never published

        JsonNode body = analitica(b, null);

        assertThat(campos(body)).containsExactly("canales", "generosPorHora", "jornadas", "participacion",
                "publicacion", "ranking");
        assertThat(body.get("publicacion").isNull()).isTrue();
        assertThat(body.get("jornadas")).isEmpty();
        assertThat(body.get("participacion")).isEmpty();
        assertThat(body.get("ranking")).isEmpty();
        assertThat(body.get("generosPorHora")).isEmpty();
        assertThat(campos(body.get("canales"))).containsExactly("ANDROID", "WEB");
        assertThat(body.get("canales").get("WEB").asLong()).isZero();
        assertThat(body.get("canales").get("ANDROID").asLong()).isZero();
    }

    @Test
    void publishedSessionsComeOldestFirstWithParticipationChannelsAndTheNewestPublication() {
        Bodega b = bodega();
        UUID vieja = jornada(b, "2026-11-14", 3L, Instant.parse("2026-11-15T07:00:00Z"));
        UUID nueva = jornada(b, "2026-11-21", 12L, Instant.parse("2026-11-22T04:45:00Z"));
        UUID sinPublicar = jornada(b, "2026-11-22", null, null);
        Object[] salsa = cancionAnalitica(b, "Salsa");

        // vieja: 3 joined; one requested, one only voted, one did nothing -> 2 active
        UUID pide = participante(vieja, "WEB");
        UUID vota = participante(vieja, "ANDROID");
        participante(vieja, "WEB");
        UUID ref = solicitud(b, vieja, salsa, pide, "2026-11-14", 21);
        voto(b, vieja, ref, salsa, pide, true);
        voto(b, vieja, ref, salsa, vota, false);
        // nueva: 2 joined through Android, none active
        participante(nueva, "ANDROID");
        participante(nueva, "ANDROID");
        // unpublished session: never counted
        participante(sinPublicar, "WEB");

        JsonNode body = analitica(b, null);

        JsonNode publicacion = body.get("publicacion");
        assertThat(campos(publicacion)).containsExactly("corteEventosAt", "publicadaAt", "version");
        assertThat(publicacion.get("version").asLong()).isEqualTo(12);
        assertThat(Instant.parse(publicacion.get("publicadaAt").asString()))
                .isEqualTo(Instant.parse("2026-11-22T04:45:00Z"));
        assertThat(Instant.parse(publicacion.get("corteEventosAt").asString()))
                .isEqualTo(Instant.parse("2026-11-22T04:44:00Z"));

        assertThat(sesiones(body.get("jornadas"))).containsExactly(vieja.toString(), nueva.toString());
        JsonNode primera = body.get("jornadas").get(0);
        assertThat(campos(primera)).containsExactly("fechaOperativa", "sesionId");
        assertThat(primera.get("fechaOperativa").asString()).isEqualTo("2026-11-14");

        JsonNode participacion = body.get("participacion");
        assertThat(sesiones(participacion)).containsExactly(vieja.toString(), nueva.toString());
        assertThat(campos(participacion.get(0))).containsExactly("activas", "ingresaron", "sesionId");
        assertThat(participacion.get(0).get("ingresaron").asLong()).isEqualTo(3);
        assertThat(participacion.get(0).get("activas").asLong()).isEqualTo(2);
        assertThat(participacion.get(1).get("ingresaron").asLong()).isEqualTo(2);
        assertThat(participacion.get(1).get("activas").asLong()).isZero();

        assertThat(body.get("canales").get("WEB").asLong()).isEqualTo(2);
        assertThat(body.get("canales").get("ANDROID").asLong()).isEqualTo(3);
    }

    @Test
    void theLastEightPublishedSessionsByDefaultAndTheRequestedNumberOtherwise() {
        Bodega b = bodega();
        List<UUID> todas = new ArrayList<>();
        for (int dia = 1; dia <= 9; dia++) {
            UUID sesion = jornada(b, String.format("2026-10-%02d", dia));
            participante(sesion, "WEB");
            todas.add(sesion);
        }
        Object[] cancion = cancionAnalitica(b, "Salsa");
        pedidaConVotos(b, todas.get(0), cancion, 4); // only in the oldest, left out by default

        JsonNode ocho = analitica(b, null);
        assertThat(sesiones(ocho.get("jornadas"))).containsExactlyElementsOf(
                todas.subList(1, 9).stream().map(UUID::toString).toList());
        assertThat(ocho.get("canales").get("WEB").asLong()).isEqualTo(8);
        assertThat(ocho.get("ranking")).isEmpty();
        assertThat(ocho.get("generosPorHora")).hasSize(8);

        JsonNode dos = analitica(b, "2");
        assertThat(sesiones(dos.get("jornadas"))).containsExactly(todas.get(7).toString(), todas.get(8).toString());
        assertThat(sesiones(dos.get("participacion"))).containsExactly(todas.get(7).toString(),
                todas.get(8).toString());

        JsonNode treinta = analitica(b, "30");
        assertThat(treinta.get("jornadas")).hasSize(9);
        assertThat(treinta.get("ranking")).hasSize(1);
        assertThat(analitica(b, "1").get("jornadas")).hasSize(1);
    }

    @Test
    void aPeriodOutsideOneToThirtyIsRejected() {
        Bodega b = bodega();
        for (String malo : List.of("0", "31", "-1", "abc", "1.5", "", "999999999999")) {
            assertProblema(analitica(b.local().token(), malo), 400, "INVALID_PERIOD");
        }
    }

    @Test
    void rankingIsTheTopEightByVotesWithTheirCompletedPlaybacks() {
        Bodega b = bodega();
        UUID uno = jornada(b, "2026-11-20");
        UUID dos = jornada(b, "2026-11-21");
        List<Object[]> canciones = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            canciones.add(cancionAnalitica(b, "Salsa"));
        }
        // votes: song i gets (i + 1) votes in total, split over the two sessions for the top one
        for (int i = 0; i < 9; i++) {
            pedidaConVotos(b, uno, canciones.get(i), i);
        }
        pedidaConVotos(b, dos, canciones.get(8), 2); // top song: 9 + 3 = 12
        // completed playbacks of the top song: two from requests, one from the default list; skipped/failed ignored
        UUID refTop = jdbc.queryForObject("SELECT solicitud_ref FROM analitica.hecho_solicitud WHERE sesion_id = ? "
                + "AND cancion_id = ?", UUID.class, dos, canciones.get(8)[0]);
        reproduccion(b, dos, canciones.get(8), refTop, "COMPLETADA");
        reproduccion(b, dos, canciones.get(8), refTop, "COMPLETADA");
        reproduccion(b, uno, canciones.get(8), null, "COMPLETADA");
        reproduccion(b, uno, canciones.get(8), null, "SALTADA");
        reproduccion(b, uno, canciones.get(8), null, "FALLIDA");
        reproduccion(b, uno, canciones.get(8), null, "INTERRUMPIDA_CIERRE");

        JsonNode ranking = analitica(b, null).get("ranking");

        assertThat(ranking).hasSize(8);
        List<Long> votos = new ArrayList<>();
        ranking.forEach(e -> votos.add(e.get("votos").asLong()));
        assertThat(votos).containsExactly(12L, 8L, 7L, 6L, 5L, 4L, 3L, 2L);
        JsonNode top = ranking.get(0);
        assertThat(campos(top)).containsExactly("cancion", "completadas", "votos");
        assertThat(top.get("completadas").asLong()).isEqualTo(3);
        assertThat(ranking.get(1).get("completadas").asLong()).isZero();
        JsonNode cancion = top.get("cancion");
        String video = (String) canciones.get(8)[1];
        assertThat(cancion.get("videoId").asString()).isEqualTo(video);
        assertThat(cancion.get("titulo").asString()).isEqualTo("Titulo " + video);
        assertThat(cancion.get("canal").asString()).isEqualTo("Canal " + video);
        assertThat(cancion.get("duracionSegundos").asInt()).isEqualTo(200);
        assertThat(campos(cancion)).containsExactly("artista", "artistaVerificado", "canal", "duracionSegundos",
                "genero", "miniaturaUrl", "titulo", "videoId");
    }

    @Test
    void genresPerHourFollowTheNightAcrossMidnightAndSuppressCellsUnderFiveParticipants() {
        Bodega b = bodega();
        UUID sabado = jornada(b, "2026-11-21"); // Saturday: ISO day 6
        Object[] salsa = cancionAnalitica(b, "Salsa");
        Object[] rock = cancionAnalitica(b, "Rock en español");
        Object[] sinGenero = cancionAnalitica(b, null);
        // 23:00 on the 21st: 5 salsa requests (5 people) and 5 rock requests by only 2 people
        for (int i = 0; i < 5; i++) {
            solicitud(b, sabado, salsa, participante(sabado, "WEB"), "2026-11-21", 23);
        }
        UUID p1 = participante(sabado, "WEB");
        UUID p2 = participante(sabado, "WEB");
        for (int i = 0; i < 5; i++) {
            solicitud(b, sabado, rock, i % 2 == 0 ? p1 : p2, "2026-11-21", 23);
        }
        // 00:00 and 01:00 belong to the same night (calendar date 22nd)
        for (int i = 0; i < 6; i++) {
            solicitud(b, sabado, sinGenero, participante(sabado, "WEB"), "2026-11-22", 0);
        }
        for (int i = 0; i < 2; i++) {
            solicitud(b, sabado, salsa, participante(sabado, "WEB"), "2026-11-22", 1);
        }
        // 22:00 last, inserted after midnight's rows on purpose
        for (int i = 0; i < 5; i++) {
            solicitud(b, sabado, rock, participante(sabado, "ANDROID"), "2026-11-21", 22);
        }

        JsonNode generos = analitica(b, null).get("generosPorHora");

        assertThat(generos).hasSize(1);
        JsonNode noche = generos.get(0);
        assertThat(campos(noche)).containsExactly("celdas", "diaSemana", "fechaOperativa", "sesionId");
        assertThat(noche.get("sesionId").asString()).isEqualTo(sabado.toString());
        assertThat(noche.get("fechaOperativa").asString()).isEqualTo("2026-11-21");
        assertThat(noche.get("diaSemana").asInt()).isEqualTo(6);

        List<String> celdas = new ArrayList<>();
        noche.get("celdas").forEach(c -> {
            assertThat(campos(c)).containsExactly("genero", "hora", "porcentaje", "suprimida");
            celdas.add(c.get("hora").asInt() + "|" + c.get("genero").asString() + "|"
                    + (c.get("porcentaje").isNull() ? "null" : c.get("porcentaje").asDouble()) + "|"
                    + c.get("suprimida").asBoolean());
        });
        assertThat(celdas).containsExactly(
                "22|Rock en español|100.0|false",
                "23|Rock en español|null|true",
                "23|Salsa|50.0|false",
                "0|Sin género|100.0|false",
                "1|Salsa|null|true");
    }

    @Test
    void anOwnerOnlySeesTheirOwnEstablishment() {
        Bodega mia = bodega();
        Bodega ajena = bodega();
        UUID suya = jornada(ajena, "2026-11-21");
        participante(suya, "WEB");
        pedidaConVotos(ajena, suya, cancionAnalitica(ajena, "Salsa"), 3);

        JsonNode body = analitica(mia, null);
        assertThat(body.get("jornadas")).isEmpty();
        assertThat(body.get("ranking")).isEmpty();
        assertThat(body.get("canales").get("WEB").asLong()).isZero();
        assertThat(body.get("publicacion").isNull()).isTrue();
    }

    @Test
    void responsesCarryNoIdentityOfAnyParticipant() {
        Bodega b = bodega();
        UUID sesion = jornada(b, "2026-11-21");
        Object[] cancion = cancionAnalitica(b, "Salsa");
        pedidaConVotos(b, sesion, cancion, 5);

        String body = analitica(b.local().token(), null).body();

        List<String> secretos = new ArrayList<>();
        secretos.addAll(jdbc.queryForList("SELECT participante_ref FROM analitica.dim_participacion WHERE sesion_id = ?",
                String.class, sesion));
        jdbc.queryForList("SELECT id FROM analitica.dim_participacion WHERE sesion_id = ?", UUID.class, sesion)
                .forEach(id -> secretos.add(id.toString()));
        jdbc.queryForList("SELECT solicitud_ref FROM analitica.hecho_solicitud WHERE sesion_id = ?", UUID.class, sesion)
                .forEach(id -> secretos.add(id.toString()));
        jdbc.queryForList("SELECT voto_ref FROM analitica.hecho_voto WHERE sesion_id = ?", UUID.class, sesion)
                .forEach(id -> secretos.add(id.toString()));
        assertThat(secretos).hasSizeGreaterThan(10);
        secretos.forEach(secreto -> assertThat(body).doesNotContain(secreto));
        assertThat(body).doesNotContain("participante").doesNotContain("seudonimo").doesNotContain("dispositivo");
    }

    @Test
    void requiresAnOwnerTokenAndAnEstablishment() {
        assertThat(analitica((String) null, null).statusCode()).isEqualTo(401);
        assertProblema(analitica(tokenSinEstablecimiento(), null), 404, "NO_ESTABLISHMENT");
    }
}
