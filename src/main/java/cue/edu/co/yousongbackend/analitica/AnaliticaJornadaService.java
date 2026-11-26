package cue.edu.co.yousongbackend.analitica;

import cue.edu.co.yousongbackend.analitica.JornadaResponse.Embudo;
import cue.edu.co.yousongbackend.analitica.JornadaResponse.Espera;
import cue.edu.co.yousongbackend.analitica.JornadaResponse.Hora;
import cue.edu.co.yousongbackend.analitica.JornadaResponse.Participacion;
import cue.edu.co.yousongbackend.analitica.JornadaResponse.Reproducciones;
import cue.edu.co.yousongbackend.analitica.JornadaResponse.Sesion;
import cue.edu.co.yousongbackend.analitica.JornadaResponse.Tramo;
import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One session of the caller's establishment computed live from {@code operacion.*}, with the same definitions the
 * pipeline publishes, so the dashboard can move during the session.
 *
 * <ul>
 *   <li>Session: the one asked for (it must belong to the caller's establishment), else the one in operation, else
 *       the latest closed one; none at all gives an empty answer.</li>
 *   <li>{@code participacion}: participations of the session; active ones created a request or voted.</li>
 *   <li>{@code canales}: the channel of a participation is the one of its first consent record (the join), or the
 *       device's channel when it has none; a participation with neither is in no channel.</li>
 *   <li>A request started when one of its playbacks reached PLAYING ({@code iniciada_at}); its first start is the
 *       earliest of them. Completed = state ATENDIDA with a COMPLETADA playback.</li>
 *   <li>{@code espera}: first start minus creation, only for started requests. Median and p95 are continuous
 *       percentiles (linear interpolation between the closest ranks, like PostgreSQL {@code percentile_cont} and
 *       Spark {@code percentile}), rounded to whole seconds. Bins of 5 minutes, {@code [0,5) ... [20,25)} and 25+.
 *       Pending requests (never started, state PENDIENTE, RESERVADA or NO_ATENDIDA_CIERRE) have no wait: they are
 *       counted apart with the median of their age, measured up to when they ended (finalizada_at), else to the
 *       session close, else to now.</li>
 *   <li>{@code porHora}: requests by creation and votes by acceptance per local hour of the establishment time zone,
 *       in night order, every hour between the first and the last active one (gaps with zeros, unless the span is
 *       over 48 hours, then only active hours).</li>
 *   <li>{@code reproducciones}: playback attempts per origin and result; the open attempt is {@code enCurso}.</li>
 *   <li>{@code generosPorHora}: see {@link CeldaGenero}, with {@code operacion.cancion.genero_propio}.</li>
 * </ul>
 * One read-only REPEATABLE READ transaction, so the whole answer is one snapshot.
 */
@Service
public class AnaliticaJornadaService {

    static final List<String> ESTADOS = List.of("PENDIENTE", "RESERVADA", "REPRODUCIENDO", "ATENDIDA", "SALTADA",
            "FALLIDA", "BLOQUEADA", "NO_ATENDIDA_CIERRE");
    private static final Set<String> PENDIENTES = Set.of("PENDIENTE", "RESERVADA", "NO_ATENDIDA_CIERRE");
    private static final int TRAMO_MIN = 5;
    private static final int TRAMOS = 6;
    private static final long HORAS_MAXIMAS_CONTINUAS = 48;

    private final JdbcTemplate jdbc;
    private final PropietarioActual propietario;
    private final Clock reloj;

    AnaliticaJornadaService(JdbcTemplate jdbc, PropietarioActual propietario, Clock reloj) {
        this.jdbc = jdbc;
        this.propietario = propietario;
        this.reloj = reloj;
    }

    /**
     * @param sesionId the session as sent ({@code null} = in operation, else latest closed)
     * @throws ApiException 404 {@code NO_ESTABLISHMENT}, 404 {@code SESSION_NOT_FOUND} when the id is not one of the
     *                      caller's sessions (malformed ids included)
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JornadaResponse jornada(String sesionId) {
        UUID establecimiento = propietario.establecimientoId();
        Instant corte = reloj.instant();
        Elegida elegida = sesionId == null ? porDefecto(establecimiento) : pedida(establecimiento, sesionId);
        if (elegida == null) {
            return vacia(corte);
        }
        ZoneId zona = ZoneId.of(jdbc.queryForObject("SELECT zona_horaria FROM operacion.establecimiento WHERE id = ?",
                String.class, establecimiento));
        UUID id = elegida.sesion().id();

        long[] participantes = new long[3];
        Map<String, Long> canales = canalesEnCero();
        jdbc.query("SELECT count(*) AS ingresaron, count(*) FILTER (WHERE activa) AS activas, "
                        + "count(*) FILTER (WHERE canal = 'WEB') AS web, count(*) FILTER (WHERE canal = 'ANDROID') AS android "
                        + "FROM (SELECT coalesce((SELECT c.canal FROM operacion.consentimiento c "
                        + "WHERE c.participacion_id = p.id ORDER BY c.registrado_at, c.id LIMIT 1), d.canal) AS canal, "
                        + "(EXISTS (SELECT 1 FROM operacion.solicitud s WHERE s.creador_id = p.id) "
                        + "OR EXISTS (SELECT 1 FROM operacion.voto v WHERE v.participacion_id = p.id)) AS activa "
                        + "FROM operacion.participacion p LEFT JOIN operacion.dispositivo d ON d.id = p.dispositivo_id "
                        + "WHERE p.sesion_id = ?) x",
                (RowCallbackHandler) fila -> {
                    participantes[0] = fila.getLong("ingresaron");
                    participantes[1] = fila.getLong("activas");
                    canales.put("WEB", fila.getLong("web"));
                    canales.put("ANDROID", fila.getLong("android"));
                }, id);

        List<Solicitud> solicitudes = jdbc.query("SELECT so.estado, so.creada_at, so.finalizada_at, so.creador_id, "
                        + "coalesce(c.genero_propio, ?) AS genero, "
                        + "(SELECT min(r.iniciada_at) FROM operacion.reproduccion r WHERE r.solicitud_id = so.id) AS inicio, "
                        + "EXISTS (SELECT 1 FROM operacion.reproduccion r WHERE r.solicitud_id = so.id "
                        + "AND r.resultado = 'COMPLETADA') AS completa "
                        + "FROM operacion.solicitud so JOIN operacion.cancion c ON c.id = so.cancion_id "
                        + "WHERE so.sesion_id = ?",
                (fila, n) -> new Solicitud(fila.getString("estado"), instante(fila, "creada_at"),
                        instante(fila, "finalizada_at"), fila.getObject("creador_id", UUID.class),
                        fila.getString("genero"), instante(fila, "inicio"), fila.getBoolean("completa")),
                CeldaGenero.SIN_GENERO, id);
        List<Instant> votos = jdbc.query("SELECT creado_at FROM operacion.voto WHERE sesion_id = ?",
                (fila, n) -> instante(fila, "creado_at"), id);

        Map<String, Long> estados = new LinkedHashMap<>();
        ESTADOS.forEach(e -> estados.put(e, 0L));
        long iniciadas = 0;
        long completadas = 0;
        List<Double> esperas = new ArrayList<>();
        List<Double> antiguedades = new ArrayList<>();
        Instant finPendientes = elegida.cerradaAt() == null ? corte : elegida.cerradaAt();
        for (Solicitud s : solicitudes) {
            estados.merge(s.estado(), 1L, Long::sum);
            if (s.inicio() != null) {
                iniciadas++;
                esperas.add(segundos(s.creada(), s.inicio()));
                if (s.estado().equals("ATENDIDA") && s.completa()) {
                    completadas++;
                }
            } else if (PENDIENTES.contains(s.estado())) {
                Instant fin = s.finalizada() != null ? s.finalizada() : finPendientes;
                antiguedades.add(Math.max(0, segundos(s.creada(), fin)));
            }
        }

        return new JornadaResponse(elegida.sesion(), !elegida.sesion().estado().equals("CERRADA"), corte,
                new Participacion(participantes[0], participantes[1]), canales, porHora(solicitudes, votos, zona),
                new Embudo(solicitudes.size(), iniciadas, completadas), estados,
                espera(esperas, antiguedades), reproducciones(id), generos(solicitudes, zona));
    }

    private record Elegida(Sesion sesion, Instant cerradaAt) {
    }

    private record Solicitud(String estado, Instant creada, Instant finalizada, UUID creador, String genero,
                             Instant inicio, boolean completa) {
    }

    private static final String SESION = "SELECT id, fecha_operativa, estado, cerrada_at FROM operacion.sesion ";

    private static Elegida elegida(ResultSet fila) throws SQLException {
        return new Elegida(new Sesion(fila.getObject("id", UUID.class), fila.getObject("fecha_operativa", LocalDate.class),
                fila.getString("estado")), instante(fila, "cerrada_at"));
    }

    private Elegida porDefecto(UUID establecimiento) {
        List<Elegida> filas = jdbc.query(SESION + "WHERE establecimiento_id = ? ORDER BY (estado <> 'CERRADA') DESC, "
                + "cerrada_at DESC NULLS LAST, abierta_at DESC, id DESC LIMIT 1", (fila, n) -> elegida(fila), establecimiento);
        return filas.isEmpty() ? null : filas.getFirst();
    }

    private Elegida pedida(UUID establecimiento, String sesionId) {
        UUID id;
        try {
            id = UUID.fromString(sesionId.strip());
        } catch (IllegalArgumentException e) {
            throw noEncontrada(sesionId);
        }
        List<Elegida> filas = jdbc.query(SESION + "WHERE id = ? AND establecimiento_id = ?", (fila, n) -> elegida(fila),
                id, establecimiento);
        if (filas.isEmpty()) {
            throw noEncontrada(sesionId);
        }
        return filas.getFirst();
    }

    private static ApiException noEncontrada(String sesionId) {
        return ApiException.notFound("SESSION_NOT_FOUND", "Session " + sesionId + " does not exist");
    }

    private static JornadaResponse vacia(Instant corte) {
        Map<String, Long> estados = new LinkedHashMap<>();
        ESTADOS.forEach(e -> estados.put(e, 0L));
        return new JornadaResponse(null, false, corte, new Participacion(0, 0), canalesEnCero(), List.of(),
                new Embudo(0, 0, 0), estados, new Espera(null, null, 0, List.of(), 0, null),
                List.of(new Reproducciones("SOLICITUD", 0, 0, 0, 0, 0), new Reproducciones("DEFECTO", 0, 0, 0, 0, 0)),
                List.of());
    }

    private static Map<String, Long> canalesEnCero() {
        Map<String, Long> canales = new LinkedHashMap<>();
        canales.put("WEB", 0L);
        canales.put("ANDROID", 0L);
        return canales;
    }

    private static Espera espera(List<Double> esperas, List<Double> antiguedades) {
        long[] porTramo = new long[TRAMOS];
        for (double espera : esperas) {
            porTramo[(int) Math.min(TRAMOS - 1, Math.floor(espera / (TRAMO_MIN * 60)))]++;
        }
        List<Tramo> tramos = new ArrayList<>(TRAMOS);
        for (int i = 0; i < TRAMOS; i++) {
            tramos.add(new Tramo(i * TRAMO_MIN, i == TRAMOS - 1 ? null : (i + 1) * TRAMO_MIN, porTramo[i]));
        }
        return new Espera(percentil(esperas, 0.5), percentil(esperas, 0.95), esperas.size(), tramos,
                antiguedades.size(), percentil(antiguedades, 0.5));
    }

    /** Continuous percentile (linear interpolation, {@code percentile_cont}) rounded to whole seconds. */
    static Long percentil(List<Double> valores, double p) {
        if (valores.isEmpty()) {
            return null;
        }
        List<Double> ordenados = valores.stream().sorted().toList();
        double posicion = p * (ordenados.size() - 1);
        int abajo = (int) Math.floor(posicion);
        int arriba = (int) Math.ceil(posicion);
        double valor = ordenados.get(abajo) + (posicion - abajo) * (ordenados.get(arriba) - ordenados.get(abajo));
        return Math.round(valor);
    }

    private static double segundos(Instant desde, Instant hasta) {
        return Duration.between(desde, hasta).toNanos() / 1_000_000_000.0;
    }

    private static List<Hora> porHora(List<Solicitud> solicitudes, List<Instant> votos, ZoneId zona) {
        TreeMap<LocalDateTime, long[]> horas = new TreeMap<>();
        for (Solicitud s : solicitudes) {
            horas.computeIfAbsent(hora(s.creada(), zona), k -> new long[2])[0]++;
        }
        for (Instant voto : votos) {
            horas.computeIfAbsent(hora(voto, zona), k -> new long[2])[1]++;
        }
        if (!horas.isEmpty()
                && ChronoUnit.HOURS.between(horas.firstKey(), horas.lastKey()) <= HORAS_MAXIMAS_CONTINUAS) {
            for (LocalDateTime h = horas.firstKey(); h.isBefore(horas.lastKey()); h = h.plusHours(1)) {
                horas.putIfAbsent(h, new long[2]);
            }
        }
        List<Hora> lista = new ArrayList<>(horas.size());
        horas.forEach((h, cuenta) -> lista.add(new Hora(h.getHour(), cuenta[0], cuenta[1])));
        return lista;
    }

    private static LocalDateTime hora(Instant instante, ZoneId zona) {
        return LocalDateTime.ofInstant(instante, zona).truncatedTo(ChronoUnit.HOURS);
    }

    private List<Reproducciones> reproducciones(UUID sesion) {
        Map<String, long[]> porOrigen = new LinkedHashMap<>();
        porOrigen.put("SOLICITUD", new long[5]);
        porOrigen.put("DEFECTO", new long[5]);
        List<String> resultados = List.of("COMPLETADA", "SALTADA", "FALLIDA", "INTERRUMPIDA_CIERRE");
        jdbc.query("SELECT origen, resultado, count(*) AS total FROM operacion.reproduccion WHERE sesion_id = ? "
                + "GROUP BY origen, resultado", (RowCallbackHandler) fila -> {
                    long[] cuenta = porOrigen.get(fila.getString("origen"));
                    String resultado = fila.getString("resultado");
                    int i = resultado == null ? 4 : resultados.indexOf(resultado);
                    if (cuenta != null && i >= 0) {
                        cuenta[i] += fila.getLong("total");
                    }
                }, sesion);
        List<Reproducciones> lista = new ArrayList<>(2);
        porOrigen.forEach((origen, c) -> lista.add(new Reproducciones(origen, c[0], c[1], c[2], c[3], c[4])));
        return lista;
    }

    private static List<CeldaGenero> generos(List<Solicitud> solicitudes, ZoneId zona) {
        Map<LocalDateTime, Map<String, List<Solicitud>>> grupos = new HashMap<>();
        for (Solicitud s : solicitudes) {
            grupos.computeIfAbsent(hora(s.creada(), zona), k -> new HashMap<>())
                    .computeIfAbsent(s.genero(), k -> new ArrayList<>()).add(s);
        }
        List<CeldaGenero.Conteo> conteos = new ArrayList<>();
        grupos.forEach((hora, porGenero) -> porGenero.forEach((genero, lista) -> {
            Set<UUID> personas = new HashSet<>();
            lista.forEach(s -> personas.add(s.creador()));
            conteos.add(new CeldaGenero.Conteo(hora.toEpochSecond(ZoneOffset.UTC), hora.getHour(), genero, lista.size(),
                    personas.size()));
        }));
        return CeldaGenero.celdas(conteos);
    }

    private static Instant instante(ResultSet fila, String columna) throws SQLException {
        OffsetDateTime valor = fila.getObject(columna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }
}
