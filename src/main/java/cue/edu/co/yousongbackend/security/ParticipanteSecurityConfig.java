package cue.edu.co.yousongbackend.security;

import cue.edu.co.yousongbackend.participante.AutenticacionParticipantes;
import cue.edu.co.yousongbackend.participante.VerificacionCredencial;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Security for anonymous clients that joined a session through the QR ({@code /api/participante/**}).
 *
 * <p>A separate chain, ordered before the staff chain of {@link SecurityConfig}, so the two credential types
 * never mix: here only participant credentials ({@code pt_...}) are accepted and a staff JWT is rejected;
 * there only JWTs are accepted, so a participant credential is rejected by the JWT decoder (401).
 * Stateless, no cookies, CSRF off for the same reason as the staff chain (header credentials only).
 */
@Configuration
class ParticipanteSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    SecurityFilterChain participanteSecurity(HttpSecurity http, AutenticacionParticipantes autenticacion)
            throws Exception {
        http
                .securityMatcher("/api/participante/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().hasRole(ParticipanteAuthenticationFilter.ROL))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> ProblemaNoAutenticado.escribir(
                                request, response, VerificacionCredencial.INVALID_CREDENTIAL,
                                "A participant credential is required")))
                .addFilterBefore(new ParticipanteAuthenticationFilter(autenticacion), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
