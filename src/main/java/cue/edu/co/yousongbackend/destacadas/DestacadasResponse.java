package cue.edu.co.yousongbackend.destacadas;

import cue.edu.co.yousongbackend.catalogo.CancionBusquedaResponse;
import java.time.Instant;
import java.util.List;

/**
 * The featured songs of an establishment. The three rankings cover the sessions opened in the last
 * {@code periodoDias} days and hold at most 10 songs each; {@code ultimasSonadas} is the last 10 playbacks ever.
 * No identity of any kind (participation, pseudonym, device, request) is part of it.
 */
public record DestacadasResponse(int periodoDias, List<Votada> masVotadas, List<Pedida> masPedidas,
                                 List<Sonada> masSonadas, List<Reciente> ultimasSonadas) {

    /** A song and every vote its requests received in the period. */
    public record Votada(CancionBusquedaResponse cancion, long votos) {
    }

    /** A song and how many times it was requested in the period. */
    public record Pedida(CancionBusquedaResponse cancion, long veces) {
    }

    /** A song and how many of its playbacks started and did not fail in the period. */
    public record Sonada(CancionBusquedaResponse cancion, long reproducciones) {
    }

    /** A playback that started: when, and whether it is the one playing now. */
    public record Reciente(CancionBusquedaResponse cancion, Instant sonoAt, boolean enCurso) {
    }
}
