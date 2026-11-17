package vn.edu.p2p.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * Owns a connected TCP socket, including on failure. No TLS/app bytes before explicit verification.
 * Call verify on a worker; close may concurrently cancel it. One reader and one writer per session.
 */
public final class PeerTlsSession implements AutoCloseable {
    public enum Role { CLIENT, SERVER }

    private final UUID id = UUID.randomUUID();
    private final Socket transport;
    private final LocalIdentity identity;
    private final Role role;
    private final int timeoutMillis;
    private TlsSessionInfo.State state = TlsSessionInfo.State.PENDING_VERIFICATION;
    private SSLSocket tls;
    private SSLSession negotiated;
    private String remoteFingerprint;
    private String protocol;
    private String cipher;

    private PeerTlsSession(Socket transport, LocalIdentity identity, Role role, int timeoutMillis) {
        this.transport = transport;
        this.identity = identity;
        this.role = role;
        this.timeoutMillis = timeoutMillis;
    }

    /** Socket ownership transfers on entry; pending means TCP only, not an authenticated TLS channel. */
    public static PeerTlsSession prepare(Socket transport, LocalIdentity identity, Role role, int timeoutMillis)
            throws IOException {
        Objects.requireNonNull(transport, "transport");
        try {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(role, "role");
            if (!transport.isConnected() || transport.isClosed() || timeoutMillis <= 0) {
                throw new IllegalArgumentException("Expected a connected socket and positive timeout");
            }
            transport.setSoTimeout(timeoutMillis);
            return new PeerTlsSession(transport, identity, role, timeoutMillis);
        } catch (IOException | RuntimeException failed) {
            try {
                transport.close();
            } catch (IOException closing) {
                failed.addSuppressed(closing);
            }
            throw failed;
        }
    }

    public synchronized TlsSessionInfo info() {
        return new TlsSessionInfo(id, state, identity.fingerprint(),
                Optional.ofNullable(remoteFingerprint), Optional.ofNullable(protocol), Optional.ofNullable(cipher));
    }

    /**
     * One-shot confirmation for this exact pending session. Both peers supply pins from an independent
     * trusted channel, then call concurrently. Never call with a pin obtained solely from relay/network.
     */
    public TlsSessionInfo verify(UUID sessionId, String trustedFingerprint) throws IOException {
        try {
            String pin = Fingerprint.canonical(trustedFingerprint);
            SSLSocket active;
            synchronized (this) {
                if (!id.equals(sessionId) || state != TlsSessionInfo.State.PENDING_VERIFICATION) {
                    throw new SSLPeerUnverifiedException("Verification does not match pending session");
                }
                state = TlsSessionInfo.State.HANDSHAKING;
                // A fresh context per connection prevents an older resumed session bypassing new pin checks.
                SSLContext context = SSLContext.getInstance("TLSv1.3");
                context.init(identity.keyManagers(), new TrustManager[] {new PinnedTrustManager(pin)}, null);
                tls = (SSLSocket) context.getSocketFactory().createSocket(
                        transport, transport.getInetAddress().getHostAddress(), transport.getPort(), true);
                tls.setUseClientMode(role == Role.CLIENT);
                tls.setEnabledProtocols(new String[] {"TLSv1.3"});
                if (role == Role.SERVER) {
                    tls.setNeedClientAuth(true);
                }
                tls.setSoTimeout(timeoutMillis);
                active = tls;
            }
            active.startHandshake();
            SSLSession result = active.getSession();
            var certificate = (X509Certificate) result.getPeerCertificates()[0];
            CertificatePolicy.validate(certificate);
            String actual = Fingerprint.sha256(certificate);
            if (!"TLSv1.3".equals(result.getProtocol()) || !Fingerprint.matches(pin, actual)
                    || !Fingerprint.matches(identity.fingerprint(),
                            Fingerprint.sha256((X509Certificate) result.getLocalCertificates()[0]))) {
                result.invalidate();
                throw new SSLPeerUnverifiedException("TLS session identity mismatch");
            }
            synchronized (this) {
                if (state == TlsSessionInfo.State.CLOSED) {
                    result.invalidate();
                    throw new IOException("Session closed during handshake");
                }
                negotiated = result;
                remoteFingerprint = actual;
                protocol = result.getProtocol();
                cipher = result.getCipherSuite();
                state = TlsSessionInfo.State.VERIFIED;
                return info();
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failed) {
            try {
                close();
            } catch (IOException closing) {
                failed.addSuppressed(closing);
            }
            if (failed instanceof IOException io) {
                throw io;
            }
            throw new IOException("TLS peer verification failed", failed);
        }
    }

    private synchronized SSLSocket verifiedSocket() throws SSLPeerUnverifiedException {
        if (state != TlsSessionInfo.State.VERIFIED) {
            throw new SSLPeerUnverifiedException("Application data requires a verified TLS session");
        }
        return tls;
    }

    public InputStream input() throws IOException {
        verifiedSocket();
        return new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    return verifiedSocket().getInputStream().read();
                } catch (IOException failed) {
                    throw terminate(failed);
                }
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    return verifiedSocket().getInputStream().read(bytes, offset, length);
                } catch (IOException failed) {
                    throw terminate(failed);
                }
            }

            @Override
            public void close() throws IOException {
                PeerTlsSession.this.close();
            }
        };
    }

    public OutputStream output() throws IOException {
        verifiedSocket();
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                try {
                    verifiedSocket().getOutputStream().write(value);
                } catch (IOException failed) {
                    throw terminate(failed);
                }
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                try {
                    verifiedSocket().getOutputStream().write(bytes, offset, length);
                } catch (IOException failed) {
                    throw terminate(failed);
                }
            }

            @Override
            public void flush() throws IOException {
                try {
                    verifiedSocket().getOutputStream().flush();
                } catch (IOException failed) {
                    throw terminate(failed);
                }
            }

            @Override
            public void close() throws IOException {
                PeerTlsSession.this.close();
            }
        };
    }

    private IOException terminate(IOException failure) {
        try {
            close();
        } catch (IOException closing) {
            failure.addSuppressed(closing);
        }
        return failure;
    }

    /** Also serves as explicit user rejection. Invalidates any completed TLS session. */
    @Override
    public synchronized void close() throws IOException {
        state = TlsSessionInfo.State.CLOSED;
        if (negotiated != null) {
            negotiated.invalidate();
        }
        try {
            if (tls != null) {
                tls.close();
            }
        } finally {
            transport.close();
        }
    }
}
