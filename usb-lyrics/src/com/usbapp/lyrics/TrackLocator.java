package com.usbapp.lyrics;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// Works out which file on disk a song that another app is playing comes from,
// so its .lrc can be written next to it (where USB Audio Player PRO looks).
//
// Players only publish tags (title, artist, album, length) over MediaSession,
// not file paths, so this tries, in order:
//   1. a file path or content Uri the player happens to expose,
//   2. Android's media library, matched on tags, mapped into a folder the user
//      granted with the folder picker,
//   3. a file-name search of the granted folders.
// Writing always goes through a granted folder, which is what lets it write to
// USB drives and SD cards.
final class TrackLocator {

    static final class Target {
        final Uri treeUri;
        final Uri parentDoc;
        final String audioName;
        final String lrcName;
        final Uri existingLrc;
        final String where;

        Target(Uri treeUri, Uri parentDoc, String audioName, Uri existingLrc, String where) {
            this.treeUri = treeUri;
            this.parentDoc = parentDoc;
            this.audioName = audioName;
            this.lrcName = Lyrics.baseName(audioName) + ".lrc";
            this.existingLrc = existingLrc;
            this.where = where;
        }
    }

    private static final Pattern TRACK_NO = Pattern.compile("^\\s*\\d{1,3}\\s*[-._)]?\\s+");
    private static final int MAX_INDEX = 50000;

    private static final class Entry {
        final Uri tree;
        final String docId;
        final String parentDocId;
        final String name;
        final String normBase;
        final String normParent;

        Entry(Uri tree, String docId, String parentDocId, String name) {
            this.tree = tree;
            this.docId = docId;
            this.parentDocId = parentDocId;
            this.name = name;
            this.normBase = Lyrics.norm(TRACK_NO.matcher(Lyrics.baseName(name)).replaceFirst(""));
            this.normParent = Lyrics.norm(parentDocId);
        }
    }

    // File-name index of the granted folders, rebuilt at most once a minute.
    private static List<Entry> index;
    private static long indexedAt;
    private static List<Uri> indexedTrees;

    private final Context ctx;
    private final ContentResolver cr;

    TrackLocator(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.cr = ctx.getContentResolver();
    }

    // Folders the user has granted read/write access to.
    List<Uri> grantedTrees() {
        List<Uri> out = new ArrayList<>();
        for (UriPermission p : cr.getPersistedUriPermissions()) {
            if (p.isReadPermission() && p.isWritePermission()
                    && DocumentsContract.isTreeUri(p.getUri())) {
                out.add(p.getUri());
            }
        }
        return out;
    }

    Target locate(String artist, String title, String album, long durationMs, String pathHint) {
        List<Uri> trees = grantedTrees();
        if (trees.isEmpty()) return null;

        Target t = fromPath(trees, pathHint);
        if (t != null) return t;
        t = fromMediaStore(trees, artist, title, album, durationMs);
        if (t != null) return t;
        return fromIndex(trees, artist, title, album, durationMs);
    }

    // ------------------------------------------------------------ path hint

    private Target fromPath(List<Uri> trees, String hint) {
        if (hint == null || hint.isEmpty()) return null;
        String path = hint;
        if (path.startsWith("file://")) path = Uri.parse(path).getPath();
        if (path == null) return null;
        if (path.startsWith("content://")) {
            Uri u = Uri.parse(path);
            if (DocumentsContract.isDocumentUri(ctx, u)) {
                String docId = DocumentsContract.getDocumentId(u);
                return fromDocId(trees, docId, "player");
            }
            return null;
        }
        // /storage/emulated/0/Music/a.flac -> primary:Music/a.flac
        // /storage/1234-ABCD/Music/a.flac  -> 1234-ABCD:Music/a.flac
        String docId = null;
        if (path.startsWith("/storage/emulated/0/")) {
            docId = "primary:" + path.substring("/storage/emulated/0/".length());
        } else if (path.startsWith("/sdcard/")) {
            docId = "primary:" + path.substring("/sdcard/".length());
        } else if (path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/")) {
            String rest = path.substring(path.startsWith("/storage/") ? 9 : 14);
            int slash = rest.indexOf('/');
            if (slash > 0) docId = rest.substring(0, slash) + ":" + rest.substring(slash + 1);
        }
        return docId == null ? null : fromDocId(trees, docId, "player");
    }

    // ----------------------------------------------------------- MediaStore

    private Target fromMediaStore(List<Uri> trees, String artist, String title, String album, long durationMs) {
        if (title.isEmpty()) return null;
        String[] proj = {
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.VOLUME_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION};
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String vol : MediaStore.getExternalVolumeNames(ctx)) {
            try (Cursor c = cr.query(MediaStore.Audio.Media.getContentUri(vol), proj,
                    MediaStore.Audio.Media.TITLE + " = ? COLLATE NOCASE", new String[]{title}, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    String name = c.getString(0), rel = c.getString(1), volume = c.getString(2);
                    if (name == null || rel == null || volume == null) continue;
                    int score = 0;
                    if (!artist.isEmpty() && artist.equalsIgnoreCase(Lyrics.nz(c.getString(3)))) score += 20;
                    if (!album.isEmpty() && album.equalsIgnoreCase(Lyrics.nz(c.getString(4)))) score += 10;
                    long d = c.getLong(5);
                    if (durationMs > 0 && d > 0) {
                        long diff = Math.abs(d - durationMs);
                        if (diff > 3000) continue;   // same title, different song
                        score -= (int) (diff / 500);
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        String v = MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume) ? "primary" : volume;
                        if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                        best = v + ":" + (rel.isEmpty() ? "" : rel + "/") + name;
                    }
                }
            } catch (Exception ignored) {
                // no media permission or volume went away; fall through to the file index
            }
        }
        return best == null ? null : fromDocId(trees, best, "media library");
    }

    // Maps "VOL:path/to/file.flac" onto a granted folder that contains it.
    private Target fromDocId(List<Uri> trees, String docId, String how) {
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String vol = docId.substring(0, colon);
        String path = docId.substring(colon + 1);
        int slash = path.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : path.substring(0, slash);
        String name = path.substring(slash + 1);

        for (Uri tree : trees) {
            String treeId;
            try {
                treeId = DocumentsContract.getTreeDocumentId(tree);
            } catch (Exception e) {
                continue;
            }
            int tc = treeId.indexOf(':');
            if (tc < 0) continue;
            String treeVol = treeId.substring(0, tc);
            String treePath = treeId.substring(tc + 1);
            if (!treeVol.equalsIgnoreCase(vol)) continue;
            if (!treePath.isEmpty() && !(parentPath.equals(treePath) || parentPath.startsWith(treePath + "/"))) continue;

            // Volume ids differ in case between MediaStore and the documents
            // provider ("1234-abcd" vs "1234-ABCD"); use the tree's spelling.
            String parentId = treeVol + ":" + parentPath;
            Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, parentId);
            List<String[]> kids = children(tree, parentId);
            if (kids == null) continue;
            boolean found = false;
            Uri lrc = null;
            String lrcName = Lyrics.baseName(name) + ".lrc";
            for (String[] k : kids) {
                if (name.equals(k[1])) found = true;
                if (lrcName.equalsIgnoreCase(k[1])) lrc = DocumentsContract.buildDocumentUriUsingTree(tree, k[0]);
            }
            if (found) {
                return new Target(tree, parentDoc, name, lrc, Lyrics.describeDocId(parentId) + "  (via " + how + ")");
            }
        }
        return null;
    }

    // ----------------------------------------------------------- file index

    private Target fromIndex(List<Uri> trees, String artist, String title, String album, long durationMs) {
        String nt = Lyrics.norm(title);
        if (nt.length() < 2) return null;
        List<Entry> idx = index(trees, false);
        List<Entry> hits = match(idx, nt, Lyrics.norm(artist), Lyrics.norm(album));
        if (hits.isEmpty()) {
            // The drive may have changed since the index was built.
            idx = index(trees, true);
            hits = match(idx, nt, Lyrics.norm(artist), Lyrics.norm(album));
        }
        if (hits.isEmpty()) return null;

        Entry pick = hits.get(0);
        if (durationMs > 0 && hits.size() > 1) {
            // Several files share the title: check the top few by length.
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < Math.min(4, hits.size()); i++) {
                Entry e = hits.get(i);
                long d = durationOf(DocumentsContract.buildDocumentUriUsingTree(e.tree, e.docId));
                long diff = d <= 0 ? Long.MAX_VALUE - 1 : Math.abs(d - durationMs);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    pick = e;
                }
            }
        }

        Uri lrc = null;
        String lrcName = Lyrics.baseName(pick.name) + ".lrc";
        for (Entry e : idx) {
            if (e.parentDocId.equals(pick.parentDocId) && e.name.equalsIgnoreCase(lrcName)) {
                lrc = DocumentsContract.buildDocumentUriUsingTree(e.tree, e.docId);
            }
        }
        return new Target(pick.tree, DocumentsContract.buildDocumentUriUsingTree(pick.tree, pick.parentDocId),
                pick.name, lrc, Lyrics.describeDocId(pick.parentDocId) + "  (via file name)");
    }

    private static List<Entry> match(List<Entry> idx, String nt, String na, String nal) {
        List<Entry> hits = new ArrayList<>();
        final List<Integer> scores = new ArrayList<>();
        for (Entry e : idx) {
            if (!Lyrics.AUDIO_EXT.contains(Lyrics.extension(e.name))) continue;
            if (!e.normBase.contains(nt)) continue;
            int score = 0;
            if (e.normBase.equals(nt) || e.normBase.endsWith(nt)) score += 30;
            if (!na.isEmpty() && (e.normBase.contains(na) || e.normParent.contains(na))) score += 20;
            if (!nal.isEmpty() && e.normParent.contains(nal)) score += 10;
            score -= Math.min(20, e.normBase.length() - nt.length());   // fewer extra words is better
            hits.add(e);
            scores.add(score);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> scores.get(b) - scores.get(a));
        List<Entry> sorted = new ArrayList<>();
        for (int i : order) sorted.add(hits.get(i));
        return sorted;
    }

    private List<Entry> index(List<Uri> trees, boolean force) {
        synchronized (TrackLocator.class) {
            long now = System.currentTimeMillis();
            boolean same = index != null && trees.equals(indexedTrees);
            if (same && (!force || now - indexedAt < 60_000)) return index;
            List<Entry> out = new ArrayList<>();
            for (Uri tree : trees) {
                try {
                    walk(tree, DocumentsContract.getTreeDocumentId(tree), out, 0);
                } catch (Exception ignored) {
                    // drive unplugged
                }
            }
            index = out;
            indexedAt = now;
            indexedTrees = new ArrayList<>(trees);
            return out;
        }
    }

    // Called after writing a file so the next lookup sees it.
    static void invalidate() {
        synchronized (TrackLocator.class) {
            index = null;
        }
    }

    private void walk(Uri tree, String docId, List<Entry> out, int depth) {
        if (depth > 12 || out.size() >= MAX_INDEX) return;
        List<String[]> kids = children(tree, docId);
        if (kids == null) return;
        for (String[] k : kids) {
            if (k[1] == null || k[1].startsWith(".")) continue;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(k[2])) {
                walk(tree, k[0], out, depth + 1);
            } else {
                String ext = Lyrics.extension(k[1]);
                if (Lyrics.AUDIO_EXT.contains(ext) || "lrc".equals(ext)) out.add(new Entry(tree, k[0], docId, k[1]));
            }
        }
    }

    // {docId, name, mime} for each child, or null if the folder can't be read.
    private List<String[]> children(Uri tree, String docId) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = cr.query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return null;
            List<String[]> out = new ArrayList<>();
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private long durationOf(Uri u) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, u);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d.trim());
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
    }

    static String key(String artist, String title, long durationMs) {
        return (artist + "\u0001" + title + "\u0001" + (durationMs / 1000)).toLowerCase(Locale.ROOT);
    }
}
