package cue.edu.co.yousongbackend.cola;

/** Request lifecycle. Constant names match the database CHECK domain. */
public enum EstadoSolicitud {
    PENDIENTE, RESERVADA, REPRODUCIENDO, ATENDIDA, SALTADA, FALLIDA, BLOQUEADA, NO_ATENDIDA_CIERRE
}
