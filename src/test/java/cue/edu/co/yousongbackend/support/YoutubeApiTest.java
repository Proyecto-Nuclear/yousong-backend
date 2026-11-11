package cue.edu.co.yousongbackend.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * API tests with the catalog switched on and pointed at a {@link LocalYoutubeServer}. The default daily
 * quota limit is set by each concrete class (quota state lives in the application context, so classes that
 * exercise the quota use a context of their own: give them a different {@code daily-quota-limit}).
 */
public abstract class YoutubeApiTest extends ApiIntegrationTest {

    public static final String CLAVE_PRUEBA = "fake-credential-for-tests";

    protected static final LocalYoutubeServer YOUTUBE = new LocalYoutubeServer();

    @DynamicPropertySource
    static void youtube(DynamicPropertyRegistry registry) {
        registry.add("app.youtube.base-url", YOUTUBE::baseUrl);
        registry.add("app.youtube.api-key", () -> CLAVE_PRUEBA);
        registry.add("app.youtube.timeout-seconds", () -> "1");
        registry.add("app.youtube.max-results", () -> "5");
    }

    @BeforeEach
    void resetYoutube() {
        YOUTUBE.reset();
    }

    /** A query no other test used, so the search cache cannot answer it. */
    protected static String consultaNueva() {
        return "cancion " + videoIdAleatorio();
    }
}
