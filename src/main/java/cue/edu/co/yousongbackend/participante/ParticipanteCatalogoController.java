package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.catalogo.BusquedaResponse;
import cue.edu.co.yousongbackend.catalogo.CatalogoService;
import cue.edu.co.yousongbackend.catalogo.OrigenBusqueda;
import cue.edu.co.yousongbackend.catalogo.SugerenciasResponse;
import cue.edu.co.yousongbackend.catalogo.SugerenciasService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog search for a participant holding a credential. Same results as the staff search, but limited per
 * participant. The limit is checked before the provider is called, so a limited request costs no quota.
 * Because joining is public, the per-participant limit does not bound the total: provider spend is also
 * charged to the establishment's daily budget and to the participant share of the quota ({@code CuotaYoutube}).
 */
@RestController
class ParticipanteCatalogoController {

    private final CatalogoService catalogo;
    private final LimitadorBusquedas limitador;
    private final SugerenciasService sugerencias;
    private final LimitadorSugerencias limitadorSugerencias;

    ParticipanteCatalogoController(CatalogoService catalogo, LimitadorBusquedas limitador,
                                   SugerenciasService sugerencias, LimitadorSugerencias limitadorSugerencias) {
        this.catalogo = catalogo;
        this.limitador = limitador;
        this.sugerencias = sugerencias;
        this.limitadorSugerencias = limitadorSugerencias;
    }

    /**
     * Search-as-you-type. It costs no provider quota, but it is still limited per participant (checked
     * before any work) because every keystroke can produce a request.
     */
    @GetMapping("/api/participante/catalogo/sugerencias")
    SugerenciasResponse sugerir(@AuthenticationPrincipal ParticipanteAutenticado participante,
                                @RequestParam(value = "q", required = false) String consulta) {
        limitadorSugerencias.registrar(participante.participacionId());
        return sugerencias.sugerir(consulta);
    }

    @GetMapping("/api/participante/catalogo/busqueda")
    BusquedaResponse buscar(@AuthenticationPrincipal ParticipanteAutenticado participante,
                            @RequestParam("q") String consulta,
                            @RequestParam(value = "pagina", required = false) String pagina) {
        limitador.registrar(participante.participacionId());
        return catalogo.buscar(consulta, pagina, OrigenBusqueda.participante(participante.establecimientoId()));
    }
}
