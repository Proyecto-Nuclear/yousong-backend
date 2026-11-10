package cue.edu.co.yousongbackend.qr;

import java.util.UUID;

/** The access QR of a session: the URL it encodes and the vector image to display or print. */
public record QrResponse(String url, UUID sesionId, String svg) {
}
