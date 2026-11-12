package cue.edu.co.yousongbackend.establecimiento;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EstablecimientoRepository extends JpaRepository<Establecimiento, UUID> {

    boolean existsByQrSlug(String qrSlug);

    Optional<Establecimiento> findByQrSlug(String qrSlug);

    boolean existsByPropietarioSub(String propietarioSub);

    @Query("select e.id from Establecimiento e where e.propietarioSub = :sub")
    Optional<UUID> findIdByPropietarioSub(@Param("sub") String propietarioSub);

    /** Row lock: serializes operations that must not interleave for one establishment (e.g. opening a session). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Establecimiento e where e.id = :id")
    Optional<Establecimiento> findByIdForUpdate(@Param("id") UUID id);
}
