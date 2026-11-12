package cue.edu.co.yousongbackend.qr;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import cue.edu.co.yousongbackend.support.QrSvgDecoder;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The access QR of an establishment, as the admin portal consumes it. Establishments print it once, so it
 * identifies the establishment only and never changes from one session to the next.
 */
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
    void theQrPointsToTheEstablishmentAndDecodesBackToThatUrl() throws Exception {
        Local local = localListoParaAbrir();

        HttpResponse<String> response = get(qr(local), adminToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        String expectedUrl = PUBLIC_BASE_URL + "/s/" + local.slug();
        assertThat(body.get("url").asString()).isEqualTo(expectedUrl);
        assertThat(body.has("sesionId")).isFalse();
        assertThat(body.get("svg").asString()).startsWith("<svg ");
        assertThat(QrSvgDecoder.decode(body.get("svg").asString())).isEqualTo(expectedUrl);
    }

    @Test
    void theQrIsAvailableWithoutAnOpenSession() {
        Local local = localListoParaAbrir();

        HttpResponse<String> response = get(qr(local), adminToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("url").asString()).isEqualTo(PUBLIC_BASE_URL + "/s/" + local.slug());
    }

    @Test
    void theQrStaysTheSameAcrossSessions() {
        Local local = localListoParaAbrir();
        String before = json(get(qr(local), adminToken())).get("url").asString();

        String first = abrirSesion(local);
        String duringFirst = json(get(qr(local), adminToken())).get("url").asString();
        post("/api/admin/sesiones/" + first + "/cierre", adminToken());
        abrirSesion(local);
        String duringSecond = json(get(qr(local), adminToken())).get("url").asString();

        assertThat(duringFirst).isEqualTo(before);
        assertThat(duringSecond).isEqualTo(before);
        assertThat(before).doesNotContain(first);
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

        assertThat(get(qr(local), operatorToken()).statusCode()).isEqualTo(403);
        assertThat(get(qr(local), null).statusCode()).isEqualTo(401);
    }
}
