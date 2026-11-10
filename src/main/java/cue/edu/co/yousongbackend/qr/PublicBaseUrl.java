package cue.edu.co.yousongbackend.qr;

import java.net.URI;
import org.springframework.util.StringUtils;

/** Validates and normalizes the public address of the client app that the QR points to. */
final class PublicBaseUrl {

    private PublicBaseUrl() {
    }

    /** Returns the address without trailing slashes; fails fast when it cannot be a web address. */
    static String normalizar(String valor) {
        if (!StringUtils.hasText(valor)) {
            throw new IllegalStateException("app.public-base-url (PUBLIC_BASE_URL) must not be blank: "
                    + "the QR codes would point nowhere");
        }
        String limpio = valor.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(limpio);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.public-base-url is not a valid URL: " + valor, e);
        }
        boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (!web || !StringUtils.hasText(uri.getHost())) {
            throw new IllegalStateException("app.public-base-url must be an http(s) address with a host: " + valor);
        }
        return limpio;
    }
}
