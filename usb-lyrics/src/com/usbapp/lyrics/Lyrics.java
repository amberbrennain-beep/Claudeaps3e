package com.usbapp.lyrics;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

// Helpers shared by the batch screen and the USB Audio Player PRO companion.
final class Lyrics {

    static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "aiff", "aif", "ape", "wv",
            "dsf", "dff"));

    private static final Pattern BAD_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private Lyrics() {
    }

    static String pick(LrclibClient.Result r, boolean allowPlain) {
        if (r == null) return null;
        if (r.synced != null) return r.synced;
        return allowPlain ? r.plain : null;
    }

    // Standard LRC ID tags so players show the right song info.
    static String withHeader(String body, String artist, String title, String album) {
        StringBuilder sb = new StringBuilder();
        if (artist != null && !artist.isEmpty()) sb.append("[ar:").append(artist).append("]\n");
        if (title != null && !title.isEmpty()) sb.append("[ti:").append(title).append("]\n");
        if (album != null && !album.isEmpty()) sb.append("[al:").append(album).append("]\n");
        sb.append("[re:USB Lyrics / lrclib.net]\n");
        sb.append(body.replace("\r\n", "\n").trim()).append('\n');
        return sb.toString();
    }

    // Writes (or replaces) lrcName inside the folder parentDoc. existingLrc may
    // be null, in which case the file is created.
    static Uri write(ContentResolver cr, Uri parentDoc, String lrcName, Uri existingLrc, String lrc) throws Exception {
        Uri target = existingLrc;
        if (target == null) {
            // application/octet-stream keeps the name exactly as given; text/plain
            // would make some providers save "Song.lrc.txt".
            target = DocumentsContract.createDocument(cr, parentDoc, "application/octet-stream", lrcName);
            if (target == null) throw new Exception("could not create " + lrcName);
        }
        try (OutputStream os = cr.openOutputStream(target, "wt")) {
            if (os == null) throw new Exception("could not open " + lrcName);
            os.write(lrc.getBytes(StandardCharsets.UTF_8));
        }
        return target;
    }

    // "primary:Music" -> "Internal storage / Music", "1234-ABCD:Songs" -> "USB / SD (1234-ABCD) / Songs"
    static String describeTree(Uri u) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(u);
        } catch (Exception e) {
            return u.toString();
        }
        return describeDocId(id);
    }

    static String describeDocId(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        String vol = id.substring(0, colon);
        String path = id.substring(colon + 1);
        String volName = "primary".equals(vol) ? "Internal storage" : "USB / SD (" + vol + ")";
        return path.isEmpty() ? volName : volName + " / " + path.replace("/", " / ");
    }

    static String label(String artist, String title) {
        return artist == null || artist.isEmpty() ? title : artist + " - " + title;
    }

    static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    // FAT32/exFAT (what most USB sticks use) reject these characters.
    static String sanitize(String name) {
        String s = BAD_CHARS.matcher(name).replaceAll("_").trim();
        return s.isEmpty() ? "lyrics" : s;
    }

    // Lower-case letters and digits only, for loose title/file-name matching.
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    static String mmss(int sec) {
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }
}
