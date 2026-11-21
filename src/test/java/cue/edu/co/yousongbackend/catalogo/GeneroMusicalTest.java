package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GeneroMusicalTest {

    private static final String WIKI = "https://en.wikipedia.org/wiki/";

    private static String de(String... urls) {
        return GeneroMusical.de(Arrays.asList(urls));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Pop_music|Pop",
            "Rock_music|Rock",
            "Hip_hop_music|Hip hop",
            "Electronic_music|Electrónica",
            "Electronic_dance_music|Electrónica",
            "Dance_music|Electrónica",
            "Reggaeton|Reggaetón",
            "Reggae|Reggae",
            "Salsa_music|Salsa",
            "Cumbia|Cumbia",
            "Vallenato|Vallenato",
            "Bachata_(music)|Bachata",
            "Merengue_music|Merengue",
            "Latin_music|Música latina",
            "Music_of_Latin_America|Música latina",
            "Jazz|Jazz",
            "Blues|Blues",
            "Classical_music|Clásica",
            "Country_music|Country",
            "Folk_music|Folk",
            "Heavy_metal_music|Metal",
            "Independent_music|Independiente",
            "Soul_music|Soul",
            "Rhythm_and_blues|R&B",
            "Christian_music|Cristiana",
            "Music_of_Asia|Música asiática"})
    void everyDocumentedTopicHasItsSpanishLabel(String slug, String etiqueta) {
        assertThat(de(WIKI + slug)).isEqualTo(etiqueta);
    }

    @Test
    void theTableCoversEveryDocumentedSlugExactlyOnce() {
        assertThat(GeneroMusical.slugs()).hasSize(26).doesNotHaveDuplicates();
    }

    @Test
    void theMostSpecificLabelWins() {
        assertThat(de(WIKI + "Pop_music", WIKI + "Music_of_Latin_America", WIKI + "Reggaeton")).isEqualTo("Reggaetón");
        assertThat(de(WIKI + "Pop_music", WIKI + "Music_of_Latin_America")).isEqualTo("Música latina");
        assertThat(de(WIKI + "Music_of_Latin_America", WIKI + "Hip_hop_music", WIKI + "Pop_music")).isEqualTo("Hip hop");
        assertThat(de(WIKI + "Music", WIKI + "Pop_music", WIKI + "Music_of_Asia")).isEqualTo("Pop");
        assertThat(de(WIKI + "Independent_music", WIKI + "Rock_music")).isEqualTo("Rock");
    }

    @Test
    void precedenceDoesNotDependOnTheOrderOfTheTopics() {
        assertThat(de(WIKI + "Reggaeton", WIKI + "Pop_music")).isEqualTo(de(WIKI + "Pop_music", WIKI + "Reggaeton"));
    }

    @Test
    void genericAndUnknownTopicsGiveNoGenre() {
        assertThat(de(WIKI + "Music")).isNull();
        assertThat(de(WIKI + "Entertainment")).isNull();
        assertThat(de(WIKI + "Music", WIKI + "Entertainment", WIKI + "Film")).isNull();
        assertThat(de(WIKI + "Polka_music_of_Mars")).isNull();
        assertThat(GeneroMusical.de(List.of())).isNull();
        assertThat(GeneroMusical.de(null)).isNull();
    }

    @Test
    void theUrlIsParsedRobustly() {
        assertThat(de(WIKI + "Pop_music?wprov=sfla1")).isEqualTo("Pop");
        assertThat(de(WIKI + "Pop_music#History")).isEqualTo("Pop");
        assertThat(de(WIKI + "Pop_music/")).isEqualTo("Pop");
        assertThat(de(WIKI + "Pop_music//")).isEqualTo("Pop");
        assertThat(de(WIKI + "Bachata_%28music%29")).isEqualTo("Bachata");
        assertThat(de("http://en.wikipedia.org/wiki/Salsa_music")).isEqualTo("Salsa");
        assertThat(de("https://es.wikipedia.org/wiki/Cumbia")).isEqualTo("Cumbia");
        assertThat(de("  " + WIKI + "Rock_music  ")).isEqualTo("Rock");
        assertThat(de(WIKI + "rock_music")).as("case-insensitive").isEqualTo("Rock");
        assertThat(de(WIKI + "Hip hop music".replace(" ", "%20"))).isEqualTo("Hip hop");
    }

    @Test
    void anythingThatIsNotAWikipediaArticleIsIgnored() {
        assertThat(de("https://example.com/wiki/Pop_music")).isNull();
        assertThat(de("https://wikipedia.org.evil.test/wiki/Pop_music")).isNull();
        assertThat(de("https://en.wikipedia.org/w/Pop_music")).isNull();
        assertThat(de("ftp://en.wikipedia.org/wiki/Pop_music")).isNull();
        assertThat(de("Pop_music")).isNull();
        assertThat(de("not a url at all")).isNull();
        assertThat(de("", null, "   ")).isNull();
        assertThat(de("https://example.com/wiki/Reggaeton", WIKI + "Pop_music")).isEqualTo("Pop");
    }
}
