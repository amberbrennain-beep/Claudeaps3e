package com.usbapp.lyrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Not lyric sources: Deezer and iTunes catalogues, used to correct tags that
// lyric databases can't match ("Bohemian Rapsody", "Queen - Bohemian
// Rhapsody" in the title field, a missing artist or length). The corrected
// artist/title/length is then retried against the real lyric sources.
// Both APIs are public and keyless; only the artist and title are sent.
final class MetadataClient {

    static final class Candidate {
        final String artist, title;
        final int durationSec;
        final String source;

        Candidate(String artist, String title, int durationSec, String source) {
            this.artist = artist;
            this.title = title;
            this.durationSec = durationSec;
            this.source = source;
        }
    }

    // Best catalogue entry that is plausibly the same recording, or null.
    Candidate deezer(String artist, String title, int durationSec) throws IOException {
        String body = LrclibClient.get("https://api.deezer.com/search?limit=8&q="
                + LrclibClient.enc((artist + " " + Lyrics.cleanTitle(title)).trim()), 2);
        if (body == null) return null;
        List<Candidate> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(body).optJSONArray("data");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONObject a = o.optJSONObject("artist");
                list.add(new Candidate(a == null ? "" : a.optString("name"), o.optString("title"),
                        o.optInt("duration", 0), "Deezer"));
            }
        } catch (Exception e) {
            return null;
        }
        return best(list, artist, title, durationSec);
    }

    Candidate itunes(String artist, String title, int durationSec) throws IOException {
        String body = LrclibClient.get("https://itunes.apple.com/search?entity=song&limit=8&term="
                + LrclibClient.enc((artist + " " + Lyrics.cleanTitle(title)).trim()), 2);
        if (body == null) return null;
        List<Candidate> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(body).optJSONArray("results");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Candidate(o.optString("artistName"), o.optString("trackName"),
                        (int) Math.round(o.optLong("trackTimeMillis", 0) / 1000.0), "iTunes"));
            }
        } catch (Exception e) {
            return null;
        }
        return best(list, artist, title, durationSec);
    }

    // Title must be close (so a typo is fixed but a different song is not
    // accepted), the artist must be close when known, and the length must
    // agree when known. Among those, prefer the closest title, then length.
    private static Candidate best(List<Candidate> list, String artist, String title, int durationSec) {
        String ct = Lyrics.cleanTitle(title);
        String ca = Lyrics.primaryArtist(artist);
        Candidate best = null;
        double bestScore = 0;
        for (Candidate c : list) {
            if (c.title.isEmpty() || c.artist.isEmpty()) continue;
            double ts = Lyrics.similarity(Lyrics.cleanTitle(c.title), ct);
            if (ts < 0.7) continue;
            if (!ca.isEmpty()) {
                double as = Math.max(Lyrics.similarity(Lyrics.primaryArtist(c.artist), ca),
                        Lyrics.norm(c.artist).contains(Lyrics.norm(ca)) ? 1 : 0);
                if (as < 0.6) continue;
            }
            int diff = 0;
            if (durationSec > 0 && c.durationSec > 0) {
                diff = Math.abs(c.durationSec - durationSec);
                if (diff > 4) continue;
            }
            double score = ts * 10 - diff;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }
}
