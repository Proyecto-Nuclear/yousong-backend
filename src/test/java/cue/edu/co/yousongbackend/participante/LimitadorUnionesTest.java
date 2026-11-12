package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The in-memory join limiter: one window per address and one per establishment, independent of each other. */
class LimitadorUnionesTest {

    private static final UUID LOCAL_A = UUID.randomUUID();
    private static final UUID LOCAL_B = UUID.randomUUID();

    private static void assertLimitado(Runnable llamada) {
        assertThatThrownBy(llamada::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo("RATE_LIMITED");
        });
    }

    @Test
    void anAddressOverItsAllowanceIsLimitedWithoutAffectingOtherAddresses() {
        LimitadorUniones limitador = new LimitadorUniones(2, 100);

        limitador.registrar("10.0.0.1", LOCAL_A);
        limitador.registrar("10.0.0.1", LOCAL_A);
        assertLimitado(() -> limitador.registrar("10.0.0.1", LOCAL_A));
        assertLimitado(() -> limitador.registrar("10.0.0.1", LOCAL_B));

        assertThatCode(() -> limitador.registrar("10.0.0.2", LOCAL_A)).doesNotThrowAnyException();
    }

    @Test
    void anEstablishmentOverItsAllowanceIsLimitedWithoutAffectingOtherEstablishments() {
        LimitadorUniones limitador = new LimitadorUniones(100, 2);

        limitador.registrar("10.0.0.1", LOCAL_A);
        limitador.registrar("10.0.0.2", LOCAL_A);
        assertLimitado(() -> limitador.registrar("10.0.0.3", LOCAL_A));

        assertThatCode(() -> limitador.registrar("10.0.0.3", LOCAL_B)).doesNotThrowAnyException();
    }

    @Test
    void theMessageNeverCarriesTheAddress() {
        LimitadorUniones limitador = new LimitadorUniones(1, 100);
        limitador.registrar("203.0.113.7", LOCAL_A);

        assertThatThrownBy(() -> limitador.registrar("203.0.113.7", LOCAL_A))
                .isInstanceOf(ApiException.class).message().doesNotContain("203.0.113.7");
    }

    @Test
    void nonPositiveLimitsAreRejectedAtStartup() {
        assertThatThrownBy(() -> new LimitadorUniones(0, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LimitadorUniones(10, 0)).isInstanceOf(IllegalStateException.class);
    }
}
