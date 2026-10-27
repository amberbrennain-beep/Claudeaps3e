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

        Result(JSONObject o) {
            artist = o.optString("artistName", "");
            title = o.optString("trackName", "");
            album = o.optString("albumName", "");
            durationSec = (int) Math.round(o.optDouble("duration", 0));
            synced = nonEmpty(o.optString("syncedLyrics", null));
            plain = nonEmpty(o.optString("plainLyrics", null));
            instrumental = o.optBoolean("instrumental", false);
        }

        private static String nonEmpty(String s) {
            return s == null || s.trim().isEmpty() || "null".equals(s) ? null : s;
        }
    }

    // Exact lookup first (best match when the file has good tags and a
    // duration), then a fuzzy search, preferring results that have timed
    // lyrics and a duration close to the file's.
    Result find(String artist, String title, String album, int durationSec) throws IOException, JSONException {
        if (durationSec > 0 && !artist.isEmpty()) {
            StringBuilder q = new StringBuilder(BASE).append("/get?")
                    .append("artist_name=").append(enc(artist))
                    .append("&track_name=").append(enc(title))
                    .append("&duration=").append(durationSec);
            if (album != null && !album.isEmpty()) q.append("&album_name=").append(enc(album));
            String body = get(q.toString());
            if (body != null) {
                Result r = new Result(new JSONObject(body));
                if (r.synced != null || r.plain != null || r.instrumental) return r;
            }
        }

        StringBuilder q = new StringBuilder(BASE).append("/search?track_name=").append(enc(title));
        if (!artist.isEmpty()) q.append("&artist_name=").append(enc(artist));
        String body = get(q.toString());
        if (body == null) return null;

        JSONArray arr;
        try {
            arr = new JSONArray(body);
        } catch (Exception e) {
            return null;
        }
        Result best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            Result r = new Result(arr.getJSONObject(i));
            if (r.synced == null && r.plain == null) continue;
            int score = 0;
            if (r.synced != null) score += 100;
            if (durationSec > 0 && r.durationSec > 0) {
                int diff = Math.abs(r.durationSec - durationSec);
                if (diff > 15) continue;   // almost certainly a different recording
                score -= diff * 5;
            }
            if (r.title.equalsIgnoreCase(title)) score += 20;
            if (!artist.isEmpty() && r.artist.equalsIgnoreCase(artist)) score += 20;
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // Returns the body on 200, null on 404, throws on anything else.
    private static String get(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
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
                    sleep(1500L * (attempt + 1));
                    continue;
                }
                throw new IOException("HTTP " + code);
            } catch (IOException e) {
                last = e;
                sleep(1000L * (attempt + 1));
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
