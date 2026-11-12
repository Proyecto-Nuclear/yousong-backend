package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** What a client sees right after scanning the QR, before joining: anonymous, no internal data. */
class EstablecimientoPublicoApiTest extends ParticipanteApiTestSupport {

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    @Test
    void anOpenEstablishmentInvitesToParticipateWithoutExposingInternalIds() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);

        HttpResponse<String> response = get(estado(local.slug()), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("nombre").asString()).isEqualTo("Local de prueba");
        assertThat(body.get("qrSlug").asString()).isEqualTo(local.slug());
        assertThat(body.get("servicio").asString()).isEqualTo("ABIERTO");
        assertThat(body.get("puedeParticipar").asBoolean()).isTrue();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("nombre", "qrSlug", "servicio", "puedeParticipar");
        assertThat(response.body()).doesNotContain(local.id().toString()).doesNotContain(sesionId.toString())
                .doesNotContainPattern(UUID_PATTERN);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void withoutASessionTheServiceIsClosed() {
        Local local = localListoParaAbrir();

        JsonNode body = json(get(estado(local.slug()), null));

        assertThat(body.get("servicio").asString()).isEqualTo("CERRADO");
        assertThat(body.get("puedeParticipar").asBoolean()).isFalse();
    }

    @Test
    void aPausedSessionIsReportedAsPaused() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        jdbc.update("UPDATE operacion.sesion SET estado = 'PAUSADA' WHERE id = ?", sesionId);

        JsonNode body = json(get(estado(local.slug()), null));

        assertThat(body.get("servicio").asString()).isEqualTo("PAUSADO");
        assertThat(body.get("puedeParticipar").asBoolean()).isFalse();
    }

    @Test
    void anInactiveEstablishmentIsClosedEvenWithASession() {
        Local local = localListoParaAbrir();
        abrirSesion(local);
        jdbc.update("UPDATE operacion.establecimiento SET activo = false WHERE id = ?", local.id());

        JsonNode body = json(get(estado(local.slug()), null));

        assertThat(body.get("servicio").asString()).isEqualTo("CERRADO");
        assertThat(body.get("puedeParticipar").asBoolean()).isFalse();
    }

    @Test
    void anUnknownSlugIsNotFound() {
        assertProblema(get(estado("no-such-place-" + UUID.randomUUID().toString().substring(0, 6)), null),
                404, "ESTABLISHMENT_NOT_FOUND");
    }

    @Test
    void thePrivacyPolicyListsBothPurposesWithTheCurrentVersion() {
        HttpResponse<String> response = get("/api/public/politica", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("version").asString()).isEqualTo(POLICY_VERSION);
        JsonNode finalidades = body.get("finalidades");
        assertThat(finalidades).hasSize(2);
        assertThat(finalidades.get(0).get("codigo").asString()).isEqualTo("F1");
        assertThat(finalidades.get(0).get("descripcion").asString()).isNotBlank();
        assertThat(finalidades.get(1).get("codigo").asString()).isEqualTo("F2");
        assertThat(finalidades.get(1).get("descripcion").asString()).isNotBlank();
    }
}
