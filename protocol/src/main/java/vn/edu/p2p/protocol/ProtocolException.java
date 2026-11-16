package vn.edu.p2p.protocol;

import java.io.IOException;

/** Invalid framing; the caller must discard the connection instead of resynchronizing. */
public final class ProtocolException extends IOException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
