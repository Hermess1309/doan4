package vn.edu.p2p.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import vn.edu.p2p.crypto.LocalIdentity;
import vn.edu.p2p.crypto.PeerTlsSession;

/** Local identity and direct-connection factory. All I/O methods are blocking. */
public final class PeerNode {
    private final LocalIdentity identity;

    private PeerNode(LocalIdentity identity) {
        this.identity = identity;
    }

    public static PeerNode loadIdentity(Path store, char[] password, String alias)
            throws IOException, GeneralSecurityException {
        return new PeerNode(LocalIdentity.load(store, password, alias));
    }

    public String localFingerprint() {
        return identity.fingerprint();
    }

    public TransferSession connect(InetSocketAddress address, int connectTimeoutMillis, int ioTimeoutMillis)
            throws IOException {
        if (connectTimeoutMillis <= 0 || ioTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        Socket socket = new Socket();
        try {
            socket.connect(address, connectTimeoutMillis);
            return prepare(socket, PeerTlsSession.Role.CLIENT, ioTimeoutMillis);
        } catch (IOException | RuntimeException failed) {
            try {
                socket.close();
            } catch (IOException closing) {
                failed.addSuppressed(closing);
            }
            throw failed;
        }
    }

    public PeerListener listen(InetSocketAddress address, int acceptTimeoutMillis, int ioTimeoutMillis)
            throws IOException {
        return new PeerListener(this, address, acceptTimeoutMillis, ioTimeoutMillis);
    }

    /** TLS role is independent of which side established the outbound TCP connection. */
    public enum TlsRole { CLIENT, SERVER }

    /**
     * Takes exclusive ownership of an already connected transport, including on failure.
     * The result is TCP-only and pending verification; call verify with an independent pin.
     * Never retain/read/write the socket after handoff. All operations are blocking.
     */
    public TransferSession attach(Socket socket, TlsRole role, int ioTimeoutMillis) throws IOException {
        java.util.Objects.requireNonNull(socket);
        try {
            java.util.Objects.requireNonNull(role);
            return prepare(socket, role == TlsRole.CLIENT
                    ? PeerTlsSession.Role.CLIENT : PeerTlsSession.Role.SERVER, ioTimeoutMillis);
        } catch (IOException | RuntimeException failure) {
            try {
                socket.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    TransferSession prepare(Socket socket, PeerTlsSession.Role role, int timeout) throws IOException {
        return new TransferSession(PeerTlsSession.prepare(socket, identity, role, timeout));
    }
}
