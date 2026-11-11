package cue.edu.co.yousongbackend.qr;

/** The access QR of an establishment: the URL it encodes and the vector image to display or print. */
public record QrResponse(String url, String svg) {
}
