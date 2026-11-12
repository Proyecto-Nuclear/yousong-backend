package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ClienteSse.Evento;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The upcoming default-list songs in the queue snapshot ({@code proximasDefecto}, {@code rotacionDefecto}): what
 * a client sees next when nobody requested anything, in the order the rotation engine will pick it.
 */
@TestPropertySource(properties = {"app.cola.sse-max-per-address=1000", "app.cola.sse-max-per-establishment=1000"})
class ColaProximasDefectoApiTest extends ColaApiTestSupport {

    private static String eventos(String slug) {
        return "/api/public/establecimientos/" + slug + "/cola/eventos";
    }

    private String cancion(int duracion) {
        String videoId = videoIdAleatorio();
        cancionGuardada(videoId, "Titulo " + videoId, "Canal - Topic", duracion);
        return videoId;
    }

    private static List<String> videos(JsonNode proximas) {
        List<String> videos = new ArrayList<>();
        proximas.forEach(item -> videos.add(item.get("cancion").get("videoId").asString()));
        return videos;
    }

    private static List<Long> esperas(JsonNode proximas) {
        List<Long> esperas = new ArrayList<>();
        proximas.forEach(item -> esperas.add(item.get("esperaEstimadaSegundos").asLong()));
        return esperas;
    }

    private static List<Integer> posiciones(JsonNode proximas) {
        List<Integer> posiciones = new ArrayList<>();
        proximas.forEach(item -> posiciones.add(item.get("posicion").asInt()));
        return posiciones;
    }

    @Test
    void anEmptyQueueShowsTheNextSequentialDefaultSongsWithGrowingWaits() {
        String a = cancion(100);
        String b = cancion(150);
        String c = cancion(200);
        String d = cancion(250);
        Local local = localConLista("SECUENCIAL", a, b, c, d);

        JsonNode cola = json(colaPublica(local.slug()));

        assertThat(cola.propertyNames()).containsExactlyInAnyOrder("colaVersion", "estado", "enReproduccion", "cola",
                "proximasDefecto", "rotacionDefecto");
        assertThat(cola.get("cola")).isEmpty();
        assertThat(cola.get("rotacionDefecto").asString()).isEqualTo("SECUENCIAL");
        JsonNode proximas = cola.get("proximasDefecto");
        assertThat(videos(proximas)).containsExactly(a, b, c, d);
        assertThat(posiciones(proximas)).containsExactly(1, 2, 3, 4);
        assertThat(esperas(proximas)).containsExactly(0L, 100L, 250L, 450L);
        JsonNode primero = proximas.get(0);
        assertThat(primero.propertyNames()).containsExactlyInAnyOrder("posicion", "cancion", "esperaEstimadaSegundos");
        assertThat(primero.get("cancion").propertyNames()).containsExactlyInAnyOrder("videoId", "titulo", "artista",
                "artistaVerificado", "duracionSegundos", "miniaturaUrl");
        assertThat(primero.get("cancion").get("titulo").asString()).isEqualTo("Titulo " + a);
        assertThat(primero.get("cancion").get("duracionSegundos").asInt()).isEqualTo(100);
    }

    @Test
    void thePreviewIsCappedAtFiveItemsAndWrapsAroundTheList() {
        List<String> lista = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            lista.add(cancion(100));
        }
        Local local = localConLista("SECUENCIAL", lista.toArray(String[]::new));
        for (int i = 0; i < 4; i++) {
            assertThat(siguiente(local.token()).statusCode()).isEqualTo(200);
            assertThat(fin("COMPLETADA", local.token()).statusCode()).isEqualTo(200);
        }

        JsonNode proximas = json(colaPublica(local.slug())).get("proximasDefecto");

        assertThat(videos(proximas)).containsExactly(lista.get(4), lista.get(5), lista.get(6), lista.get(0),
                lista.get(1));
        assertThat(esperas(proximas)).containsExactly(0L, 100L, 200L, 300L, 400L);
    }

    @Test
    void thePreviewAdvancesWhenADefaultSongStartsAndCountsWhatIsLeftOfIt() {
        String a = cancion(100);
        String b = cancion(150);
        String c = cancion(200);
        Local local = localConLista("SECUENCIAL", a, b, c);

        assertThat(siguiente(local.token()).statusCode()).isEqualTo(200);
        JsonNode cola = json(colaPublica(local.slug()));

        assertThat(cola.get("enReproduccion").get("cancion").get("videoId").asString()).isEqualTo(a);
        JsonNode proximas = cola.get("proximasDefecto");
        // A list shorter than the preview shows each playable item once, the one playing last (it comes back).
        assertThat(videos(proximas)).containsExactly(b, c, a);
        assertThat(posiciones(proximas)).containsExactly(1, 2, 3);
        long restante = cola.get("enReproduccion").get("restanteSegundos").asLong();
        assertThat(restante).isBetween(95L, 100L);
        assertThat(esperas(proximas)).containsExactly(restante, restante + 150, restante + 350);
    }

    @Test
    void blockedAndUnavailableSongsAreSkipped() {
        String a = cancion(100);
        String b = cancion(100);
        String c = cancion(100);
        String d = cancion(100);
        Local local = localConLista("SECUENCIAL", a, b, c, d);
        bloqueada(local.token(), b);
        jdbc.update("UPDATE operacion.cancion SET disponibilidad = 'NO_DISPONIBLE' WHERE youtube_video_id = ?", c);

        JsonNode proximas = json(colaPublica(local.slug())).get("proximasDefecto");

        assertThat(videos(proximas)).containsExactly(a, d);
        assertThat(esperas(proximas)).containsExactly(0L, 100L);
    }

    @Test
    void requestsKeepPriorityAndThePreviewContinuesAfterThem() {
        String a = cancion(100);
        String b = cancion(150);
        Local local = localConLista("SECUENCIAL", a, b);
        String token = unirse(local);
        solicitada(token, cancion(200));
        solicitada(token, cancion(300));

        JsonNode cola = json(colaPublica(local.slug()));

        assertThat(cola.get("cola")).hasSize(2);
        JsonNode proximas = cola.get("proximasDefecto");
        assertThat(videos(proximas)).containsExactly(a, b);
        assertThat(posiciones(proximas)).containsExactly(3, 4);
        assertThat(esperas(proximas)).containsExactly(500L, 600L);
    }

    @Test
    void aRandomListCannotBePredictedSoThePreviewIsEmpty() {
        Local local = localConLista("ALEATORIA", cancion(100), cancion(100), cancion(100));

        JsonNode cola = json(colaPublica(local.slug()));

        assertThat(cola.get("rotacionDefecto").asString()).isEqualTo("ALEATORIA");
        assertThat(cola.get("proximasDefecto").isArray()).isTrue();
        assertThat(cola.get("proximasDefecto")).isEmpty();
    }

    @Test
    void aListWithNothingPlayableHasNoPreviewAndNoRotation() {
        String a = cancion(100);
        Local local = localConLista("SECUENCIAL", a);
        bloqueada(local.token(), a);

        JsonNode cola = json(colaPublica(local.slug()));

        assertThat(cola.get("proximasDefecto")).isEmpty();
        assertThat(cola.get("rotacionDefecto").isNull()).isTrue();
    }

    @Test
    void withoutASessionThereIsNoPreview() {
        Local local = establecimiento();

        JsonNode cola = json(colaPublica(local.slug()));

        assertThat(cola.get("proximasDefecto").isArray()).isTrue();
        assertThat(cola.get("proximasDefecto")).isEmpty();
        assertThat(cola.get("rotacionDefecto").isNull()).isTrue();
    }

    @Test
    void thePreviewCarriesNoIdentityNorInternalIds() {
        Local local = localConLista("SECUENCIAL", cancion(100), cancion(100));
        UUID sesion = sesionDe(local);
        String token = unirse(local);

        String cuerpo = colaPublica(local.slug()).body();

        List<String> identidades = new ArrayList<>(List.of(token, sesion.toString()));
        jdbc.queryForList("SELECT id::text, seudonimo FROM operacion.participacion WHERE sesion_id = ?", sesion)
                .forEach(fila -> fila.values().forEach(valor -> identidades.add(String.valueOf(valor))));
        jdbc.queryForList("SELECT il.id::text FROM operacion.item_lista il JOIN operacion.sesion s "
                + "ON s.lista_id = il.lista_id WHERE s.id = ?", String.class, sesion).forEach(identidades::add);
        jdbc.queryForList("SELECT il.cancion_id::text FROM operacion.item_lista il JOIN operacion.sesion s "
                + "ON s.lista_id = il.lista_id WHERE s.id = ?", String.class, sesion).forEach(identidades::add);
        identidades.add(jdbc.queryForObject("SELECT lista_id::text FROM operacion.sesion WHERE id = ?", String.class,
                sesion));
        assertThat(identidades).hasSizeGreaterThan(6);
        assertThat(identidades).allSatisfy(identidad -> assertThat(cuerpo).doesNotContain(identidad));
    }

    @Test
    void theParticipantViewHasTheSamePreviewWithoutVotes() {
        String a = cancion(100);
        String b = cancion(150);
        Local local = localConLista("SECUENCIAL", a, b);
        String token = unirse(local);

        JsonNode propia = json(colaParticipante(token));
        JsonNode publica = json(colaPublica(local.slug()));

        assertThat(propia.get("rotacionDefecto").asString()).isEqualTo("SECUENCIAL");
        assertThat(propia.get("proximasDefecto")).isEqualTo(publica.get("proximasDefecto"));
        assertThat(videos(propia.get("proximasDefecto"))).containsExactly(a, b);
        assertThat(propia.get("proximasDefecto").get(0).has("votado")).isFalse();
        assertThat(propia.get("proximasDefecto").get(0).has("votos")).isFalse();
        assertThat(propia.get("proximasDefecto").get(0).has("solicitudId")).isFalse();
    }

    @Test
    void anotherEstablishmentsListNorItsBlocksNeverLeak() {
        String a = cancion(100);
        String b = cancion(100);
        String x = cancion(100);
        Local uno = localConLista("SECUENCIAL", a, b);
        Local otro = localConLista("SECUENCIAL", x, a);
        bloqueada(otro.token(), a);

        assertThat(videos(json(colaPublica(uno.slug())).get("proximasDefecto"))).containsExactly(a, b);
        assertThat(videos(json(colaPublica(otro.slug())).get("proximasDefecto"))).containsExactly(x);
    }

    @Test
    void aRepublishedListDoesNotChangeTheRunningSessionsPreview() {
        String a = cancion(100);
        String b = cancion(100);
        Local local = localConLista("SECUENCIAL", a, b);
        String nuevo = cancion(100);

        assertThat(put("/api/mi-establecimiento/lista-defecto", local.token(),
                "{\"politicaRotacion\":\"ALEATORIA\",\"canciones\":[\"" + nuevo + "\"]}").statusCode()).isEqualTo(201);
        JsonNode cola = json(colaPublica(local.slug()));

        // The session keeps the version it opened with; the new one applies from the next session.
        assertThat(cola.get("rotacionDefecto").asString()).isEqualTo("SECUENCIAL");
        assertThat(videos(cola.get("proximasDefecto"))).containsExactly(a, b);
    }

    @Test
    void aStreamReceivesTheAdvancedPreviewWhenADefaultSongStarts() {
        String a = cancion(100);
        String b = cancion(100);
        String c = cancion(100);
        Local local = localConLista("SECUENCIAL", a, b, c);

        try (ClienteSse cliente = ClienteSse.abrir(port, eventos(local.slug()))) {
            assertThat(videos(cliente.siguiente().json().get("proximasDefecto"))).containsExactly(a, b, c);
            assertThat(siguiente(local.token()).statusCode()).isEqualTo(200);

            Evento evento = cliente.siguiente();

            assertThat(evento.nombre()).isEqualTo("cola");
            assertThat(videos(evento.json().get("proximasDefecto"))).containsExactly(b, c, a);
        }
    }

    @Test
    void aStreamReceivesThePreviewWithoutABlockedDefaultSongAndWithItAgainWhenLifted() {
        String a = cancion(100);
        String b = cancion(100);
        String c = cancion(100);
        Local local = localConLista("SECUENCIAL", a, b, c);

        try (ClienteSse cliente = ClienteSse.abrir(port, eventos(local.slug()))) {
            assertThat(videos(cliente.siguiente().json().get("proximasDefecto"))).containsExactly(a, b, c);
            UUID bloqueo = bloqueada(local.token(), b);

            Evento bloqueado = cliente.siguiente();
            assertThat(videos(bloqueado.json().get("proximasDefecto"))).containsExactly(a, c);

            assertThat(delete("/api/mi-establecimiento/bloqueos/" + bloqueo, local.token()).statusCode())
                    .isEqualTo(200);
            Evento levantado = cliente.siguiente(Duration.ofSeconds(10));
            assertThat(videos(levantado.json().get("proximasDefecto"))).containsExactly(a, b, c);
        }
    }
}
