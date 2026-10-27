package com.usbapp.lyrics;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Companion for USB Audio Player PRO.
//
// Android only lets an app read other apps' "now playing" info (MediaSession)
// if it is a notification listener, so this service exists for that: it is
// bound by the system once the user grants notification access, follows
// UAPP's session, and for each new track finds the song file and writes its
// .lrc next to it, which is where UAPP reads lyrics from. The floating bubble
// shows what happened and lets the user pick a different match.
public class LyricsService extends NotificationListenerService {

    static final String PREFS = "usb_lyrics";
    static final String PREF_AUTO = "uapp_auto";
    static final String PREF_BUBBLE = "uapp_bubble";
    static final String PREF_ANY_PLAYER = "any_player";
    static final String PREF_PLAIN = "allow_plain";

    static final String UAPP_PREFIX = "com.extreamsd.usbaudioplayer";

    // What the bubble shows for the current track.
    enum State { IDLE, WORKING, SAVED, HAS_LYRICS, NOT_FOUND, NO_FILE, NO_FOLDER, ERROR }

    static final class Track {
        final String artist, title, album, pathHint, pkg;
        final long durationMs;

        Track(MediaMetadata md, String pkg) {
            String a = Lyrics.nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
            if (a.isEmpty()) a = Lyrics.nz(md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
            artist = a;
            title = Lyrics.nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
            album = Lyrics.nz(md.getString(MediaMetadata.METADATA_KEY_ALBUM));
            durationMs = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
            String hint = md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
            if (hint == null || hint.isEmpty()) hint = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            pathHint = hint;
            this.pkg = pkg;
        }

        String key() {
            return TrackLocator.key(artist, title, durationMs);
        }

        String label() {
            return Lyrics.label(artist, title);
        }
    }

    private static LyricsService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LrclibClient lrclib = new LrclibClient();
    private final LyricsOvhClient ovh = new LyricsOvhClient();
    private TrackLocator locator;
    private SharedPreferences prefs;
    private MediaSessionManager msm;
    private MediaController controller;
    private FloatingPanel panel;

    private Track track;
    private int generation;   // bumps on every track change so stale results are dropped
    private State state = State.IDLE;
    private String detail = "";
    private TrackLocator.Target target;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::onSessions;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onTrack(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            updatePanelVisibility();
        }

        @Override
        public void onSessionDestroyed() {
            detach();
            updatePanelVisibility();
        }
    };

    static boolean isRunning() {
        return instance != null;
    }

    // Called by the settings screen when a switch changes.
    static void refreshFromSettings() {
        LyricsService s = instance;
        if (s != null) s.ui.post(() -> {
            s.onSessions(s.activeSessions());
            s.updatePanelVisibility();
        });
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        locator = new TrackLocator(this);
        panel = new FloatingPanel(this, new FloatingPanel.Actions() {
            @Override
            public void retry() {
                if (track != null) process(track, generation, false);
            }

            @Override
            public void loadMatches() {
                LyricsService.this.loadMatches();
            }

            @Override
            public void choose(LrclibClient.Result r) {
                chooseMatch(r);
            }
        });
        msm = getSystemService(MediaSessionManager.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component(), ui);
            onSessions(activeSessions());
        } catch (SecurityException e) {
            // notification access was revoked between bind and here
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        detach();
        if (panel != null) panel.hide();
        instance = null;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        if (panel != null) panel.hide();
        instance = null;
        super.onDestroy();
    }

    private ComponentName component() {
        return new ComponentName(this, LyricsService.class);
    }

    private List<MediaController> activeSessions() {
        try {
            return msm == null ? new ArrayList<>() : msm.getActiveSessions(component());
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------- sessions

    private void onSessions(List<MediaController> sessions) {
        boolean anyPlayer = prefs.getBoolean(PREF_ANY_PLAYER, false);
        MediaController pick = null;
        if (sessions != null) {
            for (MediaController c : sessions) {
                if (c.getPackageName().startsWith(UAPP_PREFIX)) {
                    pick = c;
                    break;
                }
            }
            if (pick == null && anyPlayer) {
                for (MediaController c : sessions) {
                    if (c.getPackageName().equals(getPackageName())) continue;
                    PlaybackState ps = c.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                        pick = c;
                        break;
                    }
                }
            }
        }
        if (pick != null && controller != null
                && pick.getSessionToken().equals(controller.getSessionToken())) {
            updatePanelVisibility();
            return;
        }
        detach();
        controller = pick;
        if (controller != null) {
            controller.registerCallback(controllerCallback, ui);
            onTrack(controller.getMetadata());
        }
        updatePanelVisibility();
    }

    private void detach() {
        if (controller != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (Exception ignored) {
            }
            controller = null;
        }
    }

    private void onTrack(MediaMetadata md) {
        if (md == null || controller == null) return;
        Track t = new Track(md, controller.getPackageName());
        if (t.title.isEmpty()) return;
        if (track != null && track.key().equals(t.key())) return;
        track = t;
        generation++;
        target = null;
        if (prefs.getBoolean(PREF_AUTO, true)) {
            process(t, generation, false);
        } else {
            setState(State.IDLE, "Tap Find lyrics to look this song up.");
        }
    }

    // ------------------------------------------------------------ lookups

    private void process(Track t, int gen, boolean overwrite) {
        setState(State.WORKING, "Looking for the song file…");
        final boolean allowPlain = prefs.getBoolean(PREF_PLAIN, true);
        worker.execute(() -> {
            try {
                if (locator.grantedTrees().isEmpty()) {
                    post(gen, State.NO_FOLDER, "Open USB Lyrics and pick your music folder (the USB drive) so lyrics can be saved next to the songs.", null);
                    return;
                }
                TrackLocator.Target tg = locator.locate(t.artist, t.title, t.album, t.durationMs, t.pathHint);
                if (tg == null) {
                    post(gen, State.NO_FILE, "Couldn't find this song's file in your picked folders. Pick the folder it is in from USB Lyrics, or check the file is named after the song.", null);
                    return;
                }
                if (tg.existingLrc != null && !overwrite) {
                    post(gen, State.HAS_LYRICS, "Already has lyrics: " + tg.lrcName + "\n" + tg.where, tg);
                    return;
                }
                post(gen, State.WORKING, "Searching lyrics…\n" + tg.where, tg);
                LrclibClient.Result r = new LyricsFinder(prefs).find(t.artist, t.title, t.album,
                        (int) Math.round(t.durationMs / 1000.0), allowPlain, locator::findLocalLrc);
                String text = Lyrics.pick(r, allowPlain);
                if (text == null) {
                    post(gen, State.NOT_FOUND, (r != null && r.instrumental ? "LRCLIB lists this as instrumental."
                            : r != null ? "Only unsynced lyrics exist, and unsynced lyrics are turned off."
                            : "No lyrics found in any source.")
                            + " Tap Choose match to see every candidate.", tg);
                    return;
                }
                save(t, gen, tg, r, text);
            } catch (Exception e) {
                post(gen, State.ERROR, "Error: " + e.getMessage(), null);
            }
        });
    }

    private void save(Track t, int gen, TrackLocator.Target tg, LrclibClient.Result r, String text) throws Exception {
        String artist = t.artist.isEmpty() ? r.artist : t.artist;
        String album = t.album.isEmpty() ? r.album : t.album;
        Uri written = Lyrics.write(getContentResolver(), tg.parentDoc, tg.lrcName, tg.existingLrc,
                Lyrics.withHeader(text, artist, t.title, album));
        TrackLocator.invalidate();
        TrackLocator.Target saved = new TrackLocator.Target(tg.treeUri, tg.parentDoc, tg.audioName, written, tg.where);
        boolean synced = text == r.synced;
        post(gen, State.SAVED, "Saved " + tg.lrcName + (synced ? " (synced" : " (unsynced") + ", from " + r.source + ")\n" + tg.where
                + "\nIf USB Audio Player PRO doesn't show it yet, restart the song.", saved);
        if (!bubbleAllowed()) {
            ui.post(() -> Toast.makeText(this, "Lyrics saved: " + tg.lrcName, Toast.LENGTH_SHORT).show());
        }
    }

    private void loadMatches() {
        final Track t = track;
        final int gen = generation;
        if (t == null) return;
        panel.showMatchesLoading();
        worker.execute(() -> {
            // Everything every source has, so the user can pick when automatic matching missed.
            List<LrclibClient.Result> list = new ArrayList<>();
            String cleanT = Lyrics.cleanTitle(t.title), mainA = Lyrics.primaryArtist(t.artist);
            boolean useLrclib = prefs.getBoolean(LyricsFinder.PREF_SRC_LRCLIB, true);
            if (prefs.getBoolean(LyricsFinder.PREF_SRC_LOCAL, true)) {
                LrclibClient.Result local = locator.findLocalLrc(t.artist, t.title);
                if (local != null) list.add(local);
            }
            try {
                if (useLrclib) {
                    list.addAll(lrclib.search(t.artist, t.title));
                    if (!cleanT.equals(t.title) || !mainA.equals(t.artist)) list.addAll(lrclib.search(mainA, cleanT));
                    list.addAll(lrclib.searchText((mainA + " " + cleanT).trim()));
                    list.addAll(lrclib.search("", cleanT));
                }
            } catch (Exception ignored) {
                // show whatever the other sources found
            }
            if (prefs.getBoolean(LyricsFinder.PREF_SRC_OVH, true)) {
                try {
                    LrclibClient.Result r = ovh.find(mainA, cleanT);
                    if (r == null && !mainA.equals(t.artist)) r = ovh.find(t.artist, cleanT);
                    if (r != null) list.add(r);
                } catch (Exception ignored) {
                }
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            list.removeIf(r -> !seen.add(r.source + "|" + Lyrics.norm(r.artist) + "|" + Lyrics.norm(r.title)
                    + "|" + r.durationSec + "|" + (r.synced != null)));
            final int sec = (int) Math.round(t.durationMs / 1000.0);
            List<LrclibClient.Result> usable = new ArrayList<>();
            for (LrclibClient.Result r : list) if (r.synced != null || r.plain != null) usable.add(r);
            usable.sort((a, b) -> Integer.compare(
                    safeScore(b, t, sec), safeScore(a, t, sec)));
            final List<LrclibClient.Result> out = usable.size() > 25 ? usable.subList(0, 25) : usable;
            ui.post(() -> {
                if (gen == generation) panel.showMatches(out, sec);
            });
        });
    }

    private static int safeScore(LrclibClient.Result r, Track t, int sec) {
        // Keep wrong-length results in the list (the user may know better) but rank them last.
        int s = LrclibClient.score(r, t.artist, t.title, sec);
        return s == Integer.MIN_VALUE ? -100000 + LrclibClient.score(r, t.artist, t.title, 0) : s;
    }

    private void chooseMatch(LrclibClient.Result r) {
        final Track t = track;
        final int gen = generation;
        if (t == null) return;
        setState(State.WORKING, "Saving the chosen lyrics…");
        final TrackLocator.Target known = target;
        worker.execute(() -> {
            try {
                TrackLocator.Target tg = known != null ? known
                        : locator.locate(t.artist, t.title, t.album, t.durationMs, t.pathHint);
                if (tg == null) {
                    post(gen, State.NO_FILE, "Couldn't find this song's file in your picked folders.", null);
                    return;
                }
                String text = r.synced != null ? r.synced : r.plain;
                save(t, gen, tg, r, text);
            } catch (Exception e) {
                post(gen, State.ERROR, "Error: " + e.getMessage(), null);
            }
        });
    }

    // ------------------------------------------------------------------ UI

    private void post(int gen, State s, String msg, TrackLocator.Target tg) {
        ui.post(() -> {
            if (gen != generation) return;
            if (tg != null) target = tg;
            setState(s, msg);
        });
    }

    private void setState(State s, String msg) {
        state = s;
        detail = msg;
        if (panel != null) panel.update(track == null ? "" : track.label(), state, detail);
    }

    private boolean bubbleAllowed() {
        return prefs.getBoolean(PREF_BUBBLE, true) && Settings.canDrawOverlays(this);
    }

    private void updatePanelVisibility() {
        if (panel == null) return;
        boolean active = controller != null;
        if (active) {
            PlaybackState ps = controller.getPlaybackState();
            int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
            active = st != PlaybackState.STATE_NONE && st != PlaybackState.STATE_STOPPED
                    && st != PlaybackState.STATE_ERROR;
        }
        if (active && bubbleAllowed()) {
            panel.show();
            setState(state, detail);
        } else {
            panel.hide();
        }
    }
}
