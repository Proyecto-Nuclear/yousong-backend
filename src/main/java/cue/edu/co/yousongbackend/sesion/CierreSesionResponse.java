package cue.edu.co.yousongbackend.sesion;

/** Result of closing a session: the session and how many requests were left unattended. */
public record CierreSesionResponse(SesionResponse sesion, int pendientesAlCierre) {
}
