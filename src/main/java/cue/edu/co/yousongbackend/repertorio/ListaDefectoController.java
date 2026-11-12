package cue.edu.co.yousongbackend.repertorio;

import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The owner publishes and reads the default list of their own establishment (see {@link PropietarioActual}). */
@RestController
class ListaDefectoController {

    private static final String LISTA = "/api/mi-establecimiento/lista-defecto";

    private final ListaDefectoService service;
    private final PropietarioActual propietario;

    ListaDefectoController(ListaDefectoService service, PropietarioActual propietario) {
        this.service = service;
        this.propietario = propietario;
    }

    @PutMapping(LISTA)
    ResponseEntity<ListaDefectoResponse> publicar(@Valid @RequestBody PublicarListaDefectoRequest request) {
        ListaDefectoResponse publicada = service.publicar(propietario.establecimientoId(), request);
        return ResponseEntity.created(URI.create(LISTA)).body(publicada);
    }

    @GetMapping(LISTA)
    ListaDefectoResponse actual() {
        return service.actual(propietario.establecimientoId());
    }

    @GetMapping("/api/mi-establecimiento/listas-defecto")
    List<ListaDefectoResumenResponse> historial() {
        return service.historial(propietario.establecimientoId());
    }
}
