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

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "https://LOCALHOST", "http://127.0.0.1:3000", "http://[::1]:3000", "http://0.0.0.0"})
    void recognizesAddressesThatOnlyWorkOnTheServerItself(String value) {
        assertThat(PublicBaseUrl.apuntaAEstaMaquina(PublicBaseUrl.normalizar(value))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://yousong.example", "https://local.example.com", "http://192.168.1.20:3000", "https://localhost.example.org"})
    void doesNotFlagRealAddresses(String value) {
        assertThat(PublicBaseUrl.apuntaAEstaMaquina(PublicBaseUrl.normalizar(value))).isFalse();
    }
}
