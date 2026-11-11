package cue.edu.co.yousongbackend.acceso;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispositivoRepository extends JpaRepository<Dispositivo, UUID> {

    /** The device a secret identifies, if it is neither revoked nor expired ({@code uq_dispositivo_credencial_hash}). */
    @Query("select d from Dispositivo d where d.credencialHash = :hash and d.revocadoAt is null and d.expiraAt > :ahora")
    Optional<Dispositivo> buscarVigentePorHash(@Param("hash") String hash, @Param("ahora") Instant ahora);

    @Modifying(flushAutomatically = true)
    @Query("update Dispositivo d set d.revocadoAt = :ahora where d.revocadoAt is null and d.id = :id")
    int revocar(@Param("id") UUID id, @Param("ahora") Instant ahora);
}
