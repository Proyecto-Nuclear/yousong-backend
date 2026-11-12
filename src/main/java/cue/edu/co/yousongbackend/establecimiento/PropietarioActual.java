package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The establishment owner behind the current request. Ownership is the only authorization of the owner API:
 * the caller is the {@code sub} of the validated access token, and the establishment they act on is the one
 * that subject owns. No owner route accepts an establishment or session id from the client, so a valid token
 * can never reach another account's establishment.
 */
@Component
public class PropietarioActual {

    /** Same length as {@code operacion.establecimiento.propietario_sub}. */
    static final int MAX_SUB_LENGTH = 64;

    private final EstablecimientoRepository repository;

    PropietarioActual(EstablecimientoRepository repository) {
        this.repository = repository;
    }

    /** The identity-provider subject of the caller (401 when the token carries no usable subject). */
    public String sub() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String sub = jwt.getToken().getSubject();
            if (StringUtils.hasText(sub) && sub.length() <= MAX_SUB_LENGTH) {
                return sub;
            }
        }
        throw ApiException.unauthorized("INVALID_TOKEN", "The access token does not identify an account");
    }

    /** The id of the caller's establishment (404 NO_ESTABLISHMENT when the account has not created one yet). */
    @Transactional(readOnly = true)
    public UUID establecimientoId() {
        return repository.findIdByPropietarioSub(sub()).orElseThrow(() -> ApiException.notFound("NO_ESTABLISHMENT",
                "This account has no establishment yet: create it with POST /api/mi-establecimiento"));
    }
}
