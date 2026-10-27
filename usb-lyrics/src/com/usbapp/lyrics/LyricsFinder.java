package com.usbapp.lyrics;

import android.content.SharedPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Tries every enabled source, cheapest and best first, and stops at the first
// timed (synced) match:
//   1. lyric files already somewhere in the picked folders ("your drive"),
//   2. LRCLIB exact lookup, with the tags as-is and cleaned up,
//   3. LRCLIB searches: per field, then free text, then title only,
//   4. lyrics.ovh (unsynced only), when unsynced lyrics are allowed.
// Unsynced lyrics from an earlier source are kept and returned only if no
// later source has timed ones.
final class LyricsFinder {

    static final String PREF_SRC_LOCAL = "src_local";
    static final String PREF_SRC_LRCLIB = "src_lrclib";
    static final String PREF_SRC_OVH = "src_ovh";

    interface LocalSource {
        LrclibClient.Result find(String artist, String title);
    }

    private final LrclibClient lrclib = new LrclibClient();
    private final LyricsOvhClient ovh = new LyricsOvhClient();
    private final boolean useLocal, useLrclib, useOvh;

    LyricsFinder(SharedPreferences prefs) {
        useLocal = prefs.getBoolean(PREF_SRC_LOCAL, true);
        useLrclib = prefs.getBoolean(PREF_SRC_LRCLIB, true);
        useOvh = prefs.getBoolean(PREF_SRC_OVH, true);
    }

    // Null when nothing usable was found. Throws only if every source that was
    // tried failed with a network error, so callers can tell "offline" from
    // "not found".
    LrclibClient.Result find(String artist, String title, String album, int durationSec,
                             boolean allowPlain, LocalSource local) throws IOException {
        List<String> titles = variants(title, Lyrics.cleanTitle(title));
        List<String> artists = variants(artist, Lyrics.primaryArtist(artist));
        if (artists.isEmpty()) artists.add("");
        LrclibClient.Result plainBest = null;
        LrclibClient.Result instrumental = null;
        IOException error = null;
        boolean anyAnswered = false;

        if (useLocal && local != null) {
            LrclibClient.Result r = local.find(artist, title);
            if (r != null) {
                if (r.synced != null) return r;
                plainBest = r;
            }
        }

        if (useLrclib) {
            // Exact lookups: one request each, very precise.
            for (String a : artists) {
                for (String t : titles) {
                    LrclibClient.Result r;
                    try {
                        r = lrclib.get(a, t, album, durationSec);
                        anyAnswered = true;
                    } catch (Exception e) {
                        error = wrap(e);
                        continue;
                    }
                    if (r == null) continue;
                    if (r.synced != null) return r;
                    if (r.instrumental && r.plain == null) instrumental = r;
                    else if (plainBest == null) plainBest = r;
                }
            }

            // Searches, best-scoring result across all of them.
            List<List<LrclibClient.Result>> batches = new ArrayList<>();
            for (String a : artists) {
                for (String t : titles) {
                    try {
                        batches.add(lrclib.search(a, t));
                        anyAnswered = true;
                    } catch (Exception e) {
                        error = wrap(e);
                    }
                }
            }
            if (!hasSynced(batches, artist, title, durationSec)) {
                try {
                    String q = (Lyrics.primaryArtist(artist) + " " + Lyrics.cleanTitle(title)).trim();
                    batches.add(lrclib.searchText(q));
                    // Title alone only when the length can rule out other songs with the same name.
                    if (durationSec > 0 && !artist.isEmpty()) batches.add(lrclib.search("", Lyrics.cleanTitle(title)));
                    anyAnswered = true;
                } catch (Exception e) {
                    error = wrap(e);
                }
            }
            LrclibClient.Result best = null;
            int bestScore = Integer.MIN_VALUE;
            for (List<LrclibClient.Result> batch : batches) {
                for (LrclibClient.Result r : batch) {
                    if (r.synced == null && r.plain == null) continue;
                    int sc = LrclibClient.score(r, artist, title, durationSec);
                    if (sc > bestScore) {
                        bestScore = sc;
                        best = r;
                    }
                }
            }
            if (best != null && bestScore > -40) {
                if (best.synced != null) return best;
                if (plainBest == null) plainBest = best;
            }
        }

        if (plainBest == null && allowPlain && useOvh) {
            try {
                for (String a : artists) {
                    for (String t : titles) {
                        LrclibClient.Result r = ovh.find(a, t);
                        anyAnswered = true;
                        if (r != null) return r;
                    }
                }
            } catch (Exception e) {
                if (error == null) error = wrap(e);
            }
        }

        if (plainBest != null) return plainBest;
        if (instrumental != null) return instrumental;
        if (error != null && !anyAnswered) throw error;
        return null;
    }

    private static IOException wrap(Exception e) {
        return e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
    }

    private static boolean hasSynced(List<List<LrclibClient.Result>> batches, String artist, String title, int sec) {
        for (List<LrclibClient.Result> b : batches) {
            for (LrclibClient.Result r : b) {
                if (r.synced != null && LrclibClient.score(r, artist, title, sec) > 0) return true;
            }
        }
        return false;
    }

    private static List<String> variants(String... vs) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : vs) if (v != null && !v.trim().isEmpty()) out.add(v.trim());
        return new ArrayList<>(out);
    }
}
