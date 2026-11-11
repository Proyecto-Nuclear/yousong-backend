package cue.edu.co.yousongbackend.catalogo;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * In-memory count of the YouTube quota units used today. YouTube resets the quota at midnight Pacific time,
 * so "today" is a day in America/Los_Angeles. The count starts at zero on every restart, which can
 * under-count; the provider's own quotaExceeded answer is the final authority ({@link #agotar()}).
 */
@Component
public class CuotaYoutube {

    /** search.list costs 100 units, videos.list costs 1 per call. */
    public static final int COSTO_BUSQUEDA = 100;
    public static final int COSTO_DETALLES = 1;

    private static final ZoneId ZONA = ZoneId.of("America/Los_Angeles");

    private final Clock reloj;
    private final int limite;
    private LocalDate dia;
    private int usadas;
    private boolean agotada;

    CuotaYoutube(YoutubeProperties propiedades, Clock reloj) {
        this.reloj = reloj;
        this.limite = propiedades.dailyQuotaLimit();
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
            agotada = false;
        }
    }
}
