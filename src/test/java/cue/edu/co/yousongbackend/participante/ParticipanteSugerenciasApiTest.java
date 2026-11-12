package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalSugerenciasServer;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Search-as-you-type from the participant's phone: same suggestions as staff, limited per participant
 * (checked before any work), and over the moment the session closes.
 */
@TestPropertySource(properties = "app.participante.suggestions-per-minute=3")
class ParticipanteSugerenciasApiTest extends ParticipanteApiTestSupport {

    private static final LocalSugerenciasServer SUGERENCIAS = new LocalSugerenciasServer();
    private static final String RUTA = "/api/participante/catalogo/sugerencias?q=";

    @DynamicPropertySource
    static void sugerencias(DynamicPropertyRegistry registry) {
        registry.add("app.suggestions.youtube.base-url", SUGERENCIAS::baseUrl);
    }

    @BeforeEach
    void resetSugerencias() {
        SUGERENCIAS.reset();
    }

    private static String marca() {
        return "qz" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
    }

    private HttpResponse<String> sugerir(String consulta, String token) {
        return get(RUTA + URLEncoder.encode(consulta, StandardCharsets.UTF_8), token);
    }

    @Test
    void aParticipantGetsKnownSongsAndRemoteSuggestions() {
        String token = unirse(localAbierto());
        String m = marca();
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id, titulo, disponibilidad) VALUES (?, ?, ?, 'DISPONIBLE')",
                UUID.randomUUID(), videoIdAleatorio(), "Conocida " + m);
        SUGERENCIAS.suggestions("remota " + m);

        HttpResponse<String> response = sugerir(m, token);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("sugerencias").get(0).get("tipo").asString()).isEqualTo("CANCION");
        assertThat(json(response).get("sugerencias").get(1).get("texto").asString()).isEqualTo("remota " + m);
    }

    @Test
    void anInvalidQueryIsABadRequest() {
        String token = unirse(localAbierto());

        assertProblema(sugerir("x", token), 400, "INVALID_QUERY");
    }

    @Test
    void credentialsAreNotInterchangeable() {
        String token = unirse(localAbierto());

        assertThat(sugerir(marca(), null).statusCode()).isEqualTo(401);
        assertCredencialRechazada(sugerir(marca(), tokenSinEstablecimiento()), "INVALID_CREDENTIAL");
        assertThat(get("/api/mi-establecimiento/catalogo/sugerencias?q=" + marca(), token).statusCode()).isEqualTo(401);
        assertThat(SUGERENCIAS.count()).isZero();
    }

    @Test
    void afterTheSessionClosesItAnswersSessionEndedWithoutAnyWork() {
        Local local = localListoParaAbrir();
        UUID sesion = abrirSesion(local);
        String token = unirse(local);
        cerrarSesion(sesion);

        assertCredencialRechazada(sugerir(marca(), token), "SESSION_ENDED");
        assertThat(SUGERENCIAS.count()).isZero();
    }

    @Test
    void eachParticipantHasItsOwnLimitCheckedBeforeAnyWork() {
        Local local = localAbierto();
        String ana = unirse(local);
        String luis = unirse(local);
        for (int i = 0; i < 3; i++) {
            assertThat(sugerir(marca(), ana).statusCode()).as("suggestion " + (i + 1)).isEqualTo(200);
        }
        int llamadas = SUGERENCIAS.count();

        assertProblema(sugerir(marca(), ana), 429, "RATE_LIMITED");
        assertProblema(sugerir("x", ana), 429, "RATE_LIMITED");

        assertThat(SUGERENCIAS.count()).as("a limited request does no work").isEqualTo(llamadas);
        assertThat(sugerir(marca(), luis).statusCode()).as("another participant is not affected").isEqualTo(200);
    }

    @Test
    void ownersAreNotLimited() {
        for (int i = 0; i < 6; i++) {
            assertThat(get("/api/mi-establecimiento/catalogo/sugerencias?q=" + marca(), propietarioToken()).statusCode()).isEqualTo(200);
        }
    }
}
