package cue.edu.co.yousongbackend.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A QR that points nowhere is worse than no QR, so a bad public address must stop the application. */
class PublicBaseUrlTest {

    @Test
    void removesTrailingSlashesAndSurroundingSpaces() {
        assertThat(PublicBaseUrl.normalizar("https://yousong.example/")).isEqualTo("https://yousong.example");
        assertThat(PublicBaseUrl.normalizar("  https://yousong.example///  ")).isEqualTo("https://yousong.example");
    }

    @Test
    void keepsAPortAndAPathPrefix() {
        assertThat(PublicBaseUrl.normalizar("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(PublicBaseUrl.normalizar("https://example.com/app/")).isEqualTo("https://example.com/app");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "yousong.example", "ftp://yousong.example", "javascript:alert(1)", "https://", "https:///path"})
    void refusesAnythingThatIsNotAnHttpAddressWithAHost(String value) {
        assertThatThrownBy(() -> PublicBaseUrl.normalizar(value)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesANullValue() {
        assertThatThrownBy(() -> PublicBaseUrl.normalizar(null)).isInstanceOf(IllegalStateException.class);
    }
}
