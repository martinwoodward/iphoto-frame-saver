package io.github.martinwoodward.iphotoframesaver;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/** Uses a dedicated directory inside the user-granted SAF tree, never raw storage paths. */
final class AttachedStorage {
    private final ContentResolver resolver;
    private final Uri tree;
    private final String albumKey;
    private Uri directory;
    private final Map<String, Uri> files = new HashMap<>();
    private boolean indexed;

    AttachedStorage(ContentResolver resolver, String tree, String albumKey) {
        this.resolver = resolver;
        this.tree = Uri.parse(tree);
        this.albumKey = albumKey;
    }

    private Uri child(Uri parent, String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = resolver.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) throw new IOException("Storage unavailable");
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree,
                        cursor.getString(0));
            }
        }
        return null;
    }

    private void index(boolean create) throws IOException {
        if (indexed) return;
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri app = child(root, "Photo Frame Saver");
        if (app == null && create) app = DocumentsContract.createDocument(resolver, root,
                DocumentsContract.Document.MIME_TYPE_DIR, "Photo Frame Saver");
        if (app == null) return;
        directory = child(app, albumKey);
        if (directory == null && create) directory = DocumentsContract.createDocument(resolver, app,
                DocumentsContract.Document.MIME_TYPE_DIR, albumKey);
        if (directory == null) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getDocumentId(directory));
        try (Cursor c = resolver.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) throw new IOException("Storage unavailable");
            while (c.moveToNext()) files.put(c.getString(1),
                    DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
        }
        indexed = true;
    }

    synchronized boolean contains(String name) throws IOException {
        index(false);
        return files.containsKey(name);
    }

    synchronized boolean copyTo(String name, File target) throws IOException {
        index(false);
        Uri uri = files.get(name);
        if (uri == null) return false;
        try (InputStream in = resolver.openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Storage unavailable");
            Net.transfer(in, out, 128L * 1024 * 1024);
            out.getFD().sync();
        }
        return true;
    }

    synchronized void save(String name, File source) throws IOException {
        index(true);
        if (directory == null) throw new IOException("Storage unavailable");
        if (files.containsKey(name)) return;
        Uri stale = files.remove(name + ".part");
        if (stale != null && !DocumentsContract.deleteDocument(resolver, stale)) {
            throw new IOException("Incomplete file could not be replaced");
        }
        Uri uri = DocumentsContract.createDocument(resolver, directory, "application/octet-stream", name + ".part");
        if (uri == null) throw new IOException("Storage unavailable");
        boolean committed = false;
        try {
            try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Storage unavailable");
                Net.transfer(in, out, 128L * 1024 * 1024);
            }
            Uri finalUri;
            try { finalUri = DocumentsContract.renameDocument(resolver, uri, name); }
            catch (UnsupportedOperationException e) { throw new IOException("Storage cannot commit photo"); }
            if (finalUri == null) throw new IOException("Storage cannot commit photo");
            files.put(name, finalUri);
            committed = true;
        } finally {
            if (!committed) DocumentsContract.deleteDocument(resolver, uri);
        }
    }

    synchronized void invalidate(String name) throws IOException {
        index(false);
        Uri uri = files.get(name);
        if (uri != null && !DocumentsContract.deleteDocument(resolver, uri)) {
            throw new IOException("Damaged photo could not be replaced");
        }
        files.remove(name);
    }
}
