package vn.edu.p2p.core;

import java.util.UUID;
import java.util.function.Consumer;

final class ProgressReporter {
    private final UUID id;
    private final long total;
    private final TransferProgress.Direction direction;
    private final Consumer<TransferProgress> callback;
    private long lastNotification;

    ProgressReporter(UUID id, long total, TransferProgress.Direction direction, Consumer<TransferProgress> callback) {
        this.id = id;
        this.total = total;
        this.direction = direction;
        this.callback = callback;
    }

    void update(TransferProgress.State state, long bytes) {
        long now = System.nanoTime();
        if (state == TransferProgress.State.TRANSFERRING && bytes != 0 && bytes != total
                && now - lastNotification < 100_000_000L) {
            return;
        }
        lastNotification = now;
        if (callback != null) {
            try {
                callback.accept(new TransferProgress(id, direction, state, bytes, total));
            } catch (RuntimeException ignored) {
                // An observer failure must not turn verified on-disk completion into a false transfer failure.
            }
        }
    }
}
