package cue.edu.co.yousongbackend.reproduccion;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReproduccionRepository extends JpaRepository<Reproduccion, UUID> {

    /** Ends the playback attempt still open in the session (at most one) and returns how many were ended. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Reproduccion r
               set r.terminadaAt = :ahora, r.resultado = :resultado
             where r.sesionId = :sesionId and r.terminadaAt is null
            """)
    int terminarAbiertas(@Param("sesionId") UUID sesionId,
                         @Param("resultado") ResultadoReproduccion resultado,
                         @Param("ahora") Instant ahora);
}
