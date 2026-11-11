package cue.edu.co.yousongbackend.sesion;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SesionRepository extends JpaRepository<Sesion, UUID> {

    Optional<Sesion> findFirstByEstablecimientoIdAndEstadoIn(UUID establecimientoId, Collection<EstadoSesion> estados);

    /** Row lock so two closes (or a close and an update) of one session cannot interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sesion s where s.id = :id")
    Optional<Sesion> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Shared row lock ({@code FOR SHARE}) on the session in operation: many joins may hold it at once, but a
     * close ({@link #findByIdForUpdate}) waits for them, and a join that waited for a close sees it closed
     * (PostgreSQL re-checks the condition on the updated row), so the two can never interleave.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Sesion s where s.establecimientoId = :establecimientoId and s.estado in :estados")
    Optional<Sesion> findEnOperacionForShare(@Param("establecimientoId") UUID establecimientoId,
                                             @Param("estados") Collection<EstadoSesion> estados);
}
