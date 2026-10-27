package com.usbapp.lyrics;

import org.json.JSONObject;

import java.io.IOException;

// lyrics.ovh (https://lyricsovh.docs.apiary.io): free, keyless, plain
// (unsynced) lyrics only. Used as a fallback when LRCLIB has nothing.
final class LyricsOvhClient {

    private static final String BASE = "https://api.lyrics.ovh/v1/";

    LrclibClient.Result find(String artist, String title) throws IOException {
        if (artist.isEmpty() || title.isEmpty()) return null;
        String body = LrclibClient.get(BASE + path(artist) + "/" + path(title));
        if (body == null) return null;
        try {
            String text = new JSONObject(body).optString("lyrics", "").trim();
            // Some entries start with a "Paroles de la chanson ... par ..." credit line.
            if (text.startsWith("Paroles de la chanson")) {
                int nl = text.indexOf('\n');
                text = nl < 0 ? "" : text.substring(nl + 1).trim();
            }
            if (text.isEmpty()) return null;
            return new LrclibClient.Result(artist, title, "", 0, null, text, "lyrics.ovh");
        } catch (Exception e) {
            return null;
        }
    }

    // Path segments: URL-encoded with %20 for spaces.
    private static String path(String s) {
        return LrclibClient.enc(s).replace("+", "%20");
    }
}
