package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.sesion.EstadoSesion;

/** The service of an establishment as a client sees it. Only ABIERTO admits new participants. */
public enum EstadoServicio {
    ABIERTO, PAUSADO, CERRADO;

    /** CERRANDO counts as closed for clients: no new participants and no new requests. */
    public static EstadoServicio de(EstadoSesion estado) {
        return switch (estado) {
            case ABIERTA -> ABIERTO;
            case PAUSADA -> PAUSADO;
            case CERRANDO, CERRADA -> CERRADO;
        };
    }
}
