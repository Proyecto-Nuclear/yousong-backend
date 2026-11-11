package cue.edu.co.yousongbackend.catalogo;

/**
 * The remote suggest source failed. The message is a fixed description and there is no cause on purpose:
 * HTTP-layer messages contain the request URL, and the URL contains the user's query.
 */
class SugerenciasRemotasException extends RuntimeException {

    SugerenciasRemotasException(String motivo) {
        super(motivo, null, false, false);
    }
}
