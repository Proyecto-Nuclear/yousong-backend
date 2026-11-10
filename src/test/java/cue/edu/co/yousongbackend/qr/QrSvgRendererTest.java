package cue.edu.co.yousongbackend.qr;

import static cue.edu.co.yousongbackend.support.QrSvgDecoder.decode;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The QR is checked the only way that matters: a real decoder must read the URL back from the SVG. */
class QrSvgRendererTest {

    private static final String URL =
            "https://yousong.example/s/local-piloto/7ec06f0e-6e8a-4f68-a650-5b7e334c9191";

    @Test
    void decodesBackToTheOriginalUrl() throws Exception {
        assertThat(decode(QrSvgRenderer.render(URL))).isEqualTo(URL);
    }

    @Test
    void decodesLongerAndNonAsciiContent() throws Exception {
        String content = URL + "?utm=" + "x".repeat(120) + "&n=canción";
        assertThat(decode(QrSvgRenderer.render(content))).isEqualTo(content);
    }

    @Test
    void producesAScalableAccessibleSvg() {
        String svg = QrSvgRenderer.render(URL);

        assertThat(svg).startsWith("<svg ").endsWith("</svg>");
        assertThat(svg).contains("xmlns=\"http://www.w3.org/2000/svg\"", "viewBox=\"0 0 ", "role=\"img\"", "aria-label=");
        // No fixed pixel size: the page decides how big to draw it.
        assertThat(svg).doesNotContainPattern("<svg[^>]* width=");
    }

    @Test
    void doesNotLeakTheEncodedContentIntoTheMarkup() {
        assertThat(QrSvgRenderer.render(URL)).doesNotContain("yousong.example").doesNotContain("local-piloto");
    }

    @Test
    void isDeterministic() {
        assertThat(QrSvgRenderer.render(URL)).isEqualTo(QrSvgRenderer.render(URL));
    }

    @Test
    void differentContentGivesADifferentCode() {
        assertThat(QrSvgRenderer.render(URL)).isNotEqualTo(QrSvgRenderer.render(URL + "x"));
    }
}
