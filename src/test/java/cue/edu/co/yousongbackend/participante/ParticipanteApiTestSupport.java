package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;

/** Helpers shared by the participant tests: joining through the QR slug, opening and closing sessions. */
abstract class ParticipanteApiTestSupport extends ApiIntegrationTest {

    protected static final String POLICY_VERSION = "1.0";

    private final HttpClient http = HttpClient.newHttpClient();

    protected static String estado(String slug) {
        return "/api/public/establecimientos/" + slug;
    }

    protected static String participaciones(String slug) {
        return "/api/public/establecimientos/" + slug + "/participaciones";
    }

    protected static final String MI_SESION = "/api/participante/sesion";

    protected static String cuerpo(String canal, String politicaVersion, Boolean autorizaF1, Boolean autorizaF2) {
        StringBuilder json = new StringBuilder("{\"canal\":\"").append(canal).append("\",\"politicaVersion\":\"")
                .append(politicaVersion).append('"');
        if (autorizaF1 != null) {
            json.append(",\"autorizaF1\":").append(autorizaF1);
        }
        if (autorizaF2 != null) {
            json.append(",\"autorizaF2\":").append(autorizaF2);
        }
        return json.append('}').toString();
    }

    /** The join body of a client that remembers its device token ({@code null} behaves like a legacy client). */
    protected static String cuerpo(String canal, String politicaVersion, Boolean autorizaF1, Boolean autorizaF2,
                                   String dispositivoToken) {
        String base = cuerpo(canal, politicaVersion, autorizaF1, autorizaF2);
        if (dispositivoToken == null) {
            return base;
        }
        return base.substring(0, base.length() - 1) + ",\"dispositivoToken\":\"" + dispositivoToken + "\"}";
    }

    protected HttpResponse<String> unirse(String slug, String body) {
        return post(participaciones(slug), null, body);
    }

    /** Joins the open session of the establishment and returns the participant token. */
    protected String unirse(Local local) {
        HttpResponse<String> response = unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("token").asString();
    }

    protected UUID abrirSesion(Local local) {
        HttpResponse<String> response = post("/api/admin/establecimientos/" + local.id() + "/sesiones", adminToken());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json(response).get("id").asString());
    }

    protected void cerrarSesion(UUID sesionId) {
        HttpResponse<String> response = post("/api/admin/sesiones/" + sesionId + "/cierre", adminToken());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    /** An active establishment with an open session. */
    protected Local localAbierto() {
        Local local = localListoParaAbrir();
        abrirSesion(local);
        return local;
    }

    protected HttpResponse<String> delete(String path, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).DELETE();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return send(builder.build());
    }

    /** A request with a raw Authorization header (or none), to test malformed credentials. */
    protected HttpResponse<String> getConAutorizacion(String path, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return send(builder.build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call failed: " + request.uri(), e);
        }
    }

    protected int contar(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    protected void assertProblema(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(json(response).get("code").asString()).isEqualTo(code);
        assertThat(json(response).get("status").asInt()).isEqualTo(status);
    }

    protected void assertCredencialRechazada(HttpResponse<String> response, String code) {
        assertProblema(response, 401, code);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).startsWith("Bearer"));
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }
}
