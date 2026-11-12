package cue.edu.co.yousongbackend.cola;

/** Request lifecycle. Constant names match the database CHECK domain. */
public enum EstadoSolicitud {
    PENDIENTE, RESERVADA, REPRODUCIENDO, ATENDIDA, SALTADA, FALLIDA, BLOQUEADA, NO_ATENDIDA_CIERRE;

    /** Still in the queue or playing: the states covered by {@code uq_solicitud_activa}. */
    public boolean activa() {
        return this == PENDIENTE || this == RESERVADA || this == REPRODUCIENDO;
    }
}
