package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.shared.web.ApiException;

/** The provider could not answer. The message is safe to show: it never contains the key or provider details. */
public class YoutubeException extends RuntimeException {

    public enum Tipo {
        NOT_CONFIGURED("CATALOG_NOT_CONFIGURED", "The YouTube catalog is not configured"),
        QUOTA_EXCEEDED("CATALOG_QUOTA_EXCEEDED", "The YouTube daily quota is exhausted; try again later"),
        UNAVAILABLE("CATALOG_UNAVAILABLE", "The YouTube catalog is not available right now");

        private final String code;
        private final String message;

        Tipo(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private final Tipo tipo;

    public YoutubeException(Tipo tipo) {
        super(tipo.message);
        this.tipo = tipo;
    }

    public Tipo tipo() {
        return tipo;
    }

    public ApiException aApi() {
        return ApiException.serviceUnavailable(tipo.code, tipo.message);
    }
}
