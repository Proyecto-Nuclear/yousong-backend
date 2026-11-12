package cue.edu.co.yousongbackend.participante;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The device credential: an opaque secret {@code dv_} + base64url(32 random bytes), 256 bits of entropy, kept
 * by the client so the same phone is recognized on its next join without registration or personal data. It
 * is derived from nothing about the device (no MAC, IMEI, IP, user agent or fingerprint).
 *
 * <p>Only its SHA-256 (hex, same function as {@link TokenParticipante#hash}) is stored in
 * {@code dispositivo.credencial_hash}; the secret itself is returned once, by the join that mints it, and is
 * never stored, logged or echoed.
 */
final class TokenDispositivo {

    static final String PREFIJO = "dv_";

    private static final int BYTES = 32;
    private static final Pattern FORMATO = Pattern.compile("dv_[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenDispositivo() {
    }

    static String generar() {
        byte[] secreto = new byte[BYTES];
        RANDOM.nextBytes(secreto);
        return PREFIJO + Base64.getUrlEncoder().withoutPadding().encodeToString(secreto);
    }

    /** Cheap shape check, so garbage never reaches the database. */
    static boolean formatoValido(String token) {
        return token != null && FORMATO.matcher(token).matches();
    }

    /** Lowercase hex SHA-256 of the whole token, as stored in {@code dispositivo.credencial_hash}. */
    static String hash(String token) {
        return TokenParticipante.hash(token);
    }
}
