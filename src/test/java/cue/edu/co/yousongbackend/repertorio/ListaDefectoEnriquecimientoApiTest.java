package cue.edu.co.yousongbackend.repertorio;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import cue.edu.co.yousongbackend.support.YoutubeApiTest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** Publishing a default list stores the song metadata YouTube gives (image, title, channel, duration). */
@TestPropertySource(properties = "app.youtube.daily-quota-limit=1000000")
class ListaDefectoEnriquecimientoApiTest extends YoutubeApiTest {

    private HttpResponse<String> publicar(Local local, String... videoIds) {
        StringBuilder ids = new StringBuilder();
        for (String id : videoIds) {
            ids.append(ids.length() == 0 ? "" : ",").append('"').append(id).append('"');
        }
        return put("/api/mi-establecimiento/lista-defecto", local.token(),
                "{\"canciones\":[" + ids + "]}");
    }

    private Map<String, Object> fila(String videoId) {
        return jdbc.queryForMap("SELECT * FROM operacion.cancion WHERE youtube_video_id = ?", videoId);
    }

    private int listas(Local local) {
        return jdbc.queryForObject("SELECT count(*) FROM operacion.lista_defecto WHERE establecimiento_id = ?",
                Integer.class, local.id());
    }

    private static Instant instante(Object valor) {
        return valor instanceof Timestamp t ? t.toInstant() : ((java.time.OffsetDateTime) valor).toInstant();
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    @Test
    void publishingShowsImageTitleArtistAndDuration() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id).titulo("La camisa negra").canal("Juanes - Topic").duracion("PT3M47S"));

        HttpResponse<String> response = publicar(local, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode item = json(response).get("canciones").get(0);
        assertThat(item.get("posicion").asInt()).isEqualTo(1);
        assertThat(item.get("youtubeVideoId").asString()).isEqualTo(id);
        assertThat(item.get("titulo").asString()).isEqualTo("La camisa negra");
        assertThat(item.get("artista").asString()).isEqualTo("Juanes");
        assertThat(item.get("artistaVerificado").asBoolean()).isFalse();
        assertThat(item.get("canal").asString()).isEqualTo("Juanes - Topic");
        assertThat(item.get("duracionSegundos").asInt()).isEqualTo(227);
        assertThat(item.get("miniaturaUrl").asString()).isEqualTo("https://img.test/" + id + "/mqdefault.jpg");

        // The same shape on the current-list endpoint
        JsonNode current = json(get("/api/mi-establecimiento/lista-defecto", local.token()))
                .get("canciones").get(0);
        assertThat(current.get("titulo").asString()).isEqualTo("La camisa negra");
        assertThat(current.get("duracionSegundos").asInt()).isEqualTo(227);
    }

    @Test
    void storesTheMetadataWithItsExpiry() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id).titulo("Titulo").canal("Canal").duracion("PT1M"));
        Instant before = Instant.now();

        assertThat(publicar(local, id).statusCode()).isEqualTo(201);

        Map<String, Object> row = fila(id);
        assertThat(row.get("titulo")).isEqualTo("Titulo");
        assertThat(row.get("canal_titulo")).isEqualTo("Canal");
        assertThat(row.get("duracion_proveedor_s")).isEqualTo(60);
        assertThat(row.get("miniatura_url")).isEqualTo("https://img.test/" + id + "/mqdefault.jpg");
        assertThat(row.get("disponibilidad")).isEqualTo("DISPONIBLE");
        assertThat(row.get("artista_verificado")).as("the channel never fills the verified artist").isNull();
        Instant validado = instante(row.get("validado_at"));
        Instant expira = instante(row.get("metadatos_expiran_at"));
        assertThat(validado).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(Duration.between(validado, expira)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void aVerifiedArtistIsReportedAsVerified() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id, artista_verificado) VALUES (?, ?, 'Juanes')",
                UUID.randomUUID(), id);
        YOUTUBE.register(LocalYoutubeServer.video(id).canal("Otro canal"));

        JsonNode item = json(publicar(local, id)).get("canciones").get(0);

        assertThat(item.get("artista").asString()).isEqualTo("Juanes");
        assertThat(item.get("artistaVerificado").asBoolean()).isTrue();
        assertThat(fila(id).get("artista_verificado")).isEqualTo("Juanes");
    }

    @Test
    void aVideoThatDoesNotExistRejectsThePublicationAndWritesNothing() {
        Local local = establecimiento();
        String bueno = videoIdAleatorio();
        String fantasma = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(bueno));

        HttpResponse<String> response = publicar(local, bueno, fantasma);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VIDEO_UNAVAILABLE");
        assertThat(json(response).get("detail").asString()).contains(fantasma);
        assertThat(listas(local)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id IN (?, ?)",
                Integer.class, bueno, fantasma)).isZero();
    }

    @Test
    void anUnavailableVideoRejectsThePublication() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id).incrustable(false));

        HttpResponse<String> response = publicar(local, id);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VIDEO_UNAVAILABLE");
        assertThat(json(response).get("detail").asString()).contains(id);
        assertThat(listas(local)).isZero();
    }

    @Test
    void whenTheProviderIsDownThePublicationStillSucceedsWithoutMetadata() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.script("/videos", 500, "{}");

        HttpResponse<String> response = publicar(local, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode item = json(response).get("canciones").get(0);
        assertThat(item.get("youtubeVideoId").asString()).isEqualTo(id);
        assertThat(item.get("titulo").isNull()).isTrue();
        assertThat(item.get("artista").isNull()).isTrue();
        assertThat(item.get("artistaVerificado").asBoolean()).isFalse();
        assertThat(item.get("canal").isNull()).isTrue();
        assertThat(item.get("duracionSegundos").isNull()).isTrue();
        assertThat(item.get("miniaturaUrl").isNull()).isTrue();
        assertThat(fila(id).get("metadatos_expiran_at")).isNull();
    }

    @Test
    void whenTheProviderTimesOutThePublicationStillSucceeds() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.delay("/videos", 2500);

        assertThat(publicar(local, id).statusCode()).isEqualTo(201);
        assertThat(fila(id).get("titulo")).isNull();
    }

    @Test
    void freshMetadataIsNotFetchedAgain() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id).titulo("Primero"));
        assertThat(publicar(local, id).statusCode()).isEqualTo(201);
        assertThat(YOUTUBE.count("/videos")).isEqualTo(1);

        YOUTUBE.register(LocalYoutubeServer.video(id).titulo("Cambiado"));
        JsonNode second = json(publicar(local, id)).get("canciones").get(0);

        assertThat(YOUTUBE.count("/videos")).as("fresh metadata is not refetched").isEqualTo(1);
        assertThat(second.get("titulo").asString()).isEqualTo("Primero");
    }

    @Test
    void expiredMetadataIsFetchedAgain() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id).titulo("Primero"));
        publicar(local, id);
        jdbc.update("UPDATE operacion.cancion SET metadatos_expiran_at = ? WHERE youtube_video_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), id);

        YOUTUBE.register(LocalYoutubeServer.video(id).titulo("Actualizado"));
        JsonNode second = json(publicar(local, id)).get("canciones").get(0);

        assertThat(YOUTUBE.count("/videos")).isEqualTo(2);
        assertThat(second.get("titulo").asString()).isEqualTo("Actualizado");
        Instant expira = instante(fila(id).get("metadatos_expiran_at"));
        assertThat(expira).isAfter(Instant.now().plus(Duration.ofDays(6)));
    }

    @Test
    void onlyTheStaleVideosAreAskedFor() {
        Local local = establecimiento();
        String fresco = videoIdAleatorio();
        String nuevo = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(fresco));
        publicar(local, fresco);
        YOUTUBE.register(LocalYoutubeServer.video(nuevo));

        publicar(local, fresco, nuevo);

        assertThat(YOUTUBE.count("/videos")).isEqualTo(2);
        assertThat(YOUTUBE.last("/videos").param("id")).isEqualTo(nuevo);
    }

    @Test
    void nothingIsAskedWhenEverythingIsFresh() {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        publicar(local, id);
        YOUTUBE.reset();

        assertThat(publicar(local, id).statusCode()).isEqualTo(201);
        assertThat(YOUTUBE.count()).isZero();
    }

    @Test
    void theProviderIsNotCalledWhileTheEstablishmentRowIsLocked() throws Exception {
        Local local = establecimiento();
        String id = videoIdAleatorio();
        YOUTUBE.register(LocalYoutubeServer.video(id));
        YOUTUBE.delay("/videos", 800);

        CompletableFuture<HttpResponse<String>> publicacion = CompletableFuture.supplyAsync(() -> publicar(local, id));
        long limite = System.currentTimeMillis() + 5000;
        while (YOUTUBE.count("/videos") == 0 && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
        }
        assertThat(YOUTUBE.count("/videos")).as("the provider was called").isEqualTo(1);

        // Still waiting for the provider: no transaction may hold the establishment row.
        jdbc.queryForList("SELECT id FROM operacion.establecimiento WHERE id = ? FOR UPDATE NOWAIT", local.id());

        assertThat(publicacion.get().statusCode()).isEqualTo(201);
    }
}
