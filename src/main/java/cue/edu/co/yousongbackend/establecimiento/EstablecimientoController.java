package cue.edu.co.yousongbackend.establecimiento;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Writing is for administrators ({@code /api/admin/**}); any staff member can read ({@code /api/staff/**}). */
@RestController
class EstablecimientoController {

    private final EstablecimientoConfiguracionService service;

    EstablecimientoController(EstablecimientoConfiguracionService service) {
        this.service = service;
    }

    @PostMapping("/api/admin/establecimientos")
    ResponseEntity<EstablecimientoResponse> crear(@Valid @RequestBody CrearEstablecimientoRequest request) {
        EstablecimientoResponse creado = service.crear(request);
        return ResponseEntity.created(URI.create("/api/staff/establecimientos/" + creado.id())).body(creado);
    }

    @PatchMapping("/api/admin/establecimientos/{id}")
    EstablecimientoResponse actualizar(@PathVariable UUID id, @Valid @RequestBody ActualizarEstablecimientoRequest request) {
        return service.actualizar(id, request);
    }

    @GetMapping("/api/staff/establecimientos")
    List<EstablecimientoResponse> listar() {
        return service.listar();
    }

    @GetMapping("/api/staff/establecimientos/{id}")
    EstablecimientoResponse obtener(@PathVariable UUID id) {
        return service.obtener(id);
    }
}
