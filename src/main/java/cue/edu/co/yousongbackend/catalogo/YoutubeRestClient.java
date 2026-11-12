package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.catalogo.YoutubeException.Tipo;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * YouTube Data API v3 over HTTP. The key travels only as a query parameter of the outgoing request: it is
 * never logged, and no exception message of the HTTP layer (which includes the URL) leaves this class.
 */
@Slf4j
@Component
class YoutubeRestClient implements YoutubeClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final YoutubeProperties propiedades;
    private final RestClient http;

    YoutubeRestClient(YoutubeProperties propiedades) {
        this.propiedades = propiedades;
        Duration limite = Duration.ofSeconds(propiedades.timeoutSeconds());
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(limite)
                .build());
        fabrica.setReadTimeout(limite);
        this.http = RestClient.builder().baseUrl(propiedades.baseUrl()).requestFactory(fabrica).build();
    }

    @Override
    public PaginaIds buscar(String consulta, String pagina) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("key", propiedades.apiKey());
        variables.put("q", consulta);
        variables.put("maxResults", propiedades.maxResults());
        variables.put("regionCode", propiedades.regionCode());
        variables.put("relevanceLanguage", propiedades.relevanceLanguage());
        String plantilla = "/search?key={key}&part=snippet&type=video&q={q}&maxResults={maxResults}"
                + "&videoCategoryId=10&videoEmbeddable=true&safeSearch=moderate"
                + "&regionCode={regionCode}&relevanceLanguage={relevanceLanguage}";
        if (pagina != null && !pagina.isBlank()) {
            plantilla += "&pageToken={pageToken}";
            variables.put("pageToken", pagina);
        }
        JsonNode respuesta = llamar(plantilla, variables);

        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : respuesta.path("items")) {
            String id = VideoYoutubeParser.texto(item.path("id").path("videoId"));
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return new PaginaIds(List.copyOf(ids), VideoYoutubeParser.texto(respuesta.path("nextPageToken")));
    }

    @Override
    public List<VideoYoutube> obtener(Collection<String> videoIds) {
        List<String> ids = List.copyOf(new LinkedHashSet<>(videoIds));
        Map<String, VideoYoutube> porId = new LinkedHashMap<>();
        for (int desde = 0; desde < ids.size(); desde += LOTE_MAXIMO) {
            List<String> lote = ids.subList(desde, Math.min(ids.size(), desde + LOTE_MAXIMO));
            JsonNode respuesta = llamar("/videos?key={key}&part=snippet,contentDetails,status&id={ids}",
                    Map.of("key", propiedades.apiKey(), "ids", String.join(",", lote)));
            for (JsonNode item : respuesta.path("items")) {
                VideoYoutube video = VideoYoutubeParser.desde(item, propiedades.regionCode());
                if (video.videoId() != null) {
                    porId.put(video.videoId(), video);
                }
            }
        }
        List<VideoYoutube> ordenados = new ArrayList<>();
        for (String id : ids) {
            VideoYoutube video = porId.get(id);
            if (video != null) {
                ordenados.add(video);
            }
        }
        return ordenados;
    }

    private JsonNode llamar(String plantilla, Map<String, ?> variables) {
        try {
            String cuerpo = http.get().uri(plantilla, variables).retrieve()
                    .onStatus(HttpStatusCode::isError, (peticion, respuesta) -> {
                        String leido = new String(respuesta.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw errorDelProveedor(respuesta.getStatusCode().value(), leido);
                    })
                    .body(String.class);
            return JSON.readTree(cuerpo == null ? "" : cuerpo);
        } catch (YoutubeException e) {
            throw e;
        } catch (JacksonException e) {
            log.warn("YouTube API answered something that is not JSON");
            throw new YoutubeException(Tipo.UNAVAILABLE);
        } catch (RuntimeException e) {
            // Only the class: the message of the HTTP layer contains the full URL, key included.
            log.warn("YouTube API call failed: {}", e.getClass().getSimpleName());
            throw new YoutubeException(Tipo.UNAVAILABLE);
        }
    }

    private static YoutubeException errorDelProveedor(int estado, String cuerpo) {
        String razon = razon(cuerpo);
        log.warn("YouTube API answered HTTP {} (reason: {})", estado, razon);
        // Only the daily reasons latch the exhaustion; rateLimitExceeded is a transient short-window limit
        // that fails this call alone (it is logged above with its reason and maps to UNAVAILABLE).
        boolean cuota = estado == 403 && ("quotaExceeded".equals(razon) || "dailyLimitExceeded".equals(razon));
        return new YoutubeException(cuota ? Tipo.QUOTA_EXCEEDED : Tipo.UNAVAILABLE);
    }

    /** The first error reason of a Google API error body ({@code error.errors[0].reason}), or "unknown". */
    private static String razon(String cuerpo) {
        try {
            String razon = VideoYoutubeParser.texto(JSON.readTree(cuerpo).path("error").path("errors").path(0).path("reason"));
            return razon == null ? "unknown" : razon.substring(0, Math.min(razon.length(), 60));
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
