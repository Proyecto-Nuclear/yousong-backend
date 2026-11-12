package cue.edu.co.yousongbackend.establecimiento;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Ownership is the authorization: every owner route resolves the establishment from the token subject, so an
 * owner can only ever reach their own establishment. No route accepts an establishment or session id.
 */
class PropiedadEstablecimientoApiTest extends ColaApiTestSupport {

    private static final String MI = "/api/mi-establecimiento";

    /** Everything an owner action could change about an establishment, read straight from the database. */
    private List<Map<String, Object>> huella(Local local) {
        return jdbc.queryForList("""
                SELECT e.nombre, e.qr_slug, e.zona_horaria, e.activo, e.propietario_sub,
                       (SELECT count(*) FROM operacion.lista_defecto l WHERE l.establecimiento_id = e.id) AS listas,
                       (SELECT string_agg(s.id || ':' || s.estado || ':' || s.cola_version, ',' ORDER BY s.id)
                          FROM operacion.sesion s WHERE s.establecimiento_id = e.id) AS sesiones,
                       (SELECT count(*) FROM operacion.reproduccion r JOIN operacion.sesion s ON s.id = r.sesion_id
                          WHERE s.establecimiento_id = e.id) AS reproducciones,
                       (SELECT count(*) FROM integracion.evento_outbox o WHERE o.establecimiento_id = e.id) AS eventos
                FROM operacion.establecimiento e WHERE e.id = ?
                """, local.id());
    }

    @Test
    void luisOnlyEverActsOnHisOwnEstablishmentAndAnasIsUntouched() {
        Local ana = localAbierto();
        UUID sesionAna = sesionDe(ana);
        solicitada(unirse(ana), videoGuardado());
        Local luis = localListoParaAbrir();
        String tokenLuis = luis.token();
        List<Map<String, Object>> antes = huella(ana);

        assertThat(json(get(MI, tokenLuis)).get("id").asString()).isEqualTo(luis.id().toString());
        assertThat(json(get(MI + "?establecimientoId=" + ana.id(), tokenLuis)).get("id").asString())
                .isEqualTo(luis.id().toString());
        assertThat(json(patch(MI, tokenLuis, "{\"nombre\":\"Bar de Luis\",\"id\":\"" + ana.id() + "\"}"))
                .get("id").asString()).isEqualTo(luis.id().toString());
        assertThat(json(get(MI + "/qr", tokenLuis)).get("url").asString()).endsWith("/s/" + luis.slug());
        HttpResponse<String> abierta = post(MI + "/sesiones", tokenLuis, "{\"establecimientoId\":\"" + ana.id() + "\"}");
        assertThat(abierta.statusCode()).as(abierta.body()).isEqualTo(201);
        assertThat(json(abierta).get("establecimientoId").asString()).isEqualTo(luis.id().toString());
        assertThat(json(get(MI + "/sesion-actual", tokenLuis)).get("establecimientoId").asString())
                .isEqualTo(luis.id().toString());
        assertThat(put(MI + "/lista-defecto", tokenLuis, "{\"canciones\":[\"" + videoIdAleatorio() + "\"]}")
                .statusCode()).isEqualTo(201);
        assertThat(json(get(MI + "/listas-defecto", tokenLuis))).hasSize(2);
        assertProblema(siguiente(tokenLuis), 409, "QUEUE_EMPTY");
        assertProblema(fin("COMPLETADA", tokenLuis), 409, "NO_ACTIVE_PLAYBACK");
        assertThat(get(MI + "/catalogo/busqueda?q=juanes", tokenLuis).statusCode()).isEqualTo(503);
        assertThat(post(CIERRE, tokenLuis).statusCode()).isEqualTo(200);

        assertThat(huella(ana)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?", String.class, sesionAna))
                .isEqualTo("ABIERTA");
        assertThat(jdbc.queryForObject("SELECT nombre FROM operacion.establecimiento WHERE id = ?", String.class,
                luis.id())).isEqualTo("Bar de Luis");
    }

    @Test
    void theOldIdBasedRoutesAreGoneForEveryToken() {
        Local ana = localAbierto();
        UUID sesionAna = sesionDe(ana);
        List<Map<String, Object>> antes = huella(ana);

        for (String token : new String[] {ana.token(), localListoParaAbrir().token(), tokenSinEstablecimiento()}) {
            for (HttpResponse<String> response : List.of(
                    get("/api/staff/establecimientos/" + ana.id(), token),
                    get("/api/staff/establecimientos", token),
                    patch("/api/admin/establecimientos/" + ana.id(), token, "{\"nombre\":\"x\"}"),
                    post("/api/admin/establecimientos", token, "{\"nombre\":\"x\"}"),
                    get("/api/admin/establecimientos/" + ana.id() + "/qr", token),
                    post("/api/admin/establecimientos/" + ana.id() + "/sesiones", token),
                    post("/api/admin/sesiones/" + sesionAna + "/cierre", token),
                    get("/api/staff/establecimientos/" + ana.id() + "/sesion-actual", token),
                    put("/api/admin/establecimientos/" + ana.id() + "/lista-defecto", token, "{\"canciones\":[]}"),
                    get("/api/staff/establecimientos/" + ana.id() + "/lista-defecto", token),
                    get("/api/admin/establecimientos/" + ana.id() + "/listas-defecto", token),
                    get("/api/staff/catalogo/busqueda?q=juanes", token),
                    get("/api/staff/catalogo/sugerencias?q=juanes", token),
                    post("/api/staff/sesiones/" + sesionAna + "/reproduccion/siguiente", token),
                    post("/api/mi-establecimiento/sesiones/" + sesionAna + "/cierre", token))) {
                assertThat(response.statusCode()).as(response.uri() + " " + response.body()).isIn(401, 403, 404);
            }
        }
        assertThat(huella(ana)).isEqualTo(antes);
    }

    @Test
    void anAccountWithoutAnEstablishmentGetsNoEstablishmentEverywhereButCreation() {
        String token = tokenSinEstablecimiento();

        for (HttpResponse<String> response : List.of(
                get(MI, token),
                patch(MI, token, "{\"nombre\":\"x\"}"),
                get(MI + "/qr", token),
                post(MI + "/sesiones", token),
                get(MI + "/sesion-actual", token),
                post(CIERRE, token),
                put(MI + "/lista-defecto", token, "{\"canciones\":[\"" + videoIdAleatorio() + "\"]}"),
                get(MI + "/lista-defecto", token),
                get(MI + "/listas-defecto", token),
                get(MI + "/catalogo/busqueda?q=juanes", token),
                get(MI + "/catalogo/sugerencias?q=juanes", token),
                siguiente(token),
                fin("COMPLETADA", token))) {
            assertProblema(response, 404, "NO_ESTABLISHMENT");
        }
    }

    @Test
    void establishmentsWithoutAnOwnerAreUnreachableAndCannotBeClaimed() {
        Local huerfano = establecimientoSinPropietario();
        String token = tokenSinEstablecimiento();

        assertProblema(get(MI, token), 404, "NO_ESTABLISHMENT");
        HttpResponse<String> creado = post(MI, token, "{\"nombre\":\"Nuevo\"}");

        assertThat(creado.statusCode()).isEqualTo(201);
        assertThat(json(creado).get("id").asString()).isNotEqualTo(huerfano.id().toString());
        assertThat(jdbc.queryForObject("SELECT propietario_sub FROM operacion.establecimiento WHERE id = ?",
                String.class, huerfano.id())).isNull();
    }

    @Test
    void onlyValidAccessTokensOfThisApplicationReachTheOwnerRoutes() {
        Local ana = localListoParaAbrir();
        String sub = ana.propietario();
        Instant enUnaHora = Instant.now().plusSeconds(3600);

        assertThat(get(MI, token(sub, "at", AUDIENCE, ISSUER, enUnaHora)).statusCode()).isEqualTo(200);
        for (String invalido : new String[] {
                token(sub, "idt", AUDIENCE, ISSUER, enUnaHora),
                token(sub, null, AUDIENCE, ISSUER, enUnaHora),
                token(sub, "at", UUID.randomUUID().toString(), ISSUER, enUnaHora),
                token(sub, "at", AUDIENCE, "evil-issuer", enUnaHora),
                token(sub, "at", AUDIENCE, ISSUER, Instant.now().minusSeconds(3600)),
                sinFirma(sub),
                "not-a-jwt"}) {
            assertThat(get(MI, invalido).statusCode()).isEqualTo(401);
            assertThat(post(MI + "/sesiones", invalido).statusCode()).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.sesion WHERE establecimiento_id = ?",
                Integer.class, ana.id())).isZero();
    }

    @Test
    void anAccessTokenWithoutASubjectIsRejected() {
        HttpResponse<String> response = get(MI, token(null, "at", AUDIENCE, ISSUER, Instant.now().plusSeconds(3600)));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private static String sinFirma(String sub) {
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String claims = b64.encodeToString(("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + sub
                + "\",\"tty\":\"at\",\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}").getBytes());
        return header + "." + claims + ".";
    }

    @Test
    void theOwnerSubjectIsNeverExposed() {
        Local ana = localAbierto();

        for (HttpResponse<String> response : List.of(get(MI, ana.token()), get(MI + "/qr", ana.token()),
                get(MI + "/sesion-actual", ana.token()), get("/api/public/establecimientos/" + ana.slug(), null))) {
            assertThat(response.body()).doesNotContain(ana.propietario()).doesNotContain("propietario");
        }
        JsonNode body = json(get(MI, ana.token()));
        assertThat(body.has("propietarioSub")).isFalse();
    }
}
