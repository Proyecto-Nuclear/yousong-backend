package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cue.edu.co.yousongbackend.support.LocalSugerenciasServer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The HTTP adapter of the unofficial YouTube suggest endpoint against a local stand-in. Anything unexpected
 * gives no suggestions: a well-formed but different JSON is an empty list, everything else is a failure that
 * the guard turns into "no suggestions".
 */
class SugerenciasYoutubeHttpTest {

    private static final LocalSugerenciasServer SERVIDOR = new LocalSugerenciasServer();

    private final SugerenciasYoutubeHttp cliente = new SugerenciasYoutubeHttp(
            new SugerenciasProperties(true, SERVIDOR.baseUrl(), 300, 60, 10, 5, 10),
            new YoutubeProperties("", "http://unused.test", 5, "CO", "es", 15, 9000, 7, 60));

    @BeforeEach
    void reset() {
        SERVIDOR.reset();
    }

    @Test
    void asksForYoutubeSuggestionsInTheConfiguredLanguage() {
        SERVIDOR.suggestions("shakira");

        cliente.sugerir("Shakira Ñ");

        LocalSugerenciasServer.Recibida recibida = SERVIDOR.last();
        assertThat(recibida.param("client")).isEqualTo("firefox");
        assertThat(recibida.param("ds")).isEqualTo("yt");
        assertThat(recibida.param("hl")).isEqualTo("es");
        assertThat(recibida.param("q")).isEqualTo("Shakira Ñ");
        assertThat(recibida.query()).containsOnlyKeys("client", "ds", "hl", "q");
    }

    @Test
    void sendsNoCookiesNoCredentialsAndAGenericUserAgent() {
        SERVIDOR.suggestions("shakira");

        cliente.sugerir("shakira");

        LocalSugerenciasServer.Recibida recibida = SERVIDOR.last();
        assertThat(recibida.hasHeader("Cookie")).isFalse();
        assertThat(recibida.hasHeader("Authorization")).isFalse();
        assertThat(recibida.header("User-Agent")).isEqualTo("Mozilla/5.0");
    }

    @Test
    void parsesTheSuggestionArray() {
        SERVIDOR.script(200, "[\"sha\",[\"shakira\",\"shakira – hips don't lie\",\"  \",7,null,\"shakira\"]]");

        assertThat(cliente.sugerir("sha")).containsExactly("shakira", "shakira – hips don't lie");
    }

    @Test
    void anUnexpectedJsonShapeGivesNoSuggestions() {
        for (String body : new String[]{"[]", "{}", "[\"q\"]", "[\"q\",\"nope\"]", "[\"q\",{}]", "\"x\"", "[\"q\",[]]"}) {
            SERVIDOR.script(200, body);
            assertThat(cliente.sugerir("q")).as(body).isEmpty();
        }
    }

    @Test
    void aBodyThatIsNotJsonIsAFailure() {
        SERVIDOR.script(200, "<html>captcha</html>");

        assertThatThrownBy(() -> cliente.sugerir("q")).isInstanceOf(SugerenciasRemotasException.class);
    }

    @Test
    void anErrorStatusIsAFailure() {
        SERVIDOR.script(503, "[\"q\",[\"x\"]]");

        assertThatThrownBy(() -> cliente.sugerir("q")).isInstanceOf(SugerenciasRemotasException.class);
    }

    @Test
    void aSlowAnswerIsAFailureAfterTheTimeout() {
        SERVIDOR.suggestions("x");
        SERVIDOR.delay(2_000);
        Instant inicio = Instant.now();

        assertThatThrownBy(() -> cliente.sugerir("q")).isInstanceOf(SugerenciasRemotasException.class);
        assertThat(Duration.between(inicio, Instant.now())).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void theExceptionNeverCarriesTheQuery() {
        SERVIDOR.script(500, "boom");

        assertThatThrownBy(() -> cliente.sugerir("secretquery"))
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("secretquery"))
                .satisfies(e -> assertThat(e.getCause()).isNull());
    }
}
