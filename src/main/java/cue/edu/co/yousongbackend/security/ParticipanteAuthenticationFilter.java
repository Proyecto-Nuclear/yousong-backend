package cue.edu.co.yousongbackend.security;

import cue.edu.co.yousongbackend.participante.AutenticacionParticipantes;
import cue.edu.co.yousongbackend.participante.VerificacionCredencial.Aceptada;
import cue.edu.co.yousongbackend.participante.VerificacionCredencial.Rechazada;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer pt_...} participant credentials. A request without the header
 * continues unauthenticated (the entry point then answers 401); a header that is present but not a usable
 * participant credential is answered here with 401 and the reason code. The token is never logged.
 *
 * <p>Not a Spring bean on purpose: as a bean, Spring Boot would also register it for every request.
 */
final class ParticipanteAuthenticationFilter extends OncePerRequestFilter {

    static final String ROL = "PARTICIPANTE";

    private static final String BEARER = "Bearer ";

    private final AutenticacionParticipantes autenticacion;
    private final SecurityContextHolderStrategy contextos = SecurityContextHolder.getContextHolderStrategy();

    ParticipanteAuthenticationFilter(AutenticacionParticipantes autenticacion) {
        this.autenticacion = autenticacion;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        // Another scheme (Basic, a bare "Bearer"...) leaves no token, which the verification rejects.
        String token = header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? header.substring(BEARER.length()).strip()
                : null;
        switch (autenticacion.verificar(token)) {
            case Aceptada aceptada -> {
                UsernamePasswordAuthenticationToken autenticado = UsernamePasswordAuthenticationToken.authenticated(
                        aceptada.participante(), null, List.of(new SimpleGrantedAuthority("ROLE_" + ROL)));
                SecurityContext contexto = contextos.createEmptyContext();
                contexto.setAuthentication(autenticado);
                contextos.setContext(contexto);
                chain.doFilter(request, response);
            }
            case Rechazada rechazada -> {
                contextos.clearContext();
                ProblemaNoAutenticado.escribir(request, response, rechazada.code(), rechazada.detail());
            }
        }
    }
}
