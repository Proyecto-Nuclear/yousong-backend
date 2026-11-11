package cue.edu.co.yousongbackend.qr;

import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the access QR of an establishment. Establishments print it once, so the URL identifies the
 * establishment only ({@code /s/{qrSlug}}) and stays the same across sessions. Which session a client joins
 * is decided when it joins (the one in operation at that moment), and its credential dies with that session.
 */
@Service
public class QrService {

    private static final Logger log = LoggerFactory.getLogger(QrService.class);

    private final EstablecimientoService establecimientos;
    private final String publicBaseUrl;

    QrService(EstablecimientoService establecimientos, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.establecimientos = establecimientos;
        this.publicBaseUrl = PublicBaseUrl.normalizar(publicBaseUrl);
        if (PublicBaseUrl.apuntaAEstaMaquina(this.publicBaseUrl)) {
            log.warn("app.public-base-url points to this machine ({}). QR codes will only work on it; "
                    + "set PUBLIC_BASE_URL to the public address of the client app before printing or sharing them.",
                    this.publicBaseUrl);
        }
    }

    /** Works with or without a session in operation; 404 only for an unknown establishment. */
    @Transactional(readOnly = true)
    public QrResponse qrDelEstablecimiento(UUID establecimientoId) {
        Establecimiento establecimiento = establecimientos.obtener(establecimientoId);
        String url = publicBaseUrl + "/s/" + establecimiento.getQrSlug();
        return new QrResponse(url, QrSvgRenderer.render(url));
    }
}
