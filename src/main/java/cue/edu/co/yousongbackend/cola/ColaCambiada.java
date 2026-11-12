package cue.edu.co.yousongbackend.cola;

import java.util.UUID;

/**
 * Spring application event: what the public queue of the establishment shows changed in the current transaction
 * (a queue version was taken, or a session opened or closed). It is published inside the writing transaction and
 * {@link SuscripcionesCola} only reacts after the commit, so a rolled-back change notifies nobody.
 */
public record ColaCambiada(UUID establecimientoId) {
}
