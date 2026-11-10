package cue.edu.co.yousongbackend.repertorio;

import cue.edu.co.yousongbackend.catalogo.Cancion;
import cue.edu.co.yousongbackend.catalogo.CancionRepository;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.repertorio.ListaDefectoResponse.ItemListaResponse;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The default list an establishment plays when nobody has requested anything.
 *
 * <p>A list is never edited: every change publishes a NEW version. A session keeps pointing at the version
 * it opened with, so what played that night stays explainable, and the next session picks up the newest.
 * Authorization is by role only (see {@code SesionService}).
 */
@Service
public class ListaDefectoService {

    private static final String NOMBRE_POR_DEFECTO = "General";
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final ListaDefectoRepository listas;
    private final ItemListaRepository items;
    private final CancionRepository canciones;
    private final EstablecimientoService establecimientos;
    private final EventoOutboxPublisher eventos;
    private final EntityManager entityManager;
    private final Clock clock;

    ListaDefectoService(ListaDefectoRepository listas, ItemListaRepository items, CancionRepository canciones,
                        EstablecimientoService establecimientos, EventoOutboxPublisher eventos,
                        EntityManager entityManager, Clock clock) {
        this.listas = listas;
        this.items = items;
        this.canciones = canciones;
        this.establecimientos = establecimientos;
        this.eventos = eventos;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public ListaDefectoResponse publicar(UUID establecimientoId, PublicarListaDefectoRequest request) {
        // Validate everything before writing anything, then serialize publications of this establishment
        // so two concurrent ones get consecutive versions.
        List<String> videoIds = validarCanciones(request.canciones());
        establecimientos.obtenerBloqueando(establecimientoId);

        String nombre = request.nombre() == null || request.nombre().isBlank() ? NOMBRE_POR_DEFECTO : request.nombre().strip();
        PoliticaRotacion politica = request.politicaRotacion() == null ? PoliticaRotacion.SECUENCIAL : request.politicaRotacion();

        for (String videoId : videoIds) {
            canciones.registrarSiNoExiste(UUID.randomUUID(), videoId);
        }
        Map<String, Cancion> porVideo = canciones.findByYoutubeVideoIdIn(videoIds).stream()
                .collect(Collectors.toMap(Cancion::getYoutubeVideoId, c -> c));

        ListaDefecto lista = new ListaDefecto();
        lista.setId(UUID.randomUUID());
        lista.setEstablecimientoId(establecimientoId);
        lista.setNombre(nombre);
        lista.setVersion(listas.versionMaxima(establecimientoId, nombre) + 1);
        lista.setPoliticaRotacion(politica);
        lista.setCreadoAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        entityManager.persist(lista);

        for (int i = 0; i < videoIds.size(); i++) {
            ItemLista item = new ItemLista();
            item.setId(UUID.randomUUID());
            item.setListaId(lista.getId());
            item.setCancionId(porVideo.get(videoIds.get(i)).getId());
            item.setPosicion(i + 1);
            entityManager.persist(item);
        }
        entityManager.flush();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list_id", lista.getId());
        payload.put("version", lista.getVersion());
        payload.put("item_count", videoIds.size());
        payload.put("rotation_policy", politica.name());
        eventos.publicar("default_list.versioned", AmbitoEvento.B, "backend.repertorio", UUID.randomUUID(),
                establecimientoId, null, "LISTA_DEFECTO", lista.getId(), lista.getVersion(), payload);

        return respuesta(lista, items.findByListaIdOrderByPosicion(lista.getId()));
    }

    @Transactional(readOnly = true)
    public ListaDefectoResponse actual(UUID establecimientoId) {
        establecimientos.obtener(establecimientoId);
        ListaDefecto lista = listas.findFirstByEstablecimientoIdOrderByCreadoAtDescVersionDesc(establecimientoId)
                .orElseThrow(() -> ApiException.notFound("NO_DEFAULT_LIST", "The establishment has no default list yet"));
        return respuesta(lista, items.findByListaIdOrderByPosicion(lista.getId()));
    }

    @Transactional(readOnly = true)
    public List<ListaDefectoResumenResponse> historial(UUID establecimientoId) {
        establecimientos.obtener(establecimientoId);
        List<ListaDefecto> versiones = listas.findByEstablecimientoIdOrderByCreadoAtDescVersionDesc(establecimientoId);
        Map<UUID, Integer> totales = new HashMap<>();
        if (!versiones.isEmpty()) {
            for (Object[] fila : items.contarPorLista(versiones.stream().map(ListaDefecto::getId).toList())) {
                totales.put((UUID) fila[0], ((Long) fila[1]).intValue());
            }
        }
        return versiones.stream()
                .map(l -> new ListaDefectoResumenResponse(l.getId(), l.getNombre(), l.getVersion(),
                        l.getPoliticaRotacion(), l.getCreadoAt(), totales.getOrDefault(l.getId(), 0)))
                .toList();
    }

    private ListaDefectoResponse respuesta(ListaDefecto lista, List<ItemLista> elementos) {
        Map<UUID, Cancion> porId = canciones.findAllById(elementos.stream().map(ItemLista::getCancionId).toList()).stream()
                .collect(Collectors.toMap(Cancion::getId, c -> c));
        List<ItemListaResponse> detalle = elementos.stream().map(item -> {
            Cancion cancion = porId.get(item.getCancionId());
            return new ItemListaResponse(item.getPosicion(), cancion.getId(), cancion.getYoutubeVideoId(),
                    cancion.getTitulo(), cancion.getArtistaVerificado());
        }).toList();
        return new ListaDefectoResponse(lista.getId(), lista.getNombre(), lista.getVersion(),
                lista.getPoliticaRotacion(), lista.getCreadoAt(), detalle);
    }

    private static List<String> validarCanciones(List<String> videoIds) {
        Set<String> vistos = new HashSet<>();
        for (String videoId : videoIds) {
            if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
                throw ApiException.badRequest("INVALID_VIDEO_ID",
                        "'" + videoId + "' is not a YouTube video id (11 characters: letters, digits, '-' or '_')");
            }
            if (!vistos.add(videoId)) {
                throw ApiException.badRequest("DUPLICATE_SONG", "The video '" + videoId + "' appears more than once");
            }
        }
        return List.copyOf(videoIds);
    }
}
