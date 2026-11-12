package cue.edu.co.yousongbackend.repertorio;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemListaRepository extends JpaRepository<ItemLista, UUID> {

    List<ItemLista> findByListaIdOrderByPosicion(UUID listaId);

    /** Number of songs of each list, for the version history. */
    @Query("select i.listaId, count(i) from ItemLista i where i.listaId in :listaIds group by i.listaId")
    List<Object[]> contarPorLista(@Param("listaIds") Collection<UUID> listaIds);
}
