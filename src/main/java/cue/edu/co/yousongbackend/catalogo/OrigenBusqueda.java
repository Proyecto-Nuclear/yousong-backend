package cue.edu.co.yousongbackend.catalogo;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a catalog search is made for. Staff may spend the whole daily quota; participants spend only their
 * share of it and the daily budget of their establishment (see {@link CuotaYoutube}).
 *
 * @param establecimientoId the participant's establishment, or null for staff
 */
public record OrigenBusqueda(UUID establecimientoId) {

    public static final OrigenBusqueda STAFF = new OrigenBusqueda(null);

    public static OrigenBusqueda participante(UUID establecimientoId) {
        return new OrigenBusqueda(Objects.requireNonNull(establecimientoId, "establecimientoId"));
    }

    public boolean esStaff() {
        return establecimientoId == null;
    }
}
