package cue.edu.co.yousongbackend.reproduccion;

/** Terminal outcome of a playback attempt. Only set once the attempt has finished. */
public enum ResultadoReproduccion {
    COMPLETADA, SALTADA, FALLIDA, INTERRUMPIDA_CIERRE
}
