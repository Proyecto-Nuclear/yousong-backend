package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Talks to the REAL YouTube Data API (about 101 quota units per run). Skipped unless YOUTUBE_API_KEY is
 * set. Run it with {@code YOUTUBE_API_KEY=... ./gradlew test --tests '*YoutubeRealApiSmokeTest'}. The key is
 * never printed.
 */
@EnabledIfEnvironmentVariable(named = "YOUTUBE_API_KEY", matches = ".+")
class YoutubeRealApiSmokeTest {

    @Test
    void searchesAndReadsImageTitleChannelAndDuration() {
        YoutubeProperties properties = new YoutubeProperties(System.getenv("YOUTUBE_API_KEY"),
                "https://www.googleapis.com/youtube/v3", 10, "CO", "es", 5, 9000, 7, 60);
        YoutubeClient client = new YoutubeRestClient(properties);

        PaginaIds page = client.buscar("Juanes La camisa negra", null);
        assertThat(page.videoIds()).isNotEmpty();

        List<VideoYoutube> videos = client.obtener(page.videoIds());
        assertThat(videos).isNotEmpty();
        for (VideoYoutube video : videos) {
            assertThat(video.videoId()).isNotBlank();
            assertThat(video.titulo()).isNotBlank();
            assertThat(video.canal()).isNotBlank();
            assertThat(video.miniaturaUrl()).startsWith("https://");
            assertThat(video.duracionSegundos()).isNotNull().isNotNegative();
        }
    }
}
