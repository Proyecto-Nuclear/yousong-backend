package cue.edu.co.yousongbackend.security;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless OAuth2 resource server. Staff identities live in the identity provider (FusionAuth);
 * this backend only validates the access tokens it issues (signature through JWKS, issuer, audience,
 * expiry) and maps the {@code roles} claim to Spring authorities.
 *
 * <p>Token validation itself is configured with {@code spring.security.oauth2.resourceserver.jwt.*}.
 */
@Configuration
class SecurityConfig {

    static final String ADMINISTRADOR = "ADMINISTRADOR";
    static final String OPERADOR = "OPERADOR";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                // Bearer tokens travel in a header, never in cookies, so there is no ambient
                // credential for a cross-site request to forge: CSRF protection does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // /error is where Spring re-dispatches failures (404, 400, 500...). Blocking it would
                        // turn every error of a public endpoint into a 401 for anonymous callers.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/api/public/**").permitAll()
                        // Any other actuator endpoint someone exposes later is administrator-only.
                        .requestMatchers("/actuator/**").hasRole(ADMINISTRADOR)
                        .requestMatchers("/api/admin/**").hasRole(ADMINISTRADOR)
                        .requestMatchers("/api/staff/**").hasAnyRole(ADMINISTRADOR, OPERADOR)
                        // Deny by default: a new route needs an explicit rule above (and a test), so a
                        // valid token alone never opens an endpoint nobody thought about.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Only access tokens may call the API. FusionAuth signs ID tokens with the same key and the same
     * audience, so without this check an ID token (meant for the browser) would work as a Bearer token.
     * Since FusionAuth 1.60 every token carries a reserved {@code tty} claim: "at" or "idt".
     *
     * <p>Spring Boot adds this validator on top of its own issuer, audience and expiry checks.
     */
    @Bean
    OAuth2TokenValidator<Jwt> accessTokenOnlyValidator() {
        return new JwtClaimValidator<String>("tty", "at"::equals);
    }

    /** FusionAuth puts the user's application roles in a plain {@code roles} claim (no prefix). */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
