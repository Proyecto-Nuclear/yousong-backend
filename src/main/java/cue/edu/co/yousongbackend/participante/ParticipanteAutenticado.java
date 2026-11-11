package cue.edu.co.yousongbackend.participante;

import java.util.UUID;

/**
 * The principal of a request authenticated with a participant credential (authority {@code ROLE_PARTICIPANTE}).
 * It never carries the secret itself.
 */
public record ParticipanteAutenticado(UUID participacionId, UUID sesionId, UUID establecimientoId, String seudonimo,
                                      UUID dispositivoId) {
}
