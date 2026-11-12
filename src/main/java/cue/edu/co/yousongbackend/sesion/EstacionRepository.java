package cue.edu.co.yousongbackend.sesion;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EstacionRepository extends JpaRepository<Estacion, UUID> {

    /** A closed session leaves no station enabled; the lease is released with it. */
    @Modifying(flushAutomatically = true)
    @Query("update Estacion e set e.habilitada = false, e.leaseHasta = null where e.sesionId = :sesionId and e.habilitada = true")
    int deshabilitarDeLaSesion(@Param("sesionId") UUID sesionId);
}
