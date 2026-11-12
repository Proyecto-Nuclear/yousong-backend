package cue.edu.co.yousongbackend.reproduccion;

import cue.edu.co.yousongbackend.repertorio.PoliticaRotacion;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Picks the next song of the default list of a session: "La lista por defecto cubre una cola vacía".
 *
 * <p>The list is the version the session pinned when it opened ({@code sesion.lista_id}), never a newer one. Only
 * playable items count: songs not NO_DISPONIBLE and not blocked by the establishment. The rotation cursor is not
 * stored anywhere: it is derived from the history of the session, the last {@code DEFECTO} playback
 * ({@code reproduccion.item_lista_id}, latest {@code preparada_at}), so it survives restarts and needs no column.
 * <ul>
 *   <li>SECUENCIAL: the first playable item whose {@code posicion} is after the last one played, wrapping to the
 *       first; the first item when nothing of the list played yet in the session.</li>
 *   <li>ALEATORIA: a random playable item other than the one played last (nor another item of the same song); the
 *       same song again only when it is the only playable one.</li>
 * </ul>
 * The caller holds the session row lock, so two picks of one session never run at the same time.
 */
@Component
class RotacionListaDefecto {

    /** A default-list item chosen to play. */
    record ItemElegido(UUID itemListaId, UUID cancionId) {
    }

    private record Item(UUID id, UUID cancionId, int posicion) {
    }

    private final JdbcTemplate jdbc;

    RotacionListaDefecto(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The next item to play, or empty when the list is empty or nothing of it is playable now. */
    Optional<ItemElegido> siguiente(UUID sesionId, Set<UUID> cancionesBloqueadas) {
        List<String> politicas = jdbc.queryForList("SELECT l.politica_rotacion FROM operacion.sesion s "
                + "JOIN operacion.lista_defecto l ON l.id = s.lista_id WHERE s.id = ?", String.class, sesionId);
        if (politicas.isEmpty()) {
            return Optional.empty();
        }
        PoliticaRotacion politica = PoliticaRotacion.valueOf(politicas.get(0));
        List<Item> jugables = jdbc.query("SELECT il.id, il.cancion_id, il.posicion FROM operacion.item_lista il "
                        + "JOIN operacion.sesion s ON s.lista_id = il.lista_id "
                        + "JOIN operacion.cancion c ON c.id = il.cancion_id "
                        + "WHERE s.id = ? AND c.disponibilidad <> 'NO_DISPONIBLE' ORDER BY il.posicion",
                        (fila, n) -> new Item(fila.getObject("id", UUID.class), fila.getObject("cancion_id", UUID.class),
                                fila.getInt("posicion")), sesionId)
                .stream().filter(item -> !cancionesBloqueadas.contains(item.cancionId())).toList();
        if (jugables.isEmpty()) {
            return Optional.empty();
        }
        Optional<Item> ultimo = jdbc.query("SELECT il.id, il.cancion_id, il.posicion FROM operacion.reproduccion r "
                        + "JOIN operacion.item_lista il ON il.id = r.item_lista_id "
                        + "WHERE r.sesion_id = ? AND r.origen = 'DEFECTO' ORDER BY r.preparada_at DESC, r.id DESC LIMIT 1",
                (fila, n) -> new Item(fila.getObject("id", UUID.class), fila.getObject("cancion_id", UUID.class),
                        fila.getInt("posicion")), sesionId).stream().findFirst();

        Item elegido = politica == PoliticaRotacion.ALEATORIA ? aleatorio(jugables, ultimo) : secuencial(jugables, ultimo);
        return Optional.of(new ItemElegido(elegido.id(), elegido.cancionId()));
    }

    private static Item secuencial(List<Item> jugables, Optional<Item> ultimo) {
        if (ultimo.isEmpty()) {
            return jugables.get(0);
        }
        int posicion = ultimo.get().posicion();
        return jugables.stream().filter(item -> item.posicion() > posicion).findFirst().orElse(jugables.get(0));
    }

    private static Item aleatorio(List<Item> jugables, Optional<Item> ultimo) {
        List<Item> candidatos = ultimo.map(anterior -> jugables.stream()
                .filter(item -> !item.cancionId().equals(anterior.cancionId())).toList()).orElse(jugables);
        if (candidatos.isEmpty()) {
            candidatos = jugables;
        }
        return candidatos.get(ThreadLocalRandom.current().nextInt(candidatos.size()));
    }
}
