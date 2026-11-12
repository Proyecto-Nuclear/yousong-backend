package cue.edu.co.yousongbackend.repertorio;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
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
import tools.jackson.databind.JsonNode;

/** The default list of an establishment: published as immutable versions, never edited in place. */
class ListaDefectoApiTest extends ApiIntegrationTest {

    private static final String LISTA = "/api/mi-establecimiento/lista-defecto";
    private static final String HISTORIAL = "/api/mi-establecimiento/listas-defecto";
    private static final String SESIONES = "/api/mi-establecimiento/sesiones";

    private static String cuerpo(String nombre, String politica, String... videoIds) {
        StringBuilder ids = new StringBuilder();
        for (String id : videoIds) {
            ids.append(ids.length() == 0 ? "" : ",").append('"').append(id).append('"');
        }
        StringBuilder json = new StringBuilder("{");
        if (nombre != null) {
            json.append("\"nombre\":\"").append(nombre).append("\",");
        }
        if (politica != null) {
            json.append("\"politicaRotacion\":\"").append(politica).append("\",");
        }
        return json.append("\"canciones\":[").append(ids).append("]}").toString();
    }

    private HttpResponse<String> publicarComoAdmin(Local local, String body) {
        return put(LISTA, local.token(), body);
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    // ---------- publish ----------

    @Test
    void theOwnerPublishesTheFirstVersionKeepingTheOrder() {
        Local local = establecimiento();
        String a = videoIdAleatorio();
        String b = videoIdAleatorio();
        String c = videoIdAleatorio();

        HttpResponse<String> response = publicarComoAdmin(local, cuerpo("General", "ALEATORIA", a, b, c));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.get("nombre").asString()).isEqualTo("General");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("politicaRotacion").asString()).isEqualTo("ALEATORIA");
        assertThat(body.get("canciones").size()).isEqualTo(3);
        assertThat(body.get("canciones").get(0).get("posicion").asInt()).isEqualTo(1);
        assertThat(body.get("canciones").get(0).get("youtubeVideoId").asString()).isEqualTo(a);
        assertThat(body.get("canciones").get(2).get("youtubeVideoId").asString()).isEqualTo(c);

        String listaId = body.get("id").asString();
        assertThat(jdbc.queryForList("""
                SELECT c.youtube_video_id FROM operacion.item_lista i JOIN operacion.cancion c ON c.id = i.cancion_id
                WHERE i.lista_id = ?::uuid ORDER BY i.posicion
                """, String.class, listaId)).containsExactly(a, b, c);
    }

    @Test
    void nameAndRotationHaveDefaults() {
        JsonNode body = json(publicarComoAdmin(establecimiento(), cuerpo(null, null, videoIdAleatorio())));

        assertThat(body.get("nombre").asString()).isEqualTo("General");
        assertThat(body.get("politicaRotacion").asString()).isEqualTo("SECUENCIAL");
    }

    @Test
    void songsAreRegisteredOnceAndReusedAcrossLists() {
        String video = videoIdAleatorio();
        publicarComoAdmin(establecimiento(), cuerpo(null, null, video));
        publicarComoAdmin(establecimiento(), cuerpo(null, null, video));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?",
                Integer.class, video)).isEqualTo(1);
    }

    @Test
    void newSongsStartWithoutMetadataAndKnownOnesKeepTheirs() {
        UUID conocida = cancion();
        jdbc.update("UPDATE operacion.cancion SET titulo = 'Un titulo', artista_verificado = 'Un artista' WHERE id = ?", conocida);
        String videoConocido = jdbc.queryForObject("SELECT youtube_video_id FROM operacion.cancion WHERE id = ?", String.class, conocida);
        String videoNuevo = videoIdAleatorio();

        JsonNode canciones = json(publicarComoAdmin(establecimiento(), cuerpo(null, null, videoConocido, videoNuevo))).get("canciones");

        assertThat(canciones.get(0).get("titulo").asString()).isEqualTo("Un titulo");
        assertThat(canciones.get(0).get("artista").asString()).isEqualTo("Un artista");
        assertThat(canciones.get(0).get("artistaVerificado").asBoolean()).isTrue();
        assertThat(canciones.get(1).get("titulo").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT disponibilidad FROM operacion.cancion WHERE youtube_video_id = ?",
                String.class, videoNuevo)).isEqualTo("DESCONOCIDA");
    }

    // ---------- versions are immutable ----------

    @Test
    void publishingAgainCreatesANewVersionAndLeavesTheOldOneUntouched() {
        Local local = establecimiento();
        String a = videoIdAleatorio();
        String b = videoIdAleatorio();
        JsonNode v1 = json(publicarComoAdmin(local, cuerpo("General", null, a)));

        JsonNode v2 = json(publicarComoAdmin(local, cuerpo("General", null, b, a)));

        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("id").asString()).isNotEqualTo(v1.get("id").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.item_lista WHERE lista_id = ?::uuid",
                Integer.class, v1.get("id").asString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.lista_defecto WHERE establecimiento_id = ?",
                Integer.class, local.id())).isEqualTo(2);
    }

    @Test
    void versionsAreCountedPerListName() {
        Local local = establecimiento();
        publicarComoAdmin(local, cuerpo("General", null, videoIdAleatorio()));
        publicarComoAdmin(local, cuerpo("General", null, videoIdAleatorio()));

        JsonNode other = json(publicarComoAdmin(local, cuerpo("Viernes", null, videoIdAleatorio())));

        assertThat(other.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentPublicationsGetConsecutiveVersionsWithoutGapsOrDuplicates() throws Exception {
        Local local = establecimiento();
        int attempts = 4;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String body = cuerpo("General", null, videoIdAleatorio());
                Callable<Integer> call = () -> {
                    ready.countDown();
                    go.await();
                    return publicarComoAdmin(local, body).statusCode();
                };
                results.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForList("SELECT version FROM operacion.lista_defecto WHERE establecimiento_id = ? ORDER BY version",
                Integer.class, local.id())).containsExactly(1, 2, 3, 4);
    }

    @Test
    void twoEstablishmentsPublishingTheSameNewSongsInOppositeOrderNeverDeadlock() throws Exception {
        for (int ronda = 0; ronda < 4; ronda++) {
            Local a = establecimiento();
            Local b = establecimiento();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                ids.add(videoIdAleatorio());
            }
            List<String> alReves = new ArrayList<>(ids);
            java.util.Collections.reverse(alReves);
            String cuerpoA = cuerpo(null, null, ids.toArray(String[]::new));
            String cuerpoB = cuerpo(null, null, alReves.toArray(String[]::new));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch listos = new CountDownLatch(2);
            CountDownLatch salida = new CountDownLatch(1);
            try {
                Future<Integer> fa = pool.submit(() -> {
                    listos.countDown();
                    salida.await();
                    return publicarComoAdmin(a, cuerpoA).statusCode();
                });
                Future<Integer> fb = pool.submit(() -> {
                    listos.countDown();
                    salida.await();
                    return publicarComoAdmin(b, cuerpoB).statusCode();
                });
                listos.await();
                salida.countDown();
                assertThat(fa.get()).as("ascending publisher, round " + ronda).isEqualTo(201);
                assertThat(fb.get()).as("descending publisher, round " + ronda).isEqualTo(201);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aSessionKeepsItsListWhileTheNextSessionUsesTheNewVersion() {
        Local local = establecimiento();
        JsonNode v1 = json(publicarComoAdmin(local, cuerpo("General", null, videoIdAleatorio())));
        JsonNode sesion1 = json(post(SESIONES, local.token()));
        assertThat(sesion1.get("listaId").asString()).isEqualTo(v1.get("id").asString());

        JsonNode v2 = json(publicarComoAdmin(local, cuerpo("General", null, videoIdAleatorio())));

        assertThat(jdbc.queryForObject("SELECT lista_id FROM operacion.sesion WHERE id = ?::uuid", UUID.class,
                sesion1.get("id").asString()).toString()).isEqualTo(v1.get("id").asString());
        post("/api/mi-establecimiento/sesion/cierre", local.token());
        JsonNode sesion2 = json(post(SESIONES, local.token()));
        assertThat(sesion2.get("listaId").asString()).isEqualTo(v2.get("id").asString());
    }

    // ---------- read ----------

    @Test
    void theOwnerReadsTheLatestVersionWithItsSongs() {
        Local local = establecimiento();
        publicarComoAdmin(local, cuerpo("General", null, videoIdAleatorio()));
        String a = videoIdAleatorio();
        JsonNode v2 = json(publicarComoAdmin(local, cuerpo("General", "ALEATORIA", a)));

        HttpResponse<String> response = get(LISTA, local.token());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("id").asString()).isEqualTo(v2.get("id").asString());
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("canciones").get(0).get("youtubeVideoId").asString()).isEqualTo(a);
    }

    @Test
    void thereIsNoCurrentListBeforePublishingOne() {
        HttpResponse<String> response = get(LISTA, establecimiento().token());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("NO_DEFAULT_LIST");
    }

    @Test
    void theHistoryListsEveryVersionNewestFirstWithoutTheSongs() {
        Local local = establecimiento();
        publicarComoAdmin(local, cuerpo("General", null, videoIdAleatorio()));
        publicarComoAdmin(local, cuerpo("General", null, videoIdAleatorio(), videoIdAleatorio()));

        JsonNode history = json(get(HISTORIAL, local.token()));

        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(history.get(0).get("totalCanciones").asInt()).isEqualTo(2);
        assertThat(history.get(1).get("version").asInt()).isEqualTo(1);
        assertThat(history.get(0).has("canciones")).isFalse();
    }

    // ---------- validation ----------

    @Test
    void anEmptyListIsRejectedBecauseThereWouldBeNothingToPlay() {
        HttpResponse<String> response = publicarComoAdmin(establecimiento(), cuerpo("General", null));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void aMalformedVideoIdIsRejectedAndNamed() {
        HttpResponse<String> response = publicarComoAdmin(establecimiento(), cuerpo("General", null, videoIdAleatorio(), "corto"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_VIDEO_ID");
        assertThat(json(response).get("detail").asString()).contains("corto");
    }

    @Test
    void aHugeMalformedVideoIdIsNotEchoedBack() {
        String enorme = "B".repeat(2 * 1024 * 1024);

        HttpResponse<String> response = publicarComoAdmin(establecimiento(), cuerpo("General", null, enorme));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_VIDEO_ID");
        assertThat(response.body().length()).isLessThan(1024);
        assertThat(json(response).get("detail").asString()).startsWith("'" + "B".repeat(20) + "…'");
    }

    @Test
    void theSameVideoTwiceIsRejected() {
        String video = videoIdAleatorio();

        HttpResponse<String> response = publicarComoAdmin(establecimiento(), cuerpo("General", null, video, video));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("DUPLICATE_SONG");
    }

    @Test
    void anUnknownRotationPolicyIsRejected() {
        HttpResponse<String> response = publicarComoAdmin(establecimiento(), cuerpo("General", "SALTEADA", videoIdAleatorio()));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void tooManySongsAreRejected() {
        String[] ids = new String[201];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = videoIdAleatorio();
        }

        assertThat(code(publicarComoAdmin(establecimiento(), cuerpo("General", null, ids)))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void aRejectedPublicationLeavesNoTraceOfItself() {
        Local local = establecimiento();
        String huerfana = videoIdAleatorio();

        publicarComoAdmin(local, cuerpo("General", null, huerfana, "malo"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.lista_defecto WHERE establecimiento_id = ?",
                Integer.class, local.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?",
                Integer.class, huerfana)).isZero();
    }

    @Test
    void anAccountWithoutAnEstablishmentHasNoList() {
        String token = tokenSinEstablecimiento();

        for (HttpResponse<String> response : List.of(put(LISTA, token, cuerpo("General", null, videoIdAleatorio())),
                get(LISTA, token), get(HISTORIAL, token))) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(code(response)).isEqualTo("NO_ESTABLISHMENT");
        }
    }

    // ---------- events and authentication ----------

    @Test
    void publishingRecordsTheVersionedListEvent() {
        Local local = establecimiento();
        JsonNode lista = json(publicarComoAdmin(local, cuerpo("General", "ALEATORIA", videoIdAleatorio(), videoIdAleatorio())));
        String listaId = lista.get("id").asString();

        assertThat(jdbc.queryForObject("""
                SELECT ambito || '|' || agregado_tipo || '|' || agregado_version || '|' || (payload ->> 'item_count')
                       || '|' || (payload ->> 'rotation_policy') || '|' || (payload ->> 'list_id')
                FROM integracion.evento_outbox WHERE agregado_id = ?::uuid AND event_type = 'default_list.versioned'
                """, String.class, listaId)).isEqualTo("B|LISTA_DEFECTO|1|2|ALEATORIA|" + listaId);
    }

    @Test
    void everyListRouteRequiresAToken() {
        Local local = establecimiento();
        String body = cuerpo("General", null, videoIdAleatorio());

        assertThat(put(LISTA, null, body).statusCode()).isEqualTo(401);
        assertThat(get(HISTORIAL, null).statusCode()).isEqualTo(401);
        assertThat(get(LISTA, null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.lista_defecto WHERE establecimiento_id = ?",
                Integer.class, local.id())).isZero();
    }

    // ---------- the point of the module ----------

    @Test
    void aNewAccountConfiguresItsEstablishmentFromScratchAndOpensASession() {
        String slug = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String token = tokenSinEstablecimiento();
        assertThat(get("/api/mi-establecimiento", token).statusCode()).isEqualTo(404);
        assertThat(post("/api/mi-establecimiento", token,
                "{\"nombre\":\"Desde cero\",\"qrSlug\":\"" + slug + "\"}").statusCode()).isEqualTo(201);

        HttpResponse<String> withoutList = post(SESIONES, token);
        assertThat(code(withoutList)).isEqualTo("NO_DEFAULT_LIST");

        assertThat(put(LISTA, token, cuerpo(null, null, videoIdAleatorio())).statusCode()).isEqualTo(201);

        HttpResponse<String> opened = post(SESIONES, token);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        assertThat(json(get("/api/mi-establecimiento/qr", token)).get("url").asString())
                .isEqualTo(PUBLIC_BASE_URL + "/s/" + slug);
    }
}
