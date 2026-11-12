package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.acceso.SesionAcceso;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CredencialParticipanteRepository extends Repository<SesionAcceso, UUID> {

    /**
     * Credential, device, participation and session of a token hash, in one indexed query
     * ({@code uq_sesion_acceso_refresh_hash}). The credential names its participation, because one device
     * holds one participation per session. Staff credentials have no device nor participation and never match.
     */
    @Query("""
            select new cue.edu.co.yousongbackend.participante.CredencialParticipante(
                a.id, a.expiraAt, a.revocadaAt, d.id, d.expiraAt, d.revocadoAt,
                p.id, p.seudonimo, s.id, s.establecimientoId, s.estado)
            from SesionAcceso a
                join Dispositivo d on d.id = a.dispositivoId
                join Participacion p on p.id = a.participacionId
                join Sesion s on s.id = p.sesionId
            where a.refreshHash = :hash""")
    List<CredencialParticipante> buscarPorHash(@Param("hash") String hash);
}
