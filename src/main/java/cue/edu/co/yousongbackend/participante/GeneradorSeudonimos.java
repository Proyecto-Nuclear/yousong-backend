package cue.edu.co.yousongbackend.participante;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Random participant pseudonyms ({@code p-} and 6 hex digits), unique only inside one session. */
@Component
class GeneradorSeudonimos {

    private static final SecureRandom RANDOM = new SecureRandom();

    String nuevo() {
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return "p-" + HexFormat.of().formatHex(bytes);
    }
}
