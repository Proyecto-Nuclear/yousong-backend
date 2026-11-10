package cue.edu.co.yousongbackend.establecimiento;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Configuring establishments: create, read and update, with real security and PostgreSQL. */
class EstablecimientoApiTest extends ApiIntegrationTest {

    private static final String ADMIN = "/api/admin/establecimientos";
    private static final String STAFF = "/api/staff/establecimientos";

    private static String unico() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private HttpResponse<String> crear(String json) {
        return post(ADMIN, adminToken(), json);
    }

    private String crearOk(String nombre) {
        HttpResponse<String> response = crear("{\"nombre\":\"" + nombre + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    // ---------- create ----------

    @Test
    void administratorCreatesAnEstablishmentWithAGeneratedSlug() {
        String sufijo = unico();

        HttpResponse<String> response = crear("{\"nombre\":\"Taberna Él Rincón " + sufijo + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.get("qrSlug").asString()).isEqualTo("taberna-el-rincon-" + sufijo);
        assertThat(body.get("zonaHoraria").asString()).isEqualTo("America/Bogota");
        assertThat(body.get("activo").asBoolean()).isTrue();
        assertThat(response.headers().firstValue("Location"))
                .contains("/api/staff/establecimientos/" + body.get("id").asString());
        assertThat(jdbc.queryForObject("SELECT qr_slug FROM operacion.establecimiento WHERE id = ?::uuid",
                String.class, body.get("id").asString())).isEqualTo("taberna-el-rincon-" + sufijo);
    }

    @Test
    void anExplicitSlugAndZoneAreKept() {
        String slug = "mi-local-" + unico();

        JsonNode body = json(crear("{\"nombre\":\"Mi Local\",\"qrSlug\":\"" + slug + "\",\"zonaHoraria\":\"America/Mexico_City\"}"));

        assertThat(body.get("qrSlug").asString()).isEqualTo(slug);
        assertThat(body.get("zonaHoraria").asString()).isEqualTo("America/Mexico_City");
    }

    @Test
    void anExplicitSlugThatIsTakenIsRejected() {
        String slug = "ocupado-" + unico();
        assertThat(crear("{\"nombre\":\"Uno\",\"qrSlug\":\"" + slug + "\"}").statusCode()).isEqualTo(201);

        HttpResponse<String> second = crear("{\"nombre\":\"Dos\",\"qrSlug\":\"" + slug + "\"}");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(code(second)).isEqualTo("SLUG_TAKEN");
    }

    @Test
    void aGeneratedSlugThatIsTakenGetsADisambiguatingSuffix() {
        String nombre = "Bar Repetido " + unico();

        JsonNode first = json(crear("{\"nombre\":\"" + nombre + "\"}"));
        JsonNode second = json(crear("{\"nombre\":\"" + nombre + "\"}"));

        assertThat(second.get("qrSlug").asString()).isNotEqualTo(first.get("qrSlug").asString());
        assertThat(second.get("qrSlug").asString()).startsWith(first.get("qrSlug").asString());
    }

    @Test
    void anUnknownTimeZoneIsRejected() {
        HttpResponse<String> response = crear("{\"nombre\":\"Zona\",\"zonaHoraria\":\"Mars/Olympus\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_TIME_ZONE");
    }

    @Test
    void invalidFieldsAreReportedTogetherWithTheirNames() {
        HttpResponse<String> response = crear("{\"nombre\":\"   \",\"qrSlug\":\"Mi Local!\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response);
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("errors").toString()).contains("nombre").contains("qrSlug");
    }

    @Test
    void aTooLongNameIsRejected() {
        assertThat(code(crear("{\"nombre\":\"" + "x".repeat(121) + "\"}"))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void aMalformedBodyIsABadRequestNotAServerError() {
        HttpResponse<String> response = crear("{not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_BODY");
    }

    @Test
    void creatingRecordsAConfigurationEventInTheSameTransaction() {
        String id = crearOk("Evento " + unico());

        assertThat(jdbc.queryForObject("""
                SELECT ambito || '|' || agregado_tipo || '|' || agregado_version || '|' || (payload ->> 'entity_type')
                       || '|' || (payload ->> 'entity_ref')
                FROM integracion.evento_outbox WHERE agregado_id = ?::uuid AND event_type = 'configuration.changed'
                """, String.class, id)).isEqualTo("O|ESTABLECIMIENTO|1|ESTABLECIMIENTO|" + id);
    }

    @Test
    void onlyAdministratorsCreateEstablishments() {
        String nombre = "{\"nombre\":\"Sin permiso " + unico() + "\"}";

        assertThat(post(ADMIN, operatorToken(), nombre).statusCode()).isEqualTo(403);
        assertThat(post(ADMIN, null, nombre).statusCode()).isEqualTo(401);
    }

    // ---------- read ----------

    @Test
    void staffReadsOneEstablishmentAndTheList() {
        String id = crearOk("Lectura " + unico());

        for (String token : java.util.List.of(operatorToken(), adminToken())) {
            HttpResponse<String> one = get(STAFF + "/" + id, token);
            assertThat(one.statusCode()).isEqualTo(200);
            assertThat(json(one).get("id").asString()).isEqualTo(id);

            HttpResponse<String> all = get(STAFF, token);
            assertThat(all.statusCode()).isEqualTo(200);
            assertThat(all.body()).contains(id);
        }
    }

    @Test
    void anUnknownEstablishmentIsNotFoundAndABadIdIsABadRequest() {
        HttpResponse<String> unknown = get(STAFF + "/" + UUID.randomUUID(), operatorToken());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("ESTABLISHMENT_NOT_FOUND");

        HttpResponse<String> malformed = get(STAFF + "/not-a-uuid", operatorToken());
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(code(malformed)).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    void readingRequiresAuthentication() {
        assertThat(get(STAFF, null).statusCode()).isEqualTo(401);
    }

    // ---------- update ----------

    @Test
    void administratorUpdatesNameZoneAndStatusButNeverTheSlug() {
        String id = crearOk("Antes " + unico());
        String slugAntes = jdbc.queryForObject("SELECT qr_slug FROM operacion.establecimiento WHERE id = ?::uuid",
                String.class, id);

        HttpResponse<String> response = patch(ADMIN + "/" + id, adminToken(),
                "{\"nombre\":\"Despues\",\"zonaHoraria\":\"Europe/Madrid\",\"activo\":false,\"qrSlug\":\"otro-slug\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("nombre").asString()).isEqualTo("Despues");
        assertThat(body.get("zonaHoraria").asString()).isEqualTo("Europe/Madrid");
        assertThat(body.get("activo").asBoolean()).isFalse();
        assertThat(body.get("qrSlug").asString()).isEqualTo(slugAntes); // printed QR codes must keep working
    }

    @Test
    void aPartialUpdateChangesOnlyWhatWasSent() {
        String id = crearOk("Parcial " + unico());

        JsonNode body = json(patch(ADMIN + "/" + id, adminToken(), "{\"nombre\":\"Solo nombre\"}"));

        assertThat(body.get("nombre").asString()).isEqualTo("Solo nombre");
        assertThat(body.get("zonaHoraria").asString()).isEqualTo("America/Bogota");
        assertThat(body.get("activo").asBoolean()).isTrue();
    }

    @Test
    void anEmptyUpdateIsRejected() {
        String id = crearOk("Vacio " + unico());

        HttpResponse<String> response = patch(ADMIN + "/" + id, adminToken(), "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("EMPTY_UPDATE");
    }

    @Test
    void anInvalidUpdateChangesNothing() {
        String id = crearOk("Intacto " + unico());

        assertThat(patch(ADMIN + "/" + id, adminToken(), "{\"nombre\":\"Nuevo\",\"zonaHoraria\":\"Nope/Nope\"}")
                .statusCode()).isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT nombre FROM operacion.establecimiento WHERE id = ?::uuid",
                String.class, id)).startsWith("Intacto");
    }

    @Test
    void eachUpdateRecordsTheNextConfigurationEventVersion() {
        String id = crearOk("Versiones " + unico());
        patch(ADMIN + "/" + id, adminToken(), "{\"nombre\":\"Dos\"}");
        patch(ADMIN + "/" + id, adminToken(), "{\"zonaHoraria\":\"Europe/Madrid\"}");

        assertThat(jdbc.queryForList("""
                SELECT agregado_version FROM integracion.evento_outbox
                WHERE agregado_id = ?::uuid AND event_type = 'configuration.changed' ORDER BY agregado_version
                """, Long.class, id)).containsExactly(1L, 2L, 3L);
        assertThat(jdbc.queryForObject("""
                SELECT payload -> 'field_codes' FROM integracion.evento_outbox
                WHERE agregado_id = ?::uuid AND agregado_version = 2
                """, String.class, id)).contains("nombre").doesNotContain("zona_horaria");
    }

    @Test
    void anEstablishmentWithASessionInOperationCannotBeDeactivated() {
        Local local = localListoParaAbrir();
        String sesion = json(post("/api/admin/establecimientos/" + local.id() + "/sesiones", adminToken())).get("id").asString();

        HttpResponse<String> blocked = patch(ADMIN + "/" + local.id(), adminToken(), "{\"activo\":false}");
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("SESSION_IN_PROGRESS");

        post("/api/admin/sesiones/" + sesion + "/cierre", adminToken());
        assertThat(patch(ADMIN + "/" + local.id(), adminToken(), "{\"activo\":false}").statusCode()).isEqualTo(200);
    }

    @Test
    void anInactiveEstablishmentCannotOpenSessions() {
        Local local = localListoParaAbrir();
        patch(ADMIN + "/" + local.id(), adminToken(), "{\"activo\":false}");

        HttpResponse<String> response = post("/api/admin/establecimientos/" + local.id() + "/sesiones", adminToken());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("ESTABLISHMENT_INACTIVE");
    }

    @Test
    void updatingAnUnknownEstablishmentIsNotFound() {
        assertThat(patch(ADMIN + "/" + UUID.randomUUID(), adminToken(), "{\"nombre\":\"x\"}").statusCode()).isEqualTo(404);
    }

    @Test
    void onlyAdministratorsUpdate() {
        String id = crearOk("Permisos " + unico());

        assertThat(patch(ADMIN + "/" + id, operatorToken(), "{\"nombre\":\"x\"}").statusCode()).isEqualTo(403);
        assertThat(patch(ADMIN + "/" + id, null, "{\"nombre\":\"x\"}").statusCode()).isEqualTo(401);
    }
}
