package cue.edu.co.yousongbackend.qr;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import cue.edu.co.yousongbackend.support.QrSvgDecoder;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The access QR of the session that is currently open, as the admin portal consumes it. */
class QrApiTest extends ApiIntegrationTest {

    private static String qr(Local local) {
        return "/api/admin/establecimientos/" + local.id() + "/qr";
    }

    private String abrirSesion(Local local) {
        HttpResponse<String> response = post("/api/admin/establecimientos/" + local.id() + "/sesiones", adminToken());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    @Test
    void theQrPointsToTheOpenSessionOfTheEstablishmentAndDecodesBackToThatUrl() throws Exception {
        Local local = localListoParaAbrir();
        String sesionId = abrirSesion(local);

        HttpResponse<String> response = get(qr(local), adminToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        String expectedUrl = PUBLIC_BASE_URL + "/s/" + local.slug() + "/" + sesionId;
        assertThat(body.get("url").asString()).isEqualTo(expectedUrl);
        assertThat(body.get("sesionId").asString()).isEqualTo(sesionId);
        assertThat(body.get("svg").asString()).startsWith("<svg ");
        assertThat(QrSvgDecoder.decode(body.get("svg").asString())).isEqualTo(expectedUrl);
    }

    @Test
    void aNewSessionGetsADifferentQr() {
        Local local = localListoParaAbrir();
        String first = abrirSesion(local);
        String urlOfFirst = json(get(qr(local), adminToken())).get("url").asString();
        post("/api/admin/sesiones/" + first + "/cierre", adminToken());

        String second = abrirSesion(local);
        String urlOfSecond = json(get(qr(local), adminToken())).get("url").asString();

        assertThat(urlOfFirst).endsWith(first);
        assertThat(urlOfSecond).endsWith(second).isNotEqualTo(urlOfFirst);
    }

    @Test
    void thereIsNoQrWithoutAnOpenSession() {
        Local local = localListoParaAbrir();
        HttpResponse<String> before = get(qr(local), adminToken());
        assertThat(before.statusCode()).isEqualTo(404);
        assertThat(json(before).get("code").asString()).isEqualTo("NO_ACTIVE_SESSION");

        String sesionId = abrirSesion(local);
        post("/api/admin/sesiones/" + sesionId + "/cierre", adminToken());

        assertThat(get(qr(local), adminToken()).statusCode()).isEqualTo(404);
    }

    @Test
    void anUnknownEstablishmentIsNotFound() {
        HttpResponse<String> response = get("/api/admin/establecimientos/" + UUID.randomUUID() + "/qr", adminToken());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("ESTABLISHMENT_NOT_FOUND");
    }

    @Test
    void onlyAdministratorsSeeTheQr() {
        Local local = localListoParaAbrir();
        abrirSesion(local);

        assertThat(get(qr(local), operatorToken()).statusCode()).isEqualTo(403);
        assertThat(get(qr(local), null).statusCode()).isEqualTo(401);
    }
}
