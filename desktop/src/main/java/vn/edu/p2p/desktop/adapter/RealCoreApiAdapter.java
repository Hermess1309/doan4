package vn.edu.p2p.desktop.adapter;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import vn.edu.p2p.core.FileOffer;
import vn.edu.p2p.core.PeerListener;
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.core.SessionInfo;
import vn.edu.p2p.core.TransferProgress;
import vn.edu.p2p.core.TransferProgress.State;
import vn.edu.p2p.core.TransferRejectedException;
import vn.edu.p2p.core.TransferResult;
import vn.edu.p2p.core.TransferSession;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferDirection;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferState;
import vn.edu.p2p.desktop.util.Formatters;
import vn.edu.p2p.desktop.util.ValidationUtils;

/**
 * Triển khai Adapter thực tế kết nối trực tiếp giao diện Java Swing với Core M3 backend.
 * - Quản lý PeerNode và vòng đời TransferSession.
 * - Lắng nghe kết nối LAN trên worker thread chạy ngầm (Virtual Threads).
 * - Thực hiện I/O mạng, TLS 1.3 pinning handshake và băm SHA-256 hoàn toàn ngoài Swing EDT.
 * - Marshal toàn bộ callback trạng thái và tiến trình an toàn về EDT qua SwingUtilities.invokeLater.
 */
public class RealCoreApiAdapter implements CoreApiAdapter {

    private static final int DEFAULT_LAN_PORT = 52001;
    private static final int ACCEPT_TIMEOUT_MS = 3000;
    private static final int IO_TIMEOUT_MS = 60000;
    private static final int CONNECT_TIMEOUT_MS = 10000;

    private final PeerNode peerNode;
    private final PeerListener serverListener;
    private final ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final CopyOnWriteArrayList<Consumer<SessionViewModel>> sessionListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<TransferViewModel>> transferListeners = new CopyOnWriteArrayList<>();

    private volatile TransferSession activeSession;
    private volatile SessionViewModel currentSessionVm = SessionViewModel.createDisconnected();
    private volatile TransferViewModel currentTransferVm = TransferViewModel.createIdle();
    private volatile FileOffer pendingIncomingOffer;
    private volatile boolean isServerRole = false;

    private volatile BiConsumer<FileOffer, Consumer<File>> incomingOfferHandler;

    public RealCoreApiAdapter(PeerNode peerNode, int preferredPort) throws IOException {
        this.peerNode = Objects.requireNonNull(peerNode, "peerNode không được null");
        this.workerPool = Executors.newVirtualThreadPerTaskExecutor();

        PeerListener listener = null;
        if (preferredPort >= 0) {
            try {
                listener = peerNode.listen(new InetSocketAddress(preferredPort), ACCEPT_TIMEOUT_MS, IO_TIMEOUT_MS);
            } catch (IOException e) {
                // Nếu cổng ưu tiên bị chiếm (ví dụ chạy 2 instance trên localhost), mở cổng ngẫu nhiên khả dụng
                listener = peerNode.listen(new InetSocketAddress(0), ACCEPT_TIMEOUT_MS, IO_TIMEOUT_MS);
            }
        }
        this.serverListener = listener;

        if (this.serverListener != null) {
            startListenerLoop();
        }
    }

    public static RealCoreApiAdapter createDefault() throws Exception {
        PeerNode node = LocalIdentityHelper.getOrCreateDefaultIdentity();
        int preferredPort = Integer.getInteger("p2p.listen.port", DEFAULT_LAN_PORT);
        return new RealCoreApiAdapter(node, preferredPort);
    }

    public static RealCoreApiAdapter create(PeerNode node, int port) throws IOException {
        return new RealCoreApiAdapter(node, port);
    }

    public void setIncomingOfferHandler(BiConsumer<FileOffer, Consumer<File>> handler) {
        this.incomingOfferHandler = handler;
    }

    @Override
    public String getLocalFingerprint() {
        return peerNode.localFingerprint();
    }

    @Override
    public int getListenPort() {
        return (serverListener != null) ? serverListener.port() : 0;
    }

    private void startListenerLoop() {
        workerPool.submit(() -> {
            while (running.get() && serverListener != null) {
                try {
                    TransferSession session = serverListener.accept();
                    handleIncomingSession(session);
                } catch (SocketTimeoutException timeout) {
                    // Timeout định kỳ để kiểm tra cờ running, tiếp tục vòng lặp
                } catch (IOException e) {
                    if (!running.get()) {
                        break;
                    }
                }
            }
        });
    }

    private synchronized void handleIncomingSession(TransferSession acceptedSession) {
        if (activeSession != null) {
            try {
                acceptedSession.close();
            } catch (IOException ignored) {}
            return;
        }

        this.activeSession = acceptedSession;
        this.isServerRole = true;
        SessionInfo info = acceptedSession.info();

        SessionViewModel vm = new SessionViewModel(
                info.sessionId().toString(),
                "Peer từ mạng LAN",
                peerNode.localFingerprint(),
                "",
                false,
                SessionState.WAITING_FOR_FINGERPRINT,
                ""
        );
        dispatchSessionUpdate(vm);
    }

    @Override
    public CompletableFuture<SessionViewModel> connectDirect(String host, int port) {
        if (!ValidationUtils.isValidHostOrIp(host)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Địa chỉ IP hoặc Hostname không hợp lệ: " + host));
        }
        if (!ValidationUtils.isValidPort(port)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Cổng không hợp lệ: " + port));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                closeActiveSessionInternal();

                TransferSession session = peerNode.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS, IO_TIMEOUT_MS);
                this.activeSession = session;
                this.isServerRole = false;
                SessionInfo info = session.info();

                SessionViewModel vm = new SessionViewModel(
                        info.sessionId().toString(),
                        host + ":" + port,
                        peerNode.localFingerprint(),
                        "",
                        false,
                        SessionState.WAITING_FOR_FINGERPRINT,
                        ""
                );
                dispatchSessionUpdate(vm);
                return vm;
            } catch (IOException e) {
                SessionViewModel errVm = new SessionViewModel(
                        "",
                        host + ":" + port,
                        peerNode.localFingerprint(),
                        "",
                        false,
                        SessionState.ERROR,
                        "Kết nối TCP thất bại: " + e.getMessage()
                );
                dispatchSessionUpdate(errVm);
                throw new CompletionException(e);
            }
        }, workerPool);
    }

    @Override
    public CompletableFuture<SessionViewModel> connectRelay(String pairingCode) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Tính năng kết nối qua Relay Server sẽ được hỗ trợ tại Milestone M6 theo đặc tả dự án."));
    }

    @Override
    public CompletableFuture<SessionViewModel> startTlsHandshake(String sessionId, String expectedRemoteFingerprint) {
        if (!ValidationUtils.isValidFingerprint(expectedRemoteFingerprint)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Mã vân tay đối tác không hợp lệ (phải đủ 32 byte hex SHA-256)."));
        }

        final String canonicalPin = ValidationUtils.canonicalizeFingerprint(expectedRemoteFingerprint);

        return CompletableFuture.supplyAsync(() -> {
            TransferSession session = activeSession;
            if (session == null || !session.info().sessionId().toString().equals(sessionId)) {
                throw new IllegalStateException("Không tìm thấy phiên kết nối tương ứng ID: " + sessionId);
            }

            // Chuyển sang trạng thái đang bắt tay TLS
            SessionViewModel handshakingVm = new SessionViewModel(
                    sessionId,
                    currentSessionVm.remoteAddress(),
                    peerNode.localFingerprint(),
                    canonicalPin,
                    false,
                    SessionState.TLS_HANDSHAKING,
                    ""
            );
            dispatchSessionUpdate(handshakingVm);

            try {
                SessionInfo verifiedInfo = session.verify(UUID.fromString(sessionId), canonicalPin);
                if (verifiedInfo.state() != SessionInfo.State.VERIFIED) {
                    throw new IOException("Bắt tay TLS không đạt trạng thái VERIFIED.");
                }

                SessionViewModel verifiedVm = new SessionViewModel(
                        verifiedInfo.sessionId().toString(),
                        currentSessionVm.remoteAddress(),
                        peerNode.localFingerprint(),
                        canonicalPin,
                        false,
                        SessionState.SECURE_READY,
                        ""
                );
                dispatchSessionUpdate(verifiedVm);

                // Nếu là phía nhận (Server/Receiver role), bắt đầu luồng lắng nghe đề nghị tệp gửi đến
                if (isServerRole) {
                    startWaitingForOfferLoop(session);
                }

                return verifiedVm;
            } catch (Exception e) {
                closeActiveSessionInternal();
                SessionViewModel errVm = new SessionViewModel(
                        sessionId,
                        currentSessionVm.remoteAddress(),
                        peerNode.localFingerprint(),
                        canonicalPin,
                        false,
                        SessionState.ERROR,
                        "Bắt tay TLS thất bại (Sai vân tay hoặc lỗi mạng): " + e.getMessage()
                );
                dispatchSessionUpdate(errVm);
                throw new CompletionException(e);
            }
        }, workerPool);
    }

    private void startWaitingForOfferLoop(TransferSession session) {
        workerPool.submit(() -> {
            try {
                FileOffer offer = session.receiveOffer();
                this.pendingIncomingOffer = offer;

                TransferViewModel offerVm = new TransferViewModel(
                        offer.transferId().toString(),
                        offer.fileName(),
                        offer.size(),
                        0L,
                        0.0,
                        TransferDirection.DOWNLOAD,
                        TransferState.OFFERED,
                        ""
                );
                dispatchTransferUpdate(offerVm);

                // Marshal thông báo về Swing EDT để hiển thị hộp thoại xác nhận cho người nhận
                SwingUtilities.invokeLater(() -> {
                    if (incomingOfferHandler != null) {
                        incomingOfferHandler.accept(offer, chosenDestinationFile -> {
                            if (chosenDestinationFile != null) {
                                acceptTransfer(offer.transferId().toString(), chosenDestinationFile);
                            } else {
                                rejectTransfer(offer.transferId().toString(), "Người dùng từ chối nhận tệp.");
                            }
                        });
                    }
                });
            } catch (IOException e) {
                if (running.get() && activeSession == session) {
                    closeActiveSessionInternal();
                    dispatchSessionUpdate(SessionViewModel.createDisconnected());
                }
            }
        });
    }

    @Override
    public CompletableFuture<String> offerFile(String sessionId, File file) {
        if (file == null || !ValidationUtils.isValidReadableFile(file)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tệp tin nguồn không hợp lệ hoặc không có quyền đọc."));
        }

        return CompletableFuture.supplyAsync(() -> {
            TransferSession session = activeSession;
            if (session == null || !session.info().sessionId().toString().equals(sessionId)
                    || session.info().state() != SessionInfo.State.VERIFIED) {
                throw new IllegalStateException("Phiên chưa đạt trạng thái SECURE_READY, không được phép truyền dữ liệu.");
            }

            Path sourcePath = file.toPath();
            long totalBytes = file.length();
            String fileName = file.getName();
            AtomicLong lastBytes = new AtomicLong(0);
            AtomicLong lastTimestamp = new AtomicLong(System.currentTimeMillis());
            UUID[] capturedId = new UUID[1];

            try {
                TransferResult result = session.send(sourcePath, progress -> {
                    capturedId[0] = progress.transferId();
                    long now = System.currentTimeMillis();
                    long dt = now - lastTimestamp.get();
                    double speed = (dt > 80) ? (progress.bytesTransferred() - lastBytes.get()) * 1000.0 / dt : 0.0;
                    if (dt > 300) {
                        lastBytes.set(progress.bytesTransferred());
                        lastTimestamp.set(now);
                    }

                    TransferViewModel vm = new TransferViewModel(
                            progress.transferId().toString(),
                            fileName,
                            totalBytes,
                            progress.bytesTransferred(),
                            speed,
                            TransferDirection.UPLOAD,
                            mapState(progress.state()),
                            ""
                    );
                    dispatchTransferUpdate(vm);
                });

                TransferViewModel completedVm = new TransferViewModel(
                        result.transferId().toString(),
                        fileName,
                        result.bytes(),
                        result.bytes(),
                        0.0,
                        TransferDirection.UPLOAD,
                        TransferState.COMPLETED,
                        ""
                );
                dispatchTransferUpdate(completedVm);
                return result.transferId().toString();
            } catch (TransferRejectedException rejected) {
                TransferViewModel rejectedVm = new TransferViewModel(
                        (capturedId[0] != null) ? capturedId[0].toString() : "",
                        fileName,
                        totalBytes,
                        0L,
                        0.0,
                        TransferDirection.UPLOAD,
                        TransferState.REJECTED,
                        "Đối tác đã từ chối nhận tệp tin."
                );
                dispatchTransferUpdate(rejectedVm);
                throw new CompletionException(rejected);
            } catch (IOException e) {
                TransferViewModel failedVm = new TransferViewModel(
                        (capturedId[0] != null) ? capturedId[0].toString() : "",
                        fileName,
                        totalBytes,
                        0L,
                        0.0,
                        TransferDirection.UPLOAD,
                        TransferState.FAILED,
                        "Lỗi truyền tải: " + e.getMessage()
                );
                dispatchTransferUpdate(failedVm);
                throw new CompletionException(e);
            } finally {
                closeActiveSessionInternal();
            }
        }, workerPool);
    }

    @Override
    public CompletableFuture<Void> acceptTransfer(String transferId, File destinationPathOrDirectory) {
        if (destinationPathOrDirectory == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Đường dẫn lưu tệp không được null."));
        }

        return CompletableFuture.runAsync(() -> {
            TransferSession session = activeSession;
            FileOffer offer = pendingIncomingOffer;

            if (session == null || offer == null || !offer.transferId().toString().equals(transferId)) {
                throw new IllegalStateException("Không có yêu cầu truyền tệp phù hợp với ID: " + transferId);
            }

            Path targetPath;
            if (destinationPathOrDirectory.isDirectory()) {
                targetPath = destinationPathOrDirectory.toPath().resolve(offer.fileName());
            } else {
                targetPath = destinationPathOrDirectory.toPath();
            }

            if (Files.exists(targetPath)) {
                throw new IllegalArgumentException("Tệp đích đã tồn tại trên đĩa. Không được phép ghi đè!");
            }

            long totalBytes = offer.size();
            String fileName = offer.fileName();
            AtomicLong lastBytes = new AtomicLong(0);
            AtomicLong lastTimestamp = new AtomicLong(System.currentTimeMillis());

            try {
                TransferResult result = session.accept(offer.transferId(), targetPath, progress -> {
                    long now = System.currentTimeMillis();
                    long dt = now - lastTimestamp.get();
                    double speed = (dt > 80) ? (progress.bytesTransferred() - lastBytes.get()) * 1000.0 / dt : 0.0;
                    if (dt > 300) {
                        lastBytes.set(progress.bytesTransferred());
                        lastTimestamp.set(now);
                    }

                    TransferViewModel vm = new TransferViewModel(
                            progress.transferId().toString(),
                            fileName,
                            totalBytes,
                            progress.bytesTransferred(),
                            speed,
                            TransferDirection.DOWNLOAD,
                            mapState(progress.state()),
                            ""
                    );
                    dispatchTransferUpdate(vm);
                });

                TransferViewModel completedVm = new TransferViewModel(
                        result.transferId().toString(),
                        fileName,
                        result.bytes(),
                        result.bytes(),
                        0.0,
                        TransferDirection.DOWNLOAD,
                        TransferState.COMPLETED,
                        ""
                );
                dispatchTransferUpdate(completedVm);
            } catch (IOException e) {
                TransferViewModel failedVm = new TransferViewModel(
                        offer.transferId().toString(),
                        fileName,
                        totalBytes,
                        0L,
                        0.0,
                        TransferDirection.DOWNLOAD,
                        TransferState.FAILED,
                        "Lỗi nhận tệp: " + e.getMessage()
                );
                dispatchTransferUpdate(failedVm);
                throw new CompletionException(e);
            } finally {
                closeActiveSessionInternal();
            }
        }, workerPool);
    }

    @Override
    public CompletableFuture<Void> rejectTransfer(String transferId, String reason) {
        return CompletableFuture.runAsync(() -> {
            TransferSession session = activeSession;
            FileOffer offer = pendingIncomingOffer;

            try {
                if (session != null && offer != null && offer.transferId().toString().equals(transferId)) {
                    session.reject(offer.transferId());
                }
            } catch (IOException ignored) {
            } finally {
                closeActiveSessionInternal();
                TransferViewModel rejectedVm = new TransferViewModel(
                        transferId,
                        (offer != null) ? offer.fileName() : "",
                        (offer != null) ? offer.size() : 0L,
                        0L,
                        0.0,
                        TransferDirection.DOWNLOAD,
                        TransferState.REJECTED,
                        reason
                );
                dispatchTransferUpdate(rejectedVm);
            }
        }, workerPool);
    }

    @Override
    public CompletableFuture<Void> cancelTransfer(String transferId) {
        return CompletableFuture.runAsync(() -> {
            TransferViewModel previous = currentTransferVm;
            closeActiveSessionInternal();
            TransferViewModel cancelledVm = new TransferViewModel(
                    transferId,
                    (previous != null) ? previous.fileName() : "",
                    (previous != null) ? previous.totalBytes() : 0L,
                    (previous != null) ? previous.bytesTransferred() : 0L,
                    0.0,
                    (previous != null) ? previous.direction() : TransferDirection.DOWNLOAD,
                    TransferState.CANCELLED,
                    "Tác vụ truyền tải đã bị hủy bởi người dùng."
            );
            dispatchTransferUpdate(cancelledVm);
        }, workerPool);
    }

    @Override
    public CompletableFuture<Void> resumeTransfer(String transferId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Tính năng Resume chưa được hỗ trợ tại phiên bản Core hiện tại (theo docs/CORE_API.md)."));
    }

    @Override
    public CompletableFuture<Void> disconnect(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            closeActiveSessionInternal();
            SessionViewModel disconnected = SessionViewModel.createDisconnected();
            dispatchSessionUpdate(disconnected);
        }, workerPool);
    }

    @Override
    public void registerListeners(Consumer<SessionViewModel> onSessionUpdate, Consumer<TransferViewModel> onTransferUpdate) {
        if (onSessionUpdate != null) {
            this.sessionListeners.add(onSessionUpdate);
            onSessionUpdate.accept(currentSessionVm);
        }
        if (onTransferUpdate != null) {
            this.transferListeners.add(onTransferUpdate);
            onTransferUpdate.accept(currentTransferVm);
        }
    }

    @Override
    public void shutdown() {
        running.set(false);
        closeActiveSessionInternal();
        if (serverListener != null) {
            try {
                serverListener.close();
            } catch (IOException ignored) {}
        }
        workerPool.shutdownNow();
        try {
            workerPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isShutdown() {
        return !running.get() && workerPool.isShutdown();
    }

    private synchronized void closeActiveSessionInternal() {
        if (activeSession != null) {
            try {
                activeSession.close();
            } catch (IOException ignored) {}
            activeSession = null;
        }
        pendingIncomingOffer = null;
    }

    private void dispatchSessionUpdate(SessionViewModel session) {
        this.currentSessionVm = session;
        SwingUtilities.invokeLater(() -> {
            for (Consumer<SessionViewModel> listener : sessionListeners) {
                try {
                    listener.accept(session);
                } catch (Exception e) {
                    System.err.println("Session listener callback error: " + e.getMessage());
                }
            }
        });
    }

    private void dispatchTransferUpdate(TransferViewModel transfer) {
        this.currentTransferVm = transfer;
        SwingUtilities.invokeLater(() -> {
            for (Consumer<TransferViewModel> listener : transferListeners) {
                try {
                    listener.accept(transfer);
                } catch (Exception e) {
                    System.err.println("Transfer listener callback error: " + e.getMessage());
                }
            }
        });
    }

    private TransferState mapState(State coreState) {
        return switch (coreState) {
            case HASHING -> TransferState.HASHING;
            case OFFERED -> TransferState.OFFERED;
            case TRANSFERRING -> TransferState.TRANSFERRING;
            case VERIFYING -> TransferState.VERIFYING_FILE;
            case COMPLETED -> TransferState.COMPLETED;
            case REJECTED -> TransferState.REJECTED;
            case FAILED -> TransferState.FAILED;
        };
    }
}
