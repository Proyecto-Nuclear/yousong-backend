package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.sesion.EstadoSesion;
import java.time.Instant;
import java.util.UUID;

/** Everything needed to accept or reject a participant credential, read with one query. */
public record CredencialParticipante(
        UUID credencialId, Instant expiraAt, Instant revocadaAt,
        UUID dispositivoId, Instant dispositivoExpiraAt, Instant dispositivoRevocadoAt,
        UUID participacionId, String seudonimo,
        UUID sesionId, UUID establecimientoId, EstadoSesion estadoSesion) {
}
