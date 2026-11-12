package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lookups other modules need, with the business errors already decided. */
@Service
public class EstablecimientoService {

    private final EstablecimientoRepository repository;

    EstablecimientoService(EstablecimientoRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Establecimiento obtener(UUID id) {
        return repository.findById(id).orElseThrow(() -> noEncontrado(id));
    }

    /** Looks an establishment up by the public slug of its QR (404 when unknown). */
    @Transactional(readOnly = true)
    public Establecimiento obtenerPorSlug(String qrSlug) {
        return repository.findByQrSlug(qrSlug).orElseThrow(() ->
                ApiException.notFound("ESTABLISHMENT_NOT_FOUND", "The establishment does not exist"));
    }

    /** Same as {@link #obtener} but locks the row until the surrounding transaction ends. */
    @Transactional
    public Establecimiento obtenerBloqueando(UUID id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> noEncontrado(id));
    }

    private static ApiException noEncontrado(UUID id) {
        return ApiException.notFound("ESTABLISHMENT_NOT_FOUND", "Establishment " + id + " does not exist");
    }
}
