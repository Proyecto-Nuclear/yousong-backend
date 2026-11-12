package cue.edu.co.yousongbackend.cola;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Estimated wait of every item of the queue, in seconds.
 *
 * <p>An item waits for what is left of the song playing now plus the whole operating duration
 * ({@code cancion.duracion_operativa_s}) of every item ahead of it. What is left of the playing song is its
 * operating duration minus the seconds elapsed since {@code reproduccion.iniciada_at}, never below zero (a song
 * that overruns its estimate counts as about to end). The column defaults to the initial reference of 240
 * seconds ("referencia inicial de 240 segundos si falta") and takes the provider duration when the catalog stores
 * or refreshes the song (see {@code CancionRepository#actualizarMetadatos}); a missing or non-positive value is still read as
 * 240. It is an estimate: popularity can reorder the queue at any time.
 */
final class EsperaEstimada {

    /** "Referencia inicial de 240 segundos si falta". */
    static final int DURACION_REFERENCIA = 240;

    private EsperaEstimada() {
    }

    /** The operating duration to use: the stored one when positive, otherwise the 240-second reference. */
    static int duracion(Integer segundos) {
        return segundos == null || segundos <= 0 ? DURACION_REFERENCIA : segundos;
    }

    /**
     * What is left of the playing song at {@code ahora}. A playback prepared but not started yet still has its
     * whole duration; a clock behind the start adds no time.
     */
    static long restante(Instant iniciadaAt, Integer duracion, Instant ahora) {
        long total = duracion(duracion);
        if (iniciadaAt == null) {
            return total;
        }
        long transcurridos = Math.max(0, Duration.between(iniciadaAt, ahora).toSeconds());
        return Math.max(0, total - transcurridos);
    }

    /** The wait of each item, in queue order, given what is left of the playing song (0 when nothing plays). */
    static List<Long> esperas(long restante, List<Integer> duraciones) {
        List<Long> esperas = new ArrayList<>(duraciones.size());
        long acumulada = restante;
        for (Integer duracion : duraciones) {
            esperas.add(acumulada);
            acumulada += duracion(duracion);
        }
        return esperas;
    }
}
