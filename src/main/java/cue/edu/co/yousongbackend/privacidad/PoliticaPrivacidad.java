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

    /** A titled block of the full notice, in the order a client shows them. */
    public record Seccion(String titulo, String texto) {
    }

    private static final List<Finalidad> FINALIDADES = List.of(
            new Finalidad(FinalidadConsentimiento.F1,
                    "Participar en la noche musical: pedir y votar canciones, controlar tus apoyos, operar el "
                            + "servicio, atender incidentes y generar estadísticas de la jornada. Es necesario para "
                            + "participar."),
            new Finalidad(FinalidadConsentimiento.F2,
                    "Enviar retroalimentación voluntaria sobre la facilidad de uso de la aplicación. Es opcional y "
                            + "se autoriza por separado: puedes participar sin aceptarlo."));

    // Content from section 7 of the project document. The controller's legal data (name, address, e-mail,
    // phone) is not invented here: it must be filled in with the chosen venue before using real data.
    private static final List<Seccion> SECCIONES = List.of(
            new Seccion("Responsable",
                    "El responsable del tratamiento es quien opera este piloto en el establecimiento. Pide en el "
                            + "local su nombre, sus datos de contacto y el canal para ejercer tus derechos."),
            new Seccion("Qué datos se tratan",
                    "No te registras ni das tu nombre o correo. Se usa un identificador técnico de tu teléfono, un "
                            + "alias, tus solicitudes, tus votos y la hora en que los haces. Aunque no digan tu nombre, "
                            + "se tratan como datos personales. La cola pública no muestra identidades."),
            new Seccion("Lo que no hacemos",
                    "No hay publicidad dirigida, venta de datos, biometría, ubicación precisa ni seguimiento "
                            + "individual entre jornadas. No se recogen datos sensibles."),
            new Seccion("Cuánto tiempo se conservan",
                    "Identificador del teléfono: hasta 30 días sin actividad. Detalle de la jornada: hasta 90 días "
                            + "desde el cierre. Estadísticas agregadas: 12 meses, sin mostrar grupos de menos de "
                            + "5 personas. Respaldos: máximo 30 días."),
            new Seccion("Tus derechos",
                    "Puedes conocer, actualizar y rectificar tus datos, acceder a ellos sin costo, pedir prueba de "
                            + "tu autorización, saber cómo se usan, pedir que se supriman y revocar la autorización. "
                            + "Las consultas se responden en 10 días hábiles y los reclamos en 15 días hábiles, con las "
                            + "prórrogas que permite la ley. Después de ese trámite puedes acudir a la "
                            + "Superintendencia de Industria y Comercio."),
            new Seccion("Normas aplicables",
                    "Artículo 15 de la Constitución, Ley 1581 de 2012, Decreto 1074 de 2015, Decreto 90 de 2018 y "
                            + "Ley 1273 de 2009."),
            new Seccion("Otras condiciones",
                    "El piloto es para mayores de edad. Las canciones se reproducen con el reproductor de YouTube, "
                            + "que aplica sus propias políticas. Si rechazas el primer permiso puedes escuchar y "
                            + "consultar, pero no pedir ni votar."));

    private final String version;

    PoliticaPrivacidad(@Value("${app.privacy.policy-version:1.1}") String version) {
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

    public List<Seccion> secciones() {
        return SECCIONES;
    }
}
