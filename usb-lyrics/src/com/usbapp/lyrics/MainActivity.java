package com.usbapp.lyrics;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_PICK_TREE = 1;
    private static final String PREFS = "usb_lyrics";
    private static final String PREF_TREE = "tree_uri";

    private static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "aiff", "aif", "ape", "wv"));

    // "01 - Artist - Title", "01. Title", "Artist - Title"
    private static final Pattern TRACK_NO = Pattern.compile("^\\s*\\d{1,3}\\s*[-._)]?\\s+");

    // One audio file found on the drive.
    static final class Song {
        final Uri parentDoc;      // document Uri of the folder it lives in
        final String fileName;    // e.g. "01 - Song.mp3"
        final Uri uri;            // document Uri of the audio file
        final String lrcName;     // e.g. "01 - Song.lrc"
        final Uri existingLrc;    // document Uri of an existing .lrc, or null

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
    private final LrclibClient lrclib = new LrclibClient();
    private volatile boolean running;
    private volatile boolean cancelRequested;

    private Uri treeUri;
    private TextView tvFolder, tvSummary, tvLog;
    private CheckBox cbOverwrite, cbPlain;
    private Button btnPick, btnStart, btnSingle;
    private EditText etArtist, etTitle;
    private ProgressBar progress;

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

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE, null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            if (hasPermission(u)) setTree(u);
        }
        log("Ready. Pick the music folder on your USB drive, then tap Get lyrics.");
    }

    @Override
    protected void onDestroy() {
        cancelRequested = true;
        worker.shutdownNow();
        super.onDestroy();
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
        log("Folder selected: " + describeTree(u));
    }

    private boolean hasPermission(Uri u) {
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return true;
        }
        return false;
    }

    private void setTree(Uri u) {
        treeUri = u;
        tvFolder.setText(describeTree(u));
    }

    // "primary:Music" -> "Internal storage / Music", "1234-ABCD:Songs" -> "USB / SD (1234-ABCD) / Songs"
    private static String describeTree(Uri u) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(u);
        } catch (Exception e) {
            return u.toString();
        }
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        String vol = id.substring(0, colon);
        String path = id.substring(colon + 1);
        String volName = "primary".equals(vol) ? "Internal storage" : "USB / SD (" + vol + ")";
        return path.isEmpty() ? volName : volName + " / " + path.replace("/", " / ");
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
        log("Scanning " + describeTree(treeUri) + " …");
        List<Song> songs = new ArrayList<>();
        Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri));
        scan(DocumentsContract.getTreeDocumentId(treeUri), rootDoc, songs, 0);
        if (cancelRequested) {
            log("Stopped.");
            return;
        }
        log("Found " + songs.size() + " song" + (songs.size() == 1 ? "" : "s") + ".");
        if (songs.isEmpty()) return;

        int saved = 0, skipped = 0, missing = 0, failed = 0;
        final int total = songs.size();
        ui.post(() -> {
            progress.setIndeterminate(false);
            progress.setMax(total);
            progress.setProgress(0);
        });

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
                LrclibClient.Result r = lrclib.find(artist, title, album, duration);
                String text = pickLyrics(r, allowPlain);
                if (text == null) {
                    log("✗  " + label(artist, title) + (r != null && r.instrumental ? " — instrumental" : " — not found"));
                    missing++;
                    continue;
                }
                String lrc = withHeader(text, artist, title, album);
                writeLrc(s, lrc);
                log("✓  " + label(artist, title) + (text == r.synced ? "" : " (unsynced)"));
                saved++;
            } catch (Exception e) {
                log("!  " + s.fileName + " — " + e.getMessage());
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

    // Walks the tree, collecting audio files and noting which already have a
    // .lrc with the same base name in the same folder.
    private void scan(String docId, Uri dirDoc, List<Song> out, int depth) {
        if (depth > 12 || cancelRequested) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        List<String[]> entries = new ArrayList<>();  // {id, name, mime}
        ContentResolver cr = getContentResolver();
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                entries.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
            }
        } catch (Exception e) {
            log("Could not read a folder: " + e.getMessage());
            return;
        }

        Map<String, String> lrcByBase = new HashMap<>();
        for (String[] e : entries) {
            String name = e[1];
            if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                lrcByBase.put(baseName(name).toLowerCase(Locale.ROOT), e[0]);
            }
        }
        for (String[] e : entries) {
            String id = e[0], name = e[1], mime = e[2];
            if (name == null || name.startsWith(".")) continue;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                scan(id, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), out, depth + 1);
            } else if (AUDIO_EXT.contains(extension(name))) {
                String base = baseName(name);
                String lrcId = lrcByBase.get(base.toLowerCase(Locale.ROOT));
                out.add(new Song(dirDoc, name,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        base + ".lrc",
                        lrcId == null ? null : DocumentsContract.buildDocumentUriUsingTree(treeUri, lrcId)));
            }
        }
    }

    // {artist, title, album, durationSeconds}, falling back to the file name
    // ("Artist - Title.mp3") when the file has no tags.
    private String[] readTags(Song s) {
        String artist = "", title = "", album = "";
        int duration = 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, s.uri);
            artist = nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (artist.isEmpty()) artist = nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            title = nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            album = nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
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
            String base = TRACK_NO.matcher(baseName(s.fileName)).replaceFirst("").replace('_', ' ').trim();
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

    private static String pickLyrics(LrclibClient.Result r, boolean allowPlain) {
        if (r == null) return null;
        if (r.synced != null) return r.synced;
        return allowPlain ? r.plain : null;
    }

    // Standard LRC ID tags so players show the right song info.
    private static String withHeader(String body, String artist, String title, String album) {
        StringBuilder sb = new StringBuilder();
        if (!artist.isEmpty()) sb.append("[ar:").append(artist).append("]\n");
        if (!title.isEmpty()) sb.append("[ti:").append(title).append("]\n");
        if (album != null && !album.isEmpty()) sb.append("[al:").append(album).append("]\n");
        sb.append("[re:USB Lyrics / lrclib.net]\n");
        sb.append(body.replace("\r\n", "\n").trim()).append('\n');
        return sb.toString();
    }

    private void writeLrc(Song s, String lrc) throws Exception {
        ContentResolver cr = getContentResolver();
        Uri target = s.existingLrc;
        if (target == null) {
            // application/octet-stream keeps the name exactly as given; text/plain
            // would make some providers save "Song.lrc.txt".
            target = DocumentsContract.createDocument(cr, s.parentDoc, "application/octet-stream", s.lrcName);
            if (target == null) throw new Exception("could not create " + s.lrcName);
        }
        try (OutputStream os = cr.openOutputStream(target, "wt")) {
            if (os == null) throw new Exception("could not open " + s.lrcName);
            os.write(lrc.getBytes(StandardCharsets.UTF_8));
        }
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
                LrclibClient.Result r = lrclib.find(artist, title, "", 0);
                String text = pickLyrics(r, allowPlain);
                if (text == null) {
                    log("✗  " + label(artist, title) + " — not found");
                    return;
                }
                String a = artist.isEmpty() ? r.artist : artist;
                String name = sanitize((a.isEmpty() ? "" : a + " - ") + (r.title.isEmpty() ? title : r.title)) + ".lrc";
                Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri));
                writeLrc(new Song(rootDoc, name, null, name, null),
                        withHeader(text, a, r.title, r.album));
                log("✓  Saved " + name + (text == r.synced ? "" : " (unsynced)"));
            } catch (Exception e) {
                log("!  " + label(artist, title) + " — " + e.getMessage());
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

    private static String label(String artist, String title) {
        return artist.isEmpty() ? title : artist + " - " + title;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    // FAT32/exFAT (what most USB sticks use) reject these characters.
    private static String sanitize(String name) {
        Matcher m = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]").matcher(name);
        String s = m.replaceAll("_").trim();
        return s.isEmpty() ? "lyrics" : s;
    }
}
