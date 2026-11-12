package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The queue as clients see it: ordered by support, then by age, never stored; public without any identity,
 * and the participant's own view says what they already supported.
 */
class ColaApiTest extends ColaApiTestSupport {

    private static List<String> ids(JsonNode cola) {
        List<String> ids = new ArrayList<>();
        cola.forEach(item -> ids.add(item.get("solicitudId").asString()));
        return ids;
    }

    @Test
    void theQueueIsOrderedByVotesThenByAgeThenById() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID primera = solicitada(unirse(local), videoGuardado());
        UUID segunda = solicitada(unirse(local), videoGuardado());
        UUID tercera = solicitada(unirse(local), videoGuardado());
        votar(unirse(local), tercera);
        votar(unirse(local), tercera);
        votar(unirse(local), segunda);
        // primera: 1 vote, segunda: 2, tercera: 3
        // Two requests created at the same instant with the same votes are ordered by id.
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID creador = jdbc.queryForObject("SELECT creador_id FROM operacion.solicitud WHERE id = ?", UUID.class, primera);
        for (UUID id : List.of(b, a)) {
            UUID cancion = cancionGuardada(videoIdAleatorio(), "Empate", "Canal", 100);
            jdbc.update("INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, creada_at) "
                    + "VALUES (?, ?, ?, ?, '2020-01-01T00:00:00Z')", id, sesion, cancion, creador);
        }

        JsonNode body = json(colaPublica(local.slug()));

        assertThat(ids(body.get("cola"))).containsExactly(tercera.toString(), segunda.toString(), primera.toString(),
                a.toString(), b.toString());
        assertThat(body.get("cola").get(0).get("votos").asInt()).isEqualTo(3);
        assertThat(body.get("cola").get(0).get("posicion").asInt()).isEqualTo(1);
        assertThat(body.get("cola").get(4).get("votos").asInt()).isZero();
        assertThat(body.get("cola").get(4).get("posicion").asInt()).isEqualTo(5);
    }

    @Test
    void withEqualVotesTheOlderRequestGoesFirst() {
        Local local = localAbierto();
        UUID primera = solicitada(unirse(local), videoGuardado());
        UUID segunda = solicitada(unirse(local), videoGuardado());

        assertThat(ids(json(colaPublica(local.slug())).get("cola")))
                .containsExactly(primera.toString(), segunda.toString());
    }

    @Test
    void thePublicQueueShowsTheSongsAndNoIdentities() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String video = videoIdAleatorio();
        cancionGuardada(video, "La camisa negra", "Juanes - Topic", 227);
        UUID solicitud = solicitada(unirse(local), video);

        HttpResponse<String> response = colaPublica(local.slug());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("colaVersion").asLong()).isEqualTo(colaVersion(sesion));
        assertThat(body.get("estado").asString()).isEqualTo("ABIERTO");
        assertThat(body.get("enReproduccion").isNull()).isTrue();
        JsonNode item = body.get("cola").get(0);
        assertThat(item.get("solicitudId").asString()).isEqualTo(solicitud.toString());
        assertThat(item.get("votos").asInt()).isEqualTo(1);
        assertThat(item.has("votado")).isFalse();
        JsonNode cancion = item.get("cancion");
        assertThat(cancion.get("videoId").asString()).isEqualTo(video);
        assertThat(cancion.get("titulo").asString()).isEqualTo("La camisa negra");
        assertThat(cancion.get("artista").asString()).isEqualTo("Juanes");
        assertThat(cancion.get("artistaVerificado").asBoolean()).isFalse();
        assertThat(cancion.get("duracionSegundos").asInt()).isEqualTo(227);
        assertThat(cancion.get("miniaturaUrl").asString()).isEqualTo("https://img.test/" + video + "/mqdefault.jpg");
        String texto = response.body().toLowerCase();
        assertThat(texto).doesNotContain("participacion", "seudonimo", "dispositivo", "creador");
        String seudonimo = jdbc.queryForObject("SELECT seudonimo FROM operacion.participacion WHERE sesion_id = ?",
                String.class, sesion);
        assertThat(response.body()).doesNotContain(seudonimo);
    }

    @Test
    void aVerifiedArtistIsShownAsSuch() {
        Local local = localAbierto();
        String video = videoGuardado();
        jdbc.update("UPDATE operacion.cancion SET artista_verificado = 'Shakira' WHERE youtube_video_id = ?", video);
        solicitada(unirse(local), video);

        JsonNode cancion = json(colaPublica(local.slug())).get("cola").get(0).get("cancion");

        assertThat(cancion.get("artista").asString()).isEqualTo("Shakira");
        assertThat(cancion.get("artistaVerificado").asBoolean()).isTrue();
    }

    @Test
    void waitsAddThePlayingSongAndEverySongAhead() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID creador = participacion(sesion);
        UUID cancionSonando = cancionGuardada(videoIdAleatorio(), "Sonando", "Canal", 240);
        UUID sonando = solicitud(sesion, cancionSonando, creador, "REPRODUCIENDO", null);
        reproduccionEnCurso(sesion, cancionSonando, estacionHabilitada(local.id(), sesion), sonando);
        String primera = videoIdAleatorio();
        cancionGuardada(primera, "Uno", "Canal", 100);
        solicitada(unirse(local), primera);
        String segunda = videoIdAleatorio();
        cancionGuardada(segunda, "Dos", "Canal", 50);
        solicitada(unirse(local), segunda);

        JsonNode body = json(colaPublica(local.slug()));

        JsonNode enReproduccion = body.get("enReproduccion");
        assertThat(enReproduccion.get("solicitudId").asString()).isEqualTo(sonando.toString());
        assertThat(enReproduccion.get("cancion").get("titulo").asString()).isEqualTo("Sonando");
        assertThat(enReproduccion.get("iniciadaAt").isNull()).isFalse();
        // Started one minute ago (fixture), 240 s long: about 180 s left.
        long restante = enReproduccion.get("restanteSegundos").asLong();
        assertThat(restante).isBetween(175L, 180L);
        assertThat(body.get("cola").get(0).get("esperaEstimadaSegundos").asLong()).isEqualTo(restante);
        assertThat(body.get("cola").get(1).get("esperaEstimadaSegundos").asLong()).isEqualTo(restante + 100);
        assertThat(ids(body.get("cola"))).doesNotContain(sonando.toString());
    }

    @Test
    void popularityNeverInterruptsTheSongPlaying() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID creador = participacion(sesion);
        UUID cancionSonando = cancionGuardada(videoIdAleatorio(), "Sonando", "Canal", 240);
        UUID sonando = solicitud(sesion, cancionSonando, creador, "REPRODUCIENDO", null);
        reproduccionEnCurso(sesion, cancionSonando, estacionHabilitada(local.id(), sesion), sonando);
        UUID popular = solicitada(unirse(local), videoGuardado());
        for (int i = 0; i < 3; i++) {
            votar(unirse(local), popular);
        }

        JsonNode body = json(colaPublica(local.slug()));

        assertThat(body.get("enReproduccion").get("solicitudId").asString()).isEqualTo(sonando.toString());
        assertThat(body.get("cola").get(0).get("solicitudId").asString()).isEqualTo(popular.toString());
    }

    @Test
    void theParticipantQueueSaysWhatTheParticipantAlreadySupported() {
        Local local = localAbierto();
        String token = unirse(local);
        UUID mia = solicitada(token, videoGuardado());
        UUID ajena = solicitada(unirse(local), videoGuardado());
        UUID apoyada = solicitada(unirse(local), videoGuardado());
        votar(token, apoyada);

        HttpResponse<String> response = colaParticipante(token);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("estado").asString()).isEqualTo("ABIERTO");
        assertThat(body.get("colaVersion").asLong()).isEqualTo(colaVersion(sesionDe(local)));
        for (JsonNode item : body.get("cola")) {
            String id = item.get("solicitudId").asString();
            boolean esperado = id.equals(mia.toString()) || id.equals(apoyada.toString());
            assertThat(item.get("votado").asBoolean()).as(id).isEqualTo(esperado);
        }
        assertThat(ids(body.get("cola"))).contains(ajena.toString());
        assertThat(response.body().toLowerCase()).doesNotContain("participacion", "seudonimo", "dispositivo");
    }

    @Test
    void thePlayingSongSaysWhetherTheParticipantSupportedIt() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String token = unirse(local);
        UUID participacion = jdbc.queryForObject("SELECT id FROM operacion.participacion WHERE sesion_id = ?",
                UUID.class, sesion);
        UUID cancion = cancionGuardada(videoIdAleatorio(), "Sonando", "Canal", 240);
        UUID sonando = solicitud(sesion, cancion, participacion, "REPRODUCIENDO", null);
        jdbc.update("INSERT INTO operacion.voto (id, solicitud_id, participacion_id, sesion_id, cancion_id, es_inicial) "
                + "VALUES (?, ?, ?, ?, ?, true)", UUID.randomUUID(), sonando, participacion, sesion, cancion);
        reproduccionEnCurso(sesion, cancion, estacionHabilitada(local.id(), sesion), sonando);

        assertThat(json(colaParticipante(token)).get("enReproduccion").get("votado").asBoolean()).isTrue();
        assertThat(json(colaParticipante(unirse(local))).get("enReproduccion").get("votado").asBoolean()).isFalse();
        assertThat(json(colaPublica(local.slug())).get("enReproduccion").has("votado")).isFalse();
    }

    @Test
    void withoutASessionTheQueueIsEmptyAndClosed() {
        Local local = localListoParaAbrir();

        HttpResponse<String> response = colaPublica(local.slug());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("estado").asString()).isEqualTo("CERRADO");
        assertThat(body.get("cola").isEmpty()).isTrue();
        assertThat(body.get("enReproduccion").isNull()).isTrue();
    }

    @Test
    void aPausedSessionStillShowsItsQueue() {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        jdbc.update("UPDATE operacion.sesion SET estado = 'PAUSADA' WHERE id = ?", sesionDe(local));

        JsonNode body = json(colaPublica(local.slug()));

        assertThat(body.get("estado").asString()).isEqualTo("PAUSADO");
        assertThat(ids(body.get("cola"))).containsExactly(solicitud.toString());
    }

    @Test
    void anUnknownEstablishmentIsNotFound() {
        assertProblema(colaPublica("no-existe-" + UUID.randomUUID().toString().substring(0, 8)), 404,
                "ESTABLISHMENT_NOT_FOUND");
    }
}
