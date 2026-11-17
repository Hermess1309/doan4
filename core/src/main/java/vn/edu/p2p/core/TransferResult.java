package vn.edu.p2p.core;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Returned only after verified completion. Sender has no local destination path. */
public record TransferResult(UUID transferId, long bytes, String sha256, Optional<Path> savedTo) {}
