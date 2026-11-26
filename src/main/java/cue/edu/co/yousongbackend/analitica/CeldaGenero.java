package cue.edu.co.yousongbackend.analitica;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One cell of "géneros por hora de la noche": the share of an hour's requests that asked for a genre.
 * {@code porcentaje} is {@code 100 * requests of the genre in the hour / every request of the hour}, rounded half up
 * to one decimal. A cell whose requests come from fewer than {@value #MINIMO_PARTICIPANTES} distinct participations
 * is {@code suprimida} and carries no percentage (dictionary: "suprimir celdas menores a cinco participantes antes de
 * difusión"); its requests still count in the hour's total, so the visible shares are the real ones.
 */
public record CeldaGenero(String genero, int hora, Double porcentaje, boolean suprimida) {

    static final int MINIMO_PARTICIPANTES = 5;

    /** Label of the requests whose song has no own genre. */
    static final String SIN_GENERO = "Sin género";

    private static final Collator ESPANOL = Collator.getInstance(Locale.forLanguageTag("es"));

    /**
     * Requests of one genre in one hour. {@code orden} sorts the hours of a night chronologically (so 23 comes
     * before 0 when the night crosses midnight); {@code participantes} is the number of distinct participations
     * behind those requests.
     */
    record Conteo(long orden, int hora, String genero, long solicitudes, long participantes) {
    }

    /** The cells of one night, by hour in night order and then by genre name. */
    static List<CeldaGenero> celdas(List<Conteo> conteos) {
        Map<Integer, Long> totalPorHora = new HashMap<>();
        Map<Integer, Long> ordenDeHora = new HashMap<>();
        for (Conteo c : conteos) {
            totalPorHora.merge(c.hora(), c.solicitudes(), Long::sum);
            ordenDeHora.merge(c.hora(), c.orden(), Math::min);
        }
        List<Conteo> ordenados = new ArrayList<>(conteos);
        ordenados.sort(Comparator.<Conteo>comparingLong(c -> ordenDeHora.get(c.hora()))
                .thenComparing(Conteo::genero, ESPANOL));
        List<CeldaGenero> celdas = new ArrayList<>(ordenados.size());
        for (Conteo c : ordenados) {
            boolean suprimida = c.participantes() < MINIMO_PARTICIPANTES;
            Double porcentaje = suprimida ? null : BigDecimal.valueOf(c.solicitudes() * 100)
                    .divide(BigDecimal.valueOf(totalPorHora.get(c.hora())), 1, RoundingMode.HALF_UP).doubleValue();
            celdas.add(new CeldaGenero(c.genero(), c.hora(), porcentaje, suprimida));
        }
        return celdas;
    }
}
