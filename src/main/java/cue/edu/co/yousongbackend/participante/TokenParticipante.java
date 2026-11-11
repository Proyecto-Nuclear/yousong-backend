package cue.edu.co.yousongbackend.participante;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The participant credential: an opaque bearer secret {@code pt_} + base64url(32 random bytes), 256 bits of
 * entropy. Only its SHA-256 (hex) is stored; the secret itself is returned once and never stored or logged.
 *
 * <p>A plain (unsalted) hash is enough here because the secret is random and long, not a password: it cannot
 * be guessed or looked up in a dictionary, and a fast hash lets one indexed query find the credential.
 */
final class TokenParticipante {

    static final String PREFIJO = "pt_";

    private static final int BYTES = 32;
    private static final Pattern FORMATO = Pattern.compile("pt_[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenParticipante() {
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

    /** Lowercase hex SHA-256 of the whole token, as stored in {@code sesion_acceso.refresh_hash}. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
