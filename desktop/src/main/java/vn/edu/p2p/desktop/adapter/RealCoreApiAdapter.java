package vn.edu.p2p.desktop.adapter;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import vn.edu.p2p.core.TransferProgress.State;
import vn.edu.p2p.core.TransferRejectedException;
import vn.edu.p2p.core.TransferResult;
import vn.edu.p2p.core.TransferSession;
import vn.edu.p2p.desktop.model.RelayTicketInfo;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferDirection;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferState;
import vn.edu.p2p.desktop.util.ValidationUtils;
import vn.edu.p2p.relay.RelayClient;

/**
 * Triển khai Adapter thực tế kết nối trực tiếp giao diện Java Swing với Core M3 và Relay M6.
 * - Hỗ trợ cả LAN trực tiếp (PeerNode.connect / PeerListener.accept) lẫn Relay (RelayClient CREATE/JOIN + PeerNode.attach).
 * - Quản lý vòng đời phiên và vé chờ Relay theo sessionGeneration bất biến; mọi callback đều kiểm tra
 *   generation/sessionId cả trước khi xếp hàng và ngay tại lúc thực thi trên Swing EDT.
 * - Không bao giờ ghi mã pairing ra log; không bao giờ bật SECURE_READY trước khi mutual TLS 1.3 thành công.
 */
public class RealCoreApiAdapter implements CoreApiAdapter {

    private static final int DEFAULT_LAN_PORT = 52001;
    private static final int ACCEPT_TIMEOUT_MS = 3000;
    private static final int IO_TIMEOUT_MS = 60000;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int RELAY_CONTROL_TIMEOUT_MS = 15000;

    private record ActiveSessionContext(
            long generation,
            String sessionId,
            String remoteAddress,
            TransferSession session,
            boolean serverRole,
            boolean relay) {}

    private record PendingRelayContext(
            long generation, String relayAddress, RelayClient.Pending ticket) {}

    private record PendingOfferContext(long generation, String sessionId, FileOffer offer) {}

    private final PeerNode peerNode;
    private final PeerListener serverListener;
    private final ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final Object sessionLock = new Object();
    private final AtomicLong generationSequence = new AtomicLong(0L);
    private final AtomicLong activeGeneration = new AtomicLong(0L);

    private final CopyOnWriteArrayList<Consumer<SessionViewModel>> sessionListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<TransferViewModel>> transferListeners =
            new CopyOnWriteArrayList<>();

    private volatile ActiveSessionContext activeContext;
    private volatile PendingRelayContext activeRelayTicket;
    private volatile PendingOfferContext pendingIncomingOffer;
    private volatile SessionViewModel currentSessionVm = SessionViewModel.createDisconnected();
    private volatile TransferViewModel currentTransferVm = TransferViewModel.createIdle();

    private volatile BiConsumer<FileOffer, Consumer<File>> incomingOfferHandler;

    public RealCoreApiAdapter(PeerNode peerNode, int preferredPort) throws IOException {
        this.peerNode = Objects.requireNonNull(peerNode, "peerNode không được null");
        this.workerPool = Executors.newVirtualThreadPerTaskExecutor();

        PeerListener listener = null;
        if (preferredPort >= 0) {
            try {
                listener =
                        peerNode.listen(new InetSocketAddress(preferredPort), ACCEPT_TIMEOUT_MS, IO_TIMEOUT_MS);
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

    long getActiveGeneration() {
        return activeGeneration.get();
    }

    private void startListenerLoop() {
        workerPool.submit(
                () -> {
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

    private void handleIncomingSession(TransferSession acceptedSession) {
        ActiveSessionContext newCtx;
        synchronized (sessionLock) {
            if (!running.get() || activeContext != null || activeRelayTicket != null) {
                closeQuietly(acceptedSession);
                return;
            }
            long gen = generationSequence.incrementAndGet();
            SessionInfo info = acceptedSession.info();
            newCtx =
                    new ActiveSessionContext(
                            gen, info.sessionId().toString(), "Peer từ mạng LAN", acceptedSession, true, false);
            this.activeContext = newCtx;
            this.pendingIncomingOffer = null;
            this.activeGeneration.set(gen);
        }

        SessionViewModel vm =
                new SessionViewModel(
                        newCtx.sessionId(),
                        newCtx.remoteAddress(),
                        peerNode.localFingerprint(),
                        "",
                        false,
                        SessionState.WAITING_FOR_FINGERPRINT,
                        "",
                        newCtx.generation());
        dispatchSessionUpdate(newCtx.generation(), vm);
    }

    @Override
    public CompletableFuture<SessionViewModel> connectDirect(String host, int port) {
        if (!ValidationUtils.isValidHostOrIp(host)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Địa chỉ IP hoặc Hostname không hợp lệ: " + host));
        }
        if (!ValidationUtils.isValidPort(port)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Cổng không hợp lệ: " + port));
        }
        if (!running.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Adapter đã đóng, không thể tạo kết nối mới."));
        }

        final long attemptGeneration = invalidateAndReserveGeneration();

        return CompletableFuture.supplyAsync(
                () -> {
                    if (!running.get() || activeGeneration.get() != attemptGeneration) {
                        throw new CompletionException(
                                new IllegalStateException("Yêu cầu kết nối đã bị thay thế bởi phiên mới hơn."));
                    }
                    try {
                        TransferSession session =
                                peerNode.connect(
                                        new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS, IO_TIMEOUT_MS);
                        ActiveSessionContext installedCtx;
                        synchronized (sessionLock) {
                            if (!running.get() || activeGeneration.get() != attemptGeneration) {
                                closeQuietly(session);
                                throw new IllegalStateException(
                                        "Kết nối vừa hoàn tất nhưng đã bị thay thế bởi phiên mới hơn.");
                            }
                            SessionInfo info = session.info();
                            installedCtx =
                                    new ActiveSessionContext(
                                            attemptGeneration,
                                            info.sessionId().toString(),
                                            host + ":" + port,
                                            session,
                                            false,
                                            false);
                            this.activeContext = installedCtx;
                            this.pendingIncomingOffer = null;
                        }

                        SessionViewModel vm =
                                new SessionViewModel(
                                        installedCtx.sessionId(),
                                        installedCtx.remoteAddress(),
                                        peerNode.localFingerprint(),
                                        "",
                                        false,
                                        SessionState.WAITING_FOR_FINGERPRINT,
                                        "",
                                        installedCtx.generation());
                        dispatchSessionUpdate(installedCtx.generation(), vm);
                        return vm;
                    } catch (IOException e) {
                        SessionViewModel errVm =
                                new SessionViewModel(
                                        "",
                                        host + ":" + port,
                                        peerNode.localFingerprint(),
                                        "",
                                        false,
                                        SessionState.ERROR,
                                        "Kết nối TCP thất bại: " + e.getMessage(),
                                        attemptGeneration);
                        dispatchSessionUpdate(attemptGeneration, errVm);
                        throw new CompletionException(e);
                    }
                },
                workerPool);
    }

    @Override
    public CompletableFuture<RelayTicketInfo> createRelaySession(String relayHost, int relayPort) {
        if (!ValidationUtils.isValidHostOrIp(relayHost)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Địa chỉ Relay Server không hợp lệ: " + relayHost));
        }
        if (!ValidationUtils.isValidPort(relayPort)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Cổng Relay Server không hợp lệ: " + relayPort));
        }
        if (!running.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Adapter đã đóng, không thể tạo mã ghép nối Relay."));
        }

        final String trimmedHost = relayHost.trim();
        final String relayAddress = trimmedHost + ":" + relayPort;
        final long attemptGeneration = invalidateAndReserveGeneration();

        dispatchSessionUpdate(
                attemptGeneration,
                new SessionViewModel(
                        "",
                        "Relay (" + relayAddress + ")",
                        peerNode.localFingerprint(),
                        "",
                        true,
                        SessionState.RELAY_CONNECTING,
                        "",
                        attemptGeneration));

        return CompletableFuture.supplyAsync(
                () -> {
                    if (!running.get() || activeGeneration.get() != attemptGeneration) {
                        throw new CompletionException(
                                new IllegalStateException("Yêu cầu tạo mã Relay đã bị hủy hoặc thay thế."));
                    }
                    try {
                        RelayClient.Pending ticket =
                                RelayClient.create(
                                        new InetSocketAddress(trimmedHost, relayPort), RELAY_CONTROL_TIMEOUT_MS);
                        PendingRelayContext relayCtx;
                        synchronized (sessionLock) {
                            if (!running.get() || activeGeneration.get() != attemptGeneration) {
                                closeQuietly(ticket);
                                throw new IllegalStateException("Vé Relay vừa tạo nhưng phiên đã bị hủy/thay thế.");
                            }
                            relayCtx = new PendingRelayContext(attemptGeneration, relayAddress, ticket);
                            this.activeRelayTicket = relayCtx;
                        }

                        dispatchSessionUpdate(
                                attemptGeneration,
                                new SessionViewModel(
                                        "",
                                        "Relay (" + relayAddress + ")",
                                        peerNode.localFingerprint(),
                                        "",
                                        true,
                                        SessionState.WAITING_FOR_PEER,
                                        "",
                                        attemptGeneration));

                        // Bắt đầu chờ peer JOIN trên background worker (không chặn EDT)
                        workerPool.submit(() -> awaitRelayPair(relayCtx));

                        return new RelayTicketInfo(
                                attemptGeneration, ticket.code(), ticket.ttlMillis(), relayAddress);
                    } catch (IOException e) {
                        if (running.get() && activeGeneration.get() == attemptGeneration) {
                            dispatchSessionUpdate(
                                    attemptGeneration,
                                    new SessionViewModel(
                                            "",
                                            "Relay (" + relayAddress + ")",
                                            peerNode.localFingerprint(),
                                            "",
                                            true,
                                            SessionState.ERROR,
                                            "Không thể tạo mã ghép nối trên Relay: " + e.getMessage(),
                                            attemptGeneration));
                        }
                        throw new CompletionException(e);
                    }
                },
                workerPool);
    }

    private void awaitRelayPair(PendingRelayContext relayCtx) {
        try {
            Socket pairedSocket = relayCtx.ticket().awaitPair();
            if (!isCurrentRelayContext(relayCtx)) {
                closeQuietly(pairedSocket);
                return;
            }

            dispatchSessionUpdate(
                    relayCtx.generation(),
                    new SessionViewModel(
                            "",
                            "Relay (" + relayCtx.relayAddress() + ")",
                            peerNode.localFingerprint(),
                            "",
                            true,
                            SessionState.TCP_CONNECTED,
                            "",
                            relayCtx.generation()));

            // Chuyển hoàn toàn quyền sở hữu socket đã PAIRED cho PeerNode.attach với vai trò TLS SERVER (Receiver)
            TransferSession session =
                    peerNode.attach(pairedSocket, PeerNode.TlsRole.SERVER, IO_TIMEOUT_MS);

            ActiveSessionContext installedCtx;
            synchronized (sessionLock) {
                if (!running.get() || activeGeneration.get() != relayCtx.generation()) {
                    closeQuietly(session);
                    return;
                }
                if (this.activeRelayTicket == relayCtx) {
                    this.activeRelayTicket = null;
                }
                SessionInfo info = session.info();
                installedCtx =
                        new ActiveSessionContext(
                                relayCtx.generation(),
                                info.sessionId().toString(),
                                "Relay (" + relayCtx.relayAddress() + ")",
                                session,
                                true,
                                true);
                this.activeContext = installedCtx;
                this.pendingIncomingOffer = null;
            }

            dispatchSessionUpdate(
                    installedCtx.generation(),
                    new SessionViewModel(
                            installedCtx.sessionId(),
                            installedCtx.remoteAddress(),
                            peerNode.localFingerprint(),
                            "",
                            true,
                            SessionState.WAITING_FOR_FINGERPRINT,
                            "",
                            installedCtx.generation()));
        } catch (IOException e) {
            boolean stillCurrent = isCurrentRelayContext(relayCtx);
            closeRelayTicketIfMatches(relayCtx);
            if (stillCurrent) {
                dispatchSessionUpdate(
                        relayCtx.generation(),
                        new SessionViewModel(
                                "",
                                "Relay (" + relayCtx.relayAddress() + ")",
                                peerNode.localFingerprint(),
                                "",
                                true,
                                SessionState.ERROR,
                                "Ghép nối Relay không thành công hoặc đã hết hạn chờ: " + e.getMessage(),
                                relayCtx.generation()));
            }
        }
    }

    @Override
    public CompletableFuture<SessionViewModel> joinRelaySession(
            String relayHost, int relayPort, String pairingCode) {
        if (!ValidationUtils.isValidHostOrIp(relayHost)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Địa chỉ Relay Server không hợp lệ: " + relayHost));
        }
        if (!ValidationUtils.isValidPort(relayPort)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Cổng Relay Server không hợp lệ: " + relayPort));
        }
        final String canonicalCode = ValidationUtils.canonicalizeRelayPairingCode(pairingCode);
        if (canonicalCode.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Mã ghép nối Relay không hợp lệ (phải đủ 12 ký tự chuẩn, không gồm 0/1/I/O)."));
        }
        if (!running.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Adapter đã đóng, không thể kết nối Relay."));
        }

        final String trimmedHost = relayHost.trim();
        final String relayAddress = trimmedHost + ":" + relayPort;
        final long attemptGeneration = invalidateAndReserveGeneration();

        dispatchSessionUpdate(
                attemptGeneration,
                new SessionViewModel(
                        "",
                        "Relay (" + relayAddress + ")",
                        peerNode.localFingerprint(),
                        "",
                        true,
                        SessionState.RELAY_CONNECTING,
                        "",
                        attemptGeneration));

        return CompletableFuture.supplyAsync(
                () -> {
                    if (!running.get() || activeGeneration.get() != attemptGeneration) {
                        throw new CompletionException(
                                new IllegalStateException("Yêu cầu ghép nối Relay đã bị hủy hoặc thay thế."));
                    }
                    try {
                        Socket pairedSocket =
                                RelayClient.join(
                                        new InetSocketAddress(trimmedHost, relayPort),
                                        canonicalCode,
                                        RELAY_CONTROL_TIMEOUT_MS);
                        if (!running.get() || activeGeneration.get() != attemptGeneration) {
                            closeQuietly(pairedSocket);
                            throw new IllegalStateException(
                                    "Kết nối Relay vừa ghép xong nhưng đã bị thay thế bởi phiên mới.");
                        }

                        dispatchSessionUpdate(
                                attemptGeneration,
                                new SessionViewModel(
                                        "",
                                        "Relay (" + relayAddress + ")",
                                        peerNode.localFingerprint(),
                                        "",
                                        true,
                                        SessionState.TCP_CONNECTED,
                                        "",
                                        attemptGeneration));

                        // Chuyển hoàn toàn quyền sở hữu socket đã PAIRED cho PeerNode.attach với vai trò TLS CLIENT (Sender)
                        TransferSession session =
                                peerNode.attach(pairedSocket, PeerNode.TlsRole.CLIENT, IO_TIMEOUT_MS);

                        ActiveSessionContext installedCtx;
                        synchronized (sessionLock) {
                            if (!running.get() || activeGeneration.get() != attemptGeneration) {
                                closeQuietly(session);
                                throw new IllegalStateException(
                                        "Phiên Relay vừa tạo nhưng đã bị thay thế bởi phiên mới.");
                            }
                            SessionInfo info = session.info();
                            installedCtx =
                                    new ActiveSessionContext(
                                            attemptGeneration,
                                            info.sessionId().toString(),
                                            "Relay (" + relayAddress + ")",
                                            session,
                                            false,
                                            true);
                            this.activeContext = installedCtx;
                            this.pendingIncomingOffer = null;
                        }

                        SessionViewModel vm =
                                new SessionViewModel(
                                        installedCtx.sessionId(),
                                        installedCtx.remoteAddress(),
                                        peerNode.localFingerprint(),
                                        "",
                                        true,
                                        SessionState.WAITING_FOR_FINGERPRINT,
                                        "",
                                        installedCtx.generation());
                        dispatchSessionUpdate(installedCtx.generation(), vm);
                        return vm;
                    } catch (IOException e) {
                        if (running.get() && activeGeneration.get() == attemptGeneration) {
                            dispatchSessionUpdate(
                                    attemptGeneration,
                                    new SessionViewModel(
                                            "",
                                            "Relay (" + relayAddress + ")",
                                            peerNode.localFingerprint(),
                                            "",
                                            true,
                                            SessionState.ERROR,
                                            "Kết nối Relay thất bại (mã không hợp lệ/hết hạn hoặc máy chủ không sẵn sàng): "
                                                    + e.getMessage(),
                                            attemptGeneration));
                        }
                        throw new CompletionException(e);
                    }
                },
                workerPool);
    }

    @Override
    public CompletableFuture<SessionViewModel> connectRelay(String pairingCode) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Vui lòng dùng joinRelaySession(relayHost, relayPort, pairingCode) để kết nối Relay thật."));
    }

    @Override
    public CompletableFuture<SessionViewModel> startTlsHandshake(
            String sessionId, String expectedRemoteFingerprint) {
        if (!ValidationUtils.isValidFingerprint(expectedRemoteFingerprint)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Mã vân tay đối tác không hợp lệ (phải đủ 32 byte hex SHA-256)."));
        }

        final String canonicalPin = ValidationUtils.canonicalizeFingerprint(expectedRemoteFingerprint);
        final ActiveSessionContext ctx = getCurrentContextIfMatches(sessionId);
        if (ctx == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Không tìm thấy phiên kết nối tương ứng ID: " + sessionId));
        }

        return CompletableFuture.supplyAsync(
                () -> {
                    if (!isCurrentContext(ctx)) {
                        throw new CompletionException(
                                new IllegalStateException("Phiên kết nối đã bị thay thế trước khi bắt tay TLS."));
                    }

                    SessionViewModel handshakingVm =
                            new SessionViewModel(
                                    ctx.sessionId(),
                                    ctx.remoteAddress(),
                                    peerNode.localFingerprint(),
                                    canonicalPin,
                                    ctx.relay(),
                                    SessionState.TLS_HANDSHAKING,
                                    "",
                                    ctx.generation());
                    dispatchSessionUpdate(ctx.generation(), handshakingVm);

                    try {
                        SessionInfo verifiedInfo =
                                ctx.session().verify(UUID.fromString(ctx.sessionId()), canonicalPin);
                        if (verifiedInfo.state() != SessionInfo.State.VERIFIED) {
                            throw new IOException("Bắt tay TLS không đạt trạng thái VERIFIED.");
                        }

                        if (!isCurrentContext(ctx)) {
                            closeQuietly(ctx.session());
                            throw new IllegalStateException(
                                    "Bỏ qua kết quả bắt tay TLS của phiên cũ đã bị thay thế.");
                        }

                        SessionViewModel verifiedVm =
                                new SessionViewModel(
                                        verifiedInfo.sessionId().toString(),
                                        ctx.remoteAddress(),
                                        peerNode.localFingerprint(),
                                        canonicalPin,
                                        ctx.relay(),
                                        SessionState.SECURE_READY,
                                        "",
                                        ctx.generation());
                        dispatchSessionUpdate(ctx.generation(), verifiedVm);

                        if (ctx.serverRole() && isCurrentContext(ctx)) {
                            startWaitingForOfferLoop(ctx);
                        }

                        return verifiedVm;
                    } catch (Exception e) {
                        boolean stillCurrent = isCurrentContext(ctx);
                        closeSessionIfMatches(ctx);
                        if (stillCurrent) {
                            SessionViewModel errVm =
                                    new SessionViewModel(
                                            ctx.sessionId(),
                                            ctx.remoteAddress(),
                                            peerNode.localFingerprint(),
                                            canonicalPin,
                                            ctx.relay(),
                                            SessionState.ERROR,
                                            "Bắt tay TLS thất bại (Sai vân tay hoặc lỗi mạng): " + e.getMessage(),
                                            ctx.generation());
                            dispatchSessionUpdate(ctx.generation(), errVm);
                        }
                        throw new CompletionException(e);
                    }
                },
                workerPool);
    }

    private void startWaitingForOfferLoop(ActiveSessionContext ctx) {
        workerPool.submit(
                () -> {
                    try {
                        FileOffer offer = ctx.session().receiveOffer();
                        synchronized (sessionLock) {
                            if (!isCurrentContext(ctx)) {
                                closeQuietly(ctx.session());
                                return;
                            }
                            this.pendingIncomingOffer =
                                    new PendingOfferContext(ctx.generation(), ctx.sessionId(), offer);
                        }

                        TransferViewModel offerVm =
                                new TransferViewModel(
                                        offer.transferId().toString(),
                                        offer.fileName(),
                                        offer.size(),
                                        0L,
                                        0.0,
                                        TransferDirection.DOWNLOAD,
                                        TransferState.OFFERED,
                                        "",
                                        ctx.sessionId(),
                                        ctx.generation());
                        dispatchTransferUpdate(ctx.generation(), offerVm);

                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!isCurrentContext(ctx)) {
                                        return;
                                    }
                                    BiConsumer<FileOffer, Consumer<File>> handler = incomingOfferHandler;
                                    if (handler != null) {
                                        handler.accept(
                                                offer,
                                                chosenDestinationFile -> {
                                                    if (!isCurrentContext(ctx)) {
                                                        return;
                                                    }
                                                    if (chosenDestinationFile != null) {
                                                        acceptTransfer(
                                                                offer.transferId().toString(), chosenDestinationFile);
                                                    } else {
                                                        rejectTransfer(
                                                                offer.transferId().toString(),
                                                                "Người dùng từ chối nhận tệp.");
                                                    }
                                                });
                                    }
                                });
                    } catch (IOException e) {
                        if (isCurrentContext(ctx)) {
                            long nextGen = closeAndAdvanceGenerationIfMatches(ctx);
                            if (nextGen > 0) {
                                dispatchSessionUpdate(nextGen, SessionViewModel.createDisconnected(nextGen));
                            }
                        }
                    }
                });
    }

    @Override
    public CompletableFuture<String> offerFile(String sessionId, File file) {
        if (file == null || !ValidationUtils.isValidReadableFile(file)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Tệp tin nguồn không hợp lệ hoặc không có quyền đọc."));
        }

        final ActiveSessionContext ctx = getCurrentContextIfMatches(sessionId);
        if (ctx == null || ctx.session().info().state() != SessionInfo.State.VERIFIED) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Phiên chưa đạt trạng thái SECURE_READY, không được phép truyền dữ liệu."));
        }

        return CompletableFuture.supplyAsync(
                () -> {
                    if (!isCurrentContext(ctx)
                            || ctx.session().info().state() != SessionInfo.State.VERIFIED) {
                        throw new IllegalStateException(
                                "Phiên không còn hợp lệ hoặc chưa đạt trạng thái SECURE_READY.");
                    }

                    Path sourcePath = file.toPath();
                    long totalBytes = file.length();
                    String fileName = file.getName();
                    AtomicLong lastBytes = new AtomicLong(0);
                    AtomicLong lastTimestamp = new AtomicLong(System.currentTimeMillis());
                    UUID[] capturedId = new UUID[1];

                    try {
                        TransferResult result =
                                ctx.session()
                                        .send(
                                                sourcePath,
                                                progress -> {
                                                    capturedId[0] = progress.transferId();
                                                    if (!isCurrentContext(ctx)) {
                                                        return;
                                                    }
                                                    long now = System.currentTimeMillis();
                                                    long dt = now - lastTimestamp.get();
                                                    double speed =
                                                            (dt > 80)
                                                                    ? (progress.bytesTransferred() - lastBytes.get())
                                                                            * 1000.0
                                                                            / dt
                                                                    : 0.0;
                                                    if (dt > 300) {
                                                        lastBytes.set(progress.bytesTransferred());
                                                        lastTimestamp.set(now);
                                                    }

                                                    TransferViewModel vm =
                                                            new TransferViewModel(
                                                                    progress.transferId().toString(),
                                                                    fileName,
                                                                    totalBytes,
                                                                    progress.bytesTransferred(),
                                                                    speed,
                                                                    TransferDirection.UPLOAD,
                                                                    mapState(progress.state()),
                                                                    "",
                                                                    ctx.sessionId(),
                                                                    ctx.generation());
                                                    dispatchTransferUpdate(ctx.generation(), vm);
                                                });

                        if (isCurrentContext(ctx)) {
                            TransferViewModel completedVm =
                                    new TransferViewModel(
                                            result.transferId().toString(),
                                            fileName,
                                            result.bytes(),
                                            result.bytes(),
                                            0.0,
                                            TransferDirection.UPLOAD,
                                            TransferState.COMPLETED,
                                            "",
                                            ctx.sessionId(),
                                            ctx.generation());
                            dispatchTransferUpdate(ctx.generation(), completedVm);
                        }
                        return result.transferId().toString();
                    } catch (TransferRejectedException rejected) {
                        if (isCurrentContext(ctx)) {
                            TransferViewModel rejectedVm =
                                    new TransferViewModel(
                                            (capturedId[0] != null) ? capturedId[0].toString() : "",
                                            fileName,
                                            totalBytes,
                                            0L,
                                            0.0,
                                            TransferDirection.UPLOAD,
                                            TransferState.REJECTED,
                                            "Đối tác đã từ chối nhận tệp tin.",
                                            ctx.sessionId(),
                                            ctx.generation());
                            dispatchTransferUpdate(ctx.generation(), rejectedVm);
                        }
                        throw new CompletionException(rejected);
                    } catch (IOException e) {
                        if (isCurrentContext(ctx)) {
                            TransferViewModel failedVm =
                                    new TransferViewModel(
                                            (capturedId[0] != null) ? capturedId[0].toString() : "",
                                            fileName,
                                            totalBytes,
                                            0L,
                                            0.0,
                                            TransferDirection.UPLOAD,
                                            TransferState.FAILED,
                                            "Lỗi truyền tải: " + e.getMessage(),
                                            ctx.sessionId(),
                                            ctx.generation());
                            dispatchTransferUpdate(ctx.generation(), failedVm);
                        }
                        throw new CompletionException(e);
                    } finally {
                        closeSessionIfMatches(ctx);
                    }
                },
                workerPool);
    }

    @Override
    public CompletableFuture<Void> acceptTransfer(
            String transferId, File destinationPathOrDirectory) {
        if (destinationPathOrDirectory == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Đường dẫn lưu tệp không được null."));
        }

        final ActiveSessionContext ctx;
        final PendingOfferContext offerCtx;
        synchronized (sessionLock) {
            ctx = this.activeContext;
            offerCtx = this.pendingIncomingOffer;
        }
        if (ctx == null
                || offerCtx == null
                || offerCtx.generation() != ctx.generation()
                || !offerCtx.offer().transferId().toString().equals(transferId)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Không có yêu cầu truyền tệp phù hợp với phiên hiện hành và ID: " + transferId));
        }

        return CompletableFuture.runAsync(
                () -> {
                    if (!isCurrentContext(ctx)) {
                        throw new IllegalStateException("Phiên nhận tệp đã bị thay thế.");
                    }

                    FileOffer offer = offerCtx.offer();
                    Path targetPath =
                            destinationPathOrDirectory.isDirectory()
                                    ? destinationPathOrDirectory.toPath().resolve(offer.fileName())
                                    : destinationPathOrDirectory.toPath();

                    if (Files.exists(targetPath)) {
                        throw new IllegalArgumentException(
                                "Tệp đích đã tồn tại trên đĩa. Không được phép ghi đè!");
                    }

                    long totalBytes = offer.size();
                    String fileName = offer.fileName();
                    AtomicLong lastBytes = new AtomicLong(0);
                    AtomicLong lastTimestamp = new AtomicLong(System.currentTimeMillis());

                    try {
                        TransferResult result =
                                ctx.session()
                                        .accept(
                                                offer.transferId(),
                                                targetPath,
                                                progress -> {
                                                    if (!isCurrentContext(ctx)) {
                                                        return;
                                                    }
                                                    long now = System.currentTimeMillis();
                                                    long dt = now - lastTimestamp.get();
                                                    double speed =
                                                            (dt > 80)
                                                                    ? (progress.bytesTransferred() - lastBytes.get())
                                                                            * 1000.0
                                                                            / dt
                                                                    : 0.0;
                                                    if (dt > 300) {
                                                        lastBytes.set(progress.bytesTransferred());
                                                        lastTimestamp.set(now);
                                                    }

                                                    TransferViewModel vm =
                                                            new TransferViewModel(
                                                                    progress.transferId().toString(),
                                                                    fileName,
                                                                    totalBytes,
                                                                    progress.bytesTransferred(),
                                                                    speed,
                                                                    TransferDirection.DOWNLOAD,
                                                                    mapState(progress.state()),
                                                                    "",
                                                                    ctx.sessionId(),
                                                                    ctx.generation());
                                                    dispatchTransferUpdate(ctx.generation(), vm);
                                                });

                        if (isCurrentContext(ctx)) {
                            TransferViewModel completedVm =
                                    new TransferViewModel(
                                            result.transferId().toString(),
                                            fileName,
                                            result.bytes(),
                                            result.bytes(),
                                            0.0,
                                            TransferDirection.DOWNLOAD,
                                            TransferState.COMPLETED,
                                            "",
                                            ctx.sessionId(),
                                            ctx.generation());
                            dispatchTransferUpdate(ctx.generation(), completedVm);
                        }
                    } catch (IOException e) {
                        if (isCurrentContext(ctx)) {
                            TransferViewModel failedVm =
                                    new TransferViewModel(
                                            offer.transferId().toString(),
                                            fileName,
                                            totalBytes,
                                            0L,
                                            0.0,
                                            TransferDirection.DOWNLOAD,
                                            TransferState.FAILED,
                                            "Lỗi nhận tệp: " + e.getMessage(),
                                            ctx.sessionId(),
                                            ctx.generation());
                            dispatchTransferUpdate(ctx.generation(), failedVm);
                        }
                        throw new CompletionException(e);
                    } finally {
                        closeSessionIfMatches(ctx);
                    }
                },
                workerPool);
    }

    @Override
    public CompletableFuture<Void> rejectTransfer(String transferId, String reason) {
        final ActiveSessionContext ctx;
        final PendingOfferContext offerCtx;
        synchronized (sessionLock) {
            ctx = this.activeContext;
            offerCtx = this.pendingIncomingOffer;
        }
        return CompletableFuture.runAsync(
                () -> {
                    if (ctx == null || offerCtx == null || offerCtx.generation() != ctx.generation()) {
                        return;
                    }
                    FileOffer offer = offerCtx.offer();
                    try {
                        if (offer.transferId().toString().equals(transferId)) {
                            ctx.session().reject(offer.transferId());
                        }
                    } catch (IOException ignored) {
                    } finally {
                        boolean stillCurrent = isCurrentContext(ctx);
                        closeSessionIfMatches(ctx);
                        if (stillCurrent) {
                            TransferViewModel rejectedVm =
                                    new TransferViewModel(
                                            transferId,
                                            offer.fileName(),
                                            offer.size(),
                                            0L,
                                            0.0,
                                            TransferDirection.DOWNLOAD,
                                            TransferState.REJECTED,
                                            reason,
                                            ctx.sessionId(),
                                            ctx.generation());
                            dispatchTransferUpdate(ctx.generation(), rejectedVm);
                        }
                    }
                },
                workerPool);
    }

    @Override
    public CompletableFuture<Void> cancelTransfer(String transferId) {
        final ActiveSessionContext ctx = this.activeContext;
        if (ctx == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                () -> {
                    if (!isCurrentContext(ctx)) {
                        return;
                    }
                    TransferViewModel previous = currentTransferVm;
                    if (previous != null
                            && previous.sessionGeneration() > 0
                            && previous.sessionGeneration() != ctx.generation()) {
                        return;
                    }
                    long gen = ctx.generation();
                    String sId = ctx.sessionId();
                    closeSessionIfMatches(ctx);
                    TransferViewModel cancelledVm =
                            new TransferViewModel(
                                    transferId,
                                    (previous != null) ? previous.fileName() : "",
                                    (previous != null) ? previous.totalBytes() : 0L,
                                    (previous != null) ? previous.bytesTransferred() : 0L,
                                    0.0,
                                    (previous != null) ? previous.direction() : TransferDirection.DOWNLOAD,
                                    TransferState.CANCELLED,
                                    "Tác vụ truyền tải đã bị hủy bởi người dùng.",
                                    sId,
                                    gen);
                    dispatchTransferUpdate(gen, cancelledVm);
                },
                workerPool);
    }

    @Override
    public CompletableFuture<Void> resumeTransfer(String transferId) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Tính năng Resume chưa được hỗ trợ tại phiên bản Core hiện tại (theo docs/CORE_API.md)."));
    }

    @Override
    public CompletableFuture<Void> disconnect(String sessionId) {
        return CompletableFuture.runAsync(
                () -> {
                    long newGen;
                    synchronized (sessionLock) {
                        if (sessionId != null
                                && !sessionId.isBlank()
                                && activeContext != null
                                && !activeContext.sessionId().equals(sessionId)) {
                            return;
                        }
                        newGen = invalidateAndReserveGeneration();
                    }
                    SessionViewModel disconnected = SessionViewModel.createDisconnected(newGen);
                    dispatchSessionUpdate(newGen, disconnected);
                },
                workerPool);
    }

    @Override
    public void registerListeners(
            Consumer<SessionViewModel> onSessionUpdate, Consumer<TransferViewModel> onTransferUpdate) {
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
        invalidateAndReserveGeneration();
        if (serverListener != null) {
            try {
                serverListener.close();
            } catch (IOException ignored) {
            }
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

    private long invalidateAndReserveGeneration() {
        TransferSession toCloseSession = null;
        RelayClient.Pending toCloseTicket = null;
        long nextGen;
        synchronized (sessionLock) {
            nextGen = generationSequence.incrementAndGet();
            activeGeneration.set(nextGen);
            if (activeContext != null) {
                toCloseSession = activeContext.session();
                activeContext = null;
            }
            if (activeRelayTicket != null) {
                toCloseTicket = activeRelayTicket.ticket();
                activeRelayTicket = null;
            }
            pendingIncomingOffer = null;
        }
        closeQuietly(toCloseTicket);
        closeQuietly(toCloseSession);
        return nextGen;
    }

    private ActiveSessionContext getCurrentContextIfMatches(String sessionId) {
        synchronized (sessionLock) {
            if (!running.get() || activeContext == null || sessionId == null || sessionId.isBlank()) {
                return null;
            }
            if (activeContext.generation() != activeGeneration.get()) {
                return null;
            }
            return activeContext.sessionId().equals(sessionId) ? activeContext : null;
        }
    }

    private boolean isCurrentContext(ActiveSessionContext ctx) {
        if (ctx == null || !running.get()) {
            return false;
        }
        ActiveSessionContext current = this.activeContext;
        return current == ctx && activeGeneration.get() == ctx.generation();
    }

    private boolean isCurrentRelayContext(PendingRelayContext relayCtx) {
        if (relayCtx == null || !running.get()) {
            return false;
        }
        PendingRelayContext current = this.activeRelayTicket;
        return current == relayCtx && activeGeneration.get() == relayCtx.generation();
    }

    private void closeSessionIfMatches(ActiveSessionContext ctx) {
        if (ctx == null) {
            return;
        }
        synchronized (sessionLock) {
            if (this.activeContext == ctx) {
                this.activeContext = null;
                this.pendingIncomingOffer = null;
            }
        }
        closeQuietly(ctx.session());
    }

    private void closeRelayTicketIfMatches(PendingRelayContext relayCtx) {
        if (relayCtx == null) {
            return;
        }
        synchronized (sessionLock) {
            if (this.activeRelayTicket == relayCtx) {
                this.activeRelayTicket = null;
            }
        }
        closeQuietly(relayCtx.ticket());
    }

    private long closeAndAdvanceGenerationIfMatches(ActiveSessionContext ctx) {
        if (ctx == null) {
            return -1L;
        }
        long nextGen = -1L;
        synchronized (sessionLock) {
            if (this.activeContext == ctx && activeGeneration.get() == ctx.generation()) {
                nextGen = generationSequence.incrementAndGet();
                activeGeneration.set(nextGen);
                this.activeContext = null;
                this.pendingIncomingOffer = null;
            }
        }
        closeQuietly(ctx.session());
        return nextGen;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    void dispatchSessionUpdate(long generation, SessionViewModel session) {
        if (!running.get() || activeGeneration.get() != generation) {
            return;
        }
        synchronized (sessionLock) {
            if (!running.get() || activeGeneration.get() != generation) {
                return;
            }
            this.currentSessionVm = session;
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (!running.get() || activeGeneration.get() != generation) {
                        return;
                    }
                    for (Consumer<SessionViewModel> listener : sessionListeners) {
                        try {
                            listener.accept(session);
                        } catch (Exception e) {
                            System.err.println("Session listener callback error: " + e.getMessage());
                        }
                    }
                });
    }

    void dispatchTransferUpdate(long generation, TransferViewModel transfer) {
        if (!running.get() || activeGeneration.get() != generation) {
            return;
        }
        synchronized (sessionLock) {
            if (!running.get() || activeGeneration.get() != generation) {
                return;
            }
            this.currentTransferVm = transfer;
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (!running.get() || activeGeneration.get() != generation) {
                        return;
                    }
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
