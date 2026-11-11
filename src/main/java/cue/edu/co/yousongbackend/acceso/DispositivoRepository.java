package cue.edu.co.yousongbackend.acceso;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispositivoRepository extends JpaRepository<Dispositivo, UUID> {

    /** Revokes the devices that joined the session (each join registers its own device). */
    @Modifying(flushAutomatically = true)
    @Query("update Dispositivo d set d.revocadoAt = :ahora where d.revocadoAt is null and d.id in "
            + "(select p.dispositivoId from Participacion p where p.sesionId = :sesionId and p.dispositivoId is not null)")
    int revocarDeLaSesion(@Param("sesionId") UUID sesionId, @Param("ahora") Instant ahora);

    @Modifying(flushAutomatically = true)
    @Query("update Dispositivo d set d.revocadoAt = :ahora where d.revocadoAt is null and d.id = :id")
    int revocar(@Param("id") UUID id, @Param("ahora") Instant ahora);
}
