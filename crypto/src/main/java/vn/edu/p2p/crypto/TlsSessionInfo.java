package vn.edu.p2p.crypto;

import java.util.Optional;
import java.util.UUID;

/** Public metadata only. Never contains SSLSession, socket, certificate keys or passwords. */
public record TlsSessionInfo(
        UUID sessionId, State state, String localFingerprint,
        Optional<String> remoteFingerprint, Optional<String> protocol, Optional<String> cipherSuite) {
    public enum State { PENDING_VERIFICATION, HANDSHAKING, VERIFIED, CLOSED }
}
