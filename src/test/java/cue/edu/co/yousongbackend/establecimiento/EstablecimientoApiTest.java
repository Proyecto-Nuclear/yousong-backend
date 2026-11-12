package cue.edu.co.yousongbackend.establecimiento;

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

/**
 * The owner configures the establishment of their account: create, read and update, with real security and
 * PostgreSQL. No route takes an establishment id: the establishment is the one owned by the token subject.
 */
class EstablecimientoApiTest extends ApiIntegrationTest {

    private static final String MI = "/api/mi-establecimiento";

    private static String unico() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private HttpResponse<String> crear(String token, String json) {
        return post(MI, token, json);
    }

    /** Creates an establishment for a brand new account and returns that account's token. */
    private String crearOk(String nombre) {
        String token = tokenSinEstablecimiento();
        HttpResponse<String> response = crear(token, "{\"nombre\":\"" + nombre + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return token;
    }

    private String idDe(String token) {
        return json(get(MI, token)).get("id").asString();
    }

    private static String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    // ---------- create ----------

    @Test
    void anAccountCreatesItsEstablishmentWithAGeneratedSlugAndBecomesItsOwner() {
        String sub = UUID.randomUUID().toString();
        String sufijo = unico();

        HttpResponse<String> response = crear(tokenDe(sub), "{\"nombre\":\"Taberna Él Rincón " + sufijo + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.get("qrSlug").asString()).matches("taberna-el-rincon-" + sufijo + "-[a-z0-9]{6}");
        assertThat(body.get("zonaHoraria").asString()).isEqualTo("America/Bogota");
        assertThat(body.get("activo").asBoolean()).isTrue();
        assertThat(body.has("propietarioSub")).isFalse();
        assertThat(response.body()).doesNotContain(sub);
        assertThat(response.headers().firstValue("Location")).contains(MI);
        assertThat(jdbc.queryForObject("SELECT propietario_sub FROM operacion.establecimiento WHERE id = ?::uuid",
                String.class, body.get("id").asString())).isEqualTo(sub);
    }

    @Test
    void anExplicitSlugAndZoneAreKept() {
        String slug = "mi-local-" + unico();

        JsonNode body = json(crear(tokenSinEstablecimiento(),
                "{\"nombre\":\"Mi Local\",\"qrSlug\":\"" + slug + "\",\"zonaHoraria\":\"America/Mexico_City\"}"));

        assertThat(body.get("qrSlug").asString()).isEqualTo(slug);
        assertThat(body.get("zonaHoraria").asString()).isEqualTo("America/Mexico_City");
    }

    @Test
    void anExplicitSlugThatIsTakenIsRejected() {
        String slug = "ocupado-" + unico();
        assertThat(crear(tokenSinEstablecimiento(), "{\"nombre\":\"Uno\",\"qrSlug\":\"" + slug + "\"}").statusCode())
                .isEqualTo(201);

        HttpResponse<String> second = crear(tokenSinEstablecimiento(), "{\"nombre\":\"Dos\",\"qrSlug\":\"" + slug + "\"}");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(code(second)).isEqualTo("SLUG_TAKEN");
    }

    @Test
    void establishmentsWithTheSameNameGetDistinctUrlSafeSlugs() {
        String nombre = "Bar Repetido " + unico();

        String first = json(crear(tokenSinEstablecimiento(), "{\"nombre\":\"" + nombre + "\"}")).get("qrSlug").asString();
        String second = json(crear(tokenSinEstablecimiento(), "{\"nombre\":\"" + nombre + "\"}")).get("qrSlug").asString();

        assertThat(second).isNotEqualTo(first);
        assertThat(List.of(first, second)).allSatisfy(slug -> assertThat(slug).matches("[a-z0-9-]{3,80}")
                .startsWith(Slug.de(nombre) + "-"));
    }

    @Test
    void aGeneratedSlugFitsTheColumnForVeryLongNames() {
        JsonNode body = json(crear(tokenSinEstablecimiento(), "{\"nombre\":\"" + "x".repeat(120) + "\"}"));

        assertThat(body.get("qrSlug").asString()).matches("[a-z0-9-]{3,80}");
    }

    @Test
    void oneAccountCannotCreateASecondEstablishment() {
        String sub = UUID.randomUUID().toString();
        String token = tokenDe(sub);
        assertThat(crear(token, "{\"nombre\":\"Primero " + unico() + "\"}").statusCode()).isEqualTo(201);
        String id = idDe(token);

        HttpResponse<String> second = crear(token, "{\"nombre\":\"Segundo\",\"qrSlug\":\"segundo-" + unico() + "\"}");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(code(second)).isEqualTo("ESTABLISHMENT_ALREADY_EXISTS");
        assertThat(idDe(token)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.establecimiento WHERE propietario_sub = ?",
                Integer.class, sub)).isEqualTo(1);
    }

    @Test
    void anUnknownTimeZoneIsRejected() {
        HttpResponse<String> response = crear(tokenSinEstablecimiento(), "{\"nombre\":\"Zona\",\"zonaHoraria\":\"Mars/Olympus\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_TIME_ZONE");
    }

    @Test
    void invalidFieldsAreReportedTogetherWithTheirNames() {
        HttpResponse<String> response = crear(tokenSinEstablecimiento(), "{\"nombre\":\"   \",\"qrSlug\":\"Mi Local!\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response);
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("errors").toString()).contains("nombre").contains("qrSlug");
    }

    @Test
    void aTooLongNameIsRejected() {
        assertThat(code(crear(tokenSinEstablecimiento(), "{\"nombre\":\"" + "x".repeat(121) + "\"}")))
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void aMalformedBodyIsABadRequestNotAServerError() {
        HttpResponse<String> response = crear(tokenSinEstablecimiento(), "{not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_BODY");
    }

    @Test
    void creatingRecordsAConfigurationEventInTheSameTransaction() {
        String id = idDe(crearOk("Evento " + unico()));

        assertThat(jdbc.queryForObject("""
                SELECT ambito || '|' || agregado_tipo || '|' || agregado_version || '|' || (payload ->> 'entity_type')
                       || '|' || (payload ->> 'entity_ref')
                FROM integracion.evento_outbox WHERE agregado_id = ?::uuid AND event_type = 'configuration.changed'
                """, String.class, id)).isEqualTo("O|ESTABLECIMIENTO|1|ESTABLECIMIENTO|" + id);
    }

    @Test
    void creatingRequiresAToken() {
        assertThat(post(MI, null, "{\"nombre\":\"Anonimo\"}").statusCode()).isEqualTo(401);
    }

    // ---------- races ----------

    /** Fires the same call from several threads at the same moment and returns every response. */
    private List<HttpResponse<String>> enParalelo(int hilos, Callable<HttpResponse<String>> llamada) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch listos = new CountDownLatch(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> futuros = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    salida.await();
                    return llamada.call();
                }));
            }
            listos.await();
            salida.countDown();
            List<HttpResponse<String>> respuestas = new ArrayList<>();
            for (Future<HttpResponse<String>> futuro : futuros) {
                respuestas.add(futuro.get());
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCreationsWithTheSameNameByDifferentAccountsAllSucceedWithDistinctSlugs() throws Exception {
        String nombre = "Carrera " + unico();

        List<HttpResponse<String>> respuestas = enParalelo(6,
                () -> crear(tokenSinEstablecimiento(), "{\"nombre\":\"" + nombre + "\"}"));

        assertThat(respuestas).extracting(HttpResponse::statusCode).containsOnly(201);
        assertThat(respuestas).extracting(r -> json(r).get("qrSlug").asString()).doesNotHaveDuplicates();
    }

    @Test
    void concurrentCreationsWithTheSameExplicitSlugCreateExactlyOne() throws Exception {
        String slug = "carrera-" + unico();

        List<HttpResponse<String>> respuestas = enParalelo(6,
                () -> crear(tokenSinEstablecimiento(), "{\"nombre\":\"Misma direccion\",\"qrSlug\":\"" + slug + "\"}"));

        assertThat(respuestas).extracting(HttpResponse::statusCode).filteredOn(c -> c == 201).hasSize(1);
        // Every loser is told the address is taken; none of them gets a server error.
        assertThat(respuestas).filteredOn(r -> r.statusCode() != 201)
                .allSatisfy(r -> {
                    assertThat(r.statusCode()).isEqualTo(409);
                    assertThat(code(r)).isEqualTo("SLUG_TAKEN");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.establecimiento WHERE qr_slug = ?", Integer.class, slug))
                .isEqualTo(1);
    }

    @Test
    void concurrentCreationsByOneAccountCreateExactlyOne() throws Exception {
        String sub = UUID.randomUUID().toString();
        String token = tokenDe(sub);

        List<HttpResponse<String>> respuestas = enParalelo(6,
                () -> crear(token, "{\"nombre\":\"Doble clic " + unico() + "\"}"));

        assertThat(respuestas).extracting(HttpResponse::statusCode).filteredOn(c -> c == 201).hasSize(1);
        assertThat(respuestas).filteredOn(r -> r.statusCode() != 201)
                .allSatisfy(r -> {
                    assertThat(r.statusCode()).isEqualTo(409);
                    assertThat(code(r)).isEqualTo("ESTABLISHMENT_ALREADY_EXISTS");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operacion.establecimiento WHERE propietario_sub = ?",
                Integer.class, sub)).isEqualTo(1);
    }

    // ---------- read ----------

    @Test
    void theOwnerReadsTheirEstablishment() {
        Local local = establecimiento();

        HttpResponse<String> response = get(MI, local.token());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("id").asString()).isEqualTo(local.id().toString());
        assertThat(json(response).get("qrSlug").asString()).isEqualTo(local.slug());
        assertThat(response.body()).doesNotContain(local.propietario());
    }

    @Test
    void anAccountWithoutAnEstablishmentIsToldSo() {
        HttpResponse<String> response = get(MI, tokenSinEstablecimiento());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("NO_ESTABLISHMENT");
    }

    @Test
    void readingRequiresAuthentication() {
        assertThat(get(MI, null).statusCode()).isEqualTo(401);
    }

    // ---------- update ----------

    @Test
    void theOwnerUpdatesNameZoneAndStatusButNeverTheSlug() {
        Local local = establecimiento();

        HttpResponse<String> response = patch(MI, local.token(),
                "{\"nombre\":\"Despues\",\"zonaHoraria\":\"Europe/Madrid\",\"activo\":false,\"qrSlug\":\"otro-slug\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("nombre").asString()).isEqualTo("Despues");
        assertThat(body.get("zonaHoraria").asString()).isEqualTo("Europe/Madrid");
        assertThat(body.get("activo").asBoolean()).isFalse();
        assertThat(body.get("qrSlug").asString()).isEqualTo(local.slug()); // printed QR codes must keep working
    }

    @Test
    void aPartialUpdateChangesOnlyWhatWasSent() {
        String token = crearOk("Parcial " + unico());

        JsonNode body = json(patch(MI, token, "{\"nombre\":\"Solo nombre\"}"));

        assertThat(body.get("nombre").asString()).isEqualTo("Solo nombre");
        assertThat(body.get("zonaHoraria").asString()).isEqualTo("America/Bogota");
        assertThat(body.get("activo").asBoolean()).isTrue();
    }

    @Test
    void anEmptyUpdateIsRejected() {
        HttpResponse<String> response = patch(MI, establecimiento().token(), "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("EMPTY_UPDATE");
    }

    @Test
    void anInvalidUpdateChangesNothing() {
        String token = crearOk("Intacto " + unico());

        assertThat(patch(MI, token, "{\"nombre\":\"Nuevo\",\"zonaHoraria\":\"Nope/Nope\"}").statusCode()).isEqualTo(400);

        assertThat(json(get(MI, token)).get("nombre").asString()).startsWith("Intacto");
    }

    @Test
    void eachUpdateRecordsTheNextConfigurationEventVersion() {
        String token = crearOk("Versiones " + unico());
        String id = idDe(token);
        assertThat(patch(MI, token, "{\"nombre\":\"Dos\"}").statusCode()).isEqualTo(200);
        assertThat(patch(MI, token, "{\"zonaHoraria\":\"Europe/Madrid\"}").statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForList("""
                SELECT agregado_version FROM integracion.evento_outbox
                WHERE agregado_id = ?::uuid AND event_type = 'configuration.changed' ORDER BY agregado_version
                """, Long.class, id)).containsExactly(1L, 2L, 3L);
        assertThat(jdbc.queryForObject("""
                SELECT payload -> 'field_codes' FROM integracion.evento_outbox
                WHERE agregado_id = ?::uuid AND event_type = 'configuration.changed' AND agregado_version = 2
                """, String.class, id)).contains("nombre").doesNotContain("zona_horaria");
    }

    @Test
    void anEstablishmentWithASessionInOperationCannotBeDeactivated() {
        Local local = localListoParaAbrir();
        assertThat(post(MI + "/sesiones", local.token()).statusCode()).isEqualTo(201);

        HttpResponse<String> blocked = patch(MI, local.token(), "{\"activo\":false}");
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("SESSION_IN_PROGRESS");

        assertThat(post(MI + "/sesion/cierre", local.token()).statusCode()).isEqualTo(200);
        assertThat(patch(MI, local.token(), "{\"activo\":false}").statusCode()).isEqualTo(200);
    }

    @Test
    void anInactiveEstablishmentCannotOpenSessions() {
        Local local = localListoParaAbrir();
        patch(MI, local.token(), "{\"activo\":false}");

        HttpResponse<String> response = post(MI + "/sesiones", local.token());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("ESTABLISHMENT_INACTIVE");
    }

    @Test
    void updatingWithoutAnEstablishmentIsNotFound() {
        HttpResponse<String> response = patch(MI, tokenSinEstablecimiento(), "{\"nombre\":\"x\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("NO_ESTABLISHMENT");
    }

    @Test
    void updatingRequiresAToken() {
        assertThat(patch(MI, null, "{\"nombre\":\"x\"}").statusCode()).isEqualTo(401);
    }
}
