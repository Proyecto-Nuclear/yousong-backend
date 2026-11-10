package cue.edu.co.yousongbackend.qr;

import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.sesion.Sesion;
import cue.edu.co.yousongbackend.sesion.SesionService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the access QR of the session in operation. The URL carries both the establishment slug (which
 * local) and the session id (which night), so a link from another session cannot be used to join this one.
 */
@Service
public class QrService {

    private final EstablecimientoService establecimientos;
    private final SesionService sesiones;
    private final String publicBaseUrl;

    QrService(EstablecimientoService establecimientos, SesionService sesiones,
              @Value("${app.public-base-url}") String publicBaseUrl) {
        this.establecimientos = establecimientos;
        this.sesiones = sesiones;
        this.publicBaseUrl = PublicBaseUrl.normalizar(publicBaseUrl);
    }

    @Transactional(readOnly = true)
    public QrResponse qrDeLaSesionActual(UUID establecimientoId) {
        Establecimiento establecimiento = establecimientos.obtener(establecimientoId);
        Sesion sesion = sesiones.sesionEnOperacion(establecimientoId);
        String url = publicBaseUrl + "/s/" + establecimiento.getQrSlug() + "/" + sesion.getId();
        return new QrResponse(url, sesion.getId(), QrSvgRenderer.render(url));
    }
}
