package cue.edu.co.yousongbackend.qr;

import java.net.URI;
import java.util.Set;
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

    private static final Set<String> HOSTS_DE_ESTA_MAQUINA = Set.of("localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0");

    /**
     * True when the address only works on the machine running the server. Fine in development, but a QR
     * printed with it in production sends every patron to their own phone.
     */
    static boolean apuntaAEstaMaquina(String direccionNormalizada) {
        String host = URI.create(direccionNormalizada).getHost();
        return host != null && HOSTS_DE_ESTA_MAQUINA.contains(host.toLowerCase());
    }
}
