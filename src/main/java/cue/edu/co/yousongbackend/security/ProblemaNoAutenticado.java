package cue.edu.co.yousongbackend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a 401 as an RFC 9457 problem detail with the same shape the global exception handler produces
 * ({@code type, title, status, detail, instance, code}). Security filters run before Spring MVC, so the
 * handler cannot do it for them.
 */
final class ProblemaNoAutenticado {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProblemaNoAutenticado() {
    }

    static void escribir(HttpServletRequest request, HttpServletResponse response, String code, String detail)
            throws IOException {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        Map<String, Object> problema = new LinkedHashMap<>();
        problema.put("type", "about:blank");
        problema.put("title", status.getReasonPhrase());
        problema.put("status", status.value());
        problema.put("detail", detail);
        problema.put("instance", request.getRequestURI());
        problema.put("code", code);

        response.setStatus(status.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(JSON.writeValueAsBytes(problema));
    }
}
