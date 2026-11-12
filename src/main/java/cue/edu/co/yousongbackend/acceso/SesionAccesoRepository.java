package cue.edu.co.yousongbackend.acceso;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Credentials (refresh-style rows identified only by the SHA-256 of their secret). Revocation keeps the row
 * and stamps {@code revocadaAt}, so a rejected credential can still be told apart from an unknown one.
 */
public interface SesionAccesoRepository extends JpaRepository<SesionAcceso, UUID> {

    /** Revokes every live credential of the participations of the session. */
    @Modifying(flushAutomatically = true)
    @Query("update SesionAcceso a set a.revocadaAt = :ahora where a.revocadaAt is null and a.participacionId in "
            + "(select p.id from Participacion p where p.sesionId = :sesionId)")
    int revocarDeLaSesion(@Param("sesionId") UUID sesionId, @Param("ahora") Instant ahora);

    /** Revokes every live credential of one participation. */
    @Modifying(flushAutomatically = true)
    @Query("update SesionAcceso a set a.revocadaAt = :ahora where a.revocadaAt is null "
            + "and a.participacionId = :participacionId")
    int revocarDeLaParticipacion(@Param("participacionId") UUID participacionId, @Param("ahora") Instant ahora);

    @Modifying(flushAutomatically = true)
    @Query("update SesionAcceso a set a.revocadaAt = :ahora where a.revocadaAt is null and a.dispositivoId = :dispositivoId")
    int revocarDelDispositivo(@Param("dispositivoId") UUID dispositivoId, @Param("ahora") Instant ahora);
}
