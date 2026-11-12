package cue.edu.co.yousongbackend.catalogo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the UNOFFICIAL YouTube suggest source ({@code app.suggestions.youtube.*}). It is not a
 * documented API and can change or disappear without notice, so it can be switched off and is guarded by a
 * cache, an outbound throttle and a circuit breaker. It never uses the YouTube Data API quota.
 */
@ConfigurationProperties("app.suggestions.youtube")
public record SugerenciasProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://suggestqueries.google.com/complete/search") String baseUrl,
        @DefaultValue("1500") int timeoutMillis,
        @DefaultValue("60") int cacheMinutes,
        @DefaultValue("10") int maxCallsPerSecond,
        @DefaultValue("5") int failureThreshold,
        @DefaultValue("10") int cooldownMinutes) {

    public SugerenciasProperties {
        timeoutMillis = Math.max(100, timeoutMillis);
        cacheMinutes = Math.max(1, cacheMinutes);
        maxCallsPerSecond = Math.max(1, maxCallsPerSecond);
        failureThreshold = Math.max(1, failureThreshold);
        cooldownMinutes = Math.max(1, cooldownMinutes);
    }
}
