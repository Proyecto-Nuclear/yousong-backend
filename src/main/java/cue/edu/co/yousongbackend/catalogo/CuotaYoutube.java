package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.catalogo.YoutubeException.Tipo;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory count of the YouTube quota units used today. YouTube resets the quota at midnight Pacific time,
 * so "today" is a day in America/Los_Angeles. The count starts at zero on every restart, which can
 * under-count; the provider's own quotaExceeded answer is the final authority ({@link #agotar()}).
 *
 * <p>Joining is public, so participant searches are bounded twice: all participants together may spend only
 * {@code app.youtube.participant-quota-share} of the daily limit (the rest is a staff reserve), and the
 * participants of one establishment at most {@code app.participante.search-units-per-establishment-per-day}.
 * Both counters are per instance, in memory, reset at the same Pacific midnight and on every restart.
 */
@Component
public class CuotaYoutube {

    /** search.list costs 100 units, videos.list costs 1 per call. */
    public static final int COSTO_BUSQUEDA = 100;
    public static final int COSTO_DETALLES = 1;

    private static final ZoneId ZONA = ZoneId.of("America/Los_Angeles");

    private final Clock reloj;
    private final int limite;
    private final int limiteParticipantes;
    private final int presupuestoPorLocal;
    private final Map<UUID, Integer> usadasPorLocal = new HashMap<>();
    private LocalDate dia;
    private int usadas;
    private int usadasParticipantes;
    private boolean agotada;

    CuotaYoutube(YoutubeProperties propiedades, Clock reloj,
                 @Value("${app.youtube.participant-quota-share:0.7}") double participacion,
                 @Value("${app.participante.search-units-per-establishment-per-day:2500}") int presupuestoPorLocal) {
        this.reloj = reloj;
        this.limite = propiedades.dailyQuotaLimit();
        this.limiteParticipantes = (int) Math.floor(limite * Math.max(0, Math.min(1, participacion)));
        this.presupuestoPorLocal = Math.max(0, presupuestoPorLocal);
    }

    /** True while calls may still be made today. */
    public synchronized boolean disponible() {
        rotar();
        return !agotada && usadas < limite;
    }

    public synchronized void consumir(int unidades) {
        rotar();
        usadas += unidades;
    }

    /**
     * Charges a participant search up front, before any provider call, so concurrent requests cannot overshoot.
     * Nothing is charged when it is refused.
     *
     * @return the quota day the units were charged to, to hand back to {@link #devolver} if the call fails
     * @throws YoutubeException {@code QUOTA_EXCEEDED} when the quota or the participant share would be passed,
     *                          {@code BUDGET_EXHAUSTED} when the establishment's daily budget would be passed
     */
    public synchronized LocalDate reservar(UUID establecimientoId, int unidades) {
        rotar();
        if (agotada || usadas + unidades > limite || usadasParticipantes + unidades > limiteParticipantes) {
            throw new YoutubeException(Tipo.QUOTA_EXCEEDED);
        }
        int delLocal = usadasPorLocal.getOrDefault(establecimientoId, 0);
        if (delLocal + unidades > presupuestoPorLocal) {
            throw new YoutubeException(Tipo.BUDGET_EXHAUSTED);
        }
        usadasPorLocal.put(establecimientoId, delLocal + unidades);
        usadasParticipantes += unidades;
        usadas += unidades;
        return dia;
    }

    /**
     * Gives back reserved units that the provider did not spend (the call failed, or fewer details were needed).
     * Ignored when the quota day already rolled over, so a refund never reduces the next day's count.
     */
    public synchronized void devolver(UUID establecimientoId, LocalDate diaReserva, int unidades) {
        rotar();
        if (!diaReserva.equals(dia) || unidades <= 0) {
            return;
        }
        usadas = Math.max(0, usadas - unidades);
        usadasParticipantes = Math.max(0, usadasParticipantes - unidades);
        usadasPorLocal.computeIfPresent(establecimientoId, (id, usado) -> Math.max(0, usado - unidades));
    }

    /** Units counted today (for tests and diagnostics). */
    synchronized int usadas() {
        rotar();
        return usadas;
    }

    /** The provider said the quota is gone: no more calls until the next reset. */
    public synchronized void agotar() {
        rotar();
        agotada = true;
    }

    private void rotar() {
        LocalDate hoy = LocalDate.now(reloj.withZone(ZONA));
        if (!hoy.equals(dia)) {
            dia = hoy;
            usadas = 0;
            usadasParticipantes = 0;
            usadasPorLocal.clear();
            agotada = false;
        }
    }
}
