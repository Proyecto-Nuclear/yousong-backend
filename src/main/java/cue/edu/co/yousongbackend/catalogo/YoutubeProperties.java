package cue.edu.co.yousongbackend.catalogo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the YouTube Data API catalog ({@code app.youtube.*}). Every value has a default so the
 * application starts without any of them; a blank key simply leaves the catalog switched off.
 */
@ConfigurationProperties("app.youtube")
public record YoutubeProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://www.googleapis.com/youtube/v3") String baseUrl,
        @DefaultValue("5") int timeoutSeconds,
        @DefaultValue("CO") String regionCode,
        @DefaultValue("es") String relevanceLanguage,
        @DefaultValue("15") int maxResults,
        @DefaultValue("9000") int dailyQuotaLimit,
        @DefaultValue("7") int metadataTtlDays,
        @DefaultValue("60") int searchCacheMinutes) {

    public YoutubeProperties {
        apiKey = apiKey == null ? "" : apiKey.strip();
        baseUrl = baseUrl.replaceAll("/+$", "");
        timeoutSeconds = Math.max(1, timeoutSeconds);
        // search.list accepts 0..50 results per page
        maxResults = Math.max(1, Math.min(50, maxResults));
        searchCacheMinutes = Math.max(1, searchCacheMinutes);
    }

    public boolean configurada() {
        return !apiKey.isEmpty();
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "YoutubeProperties[baseUrl=" + baseUrl + ", configurada=" + configurada() + "]";
    }
}
