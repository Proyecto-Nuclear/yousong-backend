package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Caps on open queue streams: per client address and per establishment, both answered 429 TOO_MANY_STREAMS
 * before any stream starts. Every test of this class runs from 127.0.0.1, so it starts from an empty registry.
 */
@TestPropertySource(properties = {"app.cola.sse-heartbeat-millis=200", "app.cola.sse-max-per-address=3",
        "app.cola.sse-max-per-establishment=2"})
class ColaEventosLimitesApiTest extends ColaApiTestSupport {

    @Autowired
    private SuscripcionesCola suscripciones;

    private final List<ClienteSse> abiertos = new ArrayList<>();

    private static String eventos(String slug) {
        return "/api/public/establecimientos/" + slug + "/cola/eventos";
    }

    private ClienteSse abrir(Local local) {
        ClienteSse cliente = ClienteSse.abrir(port, eventos(local.slug()));
        abiertos.add(cliente);
        return cliente;
    }

    private void assertDemasiados(ClienteSse cliente) {
        assertThat(cliente.status()).isEqualTo(429);
        assertThat(cliente.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(cliente.cuerpo()).contains("\"code\":\"TOO_MANY_STREAMS\"");
    }

    @BeforeEach
    void registroVacio() {
        await().atMost(Duration.ofSeconds(10)).until(() -> suscripciones.suscriptores() == 0);
    }

    @AfterEach
    void cerrar() {
        abiertos.forEach(ClienteSse::close);
    }

    @Test
    void anEstablishmentAcceptsAtMostItsCapOfStreams() {
        Local local = localListoParaAbrir();
        assertThat(abrir(local).status()).isEqualTo(200);
        assertThat(abrir(local).status()).isEqualTo(200);

        assertDemasiados(abrir(local));
        assertThat(suscripciones.suscriptores(local.id())).isEqualTo(2);
    }

    @Test
    void anAddressAcceptsAtMostItsCapOfStreamsAcrossEstablishments() {
        for (int i = 0; i < 3; i++) {
            assertThat(abrir(localListoParaAbrir()).status()).isEqualTo(200);
        }

        assertDemasiados(abrir(localListoParaAbrir()));
        assertThat(suscripciones.suscriptores()).isEqualTo(3);
    }

    @Test
    void aClosedStreamFreesItsPlace() {
        Local local = localListoParaAbrir();
        ClienteSse primero = abrir(local);
        primero.siguiente();
        abrir(local).siguiente();
        assertDemasiados(abrir(local));

        primero.close();
        await().atMost(Duration.ofSeconds(10)).until(() -> suscripciones.suscriptores(local.id()) == 1);

        ClienteSse otro = abrir(local);
        assertThat(otro.status()).isEqualTo(200);
        assertThat(otro.siguiente().nombre()).isEqualTo("cola");
    }
}
