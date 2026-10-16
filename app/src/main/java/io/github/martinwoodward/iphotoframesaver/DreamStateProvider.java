package io.github.martinwoodward.iphotoframesaver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public, read-only integration point: one live boolean, never album or settings data. */
public final class DreamStateProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.martinwoodward.iphotoframesaver.playback/dream");
    private static final AtomicBoolean active = new AtomicBoolean();

    static void setDreaming(Context context, boolean dreaming) {
        if (active.getAndSet(dreaming) != dreaming) {
            context.getContentResolver().notifyChange(URI, null);
        }
    }

    @Override public boolean onCreate() { return true; }

    private void requireDreamUri(Uri uri) {
        if (!URI.equals(uri)) throw new IllegalArgumentException("Unknown playback state resource");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        requireDreamUri(uri);
        if (selection != null || (selectionArgs != null && selectionArgs.length != 0) || sortOrder != null
                || (projection != null && (projection.length != 1 || !"active".equals(projection[0])))) {
            throw new IllegalArgumentException("Only the active playback state can be read");
        }
        MatrixCursor cursor = new MatrixCursor(new String[] {"active"}, 1);
        cursor.addRow(new Object[] {active.get() ? 1 : 0});
        cursor.setNotificationUri(getContext().getContentResolver(), URI);
        return cursor;
    }

    @Override public String getType(Uri uri) {
        requireDreamUri(uri);
        return "vnd.android.cursor.item/vnd.io.github.martinwoodward.iphotoframesaver.dream";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw readOnly(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw readOnly(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw readOnly(); }
    private UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("Playback state is read-only");
    }
}
