package cue.edu.co.yousongbackend.catalogo;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a provider call is made for, which decides the part of the daily quota it may spend (see
 * {@link CuotaYoutube}). Participants and establishment owners each spend only their share of the quota and a
 * daily budget per establishment, in separate buckets; the system origin is for internal callers only and is
 * bounded by the daily limit alone. No HTTP route uses the system origin: anyone can register and create an
 * establishment, so every owner route charges {@link #propietario}.
 *
 * @param quien             the kind of caller
 * @param establecimientoId the establishment charged, or null for the system
 */
public record OrigenBusqueda(Quien quien, UUID establecimientoId) {

    public enum Quien { SISTEMA, PARTICIPANTE, PROPIETARIO }

    /** Internal (system) callers: no share and no per-establishment budget, only the daily limit. */
    public static final OrigenBusqueda STAFF = new OrigenBusqueda(Quien.SISTEMA, null);

    public OrigenBusqueda {
        Objects.requireNonNull(quien, "quien");
        if ((quien == Quien.SISTEMA) != (establecimientoId == null)) {
            throw new IllegalArgumentException("Only the system origin has no establishment");
        }
    }

    public static OrigenBusqueda participante(UUID establecimientoId) {
        return new OrigenBusqueda(Quien.PARTICIPANTE, Objects.requireNonNull(establecimientoId, "establecimientoId"));
    }

    /** An owner route of {@code establecimientoId}: charged to the owners' share and that establishment's budget. */
    public static OrigenBusqueda propietario(UUID establecimientoId) {
        return new OrigenBusqueda(Quien.PROPIETARIO, Objects.requireNonNull(establecimientoId, "establecimientoId"));
    }

    public boolean esStaff() {
        return quien == Quien.SISTEMA;
    }

    public boolean esPropietario() {
        return quien == Quien.PROPIETARIO;
    }
}
