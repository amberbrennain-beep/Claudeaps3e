package com.usbapp.lyrics;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Switch;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_PICK_TREE = 1;
    private static final int REQ_MEDIA = 2;
    private static final String PREFS = LyricsService.PREFS;
    private static final String PREF_TREE = "tree_uri";


    // "01 - Artist - Title", "01. Title", "Artist - Title"
    private static final Pattern TRACK_NO = Pattern.compile("^\\s*\\d{1,3}\\s*[-._)]?\\s+");

    // One audio file found on the drive.
    static final class Song {
        final Uri parentDoc;      // document Uri of the folder it lives in
        final String fileName;    // e.g. "01 - Song.mp3"
        final Uri uri;            // document Uri of the audio file
        final String lrcName;     // e.g. "01 - Song.lrc"
        final Uri existingLrc;    // document Uri of an existing .lrc, or null
        String folder = "";       // e.g. "Rock / Album (Disc 2)", for the log

        Song(Uri parentDoc, String fileName, Uri uri, String lrcName, Uri existingLrc) {
            this.parentDoc = parentDoc;
            this.fileName = fileName;
            this.uri = uri;
            this.lrcName = lrcName;
            this.existingLrc = existingLrc;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean cancelRequested;

    private Uri treeUri;
    private TextView tvFolder, tvSummary, tvLog;
    private CheckBox cbOverwrite, cbPlain;
    private Button btnPick, btnStart, btnSingle;
    private EditText etArtist, etTitle;
    private ProgressBar progress;
    private TextView tvUappStatus, tvStepNotif, tvStepOverlay, tvStepMedia, tvStepFolder;
    private Button btnStepNotif, btnStepOverlay, btnStepMedia, btnStepFolder;
    private Switch swAuto, swBubble, swAny;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFolder = findViewById(R.id.tv_folder);
        tvSummary = findViewById(R.id.tv_summary);
        tvLog = findViewById(R.id.tv_log);
        cbOverwrite = findViewById(R.id.cb_overwrite);
        cbPlain = findViewById(R.id.cb_plain);
        btnPick = findViewById(R.id.btn_pick);
        btnStart = findViewById(R.id.btn_start);
        btnSingle = findViewById(R.id.btn_single);
        etArtist = findViewById(R.id.et_artist);
        etTitle = findViewById(R.id.et_title);
        progress = findViewById(R.id.progress);

        btnPick.setOnClickListener(v -> pickFolder());
        btnStart.setOnClickListener(v -> {
            if (running) {
                cancelRequested = true;
                btnStart.setText("Stopping…");
            } else {
                startBatch();
            }
        });
        btnSingle.setOnClickListener(v -> startSingle());

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        cbPlain.setChecked(prefs.getBoolean(LyricsService.PREF_PLAIN, true));
        cbPlain.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(LyricsService.PREF_PLAIN, on).apply());
        setupUapp();

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE, null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            if (hasPermission(u)) setTree(u);
        }
        log("Ready. Pick the music folder on your USB drive, then tap Get lyrics.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUapp();
    }

    @Override
    protected void onDestroy() {
        cancelRequested = true;
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UAPP

    private void setupUapp() {
        tvUappStatus = findViewById(R.id.tv_uapp_status);
        tvStepNotif = findViewById(R.id.tv_step_notif);
        tvStepOverlay = findViewById(R.id.tv_step_overlay);
        tvStepMedia = findViewById(R.id.tv_step_media);
        tvStepFolder = findViewById(R.id.tv_step_folder);
        btnStepNotif = findViewById(R.id.btn_step_notif);
        btnStepOverlay = findViewById(R.id.btn_step_overlay);
        btnStepMedia = findViewById(R.id.btn_step_media);
        btnStepFolder = findViewById(R.id.btn_step_folder);
        swAuto = findViewById(R.id.sw_auto);
        swBubble = findViewById(R.id.sw_bubble);
        swAny = findViewById(R.id.sw_any);

        btnStepNotif.setOnClickListener(v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        btnStepOverlay.setOnClickListener(v -> open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        btnStepMedia.setOnClickListener(v -> requestPermissions(new String[]{mediaPermission()}, REQ_MEDIA));
        btnStepFolder.setOnClickListener(v -> pickFolder());
        findViewById(R.id.btn_app_info).setOnClickListener(v -> open(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.btn_open_uapp).setOnClickListener(v -> openUapp());

        bindSwitch(swAuto, LyricsService.PREF_AUTO, true);
        bindSwitch(swBubble, LyricsService.PREF_BUBBLE, true);
        bindSwitch(swAny, LyricsService.PREF_ANY_PLAYER, false);
        bindSwitch(findViewById(R.id.sw_src_local), LyricsFinder.PREF_SRC_LOCAL, true);
        bindSwitch(findViewById(R.id.sw_src_lrclib), LyricsFinder.PREF_SRC_LRCLIB, true);
        bindSwitch(findViewById(R.id.sw_src_ovh), LyricsFinder.PREF_SRC_OVH, true);
    }

    private void bindSwitch(Switch sw, String key, boolean def) {
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            LyricsService.refreshFromSettings();
        });
    }

    private void refreshUapp() {
        if (tvStepNotif == null) return;
        boolean notif = hasNotificationAccess();
        boolean overlay = Settings.canDrawOverlays(this);
        boolean media = checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED;
        int folders = new TrackLocator(this).grantedTrees().size();

        step(tvStepNotif, btnStepNotif, "1. Notification access (to see what UAPP is playing)", notif, "Allow", "Settings");
        step(tvStepOverlay, btnStepOverlay, "2. Display over other apps (floating bubble)", overlay, "Allow", "Settings");
        step(tvStepMedia, btnStepMedia, "3. Music library access (to find song files)", media, "Allow", "Allowed");
        step(tvStepFolder, btnStepFolder, "4. Music folder (where .lrc files are saved)"
                + (folders > 0 ? " — " + folders + " picked" : ""), folders > 0, "Pick", "Add");
        btnStepMedia.setEnabled(!media);
        btnStepFolder.setEnabled(!running);
        findViewById(R.id.tv_restricted).setVisibility(notif ? View.GONE : View.VISIBLE);
        findViewById(R.id.btn_app_info).setVisibility(notif ? View.GONE : View.VISIBLE);

        boolean installed = uappPackage() != null;
        String status;
        if (!installed) status = "USB Audio Player PRO isn't installed. Lyrics are still saved as .lrc files next to your songs.";
        else if (notif && folders > 0) status = LyricsService.isRunning()
                ? "Ready. Play a song in USB Audio Player PRO and its lyrics are saved next to the file."
                : "Almost ready. If nothing happens when a song plays, turn notification access off and on again.";
        else status = "Finish the steps below so lyrics are fetched for each song UAPP plays.";
        tvUappStatus.setText(status);
    }

    private static void step(TextView tv, Button b, String label, boolean done, String todo, String doneLabel) {
        tv.setText((done ? "✓  " : "") + label);
        b.setText(done ? doneLabel : todo);
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        ComponentName me = new ComponentName(this, LyricsService.class);
        return flat != null && (flat.contains(me.flattenToString()) || flat.contains(me.flattenToShortString()));
    }

    private static String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    private String uappPackage() {
        for (String pkg : new String[]{"com.extreamsd.usbaudioplayerpro", "com.extreamsd.usbaudioplayertrial"}) {
            if (getPackageManager().getLaunchIntentForPackage(pkg) != null) return pkg;
        }
        return null;
    }

    private void openUapp() {
        String pkg = uappPackage();
        if (pkg == null) {
            open(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.extreamsd.usbaudioplayerpro")));
            return;
        }
        open(getPackageManager().getLaunchIntentForPackage(pkg));
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            log("Could not open that screen: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshUapp();
    }

    // ---------------------------------------------------------------- folder

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_TREE);
        } catch (Exception e) {
            log("Could not open the folder picker: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            log("Warning: could not keep access to this folder after restart (" + e.getMessage() + ")");
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_TREE, u.toString()).apply();
        setTree(u);
        TrackLocator.invalidate();
        refreshUapp();
        log("Folder selected: " + Lyrics.describeTree(u));
    }

    private boolean hasPermission(Uri u) {
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return true;
        }
        return false;
    }

    private void setTree(Uri u) {
        treeUri = u;
        tvFolder.setText(Lyrics.describeTree(u));
    }

    // ----------------------------------------------------------------- batch

    private void startBatch() {
        if (treeUri == null) {
            log("Pick a folder first.");
            pickFolder();
            return;
        }
        final boolean overwrite = cbOverwrite.isChecked();
        final boolean allowPlain = cbPlain.isChecked();
        setRunning(true);
        tvLog.setText("");
        worker.execute(() -> {
            try {
                runBatch(overwrite, allowPlain);
            } catch (Throwable t) {
                log("Error: " + t);
            } finally {
                ui.post(() -> setRunning(false));
            }
        });
    }

    private void runBatch(boolean overwrite, boolean allowPlain) {
        // Every picked folder, and every subfolder inside them.
        List<Uri> trees = FolderWalker.outermost(new TrackLocator(this).grantedTrees());
        if (trees.isEmpty()) trees.add(treeUri);
        List<Song> songs = new ArrayList<>();
        int folders = 0;
        for (Uri tree : trees) {
            log("Scanning " + Lyrics.describeTree(tree) + " and all its subfolders…");
            folders += scan(tree, songs);
            if (cancelRequested) {
                log("Stopped.");
                return;
            }
        }
        int needing = 0;
        for (Song s : songs) if (s.existingLrc == null) needing++;
        log("Found " + songs.size() + " song" + (songs.size() == 1 ? "" : "s") + " in " + folders + " folder"
                + (folders == 1 ? "" : "s") + "; " + needing + " without lyrics.");
        if (songs.isEmpty()) return;

        int saved = 0, skipped = 0, missing = 0, failed = 0;
        final int total = songs.size();
        ui.post(() -> {
            progress.setIndeterminate(false);
            progress.setMax(total);
            progress.setProgress(0);
        });

        LyricsFinder finder = new LyricsFinder(prefs);
        TrackLocator locator = new TrackLocator(this);

        for (int i = 0; i < total; i++) {
            if (cancelRequested) {
                log("Stopped by user.");
                break;
            }
            Song s = songs.get(i);
            final int done = i;
            final String status = (i + 1) + " / " + total + "  ·  saved " + saved + ", not found " + missing;
            ui.post(() -> {
                progress.setProgress(done);
                tvSummary.setText(status);
            });

            if (s.existingLrc != null && !overwrite) {
                skipped++;
                continue;
            }
            try {
                String[] tags = readTags(s);
                String artist = tags[0], title = tags[1], album = tags[2];
                int duration = Integer.parseInt(tags[3]);
                if (title.isEmpty()) {
                    log("?  " + s.fileName + " — no title, skipped");
                    missing++;
                    continue;
                }
                LrclibClient.Result r = finder.find(artist, title, album, duration, allowPlain, locator::findLocalLrc);
                String text = Lyrics.pick(r, allowPlain);
                if (text == null) {
                    log("✗  " + Lyrics.label(artist, title) + (r != null && r.instrumental ? " — instrumental" : " — not found in any source"));
                    missing++;
                    continue;
                }
                String lrc = Lyrics.withHeader(text, artist, title, album);
                Lyrics.write(getContentResolver(), s.parentDoc, s.lrcName, s.existingLrc, lrc);
                log("✓  " + Lyrics.label(artist, title) + (text == r.synced ? "" : " (unsynced)") + "  · " + r.source
                        + "\n     → " + s.folder);
                saved++;
            } catch (Exception e) {
                log("!  " + s.fileName + " — " + e.getMessage() + "\n     in " + s.folder);
                failed++;
            }
        }

        final String summary = "Saved " + saved + ", already had lyrics " + skipped
                + ", not found " + missing + (failed > 0 ? ", errors " + failed : "");
        ui.post(() -> {
            progress.setProgress(total);
            tvSummary.setText(summary);
        });
        log("Done. " + summary + ".");
    }

    // Collects every audio file under the picked folder, however deep, and
    // notes which already have a .lrc with the same base name beside them.
    // Returns the number of folders scanned.
    private int scan(Uri tree, List<Song> out) {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        int[] lastReport = {0};
        return FolderWalker.walk(getContentResolver(), tree, rootId, (dirId, kids) -> {
            if (cancelRequested) return false;
            Map<String, String> lrcByBase = new HashMap<>();
            for (String[] k : kids) {
                if (k[1].toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                    lrcByBase.put(Lyrics.baseName(k[1]).toLowerCase(Locale.ROOT), k[0]);
                }
            }
            Uri dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, dirId);
            String folder = Lyrics.describeDocId(dirId);
            for (String[] k : kids) {
                String name = k[1];
                if (!Lyrics.AUDIO_EXT.contains(Lyrics.extension(name))) continue;
                String base = Lyrics.baseName(name);
                String lrcId = lrcByBase.get(base.toLowerCase(Locale.ROOT));
                Song song = new Song(dirDoc, name,
                        DocumentsContract.buildDocumentUriUsingTree(tree, k[0]),
                        base + ".lrc",
                        lrcId == null ? null : DocumentsContract.buildDocumentUriUsingTree(tree, lrcId));
                song.folder = folder;
                out.add(song);
            }
            if (out.size() - lastReport[0] >= 500) {
                lastReport[0] = out.size();
                final String msg = "Scanning… " + out.size() + " songs so far";
                ui.post(() -> tvSummary.setText(msg));
            }
            return true;
        });
    }

    // {artist, title, album, durationSeconds}, falling back to the file name
    // ("Artist - Title.mp3") when the file has no tags.
    private String[] readTags(Song s) {
        String artist = "", title = "", album = "";
        int duration = 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, s.uri);
            artist = Lyrics.nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (artist.isEmpty()) artist = Lyrics.nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            title = Lyrics.nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            album = Lyrics.nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) duration = (int) Math.round(Long.parseLong(d.trim()) / 1000.0);
        } catch (Exception ignored) {
            // unreadable or unsupported container; fall through to the file name
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }

        if (title.isEmpty() || artist.isEmpty()) {
            String base = TRACK_NO.matcher(Lyrics.baseName(s.fileName)).replaceFirst("").replace('_', ' ').trim();
            int dash = base.indexOf(" - ");
            if (dash > 0) {
                if (artist.isEmpty()) artist = base.substring(0, dash).trim();
                if (title.isEmpty()) title = base.substring(dash + 3).trim();
            } else if (title.isEmpty()) {
                title = base;
            }
        }
        return new String[]{artist, title, album, String.valueOf(duration)};
    }

    // ---------------------------------------------------------------- single

    private void startSingle() {
        final String artist = etArtist.getText().toString().trim();
        final String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) {
            etTitle.setError("Enter a song title");
            return;
        }
        if (treeUri == null) {
            log("Pick a folder first; the .lrc file is saved there.");
            pickFolder();
            return;
        }
        final boolean allowPlain = cbPlain.isChecked();
        setRunning(true);
        worker.execute(() -> {
            try {
                LrclibClient.Result r = new LyricsFinder(prefs).find(artist, title, "", 0, allowPlain,
                        new TrackLocator(MainActivity.this)::findLocalLrc);
                String text = Lyrics.pick(r, allowPlain);
                if (text == null) {
                    log("✗  " + Lyrics.label(artist, title) + " — not found");
                    return;
                }
                String a = artist.isEmpty() ? r.artist : artist;
                String name = Lyrics.sanitize((a.isEmpty() ? "" : a + " - ") + (r.title.isEmpty() ? title : r.title)) + ".lrc";
                Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri));
                Lyrics.write(getContentResolver(), rootDoc, name, null,
                        Lyrics.withHeader(text, a, r.title, r.album));
                log("✓  Saved " + name + (text == r.synced ? "" : " (unsynced)") + "  · " + r.source);
            } catch (Exception e) {
                log("!  " + Lyrics.label(artist, title) + " — " + e.getMessage());
            } finally {
                ui.post(() -> setRunning(false));
            }
        });
    }

    // ------------------------------------------------------------------ util

    private void setRunning(boolean on) {
        running = on;
        cancelRequested = false;
        btnPick.setEnabled(!on);
        btnSingle.setEnabled(!on);
        btnStart.setText(on ? "Stop" : "Get lyrics");
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) progress.setIndeterminate(true);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void log(String msg) {
        ui.post(() -> {
            CharSequence cur = tvLog.getText();
            // keep the log from growing without bound on huge libraries
            if (cur.length() > 60000) cur = cur.subSequence(cur.length() - 40000, cur.length());
            tvLog.setText(cur.length() == 0 ? msg : cur + "\n" + msg);
        });
    }
}
