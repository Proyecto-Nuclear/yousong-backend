package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ClienteSse.Evento;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The public queue says the same thing as the public establishment status: a pause or the maintenance mode of
 * the establishment shows up in the snapshot, on the REST read and on the live stream.
 */
@TestPropertySource(properties = {"app.cola.sse-heartbeat-millis=200", "app.cola.sse-max-per-address=1000",
        "app.cola.sse-max-per-establishment=1000"})
class ColaEstadoPublicoApiTest extends ColaApiTestSupport {

    private static final String MI = "/api/mi-establecimiento";

    private String estadoDeLaCola(Local local) {
        return json(colaPublica(local.slug())).get("estado").asString();
    }

    @Test
    void theQueueSnapshotShowsTheSystemPauseAndItsEnd() {
        Local local = localAbierto();
        solicitada(unirse(local), videoGuardado());
        assertThat(estadoDeLaCola(local)).isEqualTo("ABIERTO");

        assertThat(post(MI + "/pausa", local.token()).statusCode()).isEqualTo(200);
        assertThat(estadoDeLaCola(local)).isEqualTo("PAUSADO");
        assertThat(json(colaPublica(local.slug())).get("cola")).as("the waiting songs stay visible").hasSize(1);

        assertThat(post(MI + "/reanudacion", local.token()).statusCode()).isEqualTo(200);
        assertThat(estadoDeLaCola(local)).isEqualTo("ABIERTO");
    }

    @Test
    void theQueueSnapshotShowsTheMaintenanceMode() {
        Local local = localListoParaAbrir();

        assertThat(post(MI + "/mantenimiento/inicio", local.token()).statusCode()).isEqualTo(200);
        assertThat(estadoDeLaCola(local)).isEqualTo("MANTENIMIENTO");

        assertThat(post(MI + "/mantenimiento/fin", local.token()).statusCode()).isEqualTo(200);
        assertThat(estadoDeLaCola(local)).isEqualTo("CERRADO");
    }

    @Test
    void theLiveStreamDeliversThePauseWithoutAnyQueueChange() {
        Local local = localAbierto();
        try (ClienteSse cliente = ClienteSse.abrir(port, "/api/public/establecimientos/" + local.slug() + "/cola/eventos")) {
            assertThat(cliente.status()).isEqualTo(200);
            assertThat(cliente.siguiente().json().get("estado").asString()).isEqualTo("ABIERTO");

            post(MI + "/pausa", local.token());
            Evento pausado = cliente.siguiente(Duration.ofSeconds(5));
            assertThat(pausado).isNotNull();
            assertThat(pausado.json().get("estado").asString()).isEqualTo("PAUSADO");

            post(MI + "/reanudacion", local.token());
            Evento reanudado = cliente.siguiente(Duration.ofSeconds(5));
            assertThat(reanudado).isNotNull();
            assertThat(reanudado.json().get("estado").asString()).isEqualTo("ABIERTO");
        }
    }
}
