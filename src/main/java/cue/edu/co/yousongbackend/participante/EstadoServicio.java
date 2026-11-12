package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.sesion.EstadoSesion;

/**
 * The service of an establishment as a client sees it. Only ABIERTO admits new participants.
 *
 * <p>Client contract (web and Android): ABIERTO, PAUSADO (session or establishment paused by the owner), CERRADO
 * (no session, inactive, or outside the weekly windows) and MANTENIMIENTO (the owner is preparing the next
 * operating day; added later, so clients must treat any value they do not know as "cannot participate").
 */
public enum EstadoServicio {
    ABIERTO, PAUSADO, CERRADO, MANTENIMIENTO;

    /** CERRANDO counts as closed for clients: no new participants and no new requests. */
    public static EstadoServicio de(EstadoSesion estado) {
        return switch (estado) {
            case ABIERTA -> ABIERTO;
            case PAUSADA -> PAUSADO;
            case CERRANDO, CERRADA -> CERRADO;
        };
    }
}
