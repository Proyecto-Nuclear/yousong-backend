package cue.edu.co.yousongbackend.cola;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SolicitudRepository extends JpaRepository<Solicitud, UUID> {

    /**
     * Finalizes every request of the session that is still open and returns how many there were. Bulk
     * updates skip the {@code @Version} check, so the version is bumped explicitly.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Solicitud s
               set s.estado = :nuevoEstado, s.finalizadaAt = :ahora, s.version = s.version + 1
             where s.sesionId = :sesionId and s.estado in :estadosAbiertos
            """)
    int finalizarAbiertas(@Param("sesionId") UUID sesionId,
                          @Param("estadosAbiertos") Collection<EstadoSolicitud> estadosAbiertos,
                          @Param("nuevoEstado") EstadoSolicitud nuevoEstado,
                          @Param("ahora") Instant ahora);
}
