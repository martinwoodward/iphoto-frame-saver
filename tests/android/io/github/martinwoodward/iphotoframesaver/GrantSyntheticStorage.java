package io.github.martinwoodward.iphotoframesaver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Grants only the test provider's synthetic tree, not any real device storage. */
public final class GrantSyntheticStorage extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        grantUriPermission("io.github.martinwoodward.iphotoframesaver",
                DocumentsContract.buildTreeDocumentUri(
                        "io.github.martinwoodward.iphotoframesaver.tests.documents", "root"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        finish();
    }
}
