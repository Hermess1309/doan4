package vn.edu.p2p.desktop.adapter;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import vn.edu.p2p.desktop.model.RelayTicketInfo;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel;

/**
 * Adapter tĩnh/giữ chỗ an toàn cho các bài kiểm thử giao diện độc lập (Smoke Tests / Mock tests).
 * Không thực hiện kết nối mạng hay thao tác I/O.
 */
public class DummyCoreApiAdapter implements CoreApiAdapter {

    private Consumer<SessionViewModel> sessionListener;
    private Consumer<TransferViewModel> transferListener;

    @Override
    public CompletableFuture<SessionViewModel> connectDirect(String host, int port) {
        return CompletableFuture.completedFuture(SessionViewModel.createDisconnected());
    }

    @Override
    public CompletableFuture<RelayTicketInfo> createRelaySession(String relayHost, int relayPort) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("DummyCoreApiAdapter không hỗ trợ kết nối Relay thật."));
    }

    @Override
    public CompletableFuture<SessionViewModel> joinRelaySession(
            String relayHost, int relayPort, String pairingCode) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("DummyCoreApiAdapter không hỗ trợ kết nối Relay thật."));
    }

    @Override
    public CompletableFuture<SessionViewModel> connectRelay(String pairingCode) {
        return CompletableFuture.completedFuture(SessionViewModel.createDisconnected());
    }

    @Override
    public String getLocalFingerprint() {
        return "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF";
    }

    @Override
    public int getListenPort() {
        return 52001;
    }

    @Override
    public CompletableFuture<SessionViewModel> startTlsHandshake(String sessionId, String expectedRemoteFingerprint) {
        return CompletableFuture.completedFuture(SessionViewModel.createDisconnected());
    }

    @Override
    public CompletableFuture<Void> disconnect(String sessionId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<String> offerFile(String sessionId, File file) {
        return CompletableFuture.completedFuture("");
    }

    @Override
    public CompletableFuture<Void> acceptTransfer(String transferId, File destinationDirectory) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> rejectTransfer(String transferId, String reason) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> cancelTransfer(String transferId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> resumeTransfer(String transferId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void registerListeners(Consumer<SessionViewModel> onSessionUpdate, Consumer<TransferViewModel> onTransferUpdate) {
        this.sessionListener = onSessionUpdate;
        this.transferListener = onTransferUpdate;
        if (onSessionUpdate != null) {
            onSessionUpdate.accept(SessionViewModel.createDisconnected());
        }
        if (onTransferUpdate != null) {
            onTransferUpdate.accept(TransferViewModel.createIdle());
        }
    }

    @Override
    public void shutdown() {
        // No-op
    }
}
