package io.github.martinwoodward.iphotoframesaver;

final class FrameException extends Exception {
    static final String NETWORK = "Cannot reach the album. Saved photos are still available; retrying shortly.";
    static final String ALBUM = "The album is unavailable or its format changed. Check that public sharing is enabled.";
    static final String STORAGE = "Cannot save photos. Check free space and reselect attached storage if needed.";
    static final String DECODE = "This photo could not be displayed. Trying another saved photo.";
    static final String DOWNLOAD = "Photo download did not finish. Check the connection and available device storage. Saved photos are kept.";

    FrameException(String safeMessage) { super(safeMessage); }
}
