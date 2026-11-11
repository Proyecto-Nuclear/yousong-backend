package cue.edu.co.yousongbackend.catalogo;

import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The UNOFFICIAL YouTube suggest endpoint ({@code suggestqueries.google.com/complete/search?client=firefox&ds=yt})
 * over HTTP. It is undocumented and can break without notice, so the answer is parsed defensively. It costs no
 * YouTube Data API quota. The request carries only the query and the language: no cookies (the JDK client
 * has no cookie handler), no credentials and a generic User-Agent. No log line, exception message or cause
 * leaving this class contains the query (HTTP-layer messages include the URL, which includes it).
 */
@Slf4j
@Component
class SugerenciasYoutubeHttp implements SugerenciasRemotas {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAXIMO_LEIDAS = 20;
    private static final int LARGO_MAXIMO = 200;

    private final String plantilla;
    private final String idioma;
    private final RestClient http;

    SugerenciasYoutubeHttp(SugerenciasProperties propiedades, YoutubeProperties youtube) {
        this.plantilla = propiedades.baseUrl() + "?client=firefox&ds=yt&hl={hl}&q={q}";
        this.idioma = youtube.relevanceLanguage();
        Duration limite = Duration.ofMillis(propiedades.timeoutMillis());
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(limite)
                .build());
        fabrica.setReadTimeout(limite);
        this.http = RestClient.builder()
                .requestFactory(fabrica)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0")
                .build();
    }

    @Override
    public List<String> sugerir(String consulta) {
        ResponseEntity<byte[]> respuesta;
        try {
            respuesta = http.get().uri(plantilla, Map.of("hl", idioma, "q", consulta)).retrieve()
                    .onStatus(HttpStatusCode::isError, (peticion, r) -> {
                        throw new SugerenciasRemotasException("YouTube suggest answered HTTP " + r.getStatusCode().value());
                    })
                    .toEntity(byte[].class);
        } catch (SugerenciasRemotasException e) {
            log.warn("YouTube suggest call failed: {}", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.warn("YouTube suggest call failed: {}", e.getClass().getSimpleName());
            throw new SugerenciasRemotasException("YouTube suggest call failed");
        }
        if (!respuesta.getStatusCode().is2xxSuccessful()) {
            log.warn("YouTube suggest answered HTTP {}", respuesta.getStatusCode().value());
            throw new SugerenciasRemotasException("YouTube suggest answered HTTP " + respuesta.getStatusCode().value());
        }
        return leer(respuesta);
    }

    private static List<String> leer(ResponseEntity<byte[]> respuesta) {
        byte[] cuerpo = respuesta.getBody() == null ? new byte[0] : respuesta.getBody();
        JsonNode raiz;
        try {
            raiz = JSON.readTree(new String(cuerpo, juego(respuesta.getHeaders().getContentType())));
        } catch (JacksonException e) {
            log.warn("YouTube suggest answered something that is not JSON");
            throw new SugerenciasRemotasException("YouTube suggest answered something that is not JSON");
        }
        // Expected: ["query", ["suggestion 1", "suggestion 2", ...], ...]. Anything else: no suggestions.
        if (raiz == null || !raiz.isArray() || raiz.size() < 2 || !raiz.get(1).isArray()) {
            return List.of();
        }
        Set<String> leidas = new LinkedHashSet<>();
        for (JsonNode nodo : raiz.get(1)) {
            if (leidas.size() >= MAXIMO_LEIDAS) {
                break;
            }
            if (nodo != null && nodo.isString()) {
                String texto = nodo.asString().strip();
                if (!texto.isEmpty() && texto.length() <= LARGO_MAXIMO) {
                    leidas.add(texto);
                }
            }
        }
        return new ArrayList<>(leidas);
    }

    private static Charset juego(MediaType tipo) {
        try {
            return tipo != null && tipo.getCharset() != null ? tipo.getCharset() : StandardCharsets.UTF_8;
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
