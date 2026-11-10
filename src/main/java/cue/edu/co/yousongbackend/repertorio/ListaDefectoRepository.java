package cue.edu.co.yousongbackend.repertorio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListaDefectoRepository extends JpaRepository<ListaDefecto, UUID> {

    /** The most recently published default list of the establishment. */
    Optional<ListaDefecto> findFirstByEstablecimientoIdOrderByCreadoAtDescVersionDesc(UUID establecimientoId);

    /** Every published version of every list of the establishment, newest first. */
    List<ListaDefecto> findByEstablecimientoIdOrderByCreadoAtDescVersionDesc(UUID establecimientoId);

    /** Highest published version of the list with this name (0 when it has never been published). */
    @Query("select coalesce(max(l.version), 0) from ListaDefecto l where l.establecimientoId = :establecimientoId and l.nombre = :nombre")
    int versionMaxima(@Param("establecimientoId") UUID establecimientoId, @Param("nombre") String nombre);
}
