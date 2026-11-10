package cue.edu.co.yousongbackend.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spring Boot silently skips the issuer or audience check when its property is blank, and a
 * {@code ${VAR:default}} fallback does not apply to a variable that is set but empty.
 * The guard turns that silent downgrade into a startup failure.
 */
class JwtTrustGuardTest {

    private static final String AUDIENCE = "c2f6a1b4-3d5e-4f70-8a9b-6e1d2c3b4a59";

    @Test
    void acceptsAnIssuerAndAnAudience() {
        assertThatCode(() -> new JwtTrustGuard("https://example.com", List.of(AUDIENCE))).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWithABlankIssuer() {
        assertThatThrownBy(() -> new JwtTrustGuard("", List.of(AUDIENCE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");
        assertThatThrownBy(() -> new JwtTrustGuard("   ", List.of(AUDIENCE)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAnAudience() {
        assertThatThrownBy(() -> new JwtTrustGuard("https://example.com", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience");
        assertThatThrownBy(() -> new JwtTrustGuard("https://example.com", List.of("", "  ")))
                .isInstanceOf(IllegalStateException.class);
    }
}
