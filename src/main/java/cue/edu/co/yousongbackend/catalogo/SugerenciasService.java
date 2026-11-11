package cue.edu.co.yousongbackend.catalogo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Search-as-you-type, so clients do not have to type a whole song name. It never uses the YouTube Data API
 * (a search costs 100 of the 10,000 daily quota units): suggestions come from songs the system already knows
 * and from the unofficial YouTube suggest source, whose failures only remove its part of the answer.
 *
 * <p>Privacy: nothing is stored, and the query is never logged nor put in an error message.
 */
@Service
public class SugerenciasService {

    static final int MAXIMO_LOCALES = 5;
    static final int MAXIMO_REMOTAS = 5;
    static final int MAXIMO_TOTAL = 8;

    private final SugerenciasLocales locales;
    private final SugerenciasRemotasResguardadas remotas;

    SugerenciasService(SugerenciasLocales locales, SugerenciasRemotasResguardadas remotas) {
        this.locales = locales;
        this.remotas = remotas;
    }

    /**
     * Known songs (up to 5) then remote search strings (up to 5), at most 8; a remote string equal to a
     * suggested title (ignoring case and accents) or to an earlier string is not repeated.
     *
     * @throws cue.edu.co.yousongbackend.shared.web.ApiException 400 {@code INVALID_QUERY} unless the query has
     *                                                            2 to 100 characters once normalized
     */
    public SugerenciasResponse sugerir(String consulta) {
        String texto = ConsultaCatalogo.normalizar(consulta);
        List<SugerenciaResponse> sugerencias = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (CancionBusquedaResponse cancion : locales.buscar(texto, MAXIMO_LOCALES)) {
            sugerencias.add(SugerenciaResponse.cancion(cancion));
            vistas.add(ConsultaCatalogo.plegar(cancion.titulo()));
        }
        int remotasAgregadas = 0;
        for (String sugerida : remotas.sugerir(texto)) {
            if (sugerencias.size() >= MAXIMO_TOTAL || remotasAgregadas >= MAXIMO_REMOTAS) {
                break;
            }
            if (vistas.add(ConsultaCatalogo.plegar(sugerida))) {
                sugerencias.add(SugerenciaResponse.busqueda(sugerida));
                remotasAgregadas++;
            }
        }
        return new SugerenciasResponse(List.copyOf(sugerencias));
    }
}
