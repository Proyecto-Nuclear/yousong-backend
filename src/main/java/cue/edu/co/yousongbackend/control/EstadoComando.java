package cue.edu.co.yousongbackend.control;

/** Idempotent command processing state. Constant names match the database CHECK domain. */
public enum EstadoComando {
    EN_PROCESO, COMPLETADO
}
