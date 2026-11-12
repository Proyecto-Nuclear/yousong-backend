package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.ApiIntegrationTest;
import cue.edu.co.yousongbackend.support.LocalSugerenciasServer;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** With the unofficial source switched off, suggestions come only from known songs and nothing goes out. */
@TestPropertySource(properties = "app.suggestions.youtube.enabled=false")
class CatalogoSugerenciasDesactivadasApiTest extends ApiIntegrationTest {

    private static final LocalSugerenciasServer SUGERENCIAS = new LocalSugerenciasServer();

    @DynamicPropertySource
    static void sugerencias(DynamicPropertyRegistry registry) {
        registry.add("app.suggestions.youtube.base-url", SUGERENCIAS::baseUrl);
    }

    @Test
    void switchedOffOnlyLocalSongsAreSuggestedAndTheRemoteIsNeverCalled() {
        String m = CatalogoSugerenciasApiTest.marca();
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id, titulo, disponibilidad) VALUES (?, ?, ?, 'DISPONIBLE')",
                UUID.randomUUID(), videoIdAleatorio(), "Local " + m);
        SUGERENCIAS.suggestions("remota " + m);

        HttpResponse<String> response = get("/api/staff/catalogo/sugerencias?q=" + m, operatorToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("sugerencias")).hasSize(1);
        assertThat(json(response).get("sugerencias").get(0).get("texto").asString()).isEqualTo("Local " + m);
        assertThat(SUGERENCIAS.count()).isZero();
    }
}
