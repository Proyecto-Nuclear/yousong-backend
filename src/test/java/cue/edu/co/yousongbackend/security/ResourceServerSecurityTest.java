package cue.edu.co.yousongbackend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import cue.edu.co.yousongbackend.support.LocalJwksServer;
import cue.edu.co.yousongbackend.support.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Exercises the real resource-server configuration against a local JWKS endpoint, signing tokens
 * the way FusionAuth does (RS256, string issuer, application id as audience, {@code roles} claim).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceServerSecurityTest extends PostgresIntegrationTest {

    private static final String ISSUER = "test-issuer";
    private static final String AUDIENCE = "11111111-2222-3333-4444-555555555555";

    private static final RSAKey SIGNING_KEY = generateKey("trusted-key");
    private static final RSAKey UNTRUSTED_KEY = generateKey("trusted-key"); // same kid, different material
    private static final LocalJwksServer JWKS_SERVER = new LocalJwksServer(SIGNING_KEY);

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                JWKS_SERVER::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop();
    }

    // ---------- public zone ----------

    @Test
    void healthEndpointIsPublic() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void publicZoneNeedsNoToken() throws Exception {
        assertThat(get("/api/public/ping", null).statusCode()).isEqualTo(200);
    }

    @Test
    void publicZoneKeepsItsRealErrorStatusInsteadOfTurningIntoUnauthorized() throws Exception {
        // Spring re-dispatches errors to /error; that dispatch must not be blocked for anonymous callers.
        assertThat(get("/api/public/does-not-exist", null).statusCode()).isEqualTo(404);
    }

    // ---------- authentication ----------

    @Test
    void staffZoneRejectsRequestsWithoutToken() throws Exception {
        assertThat(get("/api/staff/ping", null).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsGarbageBearerToken() throws Exception {
        assertThat(get("/api/staff/ping", "not-a-jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, List.of("OPERADOR"), Instant.now().minusSeconds(3600));
        assertThat(get("/api/staff/ping", token).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsTokenFromAnotherIssuer() throws Exception {
        String token = token(SIGNING_KEY, "evil-issuer", AUDIENCE, List.of("OPERADOR"), inOneHour());
        assertThat(get("/api/staff/ping", token).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsTokenForAnotherApplication() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, UUID.randomUUID().toString(), List.of("OPERADOR"), inOneHour());
        assertThat(get("/api/staff/ping", token).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsTokenSignedWithAnUntrustedKey() throws Exception {
        String token = token(UNTRUSTED_KEY, ISSUER, AUDIENCE, List.of("ADMINISTRADOR"), inOneHour());
        assertThat(get("/api/staff/ping", token).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsIdTokensUsedAsAccessTokens() throws Exception {
        // Same issuer, audience and signing key as an access token; only the token type differs.
        String idToken = token(SIGNING_KEY, ISSUER, AUDIENCE, UUID.randomUUID().toString(),
                List.of("ADMINISTRADOR"), inOneHour(), "idt");
        assertThat(get("/api/staff/ping", idToken).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsTokensWithoutATokenTypeClaim() throws Exception {
        String untyped = token(SIGNING_KEY, ISSUER, AUDIENCE, UUID.randomUUID().toString(),
                List.of("ADMINISTRADOR"), inOneHour(), null);
        assertThat(get("/api/staff/ping", untyped).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsUnsignedTokens() throws Exception {
        String header = base64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String claims = base64("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE
                + "\",\"sub\":\"x\",\"roles\":[\"ADMINISTRADOR\"],\"exp\":" + inOneHour().getEpochSecond() + "}");
        assertThat(get("/api/staff/ping", header + "." + claims + ".").statusCode()).isEqualTo(401);
    }

    // ---------- authorization ----------

    @Test
    void operatorReachesTheStaffZoneButNotTheAdminZone() throws Exception {
        String token = validToken(List.of("OPERADOR"));
        assertThat(get("/api/staff/ping", token).statusCode()).isEqualTo(200);
        assertThat(get("/api/admin/ping", token).statusCode()).isEqualTo(403);
    }

    @Test
    void administratorReachesBothZones() throws Exception {
        String token = validToken(List.of("ADMINISTRADOR"));
        assertThat(get("/api/staff/ping", token).statusCode()).isEqualTo(200);
        assertThat(get("/api/admin/ping", token).statusCode()).isEqualTo(200);
    }

    @Test
    void validTokenWithoutRolesIsForbiddenInStaffZone() throws Exception {
        assertThat(get("/api/staff/ping", validToken(List.of())).statusCode()).isEqualTo(403);
    }

    @Test
    void unknownRoleDoesNotGrantAccess() throws Exception {
        assertThat(get("/api/staff/ping", validToken(List.of("CLIENTE"))).statusCode()).isEqualTo(403);
    }

    @Test
    void principalSubjectIsTheIdentityProviderUserId() throws Exception {
        String sub = UUID.randomUUID().toString();
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, sub, List.of("OPERADOR"), inOneHour());
        HttpResponse<String> response = get("/api/staff/me", token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(sub);
    }

    @Test
    void adminZoneRejectsRequestsWithoutToken() throws Exception {
        assertThat(get("/api/admin/ping", null).statusCode()).isEqualTo(401);
    }

    @Test
    void operatorCannotWriteInTheAdminZone() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/admin/ping")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + validToken(List.of("OPERADOR")))
                .POST(BodyPublishers.noBody()).build();
        assertThat(http.send(request, BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test
    void tokenWithoutAnyRolesClaimIsForbidden() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, null, inOneHour());
        assertThat(get("/api/staff/ping", token).statusCode()).isEqualTo(403);
    }

    @Test
    void validTokensDoNotReachActuatorEndpointsBeyondHealthAndInfo() throws Exception {
        assertThat(get("/actuator/env", validToken(List.of("OPERADOR"))).statusCode()).isEqualTo(403);
    }

    @Test
    void routesWithoutAnExplicitRuleAreDeniedEvenForAdministrators() throws Exception {
        assertThat(get("/api/unlisted/ping", validToken(List.of("ADMINISTRADOR"))).statusCode()).isEqualTo(403);
    }

    @Test
    void plainOptionsRequestsAreNotAnsweredAnonymously() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/admin/ping")).timeout(Duration.ofSeconds(10))
                .method("OPTIONS", BodyPublishers.noBody()).build();
        assertThat(http.send(request, BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    // ---------- statelessness, CSRF, CORS ----------

    @Test
    void bearerRequestsNeverCreateSessionsOrCookies() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/staff/ping")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + validToken(List.of("OPERADOR")))
                .POST(BodyPublishers.noBody()).build();
        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void corsPreflightIsAllowedForTheConfiguredFrontendOnly() throws Exception {
        HttpResponse<String> allowed = preflight("http://localhost:3000");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");

        HttpResponse<String> denied = preflight("http://evil.example");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    // ---------- helpers ----------

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/staff/ping")).timeout(Duration.ofSeconds(10))
                .method("OPTIONS", BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .build();
        return http.send(request, BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Instant inOneHour() {
        return Instant.now().plusSeconds(3600);
    }

    private static String validToken(List<String> roles) {
        return token(SIGNING_KEY, ISSUER, AUDIENCE, roles, inOneHour());
    }

    private static String token(RSAKey key, String issuer, String audience, List<String> roles, Instant expires) {
        return token(key, issuer, audience, UUID.randomUUID().toString(), roles, expires);
    }

    private static String token(RSAKey key, String issuer, String audience, String subject,
                                List<String> roles, Instant expires) {
        return token(key, issuer, audience, subject, roles, expires, "at");
    }

    /** {@code tokenType} is FusionAuth's {@code tty} claim: "at" for access tokens, "idt" for ID tokens. */
    private static String token(RSAKey key, String issuer, String audience, String subject,
                                List<String> roles, Instant expires, String tokenType) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .issueTime(new Date())
                    .expirationTime(Date.from(expires));
            if (roles != null) {
                builder.claim("roles", roles);
            }
            if (tokenType != null) {
                builder.claim("tty", tokenType);
            }
            JWTClaimsSet claims = builder.build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(String json) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static RSAKey generateKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
