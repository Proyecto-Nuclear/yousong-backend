package cue.edu.co.yousongbackend.destacadas;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The default allowance must fit a full venue: patrons on the venue's Wi-Fi share one public address, and every
 * owner reading through the web BFF arrives from the BFF server's address.
 */
class DestacadasLimitePorDefectoApiTest extends ApiIntegrationTest {

    @Test
    void aFullVenueBehindOneAddressIsNotRateLimited() {
        for (int i = 0; i < 150; i++) {
            assertThat(get("/api/public/establecimientos/no-existe/destacadas", null).statusCode()).isNotEqualTo(429);
            assertThat(get("/api/public/tendencias", null).statusCode()).isNotEqualTo(429);
        }
    }
}
