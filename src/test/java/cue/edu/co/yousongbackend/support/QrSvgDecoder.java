package cue.edu.co.yousongbackend.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independent QR reader for tests: rasterizes the SVG path of {@code QrSvgRenderer} and decodes it with ZXing. */
public final class QrSvgDecoder {

    private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"");
    private static final Pattern RUN = Pattern.compile("M(\\d+) (\\d+)h(\\d+)v1h-\\d+z");
    private static final int SCALE = 10;

    private QrSvgDecoder() {
    }

    public static String decode(String svg) throws Exception {
        Matcher box = VIEW_BOX.matcher(svg);
        assertThat(box.find()).as("viewBox").isTrue();
        int size = Integer.parseInt(box.group(1));
        int pixels = size * SCALE;

        int[] raster = new int[pixels * pixels];
        Arrays.fill(raster, 0xFFFFFFFF);
        Matcher run = RUN.matcher(svg);
        int runs = 0;
        while (run.find()) {
            runs++;
            int x = Integer.parseInt(run.group(1));
            int y = Integer.parseInt(run.group(2));
            int width = Integer.parseInt(run.group(3));
            for (int py = y * SCALE; py < (y + 1) * SCALE; py++) {
                for (int px = x * SCALE; px < (x + width) * SCALE; px++) {
                    raster[py * pixels + px] = 0xFF000000;
                }
            }
        }
        assertThat(runs).as("dark runs").isPositive();

        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(pixels, pixels, raster)));
            return new QRCodeReader().decode(bitmap).getText();
        } catch (NotFoundException e) {
            throw new AssertionError("The QR could not be decoded from the SVG", e);
        }
    }
}
