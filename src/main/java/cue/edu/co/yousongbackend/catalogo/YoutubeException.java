package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.shared.web.ApiException;

/** The provider could not answer. The message is safe to show: it never contains the key or provider details. */
public class YoutubeException extends RuntimeException {

    public enum Tipo {
        NOT_CONFIGURED("CATALOG_NOT_CONFIGURED", "The YouTube catalog is not configured"),
        QUOTA_EXCEEDED("CATALOG_QUOTA_EXCEEDED", "The YouTube daily quota is exhausted; try again later"),
        UNAVAILABLE("CATALOG_UNAVAILABLE", "The YouTube catalog is not available right now"),
        /** Not a provider failure: the establishment used its daily search budget. Answered as 429. */
        BUDGET_EXHAUSTED("SEARCH_BUDGET_EXHAUSTED",
                "This place has used its catalog searches for today; ask the staff for your song"),
        /** Not a provider failure: the establishment owner used their daily provider budget. Answered as 429. */
        OWNER_BUDGET_EXHAUSTED("SEARCH_BUDGET_EXHAUSTED",
                "Your establishment has used its YouTube lookups for today; songs already in the catalog still work. "
                        + "Try again after the daily reset");

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
        if (tipo == Tipo.BUDGET_EXHAUSTED || tipo == Tipo.OWNER_BUDGET_EXHAUSTED) {
            return ApiException.tooManyRequests(tipo.code, tipo.message);
        }
        return ApiException.serviceUnavailable(tipo.code, tipo.message);
    }
}
