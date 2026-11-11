package cue.edu.co.yousongbackend.privacidad;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The privacy policy a participant accepts before joining. Its version is recorded on every consent, so a
 * new version (a new {@code app.privacy.policy-version}) makes clients that show an older text ask again.
 *
 * <p>F1 is required to participate; F2 is optional and recorded only when the participant authorizes it.
 */
@Component
public class PoliticaPrivacidad {

    public record Finalidad(FinalidadConsentimiento codigo, String descripcion) {
    }

    private static final List<Finalidad> FINALIDADES = List.of(
            new Finalidad(FinalidadConsentimiento.F1,
                    "Participation in the music session: request and vote for songs, control of the supports you use, "
                            + "operation of the service, handling of incidents and session statistics. "
                            + "Required to participate."),
            new Finalidad(FinalidadConsentimiento.F2,
                    "Voluntary feedback about the usability of the application. Optional and separate: "
                            + "you can participate without it."));

    private final String version;

    PoliticaPrivacidad(@Value("${app.privacy.policy-version:1.0}") String version) {
        if (!StringUtils.hasText(version)) {
            throw new IllegalStateException("app.privacy.policy-version must not be blank");
        }
        this.version = version.strip();
    }

    public String version() {
        return version;
    }

    public List<Finalidad> finalidades() {
        return FINALIDADES;
    }
}
