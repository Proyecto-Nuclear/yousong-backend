package cue.edu.co.yousongbackend.repertorio;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListaDefectoRepository extends JpaRepository<ListaDefecto, UUID> {

    /** The most recently published default list of the establishment. */
    Optional<ListaDefecto> findFirstByEstablecimientoIdOrderByCreadoAtDescVersionDesc(UUID establecimientoId);
}
