package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announces a change of the configuration of an establishment with a {@code configuration.changed} event in the
 * caller's transaction. Callers hold the establishment row lock, which gives each event its own version.
 */
@Component
class CambiosConfiguracion {

    private static final String EVENTO = "configuration.changed";
    private static final String AGREGADO = "ESTABLECIMIENTO";

    private final EventoOutboxPublisher eventos;

    CambiosConfiguracion(EventoOutboxPublisher eventos) {
        this.eventos = eventos;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void publicar(UUID establecimientoId, List<String> campos) {
        long version = eventos.siguienteVersion(AGREGADO, establecimientoId, EVENTO);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity_type", AGREGADO);
        payload.put("entity_ref", establecimientoId);
        payload.put("version", version);
        payload.put("field_codes", campos);
        eventos.publicar(EVENTO, AmbitoEvento.O, "backend.configuracion", UUID.randomUUID(), establecimientoId,
                null, AGREGADO, establecimientoId, version, payload);
    }
}
