package cue.edu.co.yousongbackend.catalogo;

import java.util.List;

/**
 * A remote source of search strings for search-as-you-type. Implementations throw
 * {@link SugerenciasRemotasException} on any failure; {@link SugerenciasRemotasResguardadas} turns failures
 * into "no suggestions" so the endpoint never fails because of the remote.
 */
interface SugerenciasRemotas {

    /** Search strings for an already normalized query, best first. Never null. */
    List<String> sugerir(String consulta);
}
