package cue.edu.co.yousongbackend.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Renders a QR code as a self-contained SVG. Vector output scales to any screen or print size, needs no
 * image libraries, and has no fixed pixel size: the page decides how large to draw it.
 *
 * <p>Each row of dark modules is drawn as one horizontal run ({@code M x y h w v1 h-w z}).
 */
public final class QrSvgRenderer {

    /** Blank border required around a QR code by the specification, in modules. */
    private static final int QUIET_ZONE = 4;

    private QrSvgRenderer() {
    }

    public static String render(String content) {
        BitMatrix matrix = encode(content);
        int modules = matrix.getWidth();
        int size = modules + 2 * QUIET_ZONE;

        StringBuilder path = new StringBuilder();
        for (int y = 0; y < modules; y++) {
            int x = 0;
            while (x < modules) {
                if (!matrix.get(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < modules && matrix.get(x, y)) {
                    x++;
                }
                int run = x - start;
                path.append('M').append(start + QUIET_ZONE).append(' ').append(y + QUIET_ZONE)
                        .append('h').append(run).append("v1h-").append(run).append('z');
            }
        }

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size + "\""
                + " shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"Código QR de acceso a la sesión\">"
                + "<rect width=\"" + size + "\" height=\"" + size + "\" fill=\"#ffffff\"/>"
                + "<path fill=\"#000000\" d=\"" + path + "\"/>"
                + "</svg>";
    }

    private static BitMatrix encode(String content) {
        try {
            // margin 0: the quiet zone is added around the matrix when drawing.
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                    EncodeHintType.MARGIN, 0));
        } catch (WriterException e) {
            throw new IllegalArgumentException("The content cannot be encoded as a QR code", e);
        }
    }
}
