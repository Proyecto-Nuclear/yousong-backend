package cue.edu.co.yousongbackend.repertorio;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Publishing and the history are for administrators; any staff member can read the current list. */
@RestController
class ListaDefectoController {

    private final ListaDefectoService service;

    ListaDefectoController(ListaDefectoService service) {
        this.service = service;
    }

    @PutMapping("/api/admin/establecimientos/{establecimientoId}/lista-defecto")
    ResponseEntity<ListaDefectoResponse> publicar(@PathVariable UUID establecimientoId,
                                                  @Valid @RequestBody PublicarListaDefectoRequest request) {
        ListaDefectoResponse publicada = service.publicar(establecimientoId, request);
        return ResponseEntity
                .created(URI.create("/api/staff/establecimientos/" + establecimientoId + "/lista-defecto"))
                .body(publicada);
    }

    @GetMapping("/api/staff/establecimientos/{establecimientoId}/lista-defecto")
    ListaDefectoResponse actual(@PathVariable UUID establecimientoId) {
        return service.actual(establecimientoId);
    }

    @GetMapping("/api/admin/establecimientos/{establecimientoId}/listas-defecto")
    List<ListaDefectoResumenResponse> historial(@PathVariable UUID establecimientoId) {
        return service.historial(establecimientoId);
    }
}
