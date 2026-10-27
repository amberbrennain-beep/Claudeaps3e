package com.usbapp.lyrics;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Walks every subfolder of a picked folder, however deep, without recursion
// (deep trees can't overflow the stack) and without visiting a folder twice.
// Skips only system/trash folders and macOS "._" resource-fork files, which
// look like audio files on USB drives that have been on a Mac.
final class FolderWalker {

    interface Visitor {
        // kids: {docId, name, mime}. Return false to stop the walk.
        boolean folder(String docId, List<String[]> kids);
    }

    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            "lost.dir", "system volume information", "$recycle.bin", "recycler", ".trashes", ".trash",
            ".spotlight-v100", ".fseventsd", ".temporaryitems", ".thumbnails", ".android_secure"));

    // Far deeper than any real music library; only guards against provider bugs.
    private static final int MAX_DEPTH = 64;

    private FolderWalker() {
    }

    // Returns the number of folders visited.
    static int walk(ContentResolver cr, Uri tree, String rootId, Visitor v) {
        Deque<Object[]> stack = new ArrayDeque<>();   // {docId, depth}
        Set<String> seen = new HashSet<>();
        stack.push(new Object[]{rootId, 0});
        int folders = 0;
        while (!stack.isEmpty()) {
            Object[] top = stack.pop();
            String id = (String) top[0];
            int depth = (Integer) top[1];
            if (!seen.add(id)) continue;
            List<String[]> kids = children(cr, tree, id);
            if (kids == null) continue;
            folders++;
            List<String[]> visible = new ArrayList<>(kids.size());
            for (String[] k : kids) {
                String name = k[1];
                if (name == null || name.startsWith("._")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(k[2])) {
                    if (depth < MAX_DEPTH && !skipDir(name, depth, id)) stack.push(new Object[]{k[0], depth + 1});
                } else {
                    visible.add(k);
                }
            }
            if (!v.folder(id, visible)) break;
        }
        return folders;
    }

    private static boolean skipDir(String name, int depth, String parentId) {
        String n = name.toLowerCase(Locale.ROOT);
        if (SKIP_DIRS.contains(n)) return true;
        // Android/data and Android/obb hold app files, never music, and are slow to list.
        return n.equals("android") && depth == 0 && parentId.endsWith(":");
    }

    // {docId, name, mime} for each child, or null if the folder can't be read.
    static List<String[]> children(ContentResolver cr, Uri tree, String docId) {
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

    // Picked folders, minus any that sit inside another picked folder, so
    // nothing is scanned twice.
    static List<Uri> outermost(List<Uri> trees) {
        List<Uri> out = new ArrayList<>();
        for (Uri t : trees) {
            String id = DocumentsContract.getTreeDocumentId(t);
            boolean nested = false;
            for (Uri o : trees) {
                if (o.equals(t)) continue;
                String oid = DocumentsContract.getTreeDocumentId(o);
                if (contains(oid, id) && !(contains(id, oid) && trees.indexOf(o) > trees.indexOf(t))) {
                    nested = true;
                    break;
                }
            }
            if (!nested) out.add(t);
        }
        return out;
    }

    // "VOL:Music" contains "VOL:Music/Album"; "VOL:" contains everything on VOL.
    private static boolean contains(String outer, String inner) {
        if (outer.equalsIgnoreCase(inner)) return true;
        String o = outer.endsWith(":") || outer.endsWith("/") ? outer : outer + "/";
        return inner.toLowerCase(Locale.ROOT).startsWith(o.toLowerCase(Locale.ROOT));
    }
}
