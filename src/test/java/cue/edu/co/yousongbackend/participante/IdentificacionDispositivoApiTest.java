package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.acceso.AccesoParticipantes;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Device identification: one phone, one participation per session, without registration or personal data.
 * The client keeps an opaque device token; the server keeps only its hash.
 */
class IdentificacionDispositivoApiTest extends ParticipanteApiTestSupport {

    private static final String FORMATO_DISPOSITIVO = "dv_[A-Za-z0-9_-]{43}";

    @Autowired
    private AccesoParticipantes accesos;

    /** A join that must succeed; returns its body. */
    private JsonNode unirseOk(String slug, String dispositivoToken) {
        return unirseOk(slug, dispositivoToken, false);
    }

    private JsonNode unirseOk(String slug, String dispositivoToken, boolean autorizaF2) {
        HttpResponse<String> response = unirse(slug, cuerpo("WEB", POLICY_VERSION, true, autorizaF2, dispositivoToken));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response);
    }

    private static String sha256(String valor) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.US_ASCII)));
    }

    private UUID dispositivoDe(String dispositivoToken) throws Exception {
        return jdbc.queryForObject("SELECT id FROM operacion.dispositivo WHERE credencial_hash = ?", UUID.class,
                sha256(dispositivoToken));
    }

    private int participacionesDe(UUID sesionId) {
        return contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesionId);
    }

    private int dispositivos() {
        return contar("SELECT count(*) FROM operacion.dispositivo");
    }

    // ---------- first join ----------

    @Test
    void theFirstJoinReturnsADeviceTokenAndStoresOnlyItsHash() throws Exception {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);

        JsonNode body = unirseOk(local.slug(), null);

        String dispositivoToken = body.get("dispositivoToken").asString();
        assertThat(dispositivoToken).matches(FORMATO_DISPOSITIVO);
        UUID dispositivoId = jdbc.queryForObject(
                "SELECT dispositivo_id FROM operacion.participacion WHERE sesion_id = ?", UUID.class, sesionId);
        String hash = jdbc.queryForObject("SELECT credencial_hash FROM operacion.dispositivo WHERE id = ?",
                String.class, dispositivoId);
        assertThat(hash).isEqualTo(sha256(dispositivoToken)).isNotEqualTo(dispositivoToken);

        // Every text-like column of every application table: the raw device token appears nowhere.
        List<Map<String, Object>> columnas = jdbc.queryForList("SELECT c.table_schema, c.table_name, c.column_name "
                + "FROM information_schema.columns c JOIN information_schema.tables t "
                + "ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE' "
                + "WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema') "
                + "AND c.data_type IN ('character varying', 'character', 'text', 'jsonb', 'json')");
        for (Map<String, Object> columna : columnas) {
            String sql = "SELECT count(*) FROM \"" + columna.get("table_schema") + "\".\"" + columna.get("table_name")
                    + "\" WHERE \"" + columna.get("column_name") + "\"::text LIKE ?";
            assertThat(contar(sql, "%" + dispositivoToken.substring(3) + "%"))
                    .as("%s.%s.%s", columna.get("table_schema"), columna.get("table_name"), columna.get("column_name"))
                    .isZero();
        }
    }

    @Test
    void aLegacyClientWithoutDeviceTokenGetsANewDeviceOnEveryJoin() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);

        JsonNode primero = unirseOk(local.slug(), null);
        JsonNode segundo = unirseOk(local.slug(), null);

        assertThat(participacionesDe(sesionId)).isEqualTo(2);
        assertThat(contar("SELECT count(DISTINCT dispositivo_id) FROM operacion.participacion WHERE sesion_id = ?",
                sesionId)).isEqualTo(2);
        assertThat(primero.get("seudonimo").asString()).isNotEqualTo(segundo.get("seudonimo").asString());
        assertThat(primero.get("dispositivoToken").asString())
                .isNotEqualTo(segundo.get("dispositivoToken").asString());
    }

    // ---------- rejoining the same session ----------

    @Test
    void rejoiningTheSameSessionReturnsTheSameParticipationAndRotatesTheCredential() throws Exception {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        JsonNode primero = unirseOk(local.slug(), null);
        String dispositivoToken = primero.get("dispositivoToken").asString();
        int antes = dispositivos();

        JsonNode segundo = unirseOk(local.slug(), dispositivoToken);

        assertThat(segundo.get("seudonimo").asString()).isEqualTo(primero.get("seudonimo").asString());
        assertThat(segundo.has("dispositivoToken")).as(segundo.toString()).isFalse();
        assertThat(segundo.get("token").asString()).matches("pt_[A-Za-z0-9_-]{43}")
                .isNotEqualTo(primero.get("token").asString());
        assertThat(segundo.get("servicio").asString()).isEqualTo("ABIERTO");
        assertThat(segundo.get("establecimiento").get("nombre").asString()).isEqualTo("Local de prueba");
        assertThat(participacionesDe(sesionId)).isEqualTo(1);
        assertThat(dispositivos()).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM operacion.consentimiento c JOIN operacion.participacion p "
                + "ON p.id = c.participacion_id WHERE p.sesion_id = ? AND c.finalidad = 'F1'", sesionId)).isEqualTo(1);

        assertCredencialRechazada(get(MI_SESION, primero.get("token").asString()), "INVALID_CREDENTIAL");
        HttpResponse<String> mia = get(MI_SESION, segundo.get("token").asString());
        assertThat(mia.statusCode()).as(mia.body()).isEqualTo(200);
        assertThat(json(mia).get("seudonimo").asString()).isEqualTo(primero.get("seudonimo").asString());
        assertThat(contar("SELECT count(*) FROM operacion.sesion_acceso WHERE dispositivo_id = ? "
                + "AND revocada_at IS NULL", dispositivoDe(dispositivoToken))).isEqualTo(1);
    }

    @Test
    void rejoiningAfterLeavingReturnsTheSameParticipation() throws Exception {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        JsonNode primero = unirseOk(local.slug(), null);
        String dispositivoToken = primero.get("dispositivoToken").asString();
        assertThat(delete(MI_SESION, primero.get("token").asString()).statusCode()).isEqualTo(204);

        JsonNode segundo = unirseOk(local.slug(), dispositivoToken);

        assertThat(segundo.get("seudonimo").asString()).isEqualTo(primero.get("seudonimo").asString());
        assertThat(segundo.has("dispositivoToken")).isFalse();
        assertThat(participacionesDe(sesionId)).isEqualTo(1);
        assertCredencialRechazada(get(MI_SESION, primero.get("token").asString()), "INVALID_CREDENTIAL");
        assertThat(get(MI_SESION, segundo.get("token").asString()).statusCode()).isEqualTo(200);
        assertThat(contar("SELECT count(*) FROM operacion.dispositivo WHERE id = ? AND revocado_at IS NULL",
                dispositivoDe(dispositivoToken))).isEqualTo(1);
    }

    @Test
    void theOptionalPurposeIsAddedOnRejoinButNeverDuplicated() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        String dispositivoToken = unirseOk(local.slug(), null, false).get("dispositivoToken").asString();

        unirseOk(local.slug(), dispositivoToken, true);
        unirseOk(local.slug(), dispositivoToken, true);
        unirseOk(local.slug(), dispositivoToken, false);

        assertThat(contar("SELECT count(*) FROM operacion.consentimiento c JOIN operacion.participacion p "
                + "ON p.id = c.participacion_id WHERE p.sesion_id = ? AND c.finalidad = 'F2'", sesionId)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM operacion.consentimiento c JOIN operacion.participacion p "
                + "ON p.id = c.participacion_id WHERE p.sesion_id = ? AND c.finalidad = 'F1'", sesionId)).isEqualTo(1);
    }

    @Test
    void theRequiredConsentAndThePolicyVersionAreStillCheckedOnRejoin() {
        Local local = localListoParaAbrir();
        abrirSesion(local);
        JsonNode primero = unirseOk(local.slug(), null);
        String dispositivoToken = primero.get("dispositivoToken").asString();
        int credenciales = contar("SELECT count(*) FROM operacion.sesion_acceso");

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, false, false, dispositivoToken)),
                400, "CONSENT_REQUIRED");
        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, null, false, dispositivoToken)),
                400, "CONSENT_REQUIRED");
        assertProblema(unirse(local.slug(), cuerpo("WEB", "0.9", true, false, dispositivoToken)),
                409, "POLICY_VERSION_MISMATCH");

        assertThat(contar("SELECT count(*) FROM operacion.sesion_acceso")).isEqualTo(credenciales);
        assertThat(get(MI_SESION, primero.get("token").asString()).statusCode()).isEqualTo(200);
    }

    // ---------- another night ----------

    @Test
    void aNewSessionGetsANewParticipationForTheSameDevice() throws Exception {
        Local local = localListoParaAbrir();
        UUID primera = abrirSesion(local);
        JsonNode anoche = unirseOk(local.slug(), null);
        String dispositivoToken = anoche.get("dispositivoToken").asString();
        cerrarSesion(primera);
        UUID segunda = abrirSesion(local);

        JsonNode hoy = unirseOk(local.slug(), dispositivoToken);

        assertThat(hoy.has("dispositivoToken")).isFalse();
        UUID dispositivoId = dispositivoDe(dispositivoToken);
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM operacion.participacion WHERE sesion_id = ?",
                UUID.class, primera)).isEqualTo(dispositivoId);
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM operacion.participacion WHERE sesion_id = ?",
                UUID.class, segunda)).isEqualTo(dispositivoId);
        assertThat(participacionesDe(segunda)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM operacion.consentimiento c JOIN operacion.participacion p "
                + "ON p.id = c.participacion_id WHERE p.sesion_id = ? AND c.finalidad = 'F1'", segunda)).isEqualTo(1);

        assertCredencialRechazada(get(MI_SESION, anoche.get("token").asString()), "SESSION_ENDED");
        HttpResponse<String> mia = get(MI_SESION, hoy.get("token").asString());
        assertThat(mia.statusCode()).as(mia.body()).isEqualTo(200);
        assertThat(json(mia).get("seudonimo").asString()).isEqualTo(hoy.get("seudonimo").asString());
    }

    @Test
    void aDeviceIsNotTiedToAnEstablishment() throws Exception {
        Local primero = localAbierto();
        Local segundo = localListoParaAbrir();
        UUID sesionSegundo = abrirSesion(segundo);
        String dispositivoToken = unirseOk(primero.slug(), null).get("dispositivoToken").asString();

        JsonNode body = unirseOk(segundo.slug(), dispositivoToken);

        assertThat(body.has("dispositivoToken")).isFalse();
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM operacion.participacion WHERE sesion_id = ?",
                UUID.class, sesionSegundo)).isEqualTo(dispositivoDe(dispositivoToken));
        HttpResponse<String> mia = get(MI_SESION, body.get("token").asString());
        assertThat(mia.statusCode()).as(mia.body()).isEqualTo(200);
        assertThat(json(mia).get("establecimiento").get("nombre").asString()).isEqualTo("Local de prueba");
        assertThat(json(mia).get("seudonimo").asString()).isEqualTo(body.get("seudonimo").asString());
    }

    // ---------- tokens that do not identify a device ----------

    @Test
    void anUnusableDeviceTokenSilentlyMintsANewDevice() throws Exception {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        JsonNode original = unirseOk(local.slug(), null);
        String expirado = unirseOk(local.slug(), null).get("dispositivoToken").asString();
        jdbc.update("UPDATE operacion.dispositivo SET expira_at = now() - interval '1 second' WHERE id = ?",
                dispositivoDe(expirado));
        String revocado = unirseOk(local.slug(), null).get("dispositivoToken").asString();
        accesos.revocarDispositivo(dispositivoDe(revocado), Instant.now());
        List<String> inservibles = List.of("garbage", "dv_" + "A".repeat(43), original.get("token").asString(),
                expirado, revocado);
        int participaciones = participacionesDe(sesionId);

        for (String inservible : inservibles) {
            int antes = dispositivos();
            HttpResponse<String> response = unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false, inservible));

            assertThat(response.statusCode()).as(inservible + ": " + response.body()).isEqualTo(201);
            JsonNode body = json(response);
            assertThat(body.get("dispositivoToken").asString()).matches(FORMATO_DISPOSITIVO).isNotEqualTo(inservible);
            assertThat(body.get("seudonimo").asString()).isNotEqualTo(original.get("seudonimo").asString());
            assertThat(dispositivos()).isEqualTo(antes + 1);
            assertThat(response.body()).doesNotContain(inservible);
        }
        assertThat(participacionesDe(sesionId)).isEqualTo(participaciones + inservibles.size());
    }

    @Test
    void identifyingADeviceRenewsItsExpiry() throws Exception {
        Local local = localAbierto();
        String dispositivoToken = unirseOk(local.slug(), null).get("dispositivoToken").asString();
        UUID dispositivoId = dispositivoDe(dispositivoToken);
        jdbc.update("UPDATE operacion.dispositivo SET expira_at = now() + interval '1 day', "
                + "ultimo_acceso_at = now() - interval '5 days' WHERE id = ?", dispositivoId);
        Instant antes = Instant.now();

        unirseOk(local.slug(), dispositivoToken);

        Map<String, Object> dispositivo = jdbc.queryForMap(
                "SELECT expira_at, ultimo_acceso_at FROM operacion.dispositivo WHERE id = ?", dispositivoId);
        Instant expira = ((Timestamp) dispositivo.get("expira_at")).toInstant();
        Instant ultimoAcceso = ((Timestamp) dispositivo.get("ultimo_acceso_at")).toInstant();
        assertThat(Duration.between(antes, expira)).isBetween(Duration.ofDays(29), Duration.ofDays(31));
        assertThat(Duration.between(antes, ultimoAcceso).abs()).isLessThan(Duration.ofMinutes(1));
    }

    // ---------- concurrency ----------

    @Test
    void concurrentJoinsOfOneDeviceYieldOneParticipation() throws Exception {
        Local primero = localAbierto();
        Local segundo = localListoParaAbrir();
        UUID sesionId = abrirSesion(segundo);
        String dispositivoToken = unirseOk(primero.slug(), null).get("dispositivoToken").asString();
        int intentos = 8;
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(intentos);
        List<HttpResponse<String>> respuestas = new ArrayList<>();
        try {
            List<Future<HttpResponse<String>>> uniones = new ArrayList<>();
            for (int i = 0; i < intentos; i++) {
                uniones.add(pool.submit(() -> {
                    salida.await();
                    return unirse(segundo.slug(), cuerpo("WEB", POLICY_VERSION, true, false, dispositivoToken));
                }));
            }
            salida.countDown();
            for (Future<HttpResponse<String>> union : uniones) {
                respuestas.add(union.get());
            }
        } finally {
            pool.shutdownNow();
        }

        String seudonimo = null;
        for (HttpResponse<String> response : respuestas) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(json(response).has("dispositivoToken")).isFalse();
            String actual = json(response).get("seudonimo").asString();
            if (seudonimo == null) {
                seudonimo = actual;
            }
            assertThat(actual).isEqualTo(seudonimo);
        }
        assertThat(participacionesDe(sesionId)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM operacion.sesion_acceso a JOIN operacion.participacion p "
                + "ON p.id = a.participacion_id WHERE p.sesion_id = ? AND a.revocada_at IS NULL", sesionId)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM operacion.consentimiento c JOIN operacion.participacion p "
                + "ON p.id = c.participacion_id WHERE p.sesion_id = ?", sesionId)).isEqualTo(1);
    }
}
