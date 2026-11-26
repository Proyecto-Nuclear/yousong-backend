package cue.edu.co.yousongbackend.destacadas;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/public/establecimientos/{qrSlug}/destacadas}: the most voted, most requested and most played songs
 * of an establishment in the sessions opened in the last {@code dias} days, and its last ten playbacks. Fixtures are
 * plain SQL: closed sessions opened at chosen instants, requests, votes and playbacks.
 */
@TestPropertySource(properties = "app.destacadas.per-minute-per-address=100000")
class DestacadasApiTest extends ColaApiTestSupport {

    @Autowired
    private DestacadasService destacadas;

    /** A fixture establishment with its default list and playback station. */
    private record Bar(Local local, UUID lista, UUID estacion) {
    }

    @BeforeEach
    void ventanaNueva() {
        destacadas.reiniciar();
    }

    // ---------- fixtures ----------

    private Bar bar() {
        Local local = establecimiento();
        UUID lista = listaDefecto(local.id());
        UUID estacion = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.estacion (id, establecimiento_id, nombre) VALUES (?, ?, 'Cabina')",
                estacion, local.id());
        return new Bar(local, lista, estacion);
    }

    private static Instant haceDias(double dias) {
        return Instant.now().minus((long) (dias * 24 * 60), ChronoUnit.MINUTES);
    }

    /** A closed session of the bar opened at the given instant (closed an hour later). */
    private UUID sesion(Bar bar, Instant abierta) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.sesion (id, establecimiento_id, lista_id, fecha_operativa, estado, abierta_at, "
                        + "cerrada_at) VALUES (?, ?, ?, current_date, 'CERRADA', ?, ?)", id, bar.local().id(), bar.lista(),
                java.sql.Timestamp.from(abierta), java.sql.Timestamp.from(abierta.plus(1, ChronoUnit.HOURS)));
        return id;
    }

    private UUID sesionAbierta(Bar bar) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.sesion (id, establecimiento_id, lista_id, fecha_operativa, estado, abierta_at) "
                + "VALUES (?, ?, ?, current_date, 'ABIERTA', now() - interval '1 hour')", id, bar.local().id(), bar.lista());
        return id;
    }

    private String nuevaCancion() {
        String video = videoIdAleatorio();
        cancionGuardada(video, "Titulo " + video, "Canal - Topic", 200);
        return video;
    }

    /** A request of the song in the session, created at the given instant, with its creator's initial vote. */
    private UUID pedida(UUID sesion, String video, String estado, Instant creada) {
        UUID cancion = cancionDeVideo(video);
        UUID creador = participacion(sesion);
        UUID id = UUID.randomUUID();
        boolean activa = estado.equals("PENDIENTE") || estado.equals("RESERVADA") || estado.equals("REPRODUCIENDO");
        jdbc.update("INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, estado, creada_at, "
                        + "finalizada_at) VALUES (?, ?, ?, ?, ?, ?, ?)", id, sesion, cancion, creador, estado,
                java.sql.Timestamp.from(creada), activa ? null : java.sql.Timestamp.from(creada.plusSeconds(60)));
        votoDe(id, sesion, cancion, creador, creada, true);
        return id;
    }

    private UUID pedida(UUID sesion, String video, Instant creada) {
        return pedida(sesion, video, "ATENDIDA", creada);
    }

    private void votoDe(UUID solicitud, UUID sesion, UUID cancion, UUID participacion, Instant creado, boolean inicial) {
        jdbc.update("INSERT INTO operacion.voto (id, solicitud_id, participacion_id, sesion_id, cancion_id, es_inicial, "
                        + "creado_at) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), solicitud, participacion, sesion,
                cancion, inicial, java.sql.Timestamp.from(creado));
    }

    /** {@code cantidad} more votes (from new participants) on the request, all at the given instant. */
    private void votos(UUID solicitud, int cantidad, Instant creado) {
        UUID sesion = jdbc.queryForObject("SELECT sesion_id FROM operacion.solicitud WHERE id = ?", UUID.class, solicitud);
        UUID cancion = jdbc.queryForObject("SELECT cancion_id FROM operacion.solicitud WHERE id = ?", UUID.class, solicitud);
        for (int i = 0; i < cantidad; i++) {
            votoDe(solicitud, sesion, cancion, participacion(sesion), creado, false);
        }
    }

    /** A playback of a request ({@code solicitud} not null) or of the default list, started at {@code iniciada}. */
    private void reproduccion(Bar bar, UUID sesion, String video, UUID solicitud, Instant iniciada, String resultado) {
        UUID cancion = cancionDeVideo(video);
        UUID item = null;
        if (solicitud == null) {
            item = UUID.randomUUID();
            Integer posicion = jdbc.queryForObject("SELECT coalesce(max(posicion), 0) + 1 FROM operacion.item_lista "
                    + "WHERE lista_id = ?", Integer.class, bar.lista());
            jdbc.update("INSERT INTO operacion.item_lista (id, lista_id, cancion_id, posicion) VALUES (?, ?, ?, ?)",
                    item, bar.lista(), cancion, posicion);
        }
        Instant preparada = iniciada == null ? Instant.now().minusSeconds(30) : iniciada.minusSeconds(5);
        Instant terminada = resultado == null ? null : (iniciada == null ? preparada : iniciada).plusSeconds(120);
        jdbc.update("INSERT INTO operacion.reproduccion (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id, "
                        + "item_lista_id, preparada_at, iniciada_at, terminada_at, resultado) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), sesion, cancion, bar.estacion(),
                solicitud == null ? "DEFECTO" : "SOLICITUD", solicitud, item, java.sql.Timestamp.from(preparada),
                iniciada == null ? null : java.sql.Timestamp.from(iniciada),
                terminada == null ? null : java.sql.Timestamp.from(terminada), resultado);
    }

    private HttpResponse<String> destacadas(String slug, String dias) {
        return get("/api/public/establecimientos/" + slug + "/destacadas" + (dias == null ? "" : "?dias=" + dias), null);
    }

    private JsonNode destacadas(Bar bar) {
        HttpResponse<String> response = destacadas(bar.local().slug(), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static List<String> videos(JsonNode lista) {
        List<String> ids = new ArrayList<>();
        lista.forEach(e -> ids.add(e.get("cancion").get("videoId").asString()));
        return ids;
    }

    private static List<Long> numeros(JsonNode lista, String campo) {
        List<Long> valores = new ArrayList<>();
        lista.forEach(e -> valores.add(e.get(campo).asLong()));
        return valores;
    }

    // ---------- shape ----------

    @Test
    void anEstablishmentWithoutHistoryAnswersEmptyLists() {
        JsonNode cuerpo = destacadas(bar());

        assertThat(cuerpo.propertyNames()).containsExactlyInAnyOrder("periodoDias", "masVotadas", "masPedidas",
                "masSonadas", "ultimasSonadas");
        assertThat(cuerpo.get("periodoDias").asInt()).isEqualTo(30);
        for (String lista : List.of("masVotadas", "masPedidas", "masSonadas", "ultimasSonadas")) {
            assertThat(cuerpo.get(lista).isArray()).as(lista).isTrue();
            assertThat(cuerpo.get(lista)).as(lista).isEmpty();
        }
    }

    @Test
    void everyListCarriesCatalogSongsAndItsOwnCount() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(1));
        String video = nuevaCancion();
        jdbc.update("UPDATE operacion.cancion SET genero_propio = 'Salsa', fuente_clasificacion = 'youtube_topic' "
                + "WHERE youtube_video_id = ?", video);
        UUID solicitud = pedida(sesion, video, haceDias(1));
        reproduccion(bar, sesion, video, solicitud, haceDias(1), "COMPLETADA");

        JsonNode cuerpo = destacadas(bar);

        assertThat(cuerpo.get("masVotadas").get(0).propertyNames()).containsExactlyInAnyOrder("cancion", "votos");
        assertThat(cuerpo.get("masPedidas").get(0).propertyNames()).containsExactlyInAnyOrder("cancion", "veces");
        assertThat(cuerpo.get("masSonadas").get(0).propertyNames()).containsExactlyInAnyOrder("cancion", "reproducciones");
        assertThat(cuerpo.get("ultimasSonadas").get(0).propertyNames())
                .containsExactlyInAnyOrder("cancion", "sonoAt", "enCurso");
        JsonNode cancion = cuerpo.get("masPedidas").get(0).get("cancion");
        assertThat(cancion.propertyNames()).containsExactlyInAnyOrder("videoId", "titulo", "artista",
                "artistaVerificado", "canal", "duracionSegundos", "miniaturaUrl", "genero");
        assertThat(cancion.get("videoId").asString()).isEqualTo(video);
        assertThat(cancion.get("titulo").asString()).isEqualTo("Titulo " + video);
        assertThat(cancion.get("artista").asString()).isEqualTo("Canal");
        assertThat(cancion.get("canal").asString()).isEqualTo("Canal - Topic");
        assertThat(cancion.get("duracionSegundos").asInt()).isEqualTo(200);
        assertThat(cancion.get("genero").asString()).isEqualTo("Salsa");
        assertThat(cuerpo.get("masVotadas").get(0).get("votos").asLong()).isEqualTo(1);
        assertThat(cuerpo.get("masPedidas").get(0).get("veces").asLong()).isEqualTo(1);
        assertThat(cuerpo.get("masSonadas").get(0).get("reproducciones").asLong()).isEqualTo(1);
        assertThat(cuerpo.get("ultimasSonadas").get(0).get("enCurso").asBoolean()).isFalse();
    }

    @Test
    void noIdentityAppearsAnywhere() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(1));
        String video = nuevaCancion();
        UUID solicitud = pedida(sesion, video, haceDias(1));
        votos(solicitud, 2, haceDias(1));
        reproduccion(bar, sesion, video, solicitud, haceDias(1), "COMPLETADA");

        String cuerpo = destacadas(bar.local().slug(), null).body();

        assertThat(cuerpo).doesNotContainIgnoringCase("participacion").doesNotContainIgnoringCase("seudonimo")
                .doesNotContainIgnoringCase("dispositivo").doesNotContainIgnoringCase("creador")
                .doesNotContain("solicitudId").doesNotContain(solicitud.toString()).doesNotContain(sesion.toString());
        for (String seudonimo : jdbc.queryForList("SELECT seudonimo FROM operacion.participacion WHERE sesion_id = ?",
                String.class, sesion)) {
            assertThat(cuerpo).doesNotContain(seudonimo);
        }
        for (UUID participacion : jdbc.queryForList("SELECT id FROM operacion.participacion WHERE sesion_id = ?",
                UUID.class, sesion)) {
            assertThat(cuerpo).doesNotContain(participacion.toString());
        }
    }

    // ---------- most requested ----------

    @Test
    void mostRequestedIsOrderedByRequestsThenLatestRequest() {
        Bar bar = bar();
        UUID s1 = sesion(bar, haceDias(5));
        UUID s2 = sesion(bar, haceDias(3));
        UUID s3 = sesion(bar, haceDias(1));
        String a = nuevaCancion();
        String b = nuevaCancion();
        String c = nuevaCancion();
        String d = nuevaCancion();
        pedida(s1, a, haceDias(5));
        pedida(s2, a, haceDias(3));
        pedida(s3, a, haceDias(1));
        pedida(s1, b, haceDias(5));
        pedida(s2, b, haceDias(3));
        pedida(s1, c, haceDias(5));
        pedida(s3, c, haceDias(1));
        pedida(s2, d, haceDias(3));

        JsonNode masPedidas = destacadas(bar).get("masPedidas");

        assertThat(videos(masPedidas)).containsExactly(a, c, b, d);
        assertThat(numeros(masPedidas, "veces")).containsExactly(3L, 2L, 2L, 1L);
    }

    @Test
    void aCompleteTieIsBrokenBySongId() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(1));
        Instant mismo = haceDias(1);
        List<String> videos = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String video = nuevaCancion();
            videos.add(video);
            pedida(sesion, video, mismo);
        }
        List<String> porId = jdbc.queryForList("SELECT youtube_video_id FROM operacion.cancion "
                + "WHERE youtube_video_id IN (?, ?, ?, ?) ORDER BY id", String.class, videos.toArray());

        JsonNode cuerpo = destacadas(bar);

        assertThat(videos(cuerpo.get("masPedidas"))).containsExactlyElementsOf(porId);
        assertThat(videos(cuerpo.get("masVotadas"))).containsExactlyElementsOf(porId);
    }

    @Test
    void requestsRemovedByTheStaffOrBlockedDoNotCount() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(1));
        String video = nuevaCancion();
        for (String estado : List.of("PENDIENTE", "ATENDIDA", "FALLIDA", "NO_ATENDIDA_CIERRE")) {
            pedida(sesion, video, estado, haceDias(1));
        }
        UUID saltadaSonando = pedida(sesion, video, "SALTADA", haceDias(1));
        reproduccion(bar, sesion, video, saltadaSonando, haceDias(1), "SALTADA");
        // Removed from the queue by the staff (SALTADA without any playback) and taken out by a block.
        UUID quitada = pedida(sesion, video, "SALTADA", haceDias(1));
        votos(quitada, 5, haceDias(1));
        UUID bloqueada = pedida(sesion, video, "BLOQUEADA", haceDias(1));
        votos(bloqueada, 5, haceDias(1));

        JsonNode cuerpo = destacadas(bar);

        assertThat(numeros(cuerpo.get("masPedidas"), "veces")).containsExactly(5L);
        assertThat(numeros(cuerpo.get("masVotadas"), "votos")).as("their votes do not count either").containsExactly(5L);
    }

    // ---------- most voted ----------

    @Test
    void mostVotedCountsEveryVoteOfTheSongsRequestsThenLatestVote() {
        Bar bar = bar();
        UUID s1 = sesion(bar, haceDias(4));
        UUID s2 = sesion(bar, haceDias(2));
        String a = nuevaCancion();
        String b = nuevaCancion();
        String c = nuevaCancion();
        votos(pedida(s1, a, haceDias(4)), 3, haceDias(4));
        votos(pedida(s2, a, haceDias(2)), 2, haceDias(2));
        votos(pedida(s1, b, haceDias(4)), 4, haceDias(4));
        votos(pedida(s2, c, haceDias(2)), 4, haceDias(2));

        JsonNode masVotadas = destacadas(bar).get("masVotadas");

        assertThat(videos(masVotadas)).containsExactly(a, c, b);
        assertThat(numeros(masVotadas, "votos")).containsExactly(7L, 5L, 5L);
    }

    // ---------- most played and last played ----------

    @Test
    void mostPlayedCountsStartedPlaybacksOfBothOriginsExceptFailedOnes() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(2));
        String a = nuevaCancion();
        String b = nuevaCancion();
        String fallida = nuevaCancion();
        String sinIniciar = nuevaCancion();
        reproduccion(bar, sesion, a, pedida(sesion, a, haceDias(2)), haceDias(2), "COMPLETADA");
        reproduccion(bar, sesion, a, null, haceDias(1.9), "COMPLETADA");
        reproduccion(bar, sesion, a, null, haceDias(1.8), "SALTADA");
        reproduccion(bar, sesion, b, null, haceDias(1.7), "INTERRUMPIDA_CIERRE");
        reproduccion(bar, sesion, b, null, haceDias(1.6), "FALLIDA");
        reproduccion(bar, sesion, fallida, null, haceDias(1.5), "FALLIDA");
        reproduccion(bar, sesion, sinIniciar, null, null, "FALLIDA");

        JsonNode cuerpo = destacadas(bar);

        assertThat(videos(cuerpo.get("masSonadas"))).containsExactly(a, b);
        assertThat(numeros(cuerpo.get("masSonadas"), "reproducciones")).containsExactly(3L, 1L);
        assertThat(videos(cuerpo.get("ultimasSonadas"))).as("every started playback, newest first")
                .containsExactly(fallida, b, b, a, a, a);
    }

    @Test
    void theSongPlayingNowIsCountedAndMarkedInCourse() {
        Bar bar = bar();
        UUID sesion = sesionAbierta(bar);
        String antes = nuevaCancion();
        String ahora = nuevaCancion();
        reproduccion(bar, sesion, antes, null, Instant.now().minusSeconds(600), "COMPLETADA");
        reproduccion(bar, sesion, ahora, null, Instant.now().minusSeconds(60), null);

        JsonNode cuerpo = destacadas(bar);

        JsonNode ultimas = cuerpo.get("ultimasSonadas");
        assertThat(videos(ultimas)).containsExactly(ahora, antes);
        assertThat(ultimas.get(0).get("enCurso").asBoolean()).isTrue();
        assertThat(ultimas.get(1).get("enCurso").asBoolean()).isFalse();
        assertThat(Instant.parse(ultimas.get(0).get("sonoAt").asString()))
                .isCloseTo(Instant.now().minusSeconds(60), org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS));
        assertThat(videos(cuerpo.get("masSonadas"))).contains(ahora, antes);
    }

    @Test
    void theLastPlayedAreTheLastTenEverAndIgnoreThePeriod() {
        Bar bar = bar();
        UUID vieja = sesion(bar, haceDias(400));
        List<String> esperadas = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String video = nuevaCancion();
            reproduccion(bar, vieja, video, null, haceDias(400).plusSeconds(600L * i), "COMPLETADA");
            esperadas.add(0, video);
        }

        JsonNode cuerpo = destacadas(bar);

        assertThat(videos(cuerpo.get("ultimasSonadas"))).containsExactlyElementsOf(esperadas.subList(0, 10));
        assertThat(cuerpo.get("masSonadas")).as("outside the period").isEmpty();
    }

    @Test
    void eachRankingHoldsAtMostTenSongs() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(1));
        for (int i = 0; i < 12; i++) {
            String video = nuevaCancion();
            reproduccion(bar, sesion, video, pedida(sesion, video, haceDias(1)), haceDias(1), "COMPLETADA");
        }

        JsonNode cuerpo = destacadas(bar);

        assertThat(cuerpo.get("masVotadas")).hasSize(10);
        assertThat(cuerpo.get("masPedidas")).hasSize(10);
        assertThat(cuerpo.get("masSonadas")).hasSize(10);
        assertThat(cuerpo.get("ultimasSonadas")).hasSize(10);
    }

    // ---------- period ----------

    @Test
    void onlySessionsOpenedInThePeriodCount() {
        Bar bar = bar();
        String reciente = nuevaCancion();
        String vieja = nuevaCancion();
        UUID s1 = sesion(bar, haceDias(2));
        UUID s2 = sesion(bar, haceDias(10));
        UUID s3 = sesion(bar, haceDias(40));
        pedida(s1, reciente, haceDias(2));
        pedida(s2, vieja, haceDias(10));
        pedida(s3, vieja, haceDias(40));
        pedida(s3, vieja, haceDias(40));

        JsonNode treinta = destacadas(bar);
        JsonNode siete = json(destacadas(bar.local().slug(), "7"));
        JsonNode todo = json(destacadas(bar.local().slug(), "365"));

        assertThat(videos(treinta.get("masPedidas"))).containsExactly(reciente, vieja);
        assertThat(numeros(treinta.get("masPedidas"), "veces")).containsExactly(1L, 1L);
        assertThat(siete.get("periodoDias").asInt()).isEqualTo(7);
        assertThat(videos(siete.get("masPedidas"))).containsExactly(reciente);
        assertThat(videos(todo.get("masPedidas"))).containsExactly(vieja, reciente);
        assertThat(numeros(todo.get("masPedidas"), "veces")).containsExactly(3L, 1L);
    }

    @Test
    void anInvalidPeriodIsRejected() {
        Bar bar = bar();
        for (String dias : List.of("0", "366", "abc", "-1", "", "1.5", "99999999999")) {
            HttpResponse<String> response = destacadas(bar.local().slug(), dias);
            assertThat(response.statusCode()).as("dias=" + dias).isEqualTo(400);
            assertThat(json(response).get("code").asString()).isEqualTo("INVALID_PERIOD");
        }
        assertThat(destacadas(bar.local().slug(), "1").statusCode()).isEqualTo(200);
        assertThat(destacadas(bar.local().slug(), "365").statusCode()).isEqualTo(200);
    }

    // ---------- establishment ----------

    @Test
    void anUnknownSlugIsNotFound() {
        HttpResponse<String> response = destacadas("no-existe-" + UUID.randomUUID().toString().substring(0, 8), null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("ESTABLISHMENT_NOT_FOUND");
    }

    @Test
    void anInactiveEstablishmentStillAnswersItsHistory() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(1));
        String video = nuevaCancion();
        pedida(sesion, video, haceDias(1));
        jdbc.update("UPDATE operacion.establecimiento SET activo = false WHERE id = ?", bar.local().id());

        assertThat(videos(destacadas(bar).get("masPedidas"))).containsExactly(video);
    }

    @Test
    void establishmentsAreIsolated() {
        Bar propio = bar();
        Bar otro = bar();
        String compartida = nuevaCancion();
        String ajena = nuevaCancion();
        UUID sesionPropia = sesion(propio, haceDias(1));
        UUID sesionAjena = sesion(otro, haceDias(1));
        pedida(sesionPropia, compartida, haceDias(1));
        reproduccion(otro, sesionAjena, ajena, pedida(sesionAjena, ajena, haceDias(1)), haceDias(1), "COMPLETADA");
        pedida(sesionAjena, compartida, haceDias(1));

        JsonNode cuerpo = destacadas(propio);

        assertThat(videos(cuerpo.get("masPedidas"))).containsExactly(compartida);
        assertThat(numeros(cuerpo.get("masPedidas"), "veces")).containsExactly(1L);
        assertThat(cuerpo.get("masSonadas")).isEmpty();
        assertThat(cuerpo.get("ultimasSonadas")).isEmpty();
    }

    @Test
    void songsBlockedNowLeaveTheRankingsButStayInTheHistory() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(1));
        String bloqueada = nuevaCancion();
        String libre = nuevaCancion();
        reproduccion(bar, sesion, bloqueada, pedida(sesion, bloqueada, haceDias(1)), haceDias(1), "COMPLETADA");
        reproduccion(bar, sesion, libre, pedida(sesion, libre, haceDias(1)), haceDias(1), "COMPLETADA");
        jdbc.update("INSERT INTO operacion.bloqueo_contenido (id, establecimiento_id, cancion_id, motivo_codigo, desde_at) "
                + "VALUES (?, ?, ?, 'INAPROPIADO', now() - interval '1 minute')", UUID.randomUUID(), bar.local().id(),
                cancionDeVideo(bloqueada));

        JsonNode cuerpo = destacadas(bar);

        assertThat(videos(cuerpo.get("masVotadas"))).containsExactly(libre);
        assertThat(videos(cuerpo.get("masPedidas"))).containsExactly(libre);
        assertThat(videos(cuerpo.get("masSonadas"))).containsExactly(libre);
        assertThat(videos(cuerpo.get("ultimasSonadas"))).containsExactlyInAnyOrder(bloqueada, libre);
    }

    // ---------- performance ----------

    @Test
    void aFewThousandRowsAnswerQuickly() {
        Bar bar = bar();
        UUID sesion = sesion(bar, haceDias(1));
        List<UUID> canciones = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            canciones.add(cancionDeVideo(nuevaCancion()));
        }
        UUID creador = participacion(sesion);
        // 3000 requests spread over the 50 songs (song i gets 60 of them), each with its initial vote, and as many
        // started default-list playbacks.
        jdbc.update("INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, estado, creada_at, finalizada_at) "
                + "SELECT gen_random_uuid(), ?, (?::uuid[])[1 + (g % 50)], ?, 'ATENDIDA', now() - interval '1 day', "
                + "now() - interval '1 day' FROM generate_series(0, 2999) g",
                sesion, canciones.stream().map(UUID::toString).toArray(String[]::new), creador);
        // 60 voters: voter k supports the k-th request of every song (one support per voter and song), 3000 votes.
        jdbc.update("INSERT INTO operacion.participacion (id, sesion_id, seudonimo) "
                + "SELECT gen_random_uuid(), ?, 'votante-' || g FROM generate_series(1, 60) g", sesion);
        jdbc.update("INSERT INTO operacion.voto (id, solicitud_id, participacion_id, sesion_id, cancion_id, es_inicial) "
                + "SELECT gen_random_uuid(), s.id, p.id, s.sesion_id, s.cancion_id, false FROM "
                + "(SELECT id, sesion_id, cancion_id, row_number() OVER (PARTITION BY cancion_id ORDER BY id) AS n "
                + "FROM operacion.solicitud WHERE sesion_id = ?) s JOIN "
                + "(SELECT id, row_number() OVER (ORDER BY id) AS n FROM operacion.participacion "
                + "WHERE sesion_id = ? AND seudonimo LIKE 'votante-%') p ON p.n = s.n", sesion, sesion);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.item_lista (id, lista_id, cancion_id, posicion) VALUES (?, ?, ?, 1)", item,
                bar.lista(), canciones.get(7));
        jdbc.update("INSERT INTO operacion.reproduccion (id, sesion_id, cancion_id, estacion_id, origen, item_lista_id, "
                + "preparada_at, iniciada_at, terminada_at, resultado) SELECT gen_random_uuid(), ?, ?, ?, 'DEFECTO', ?, "
                + "now() - interval '1 day' + g * interval '1 second', now() - interval '1 day' + g * interval '1 second', "
                + "now() - interval '1 day' + g * interval '1 second', 'COMPLETADA' FROM generate_series(0, 2999) g",
                sesion, canciones.get(7), bar.estacion(), item);

        long inicio = System.nanoTime();
        JsonNode cuerpo = destacadas(bar);
        long milisegundos = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(milisegundos).isLessThan(2000);
        assertThat(cuerpo.get("masPedidas")).hasSize(10);
        assertThat(numeros(cuerpo.get("masPedidas"), "veces")).containsOnly(60L);
        assertThat(numeros(cuerpo.get("masVotadas"), "votos")).containsOnly(60L);
        assertThat(cuerpo.get("masSonadas").get(0).get("reproducciones").asLong()).isEqualTo(3000);
        assertThat(cuerpo.get("ultimasSonadas")).hasSize(10);
    }
}
