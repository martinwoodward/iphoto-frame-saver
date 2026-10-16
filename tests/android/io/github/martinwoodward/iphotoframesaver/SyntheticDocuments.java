package io.github.martinwoodward.iphotoframesaver;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** An isolated fake removable drive, installed only in the optional test APK. */
public final class SyntheticDocuments extends DocumentsProvider {
    private File root;
    @Override public boolean onCreate() {
        root = new File(getContext().getFilesDir(), "synthetic-drive");
        return root.isDirectory() || root.mkdirs();
    }
    private File file(String id) throws FileNotFoundException {
        try {
            File file = "root".equals(id) ? root : new File(root, id);
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator) && !file.equals(root)) {
                throw new FileNotFoundException("Synthetic path outside root");
            }
            return file;
        } catch (IOException e) { throw new FileNotFoundException("Synthetic drive unavailable"); }
    }
    private String id(File file) { return file.equals(root) ? "root" : file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1); }
    private String[] columns(String[] projection) {
        return projection == null ? new String[] {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE} : projection;
    }
    private void row(MatrixCursor cursor, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            if (Document.COLUMN_DOCUMENT_ID.equals(column)) row.add(id(file));
            else if (Document.COLUMN_DISPLAY_NAME.equals(column)) row.add(file.getName());
            else if (Document.COLUMN_MIME_TYPE.equals(column)) row.add(file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
            else if (Document.COLUMN_FLAGS.equals(column)) row.add(Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE
                    | (file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : 0));
            else if (Document.COLUMN_SIZE.equals(column)) row.add(file.length());
            else row.add(null);
        }
    }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[] {"root_id"}); }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(columns(projection));
        row(cursor, file(documentId));
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(columns(projection));
        File[] files = file(parent).listFiles();
        if (files != null) for (File child : files) row(cursor, child);
        return cursor;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (name.contains("/") || name.contains("..")) throw new FileNotFoundException("Invalid synthetic name");
        File target = new File(file(parent), name);
        try {
            boolean created = Document.MIME_TYPE_DIR.equals(mime) ? target.mkdir() : target.createNewFile();
            if (!created && !target.exists()) throw new FileNotFoundException("Synthetic create failed");
            return id(target);
        } catch (IOException e) { throw new FileNotFoundException("Synthetic create failed"); }
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        File target = file(documentId);
        if (target.equals(root) || !target.delete()) throw new FileNotFoundException("Synthetic delete failed");
    }
    @Override public String renameDocument(String documentId, String name) throws FileNotFoundException {
        if (name.contains("/") || name.contains("..")) throw new FileNotFoundException("Invalid synthetic name");
        File source = file(documentId);
        File target = new File(source.getParentFile(), name);
        if (!source.renameTo(target)) throw new FileNotFoundException("Synthetic rename failed");
        return id(target);
    }
    @Override public boolean isChildDocument(String parentId, String documentId) {
        try {
            return file(documentId).getAbsolutePath().startsWith(file(parentId).getAbsolutePath() + File.separator);
        } catch (FileNotFoundException e) { return false; }
    }
}
