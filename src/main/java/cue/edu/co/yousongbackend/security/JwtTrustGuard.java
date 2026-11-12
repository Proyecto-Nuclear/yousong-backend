package cue.edu.co.yousongbackend.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fails the application start when the token issuer or audience is not configured.
 *
 * <p>Spring Boot only adds the issuer and audience validators when their properties have text, so a
 * blank value would silently accept tokens from any tenant or application of the identity provider.
 * A {@code ${VAR:default}} fallback does not protect against that, because it only applies when the
 * variable is missing, not when it is set to an empty value.
 */
@Component
class JwtTrustGuard {

    JwtTrustGuard(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") List<String> audiences) {
        if (!StringUtils.hasText(issuer)) {
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri (FUSIONAUTH_ISSUER) must not be blank: "
                            + "tokens would be accepted from any issuer");
        }
        if (audiences == null || audiences.stream().noneMatch(StringUtils::hasText)) {
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.audiences (FUSIONAUTH_APPLICATION_ID) must not be "
                            + "blank: tokens would be accepted for any application");
        }
    }
}
