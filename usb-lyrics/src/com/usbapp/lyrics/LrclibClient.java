package com.usbapp.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

// Minimal client for the public LRCLIB API (https://lrclib.net/docs).
// No API key is needed; LRCLIB asks clients to send an identifying User-Agent.
final class LrclibClient {

    private static final String BASE = "https://lrclib.net/api";
    private static final String USER_AGENT =
            "USBLyrics/1.0 (https://github.com/amberbrennain-beep/claudeaps3e)";

    static final class Result {
        final String artist;
        final String title;
        final String album;
        final int durationSec;
        final String synced;   // LRC with [mm:ss.xx] timestamps, or null
        final String plain;    // untimed text, or null
        final boolean instrumental;
        final String source;   // shown to the user: "LRCLIB", "lyrics.ovh", "your drive"

        Result(String artist, String title, String album, int durationSec,
               String synced, String plain, String source) {
            this.artist = artist;
            this.title = title;
            this.album = album;
            this.durationSec = durationSec;
            this.synced = nonEmpty(synced);
            this.plain = nonEmpty(plain);
            this.instrumental = false;
            this.source = source;
        }

        Result(JSONObject o) {
            artist = o.optString("artistName", "");
            title = o.optString("trackName", "");
            album = o.optString("albumName", "");
            durationSec = (int) Math.round(o.optDouble("duration", 0));
            synced = nonEmpty(o.optString("syncedLyrics", null));
            plain = nonEmpty(o.optString("plainLyrics", null));
            instrumental = o.optBoolean("instrumental", false);
            source = "LRCLIB";
        }

        private static String nonEmpty(String s) {
            return s == null || s.trim().isEmpty() || "null".equals(s) ? null : s;
        }
    }

    // Exact lookup: best when the file has good tags and a duration. Null
    // when LRCLIB has no record within a couple of seconds of that length.
    Result get(String artist, String title, String album, int durationSec) throws IOException, JSONException {
        if (durationSec <= 0 || artist.isEmpty() || title.isEmpty()) return null;
        StringBuilder q = new StringBuilder()
                .append("artist_name=").append(enc(artist))
                .append("&track_name=").append(enc(title))
                .append("&duration=").append(durationSec);
        if (album != null && !album.isEmpty()) q.append("&album_name=").append(enc(album));
        // One attempt each: on a miss /get may answer 503 while it checks
        // upstream, and retrying that only slows the fallbacks down. /get-cached
        // answers from LRCLIB's own database only, so it still works then.
        String body;
        try {
            body = get(BASE + "/get?" + q, 1);
        } catch (IOException e) {
            body = get(BASE + "/get-cached?" + q, 1);
        }
        if (body == null) return null;
        Result r = new Result(new JSONObject(body));
        return r.synced != null || r.plain != null || r.instrumental ? r : null;
    }

    // Free-text search over title, artist and album together; catches tags
    // that put the artist in the title or vice versa.
    List<Result> searchText(String query) throws IOException, JSONException {
        return parse(get(BASE + "/search?q=" + enc(query)));
    }

    // Raw search results, best first, for letting the user choose a match.
    List<Result> search(String artist, String title) throws IOException, JSONException {
        StringBuilder q = new StringBuilder(BASE).append("/search?track_name=").append(enc(title));
        if (!artist.isEmpty()) q.append("&artist_name=").append(enc(artist));
        return parse(get(q.toString()));
    }

    private static List<Result> parse(String body) throws JSONException {
        List<Result> out = new ArrayList<>();
        if (body == null) return out;
        JSONArray arr;
        try {
            arr = new JSONArray(body);
        } catch (JSONException e) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) out.add(new Result(arr.getJSONObject(i)));
        return out;
    }

    // Higher is better; Integer.MIN_VALUE means "different recording".
    static int score(Result r, String artist, String title, int durationSec) {
        int score = 0;
        if (r.synced != null) score += 100;
        else if (r.plain == null) score -= 200;
        if (durationSec > 0 && r.durationSec > 0) {
            int diff = Math.abs(r.durationSec - durationSec);
            if (diff > 15) return Integer.MIN_VALUE;   // almost certainly a different recording
            score -= diff * 5;
        }
        if (Lyrics.norm(Lyrics.cleanTitle(r.title)).equals(Lyrics.norm(Lyrics.cleanTitle(title)))) score += 20;
        if (!artist.isEmpty()) {
            String ra = Lyrics.norm(Lyrics.primaryArtist(r.artist));
            String a = Lyrics.norm(Lyrics.primaryArtist(artist));
            if (ra.equals(a)) score += 20;
            else if (!ra.contains(a) && !a.contains(ra)) score -= 40;   // different artist, same song title
        }
        return score;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // Returns the body on 200, null on 404, throws on anything else.
    static String get(String url) throws IOException {
        return get(url, 3);
    }

    static String get(String url, int attempts) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            try {
                int code = c.getResponseCode();
                if (code == 404) return null;
                if (code == 200) return readAll(c.getInputStream());
                if (code == 429 || code >= 500) {
                    last = new IOException("HTTP " + code);
                    if (attempt + 1 < attempts) sleep(1500L * (attempt + 1));
                    continue;
                }
                throw new IOException("HTTP " + code);
            } catch (IOException e) {
                last = e;
                if (attempt + 1 < attempts) sleep(1000L * (attempt + 1));
            } finally {
                c.disconnect();
            }
        }
        throw last;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
