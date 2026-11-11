package cue.edu.co.yousongbackend.catalogo;

import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** Turns one videos.list item (parts snippet, contentDetails, status) into a {@link VideoYoutube}. */
final class VideoYoutubeParser {

    static final String NO_INCRUSTABLE = "NOT_EMBEDDABLE";
    static final String NO_PUBLICO = "NOT_PUBLIC";
    static final String NO_PROCESADO = "NOT_PROCESSED";
    static final String EN_VIVO = "LIVE_BROADCAST";
    static final String BLOQUEADO_EN_REGION = "REGION_BLOCKED";

    private VideoYoutubeParser() {
    }

    /**
     * A video can be used only when it can be embedded, is public and fully processed, is not a live or
     * upcoming broadcast, and is not blocked in {@code region}. Anything the provider does not state about
     * embedding, privacy or processing counts against the video: we cannot promise it will play.
     */
    static VideoYoutube desde(JsonNode item, String region) {
        JsonNode snippet = item.path("snippet");
        JsonNode detalles = item.path("contentDetails");
        JsonNode estado = item.path("status");

        String motivo = motivoNoDisponible(snippet, detalles, estado, region);
        Integer duracion = DuracionIso8601.segundos(texto(detalles.path("duration")));
        return new VideoYoutube(
                texto(item.path("id")),
                texto(snippet.path("title")),
                texto(snippet.path("channelTitle")),
                miniatura(snippet.path("thumbnails")),
                duracion,
                motivo == null,
                motivo);
    }

    private static String motivoNoDisponible(JsonNode snippet, JsonNode detalles, JsonNode estado, String region) {
        if (!estado.path("embeddable").asBoolean(false)) {
            return NO_INCRUSTABLE;
        }
        if (!"public".equals(texto(estado.path("privacyStatus")))) {
            return NO_PUBLICO;
        }
        if (!"processed".equals(texto(estado.path("uploadStatus")))) {
            return NO_PROCESADO;
        }
        String envivo = texto(snippet.path("liveBroadcastContent"));
        if (envivo != null && !"none".equals(envivo)) {
            return EN_VIVO;
        }
        return bloqueadoEn(detalles.path("regionRestriction"), region) ? BLOQUEADO_EN_REGION : null;
    }

    private static boolean bloqueadoEn(JsonNode restriccion, String region) {
        if (!restriccion.isObject() || region == null) {
            return false;
        }
        if (restriccion.path("blocked").isArray() && contiene(restriccion.path("blocked"), region)) {
            return true;
        }
        return restriccion.path("allowed").isArray() && !contiene(restriccion.path("allowed"), region);
    }

    private static boolean contiene(JsonNode paises, String region) {
        for (JsonNode pais : paises) {
            if (pais.isString() && pais.asString().toUpperCase(Locale.ROOT).equals(region.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String miniatura(JsonNode miniaturas) {
        for (String tamano : new String[] {"medium", "high", "default"}) {
            String url = texto(miniaturas.path(tamano).path("url"));
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }

    static String texto(JsonNode nodo) {
        return nodo.isString() ? nodo.asString() : null;
    }
}
