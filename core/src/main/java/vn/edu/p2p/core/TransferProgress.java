package vn.edu.p2p.core;

import java.util.UUID;

/** Callback runs on the calling worker; desktop must marshal to EDT. */
public record TransferProgress(UUID transferId, Direction direction, State state,
        long bytesTransferred, long totalBytes) {
    public enum Direction { SEND, RECEIVE }
    public enum State { HASHING, OFFERED, TRANSFERRING, VERIFYING, COMPLETED, REJECTED, FAILED }
}
