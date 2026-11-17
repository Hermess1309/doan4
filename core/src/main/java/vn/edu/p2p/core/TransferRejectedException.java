package vn.edu.p2p.core;

import java.io.IOException;

public final class TransferRejectedException extends IOException {
    private static final long serialVersionUID = 1L;

    public TransferRejectedException() {
        super("Peer rejected file offer");
    }
}
