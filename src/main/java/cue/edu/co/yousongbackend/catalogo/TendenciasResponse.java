package cue.edu.co.yousongbackend.catalogo;

import java.time.Instant;
import java.util.List;

/**
 * The most popular music on YouTube in {@code region} (ISO 3166-1 alpha-2, "CO"), as catalog songs in chart order
 * (at most 25, only the ones the player can play). {@code actualizadoAt} is when YouTube was asked; it can be up to
 * 48 hours old when the provider is failing.
 */
public record TendenciasResponse(String region, Instant actualizadoAt, List<CancionBusquedaResponse> canciones) {
}
