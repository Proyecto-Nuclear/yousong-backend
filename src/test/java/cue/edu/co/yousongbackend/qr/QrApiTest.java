package cue.edu.co.yousongbackend.qr;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import cue.edu.co.yousongbackend.support.QrSvgDecoder;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The access QR of the owner's establishment, as the admin portal consumes it. Establishments print it once,
 * so it identifies the establishment only and never changes from one session to the next.
 */
class QrApiTest extends ApiIntegrationTest {

    private static final String QR = "/api/mi-establecimiento/qr";

    private String abrirSesion(Local local) {
        HttpResponse<String> response = post("/api/mi-establecimiento/sesiones", local.token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    @Test
    void theQrPointsToTheEstablishmentAndDecodesBackToThatUrl() throws Exception {
        Local local = localListoParaAbrir();

        HttpResponse<String> response = get(QR, local.token());

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

        HttpResponse<String> response = get(QR, local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("url").asString()).isEqualTo(PUBLIC_BASE_URL + "/s/" + local.slug());
    }

    @Test
    void theQrStaysTheSameAcrossSessions() {
        Local local = localListoParaAbrir();
        String before = json(get(QR, local.token())).get("url").asString();

        String first = abrirSesion(local);
        String duringFirst = json(get(QR, local.token())).get("url").asString();
        post("/api/mi-establecimiento/sesion/cierre", local.token());
        abrirSesion(local);
        String duringSecond = json(get(QR, local.token())).get("url").asString();

        assertThat(duringFirst).isEqualTo(before);
        assertThat(duringSecond).isEqualTo(before);
        assertThat(before).doesNotContain(first);
    }

    @Test
    void anAccountWithoutAnEstablishmentHasNoQr() {
        HttpResponse<String> response = get(QR, tokenSinEstablecimiento());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NO_ESTABLISHMENT");
    }

    @Test
    void theQrRequiresAToken() {
        assertThat(get(QR, null).statusCode()).isEqualTo(401);
    }
}
