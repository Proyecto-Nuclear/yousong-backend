package cue.edu.co.yousongbackend.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for API tests: a real PostgreSQL, the real security configuration (tokens signed with a local key
 * and published through a local JWKS endpoint) and an HTTP client, plus fixtures written with plain SQL so
 * they do not depend on the code under test.
 *
 * <p>API calls run in their own transactions, so tests do not roll back: every test creates its own
 * establishment and never depends on global state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiIntegrationTest extends PostgresIntegrationTest {

    protected static final String PUBLIC_BASE_URL = "https://yousong.test";

    private static final String ISSUER = "test-issuer";
    private static final String AUDIENCE = "11111111-2222-3333-4444-555555555555";
    private static final RSAKey SIGNING_KEY = generateKey();
    private static final LocalJwksServer JWKS = new LocalJwksServer(SIGNING_KEY);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    protected record Local(UUID id, String slug) {
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void identityProviderAndPublicUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWKS::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
        // A trailing slash on purpose: the service must normalize it.
        registry.add("app.public-base-url", () -> PUBLIC_BASE_URL + "/");
    }

    // ---------- tokens ----------

    protected static String adminToken() {
        return token(List.of("ADMINISTRADOR"));
    }

    protected static String operatorToken() {
        return token(List.of("OPERADOR"));
    }

    private static String token(List<String> roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .subject(UUID.randomUUID().toString())
                    .claim("roles", roles)
                    .claim("tty", "at")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(SIGNING_KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("api-test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- HTTP ----------

    protected HttpResponse<String> get(String path, String bearer) {
        return send(request(path, bearer).GET().build());
    }

    protected HttpResponse<String> post(String path, String bearer) {
        return send(request(path, bearer).POST(BodyPublishers.noBody()).build());
    }

    private HttpRequest.Builder request(String path, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call failed: " + request.uri(), e);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    // ---------- fixtures (plain SQL) ----------

    protected Local establecimiento() {
        return establecimiento("America/Bogota", true);
    }

    protected Local establecimiento(String zonaHoraria, boolean activo) {
        UUID id = UUID.randomUUID();
        String slug = "local-" + id.toString().replace("-", "").substring(0, 10);
        jdbc.update("INSERT INTO operacion.establecimiento (id, nombre, qr_slug, zona_horaria, activo) "
                + "VALUES (?, 'Local de prueba', ?, ?, ?)", id, slug, zonaHoraria, activo);
        return new Local(id, slug);
    }

    protected UUID listaDefecto(UUID establecimientoId, String nombre, String creadoAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.lista_defecto (id, establecimiento_id, nombre, version, creado_at) "
                + "VALUES (?, ?, ?, 1, ?::timestamptz)", id, establecimientoId, nombre, creadoAt);
        return id;
    }

    protected UUID listaDefecto(UUID establecimientoId) {
        return listaDefecto(establecimientoId, "General", "2026-01-01T00:00:00Z");
    }

    /** An establishment that is ready to open a session: active, with a default list. */
    protected Local localListoParaAbrir() {
        Local local = establecimiento();
        listaDefecto(local.id());
        return local;
    }

    protected UUID participacion(UUID sesionId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.participacion (id, sesion_id, seudonimo) VALUES (?, ?, ?)",
                id, sesionId, "p-" + id.toString().substring(0, 8));
        return id;
    }

    protected UUID cancion() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id) VALUES (?, ?)",
                id, id.toString().replace("-", "").substring(0, 11));
        return id;
    }

    protected UUID solicitud(UUID sesionId, UUID cancionId, UUID creadorId, String estado, String finalizadaAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, estado, finalizada_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::timestamptz)", id, sesionId, cancionId, creadorId, estado, finalizadaAt);
        return id;
    }

    protected UUID estacionHabilitada(UUID establecimientoId, UUID sesionId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.estacion (id, establecimiento_id, sesion_id, nombre, habilitada, lease_hasta) "
                + "VALUES (?, ?, ?, 'Cabina', true, now() + interval '1 hour')", id, establecimientoId, sesionId);
        return id;
    }

    protected UUID reproduccionEnCurso(UUID sesionId, UUID cancionId, UUID estacionId, UUID solicitudId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.reproduccion (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id, "
                + "preparada_at, iniciada_at) VALUES (?, ?, ?, ?, 'SOLICITUD', ?, now() - interval '2 minutes', "
                + "now() - interval '1 minute')", id, sesionId, cancionId, estacionId, solicitudId);
        return id;
    }
}
