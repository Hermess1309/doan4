package vn.edu.p2p.core;

import java.util.Optional;
import java.util.UUID;

/** Core-owned metadata; no dependency on crypto or UI types in the public API. */
public record SessionInfo(UUID sessionId, State state, String localFingerprint,
        Optional<String> remoteFingerprint, Optional<String> protocol, Optional<String> cipherSuite) {
    public enum State { PENDING_VERIFICATION, HANDSHAKING, VERIFIED, CLOSED }
}
