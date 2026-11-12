package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.control.AuditoriaService;
import cue.edu.co.yousongbackend.control.ResultadoAuditoria;
import cue.edu.co.yousongbackend.establecimiento.HorarioDtos.VentanaRequest;
import cue.edu.co.yousongbackend.establecimiento.HorarioDtos.VentanaResponse;
import cue.edu.co.yousongbackend.establecimiento.HorarioSemanal.Ventana;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weekly windows of an establishment ({@code operacion.horario}). With at least one active window the QR only
 * admits participants inside a window (see {@link HorarioSemanal}); without windows nothing is restricted.
 *
 * <p>The owner replaces the whole weekly set at once. Replacing deletes the previous rows and inserts new ones
 * (new ids): windows have no identity of their own and nothing references them. Callers pass the establishment
 * of the current owner (see {@link PropietarioActual}).
 *
 * <p>Rows are written and read with plain JDBC: {@code inicio} and {@code fin} are wall-clock {@code time} values,
 * and binding them through Hibernate would shift them by the JVM-to-UTC offset ({@code hibernate.jdbc.time_zone}).
 */
@Service
public class HorarioService {

    static final int MAX_VENTANAS = 21;

    private static final Pattern HORA = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("HH:mm");
    private static final Comparator<Ventana> ORDEN =
            Comparator.comparing(Ventana::dia).thenComparing(Ventana::inicio);

    private final EstablecimientoService establecimientos;
    private final CambiosConfiguracion cambios;
    private final AuditoriaService auditoria;
    private final JdbcTemplate jdbc;

    HorarioService(EstablecimientoService establecimientos, CambiosConfiguracion cambios, AuditoriaService auditoria,
                   JdbcTemplate jdbc) {
        this.establecimientos = establecimientos;
        this.cambios = cambios;
        this.auditoria = auditoria;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<VentanaResponse> listar(UUID establecimientoId) {
        return ventanasDe(establecimientoId).stream().map(HorarioService::respuesta).toList();
    }

    /**
     * Replaces the whole weekly set atomically (an empty list removes every window). The set is validated as a
     * whole first, so a rejected request changes nothing.
     */
    @Transactional
    public List<VentanaResponse> reemplazar(UUID establecimientoId, List<VentanaRequest> solicitud) {
        List<Ventana> ventanas = validar(solicitud);
        // The row lock serializes replacements of one establishment and versions the configuration event.
        establecimientos.obtenerBloqueando(establecimientoId);
        jdbc.update("DELETE FROM operacion.horario WHERE establecimiento_id = ?", establecimientoId);
        for (Ventana ventana : ventanas) {
            jdbc.update("INSERT INTO operacion.horario (id, establecimiento_id, dia_semana, inicio, fin, cruza_medianoche, "
                            + "activo) VALUES (?, ?, ?, ?, ?, ?, true)",
                    UUID.randomUUID(), establecimientoId, (short) ventana.dia().getValue(), ventana.inicio(), ventana.fin(),
                    ventana.cruzaMedianoche());
        }
        cambios.publicar(establecimientoId, List.of("horario"));
        auditoria.registrar(establecimientoId, "HORARIO_REEMPLAZADO", "ESTABLECIMIENTO", establecimientoId,
                ResultadoAuditoria.EXITO);
        return ventanas.stream().sorted(ORDEN).map(HorarioService::respuesta).toList();
    }

    /** The schedule of the establishment in its own time zone (no restriction when it has no active window). */
    @Transactional(readOnly = true)
    public HorarioSemanal horarioDe(Establecimiento establecimiento) {
        return HorarioSemanal.de(ventanasDe(establecimiento.getId()), ZoneId.of(establecimiento.getZonaHoraria()));
    }

    private List<Ventana> ventanasDe(UUID establecimientoId) {
        return jdbc.query("SELECT dia_semana, inicio, fin FROM operacion.horario WHERE establecimiento_id = ? "
                        + "AND activo ORDER BY dia_semana, inicio",
                (fila, n) -> new Ventana(DayOfWeek.of(fila.getInt("dia_semana")), fila.getObject("inicio", LocalTime.class),
                        fila.getObject("fin", LocalTime.class)),
                establecimientoId);
    }

    private static List<Ventana> validar(List<VentanaRequest> solicitud) {
        if (solicitud == null) {
            throw invalido("Send the weekly windows as a JSON array");
        }
        if (solicitud.size() > MAX_VENTANAS) {
            throw ApiException.badRequest("TOO_MANY_WINDOWS", "At most " + MAX_VENTANAS + " weekly windows are allowed");
        }
        List<Ventana> ventanas = new ArrayList<>(solicitud.size());
        for (VentanaRequest ventana : solicitud) {
            ventanas.add(ventana(ventana));
        }
        for (int i = 0; i < ventanas.size(); i++) {
            for (int j = i + 1; j < ventanas.size(); j++) {
                if (HorarioSemanal.seSolapan(ventanas.get(i), ventanas.get(j))) {
                    throw ApiException.badRequest("SCHEDULE_OVERLAP", "Window " + (i + 1) + " overlaps window " + (j + 1));
                }
            }
        }
        return ventanas;
    }

    private static Ventana ventana(VentanaRequest solicitud) {
        if (solicitud == null || solicitud.diaSemana() == null || solicitud.diaSemana() < 1 || solicitud.diaSemana() > 7) {
            throw invalido("diaSemana must be 1 (Monday) to 7 (Sunday)");
        }
        LocalTime inicio = hora(solicitud.inicio(), "inicio");
        LocalTime fin = hora(solicitud.fin(), "fin");
        if (inicio.equals(fin)) {
            throw invalido("A window cannot start and end at the same time");
        }
        return new Ventana(DayOfWeek.of(solicitud.diaSemana()), inicio, fin);
    }

    private static LocalTime hora(String valor, String campo) {
        if (valor == null || !HORA.matcher(valor).matches()) {
            throw invalido(campo + " must be a time formatted as HH:mm");
        }
        return LocalTime.parse(valor, FORMATO);
    }

    private static ApiException invalido(String detalle) {
        return ApiException.badRequest("INVALID_SCHEDULE", detalle);
    }

    private static VentanaResponse respuesta(Ventana ventana) {
        return new VentanaResponse(ventana.dia().getValue(), ventana.inicio().format(FORMATO),
                ventana.fin().format(FORMATO), ventana.cruzaMedianoche());
    }
}
