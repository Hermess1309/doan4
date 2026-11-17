package vn.edu.p2p.core;

import java.util.UUID;

/** Validated remote display metadata, never a destination path. */
public record FileOffer(UUID transferId, String fileName, long size, String sha256) {}
