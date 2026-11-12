package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.LocalYoutubeServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** How a videos.list item becomes a {@link VideoYoutube}, and which videos count as unavailable. */
class VideoYoutubeParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static VideoYoutube parse(String itemJson) {
        return VideoYoutubeParser.desde(JSON.readTree(itemJson), "CO");
    }

    private static LocalYoutubeServer.Video sano() {
        return LocalYoutubeServer.video("abcdefghijk").titulo("La camisa negra").canal("Juanes - Topic").duracion("PT3M47S");
    }

    @Test
    void mapsTitleChannelDurationAndThumbnail() {
        VideoYoutube video = parse(sano().json());

        assertThat(video.videoId()).isEqualTo("abcdefghijk");
        assertThat(video.titulo()).isEqualTo("La camisa negra");
        assertThat(video.canal()).isEqualTo("Juanes - Topic");
        assertThat(video.duracionSegundos()).isEqualTo(227);
        assertThat(video.miniaturaUrl()).isEqualTo("https://img.test/abcdefghijk/mqdefault.jpg");
        assertThat(video.disponible()).isTrue();
        assertThat(video.motivoNoDisponible()).isNull();
    }

    @Test
    void prefersMediumThenHighThenDefaultThumbnail() {
        assertThat(parse(sano().miniaturas("""
                {"default":{"url":"https://img.test/d.jpg"},"medium":{"url":"https://img.test/m.jpg"},"high":{"url":"https://img.test/h.jpg"}}""").json())
                .miniaturaUrl()).isEqualTo("https://img.test/m.jpg");
        assertThat(parse(sano().miniaturas("""
                {"default":{"url":"https://img.test/d.jpg"},"high":{"url":"https://img.test/h.jpg"}}""").json())
                .miniaturaUrl()).isEqualTo("https://img.test/h.jpg");
        assertThat(parse(sano().miniaturas("""
                {"default":{"url":"https://img.test/d.jpg"}}""").json())
                .miniaturaUrl()).isEqualTo("https://img.test/d.jpg");
        assertThat(parse(sano().miniaturas("{}").json()).miniaturaUrl()).isNull();
    }

    @Test
    void aVideoWithoutADurationHasNullSeconds() {
        assertThat(parse(sano().duracion(null).json()).duracionSegundos()).isNull();
    }

    @Test
    void aZeroDurationIsKeptAsZero() {
        assertThat(parse(sano().duracion("P0D").json()).duracionSegundos()).isZero();
    }

    @Test
    void notEmbeddableIsUnavailable() {
        VideoYoutube video = parse(sano().incrustable(false).json());
        assertThat(video.disponible()).isFalse();
        assertThat(video.motivoNoDisponible()).isEqualTo("NOT_EMBEDDABLE");
    }

    @Test
    void nonPublicIsUnavailable() {
        assertThat(parse(sano().privacidad("private").json()).disponible()).isFalse();
        assertThat(parse(sano().privacidad("unlisted").json()).disponible()).isFalse();
        assertThat(parse(sano().privacidad("unlisted").json()).motivoNoDisponible()).isEqualTo("NOT_PUBLIC");
    }

    @Test
    void notProcessedIsUnavailable() {
        VideoYoutube video = parse(sano().subida("uploaded").json());
        assertThat(video.disponible()).isFalse();
        assertThat(video.motivoNoDisponible()).isEqualTo("NOT_PROCESSED");
    }

    @Test
    void liveOrUpcomingBroadcastsAreUnavailable() {
        assertThat(parse(sano().envivo("live").json()).disponible()).isFalse();
        VideoYoutube upcoming = parse(sano().envivo("upcoming").json());
        assertThat(upcoming.disponible()).isFalse();
        assertThat(upcoming.motivoNoDisponible()).isEqualTo("LIVE_BROADCAST");
    }

    @Test
    void blockedInTheConfiguredRegionIsUnavailable() {
        VideoYoutube video = parse(sano().restriccion("{\"blocked\":[\"US\",\"CO\"]}").json());
        assertThat(video.disponible()).isFalse();
        assertThat(video.motivoNoDisponible()).isEqualTo("REGION_BLOCKED");
    }

    @Test
    void blockedElsewhereIsStillAvailable() {
        assertThat(parse(sano().restriccion("{\"blocked\":[\"US\",\"DE\"]}").json()).disponible()).isTrue();
    }

    @Test
    void allowedListThatExcludesTheRegionIsUnavailable() {
        assertThat(parse(sano().restriccion("{\"allowed\":[\"US\",\"MX\"]}").json()).disponible()).isFalse();
        assertThat(parse(sano().restriccion("{\"allowed\":[\"US\",\"CO\"]}").json()).disponible()).isTrue();
        assertThat(parse(sano().restriccion("{\"allowed\":[]}").json()).disponible()).isFalse();
    }

    @Test
    void regionComparisonIgnoresCase() {
        assertThat(VideoYoutubeParser.desde(JSON.readTree(sano().restriccion("{\"blocked\":[\"co\"]}").json()), "CO")
                .disponible()).isFalse();
    }
}
