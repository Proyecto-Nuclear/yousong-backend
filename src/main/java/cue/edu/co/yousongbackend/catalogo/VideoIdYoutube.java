package cue.edu.co.yousongbackend.catalogo;

import java.util.regex.Pattern;

/** The shape of a YouTube video id: 11 characters of the URL-safe alphabet. */
public final class VideoIdYoutube {

    private static final Pattern FORMATO = Pattern.compile("[A-Za-z0-9_-]{11}");

    private VideoIdYoutube() {
    }

    public static boolean valido(String videoId) {
        return videoId != null && FORMATO.matcher(videoId).matches();
    }
}
