package com.usbapp.lyrics;

import android.content.SharedPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// Looks one song up across every enabled source. Requests inside a stage run
// in parallel; stages run in order and the search stops at the first timed
// (synced) match, so a hit in an early stage never pays for the later ones:
//   0. lyric files already somewhere in the picked folders ("your drive")
//   1. LRCLIB exact lookups for each tag variant, while Deezer and iTunes
//      look the song up to correct the tags
//   2. exact lookups again for any corrected artist/title found in stage 1
//   3. LRCLIB searches (per field, free text, title only) over all variants
//   4. lyrics.ovh for each variant (unsynced only), if nothing was found yet
// Unsynced lyrics from an earlier stage are kept and returned only if no
// later stage has timed ones. A LyricsFinder holds no per-song state, so one
// instance can serve many threads.
final class LyricsFinder {

    static final String PREF_SRC_LOCAL = "src_local";
    static final String PREF_SRC_LRCLIB = "src_lrclib";
    static final String PREF_SRC_OVH = "src_ovh";
    static final String PREF_SRC_META = "src_meta";

    interface LocalSource {
        LrclibClient.Result find(String artist, String title);
    }

    // Shared by all lookups. Batch workers wait on these tasks but the tasks
    // never wait on anything, so the pool can't deadlock.
    private static final AtomicInteger POOL_ID = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(12, r -> {
        Thread t = new Thread(r, "lyrics-src-" + POOL_ID.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final LrclibClient lrclib = new LrclibClient();
    private final LyricsOvhClient ovh = new LyricsOvhClient();
    private final MetadataClient meta = new MetadataClient();
    private final boolean useLocal, useLrclib, useOvh, useMeta;

    LyricsFinder(SharedPreferences prefs) {
        useLocal = prefs.getBoolean(PREF_SRC_LOCAL, true);
        useLrclib = prefs.getBoolean(PREF_SRC_LRCLIB, true);
        useOvh = prefs.getBoolean(PREF_SRC_OVH, true);
        useMeta = prefs.getBoolean(PREF_SRC_META, true);
    }

    // Null when nothing usable was found. Throws only if every request that was
    // made failed, so callers can tell "offline" from "not found".
    LrclibClient.Result find(String artist, String title, String album, int durationSec,
                             boolean allowPlain, LocalSource local) throws IOException {
        return new Search(artist, title, album, durationSec, allowPlain).run(local);
    }

    private static final class Pair {
        final String artist, title;

        Pair(String artist, String title) {
            this.artist = artist;
            this.title = title;
        }
    }

    private final class Search {
        final String artist, title, album;
        final int sec;
        final boolean allowPlain;
        final AtomicBoolean answered = new AtomicBoolean();
        final AtomicReference<IOException> error = new AtomicReference<>();
        final Map<String, Pair> pairs = new LinkedHashMap<>();   // artist/title variants to try
        LrclibClient.Result plainBest, instrumental;
        int effSec;   // the file's length, or the catalogue's when the file has none

        Search(String artist, String title, String album, int sec, boolean allowPlain) {
            this.artist = artist;
            this.title = title;
            this.album = album;
            this.sec = sec;
            this.effSec = sec;
            this.allowPlain = allowPlain;
            addPair(artist, title);
            addPair(artist, Lyrics.cleanTitle(title));
            addPair(Lyrics.primaryArtist(artist), title);
            addPair(Lyrics.primaryArtist(artist), Lyrics.cleanTitle(title));
        }

        boolean addPair(String a, String t) {
            a = a == null ? "" : a.trim();
            t = t == null ? "" : t.trim();
            if (t.isEmpty()) return false;
            return pairs.putIfAbsent(Lyrics.norm(a) + "|" + Lyrics.norm(t), new Pair(a, t)) == null;
        }

        LrclibClient.Result run(LocalSource local) throws IOException {
            if (useLocal && local != null) {
                LrclibClient.Result r = local.find(artist, title);
                if (r != null) {
                    if (r.synced != null) return r;
                    plainBest = r;
                }
            }

            // Stage 1: exact lookups, and tag correction alongside.
            List<Callable<Object>> tasks = new ArrayList<>();
            if (useLrclib) for (Pair p : new ArrayList<>(pairs.values())) tasks.add(() -> lrclib.get(p.artist, p.title, album, sec));
            if (useMeta) {
                tasks.add(() -> meta.deezer(artist, title, sec));
                tasks.add(() -> meta.itunes(artist, title, sec));
            }
            List<Pair> corrected = new ArrayList<>();
            for (Object o : par(tasks)) {
                if (o instanceof LrclibClient.Result) {
                    LrclibClient.Result r = (LrclibClient.Result) o;
                    if (r.synced != null) return r;
                    keep(r);
                } else if (o instanceof MetadataClient.Candidate) {
                    MetadataClient.Candidate c = (MetadataClient.Candidate) o;
                    if (effSec <= 0 && c.durationSec > 0) effSec = c.durationSec;
                    for (String[] v : new String[][]{{c.artist, c.title}, {c.artist, Lyrics.cleanTitle(c.title)},
                            {Lyrics.primaryArtist(c.artist), Lyrics.cleanTitle(c.title)}}) {
                        if (addPair(v[0], v[1])) corrected.add(pairs.get(Lyrics.norm(v[0].trim()) + "|" + Lyrics.norm(v[1].trim())));
                    }
                }
            }

            // Stage 2: exact lookups for the corrected names (and the catalogue's length).
            if (useLrclib && !corrected.isEmpty()) {
                tasks = new ArrayList<>();
                for (Pair p : corrected) tasks.add(() -> lrclib.get(p.artist, p.title, album, effSec));
                for (Object o : par(tasks)) {
                    if (!(o instanceof LrclibClient.Result)) continue;
                    LrclibClient.Result r = (LrclibClient.Result) o;
                    if (r.synced != null) return r;
                    keep(r);
                }
            }

            // Stage 3: searches over every variant, best-scoring result overall.
            if (useLrclib) {
                tasks = new ArrayList<>();
                for (Pair p : pairs.values()) tasks.add(() -> lrclib.search(p.artist, p.title));
                for (Pair p : pairs.values()) {
                    String q = (Lyrics.primaryArtist(p.artist) + " " + Lyrics.cleanTitle(p.title)).trim();
                    tasks.add(() -> lrclib.searchText(q));
                }
                // Title alone only when the length can rule out other songs with the same name.
                if (effSec > 0 && !artist.isEmpty()) tasks.add(() -> lrclib.search("", Lyrics.cleanTitle(title)));
                LrclibClient.Result best = null;
                int bestScore = Integer.MIN_VALUE;
                for (Object o : par(tasks)) {
                    if (!(o instanceof List)) continue;
                    for (Object item : (List<?>) o) {
                        LrclibClient.Result r = (LrclibClient.Result) item;
                        if (r.synced == null && r.plain == null) continue;
                        int sc = LrclibClient.score(r, artist, title, effSec);
                        if (sc > bestScore) {
                            bestScore = sc;
                            best = r;
                        }
                    }
                }
                if (best != null && bestScore > -40) {
                    if (best.synced != null) return best;
                    keep(best);
                }
            }

            // Stage 4: unsynced fallback.
            if (plainBest == null && allowPlain && useOvh) {
                tasks = new ArrayList<>();
                for (Pair p : pairs.values()) tasks.add(() -> ovh.find(p.artist, p.title));
                for (Object o : par(tasks)) {
                    if (o instanceof LrclibClient.Result) return (LrclibClient.Result) o;
                }
            }

            if (plainBest != null) return plainBest;
            if (instrumental != null) return instrumental;
            if (error.get() != null && !answered.get()) throw error.get();
            return null;
        }

        void keep(LrclibClient.Result r) {
            if (r.instrumental && r.plain == null) instrumental = r;
            else if (plainBest == null) plainBest = r;
        }

        // Runs the tasks concurrently and returns their results in task order;
        // a failed task yields null and is remembered in `error`.
        List<Object> par(List<Callable<Object>> tasks) throws IOException {
            List<Callable<Object>> wrapped = new ArrayList<>(tasks.size());
            for (Callable<Object> c : tasks) {
                wrapped.add(() -> {
                    try {
                        Object v = c.call();
                        answered.set(true);
                        return v;
                    } catch (Exception e) {
                        error.compareAndSet(null, e instanceof IOException ? (IOException) e
                                : new IOException(e.getMessage(), e));
                        return null;
                    }
                });
            }
            List<Object> out = new ArrayList<>(tasks.size());
            try {
                for (Future<Object> f : POOL.invokeAll(wrapped)) {
                    try {
                        out.add(f.get());
                    } catch (ExecutionException e) {
                        out.add(null);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("cancelled");
            }
            return out;
        }
    }
}
