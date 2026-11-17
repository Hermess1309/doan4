package vn.edu.p2p.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import vn.edu.p2p.crypto.PeerTlsSession;

/** Owns only the listening socket; accepted sessions are independently caller-owned. */
public final class PeerListener implements AutoCloseable {
    private final PeerNode node;
    private final ServerSocket listener = new ServerSocket();
    private final int ioTimeout;

    PeerListener(PeerNode node, InetSocketAddress address, int acceptTimeout, int ioTimeout) throws IOException {
        this.node = node;
        if (acceptTimeout <= 0 || ioTimeout <= 0) {
            listener.close();
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        this.ioTimeout = ioTimeout;
        try {
            listener.bind(address, 8);
            listener.setSoTimeout(acceptTimeout);
        } catch (IOException | RuntimeException failed) {
            listener.close();
            throw failed;
        }
    }

    public int port() {
        return listener.getLocalPort();
    }

    public TransferSession accept() throws IOException {
        return node.prepare(listener.accept(), PeerTlsSession.Role.SERVER, ioTimeout);
    }

    @Override
    public void close() throws IOException {
        listener.close();
    }
}
