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
 * <p>Joining is public and so is creating an establishment, so both kinds of caller are bounded twice, each in a
 * bucket of its own:
 * <ul>
 *   <li>participants: all together at most {@code app.youtube.participant-quota-share} of the daily limit, and the
 *       participants of one establishment at most {@code app.participante.search-units-per-establishment-per-day};</li>
 *   <li>establishment owners: all together at most {@code app.youtube.owner-quota-share}, and each establishment's
 *       owner at most {@code app.propietario.search-units-per-establishment-per-day} (429
 *       {@code SEARCH_BUDGET_EXHAUSTED}).</li>
 * </ul>
 * Whatever neither share covers stays a reserve for internal (system) callers. Both kinds count toward the
 * total, and an owner exhausting their budget never blocks that establishment's patrons, nor the reverse.
 *
 * <p>Trade-off, on purpose: many self-registered accounts together can still use up the OWNER share and block
 * other owners' NEW provider calls until the reset (cache hits and songs already stored keep working), but they
 * can no longer starve the patrons, whose share is separate.
 *
 * <p>Every counter is per instance, in memory, reset at the same Pacific midnight and on every restart; with
 * several instances each one allows its own amounts.
 */
@Component
public class CuotaYoutube {

    /** search.list costs 100 units, videos.list costs 1 per call. */
    public static final int COSTO_BUSQUEDA = 100;
    public static final int COSTO_DETALLES = 1;

    private static final ZoneId ZONA = ZoneId.of("America/Los_Angeles");

    /**
     * One share of the daily quota and the daily budget of each establishment within it.
     *
     * @param agotado          what a used establishment budget answers
     * @param cuotaAgotada     what a used share answers
     * @param presupuestoPrimero whether the establishment budget is checked before the quota and the share, so an
     *                         owner over their own budget always gets the budget error
     */
    private static final class Reparto {
        private final int limite;
        private final int presupuestoPorLocal;
        private final Tipo agotado;
        private final Tipo cuotaAgotada;
        private final boolean presupuestoPrimero;
        private final Map<UUID, Integer> usadasPorLocal = new HashMap<>();
        private int usadas;

        private Reparto(int limite, int presupuestoPorLocal, Tipo agotado, Tipo cuotaAgotada, boolean presupuestoPrimero) {
            this.limite = limite;
            this.presupuestoPorLocal = Math.max(0, presupuestoPorLocal);
            this.agotado = agotado;
            this.cuotaAgotada = cuotaAgotada;
            this.presupuestoPrimero = presupuestoPrimero;
        }

        private void reiniciar() {
            usadas = 0;
            usadasPorLocal.clear();
        }
    }

    private final Clock reloj;
    private final int limite;
    private final Reparto participantes;
    private final Reparto propietarios;
    private LocalDate dia;
    private int usadas;
    private boolean agotada;

    CuotaYoutube(YoutubeProperties propiedades, Clock reloj,
                 @Value("${app.youtube.participant-quota-share:0.7}") double participacion,
                 @Value("${app.participante.search-units-per-establishment-per-day:2500}") int presupuestoPorLocal,
                 @Value("${app.youtube.owner-quota-share:0.25}") double participacionPropietarios,
                 @Value("${app.propietario.search-units-per-establishment-per-day:1000}") int presupuestoPropietario) {
        this.reloj = reloj;
        this.limite = propiedades.dailyQuotaLimit();
        validar(participacion, participacionPropietarios);
        this.participantes = new Reparto(parte(limite, participacion), presupuestoPorLocal, Tipo.BUDGET_EXHAUSTED,
                Tipo.QUOTA_EXCEEDED, false);
        this.propietarios = new Reparto(parte(limite, participacionPropietarios), presupuestoPropietario,
                Tipo.OWNER_BUDGET_EXHAUSTED, Tipo.OWNER_SHARE_EXHAUSTED, true);
    }

    /** Fails fast on shares outside [0, 1] or adding up to more than the whole daily limit. */
    private static void validar(double participacion, double participacionPropietarios) {
        if (!(participacion >= 0 && participacion <= 1)) {
            throw new IllegalStateException("app.youtube.participant-quota-share must be between 0 and 1, was "
                    + participacion);
        }
        if (!(participacionPropietarios >= 0 && participacionPropietarios <= 1)) {
            throw new IllegalStateException("app.youtube.owner-quota-share must be between 0 and 1, was "
                    + participacionPropietarios);
        }
        if (participacion + participacionPropietarios > 1) {
            throw new IllegalStateException("app.youtube.participant-quota-share (" + participacion
                    + ") plus app.youtube.owner-quota-share (" + participacionPropietarios + ") must not exceed 1");
        }
    }

    private static int parte(int limite, double participacion) {
        return (int) Math.floor(limite * participacion);
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

    /** {@link #reservar(OrigenBusqueda, int)} for the participants of an establishment. */
    synchronized LocalDate reservar(UUID establecimientoId, int unidades) {
        return reservar(OrigenBusqueda.participante(establecimientoId), unidades);
    }

    /**
     * Charges a participant or owner call up front, before any provider call, so concurrent requests cannot
     * overshoot. Nothing is charged when it is refused.
     *
     * @return the quota day the units were charged to, to hand back to {@link #devolver} if the call fails
     * @throws YoutubeException {@code QUOTA_EXCEEDED} when the daily quota would be passed (or the participant
     *                          share), {@code OWNER_SHARE_EXHAUSTED} when the owner share would be passed,
     *                          {@code BUDGET_EXHAUSTED} / {@code OWNER_BUDGET_EXHAUSTED} when the establishment's
     *                          daily budget would be passed. For an owner the budget is checked first, so it wins
     *                          when both apply.
     */
    public synchronized LocalDate reservar(OrigenBusqueda origen, int unidades) {
        Reparto reparto = reparto(origen);
        rotar();
        int delLocal = reparto.usadasPorLocal.getOrDefault(origen.establecimientoId(), 0);
        boolean sinPresupuesto = delLocal + unidades > reparto.presupuestoPorLocal;
        if (sinPresupuesto && reparto.presupuestoPrimero) {
            throw new YoutubeException(reparto.agotado);
        }
        if (agotada || usadas + unidades > limite) {
            throw new YoutubeException(Tipo.QUOTA_EXCEEDED);
        }
        if (reparto.usadas + unidades > reparto.limite) {
            throw new YoutubeException(reparto.cuotaAgotada);
        }
        if (sinPresupuesto) {
            throw new YoutubeException(reparto.agotado);
        }
        reparto.usadasPorLocal.put(origen.establecimientoId(), delLocal + unidades);
        reparto.usadas += unidades;
        usadas += unidades;
        return dia;
    }

    /** {@link #devolver(OrigenBusqueda, LocalDate, int)} for the participants of an establishment. */
    synchronized void devolver(UUID establecimientoId, LocalDate diaReserva, int unidades) {
        devolver(OrigenBusqueda.participante(establecimientoId), diaReserva, unidades);
    }

    /**
     * Gives back reserved units that the provider did not spend (the call failed, or fewer details were needed).
     * Ignored when the quota day already rolled over, so a refund never reduces the next day's count.
     */
    public synchronized void devolver(OrigenBusqueda origen, LocalDate diaReserva, int unidades) {
        Reparto reparto = reparto(origen);
        rotar();
        if (!diaReserva.equals(dia) || unidades <= 0) {
            return;
        }
        usadas = Math.max(0, usadas - unidades);
        reparto.usadas = Math.max(0, reparto.usadas - unidades);
        reparto.usadasPorLocal.computeIfPresent(origen.establecimientoId(), (id, usado) -> Math.max(0, usado - unidades));
    }

    private Reparto reparto(OrigenBusqueda origen) {
        if (origen.esStaff()) {
            throw new IllegalArgumentException("System calls are not reserved: they are counted with consumir");
        }
        return origen.esPropietario() ? propietarios : participantes;
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
            participantes.reiniciar();
            propietarios.reiniciar();
            agotada = false;
        }
    }
}
