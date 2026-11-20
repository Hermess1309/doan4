package vn.edu.p2p.desktop.adapter;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
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

    private enum OperationPhase {
        PENDING,
        INSTALLED,
        COMMITTED,
        CANCELLED
    }

    /**
     * Ngữ cảnh điều khiển cancellation và chuyển giao ownership cho các thao tác khởi tạo kết nối
     * (LAN connect, Relay CREATE, Relay JOIN) chạy trên Virtual Thread JDK 21.
     *
     * <p>Vòng đời trạng thái (bảo vệ bởi {@code sessionLock}):
     * {@code PENDING -> INSTALLED -> COMMITTED} hoặc {@code CANCELLED}.
     * {@code activeOperation} chỉ được giải phóng sau khi {@code resultFuture.complete(result)}
     * thành công và trạng thái chuyển sang {@code COMMITTED}, giúp tránh race condition khi
     * {@code future.cancel(true)} xảy ra sau khi ticket/session đã cài đặt nhưng trước khi Future hoàn tất.
     */
    private static final class PendingOperationContext {
        final long generation;
        final CompletableFuture<?> resultFuture;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        OperationPhase phase = OperationPhase.PENDING;
        PendingRelayContext installedRelayTicket;
        ActiveSessionContext installedSession;
        private Future<?> workerTask;

        PendingOperationContext(long generation, CompletableFuture<?> resultFuture) {
            this.generation = generation;
            this.resultFuture = resultFuture;
        }

        void bindTask(Future<?> task) {
            boolean shouldCancelNow;
            synchronized (this) {
                this.workerTask = task;
                shouldCancelNow = cancelled.get();
            }
            if (shouldCancelNow && task != null) {
                task.cancel(true);
            }
        }

        void cancelOperation() {
            cancelled.set(true);
            Future<?> task;
            synchronized (this) {
                task = this.workerTask;
            }
            if (task != null) {
                task.cancel(true);
            }
            resultFuture.cancel(false);
        }
    }

    private static final class PendingRelayContext {
        final long generation;
        final String relayAddress;
        final RelayClient.Pending ticket;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        private Future<?> waiterTask;

        PendingRelayContext(long generation, String relayAddress, RelayClient.Pending ticket) {
            this.generation = generation;
            this.relayAddress = relayAddress;
            this.ticket = ticket;
        }

        void bindWaiter(Future<?> task) {
            boolean shouldCancelNow;
            synchronized (this) {
                this.waiterTask = task;
                shouldCancelNow = cancelled.get();
            }
            if (shouldCancelNow && task != null) {
                task.cancel(true);
            }
        }

        void cancelWaiter() {
            cancelled.set(true);
            Future<?> task;
            synchronized (this) {
                task = this.waiterTask;
            }
            if (task != null) {
                task.cancel(true);
            }
        }
    }

    /**
     * Tài nguyên đã được tách khỏi trạng thái hoạt động của adapter dưới {@code sessionLock}.
     * Mỗi thực thể giữ đúng tham chiếu ticket/session của phiên đó (không bao giờ đóng nhầm phiên khác)
     * và đảm bảo đóng I/O tối đa một lần (idempotent) ngay cả khi nhiều luồng ({@code disconnect},
     * {@code cancel}, {@code shutdown}) cùng tiến hành dọn dẹp đồng thời.
     */
    private static final class DetachedResources {
        final long nextGeneration;
        final PendingOperationContext operationToCancel;
        final PendingRelayContext relayTicketToClose;
        final TransferSession sessionToClose;
        private final AtomicBoolean ioClosed = new AtomicBoolean(false);

        DetachedResources(
                long nextGeneration,
                PendingOperationContext operationToCancel,
                PendingRelayContext relayTicketToClose,
                TransferSession sessionToClose) {
            this.nextGeneration = nextGeneration;
            this.operationToCancel = operationToCancel;
            this.relayTicketToClose = relayTicketToClose;
            this.sessionToClose = sessionToClose;
        }

        boolean hasCloseableIo() {
            return relayTicketToClose != null || sessionToClose != null;
        }

        void closeIoIdempotently() {
            if (!hasCloseableIo()) {
                return;
            }
            if (ioClosed.compareAndSet(false, true)) {
                if (relayTicketToClose != null) {
                    closeQuietly(relayTicketToClose.ticket);
                }
                closeQuietly(sessionToClose);
            }
        }
    }

    private record PendingOfferContext(long generation, String sessionId, FileOffer offer) {}

    private final PeerNode peerNode;
    private final PeerListener serverListener;
    private final ExecutorService workerPool;
    private final ExecutorService cleanupPool;
    private final Set<DetachedResources> pendingCleanups = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final Object sessionLock = new Object();
    private final AtomicLong generationSequence = new AtomicLong(0L);
    private final AtomicLong activeGeneration = new AtomicLong(0L);

    private final CopyOnWriteArrayList<Consumer<SessionViewModel>> sessionListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<TransferViewModel>> transferListeners =
            new CopyOnWriteArrayList<>();

    private volatile ActiveSessionContext activeContext;
    private volatile PendingOperationContext activeOperation;
    private volatile PendingRelayContext activeRelayTicket;
    private volatile PendingOfferContext pendingIncomingOffer;
    private volatile SessionViewModel currentSessionVm = SessionViewModel.createDisconnected();
    private volatile TransferViewModel currentTransferVm = TransferViewModel.createIdle();

    private volatile BiConsumer<FileOffer, Consumer<File>> incomingOfferHandler;
    private volatile Runnable onAfterOperationInstalledForTest;
    private volatile Runnable onAfterDisconnectDetachedForTest;
    private volatile Runnable onBeforeCancelDetachedHandlesForTest;

    public RealCoreApiAdapter(PeerNode peerNode, int preferredPort) throws IOException {
        this.peerNode = Objects.requireNonNull(peerNode, "peerNode không được null");
        this.workerPool = Executors.newVirtualThreadPerTaskExecutor();
        this.cleanupPool = Executors.newVirtualThreadPerTaskExecutor();

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

    void setOnAfterOperationInstalledForTest(Runnable hook) {
        this.onAfterOperationInstalledForTest = hook;
    }

    void setOnAfterDisconnectDetachedForTest(Runnable hook) {
        this.onAfterDisconnectDetachedForTest = hook;
    }

    void setOnBeforeCancelDetachedHandlesForTest(Runnable hook) {
        this.onBeforeCancelDetachedHandlesForTest = hook;
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
        ActiveSessionContext newCtx = null;
        synchronized (sessionLock) {
            if (!running.get()
                    || activeContext != null
                    || activeRelayTicket != null
                    || activeOperation != null) {
                newCtx = null;
            } else {
                long gen = generationSequence.incrementAndGet();
                SessionInfo info = acceptedSession.info();
                newCtx =
                        new ActiveSessionContext(
                                gen, info.sessionId().toString(), "Peer từ mạng LAN", acceptedSession, true, false);
                this.activeContext = newCtx;
                this.pendingIncomingOffer = null;
                this.activeGeneration.set(gen);
            }
        }
        if (newCtx == null) {
            closeQuietly(acceptedSession);
            return;
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

        CompletableFuture<SessionViewModel> resultFuture = new CompletableFuture<>();
        PendingOperationContext opCtx = startCancellableOperation(resultFuture);
        if (opCtx == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Adapter đã đóng, không thể tạo kết nối mới."));
        }
        final long attemptGeneration = opCtx.generation;

        submitCancellableWorker(
                opCtx,
                resultFuture,
                () -> {
                            if (isOperationSuperseded(opCtx)) {
                                cancelOperationIfNotCommitted(opCtx);
                                resultFuture.cancel(false);
                                return;
                            }
                            try {
                                TransferSession session =
                                        peerNode.connect(
                                                new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS, IO_TIMEOUT_MS);
                                ActiveSessionContext installedCtx = null;
                                boolean supersededBeforeInstall = false;
                                synchronized (sessionLock) {
                                    if (isOperationSuperseded(opCtx)
                                            || opCtx.phase != OperationPhase.PENDING) {
                                        opCtx.phase = OperationPhase.CANCELLED;
                                        clearOperationIfMatchesLocked(opCtx);
                                        supersededBeforeInstall = true;
                                    } else {
                                        SessionInfo info = session.info();
                                        installedCtx =
                                                new ActiveSessionContext(
                                                        attemptGeneration,
                                                        info.sessionId().toString(),
                                                        host + ":" + port,
                                                        session,
                                                        false,
                                                        false);
                                        opCtx.installedSession = installedCtx;
                                        opCtx.phase = OperationPhase.INSTALLED;
                                        this.activeContext = installedCtx;
                                        this.pendingIncomingOffer = null;
                                    }
                                }
                                if (supersededBeforeInstall) {
                                    closeQuietly(session);
                                    resultFuture.cancel(false);
                                    return;
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
                                if (!commitInstalledOperation(opCtx, resultFuture, vm)) {
                                    return;
                                }
                                dispatchSessionUpdate(installedCtx.generation(), vm);
                            } catch (IOException | RuntimeException e) {
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
                                failOperationExceptionally(opCtx, resultFuture, e, errVm);
                            }
                        });
        return resultFuture;
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
        CompletableFuture<RelayTicketInfo> resultFuture = new CompletableFuture<>();
        PendingOperationContext opCtx = startCancellableOperation(resultFuture);
        if (opCtx == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Adapter đã đóng, không thể tạo mã ghép nối Relay."));
        }
        final long attemptGeneration = opCtx.generation;

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

        submitCancellableWorker(
                opCtx,
                resultFuture,
                () -> {
                            if (isOperationSuperseded(opCtx)) {
                                cancelOperationIfNotCommitted(opCtx);
                                resultFuture.cancel(false);
                                return;
                            }
                            try {
                                RelayClient.Pending ticket =
                                        RelayClient.create(
                                                new InetSocketAddress(trimmedHost, relayPort),
                                                RELAY_CONTROL_TIMEOUT_MS);
                                PendingRelayContext relayCtx = null;
                                boolean supersededBeforeInstall = false;
                                synchronized (sessionLock) {
                                    if (isOperationSuperseded(opCtx)
                                            || opCtx.phase != OperationPhase.PENDING) {
                                        opCtx.phase = OperationPhase.CANCELLED;
                                        clearOperationIfMatchesLocked(opCtx);
                                        supersededBeforeInstall = true;
                                    } else {
                                        relayCtx =
                                                new PendingRelayContext(attemptGeneration, relayAddress, ticket);
                                        opCtx.installedRelayTicket = relayCtx;
                                        opCtx.phase = OperationPhase.INSTALLED;
                                        this.activeRelayTicket = relayCtx;
                                    }
                                }
                                if (supersededBeforeInstall) {
                                    closeQuietly(ticket);
                                    resultFuture.cancel(false);
                                    return;
                                }

                                RelayTicketInfo ticketInfo =
                                        new RelayTicketInfo(
                                                attemptGeneration, ticket.code(), ticket.ttlMillis(), relayAddress);
                                if (!commitInstalledOperation(opCtx, resultFuture, ticketInfo)) {
                                    return;
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

                                // Chỉ khởi chạy awaitPair sau khi COMMITTED (nếu bị disconnect/shutdown ngay sau commit thì dừng)
                                if (isCurrentRelayContext(relayCtx)) {
                                    final PendingRelayContext committedRelayCtx = relayCtx;
                                    try {
                                        Future<?> waiterTask =
                                                workerPool.submit(() -> awaitRelayPair(committedRelayCtx));
                                        committedRelayCtx.bindWaiter(waiterTask);
                                    } catch (RejectedExecutionException rejected) {
                                        closeRelayTicketIfMatches(committedRelayCtx);
                                    }
                                }
                            } catch (IOException | RuntimeException e) {
                                SessionViewModel errVm =
                                        new SessionViewModel(
                                                "",
                                                "Relay (" + relayAddress + ")",
                                                peerNode.localFingerprint(),
                                                "",
                                                true,
                                                SessionState.ERROR,
                                                "Không thể tạo mã ghép nối trên Relay: " + e.getMessage(),
                                                attemptGeneration);
                                failOperationExceptionally(opCtx, resultFuture, e, errVm);
                            }
                        });
        return resultFuture;
    }

    private void awaitRelayPair(PendingRelayContext relayCtx) {
        try {
            Socket pairedSocket = relayCtx.ticket.awaitPair();
            if (relayCtx.cancelled.get()
                    || Thread.currentThread().isInterrupted()
                    || !isCurrentRelayContext(relayCtx)) {
                closeQuietly(pairedSocket);
                return;
            }

            dispatchSessionUpdate(
                    relayCtx.generation,
                    new SessionViewModel(
                            "",
                            "Relay (" + relayCtx.relayAddress + ")",
                            peerNode.localFingerprint(),
                            "",
                            true,
                            SessionState.TCP_CONNECTED,
                            "",
                            relayCtx.generation));

            // Chuyển hoàn toàn quyền sở hữu socket đã PAIRED cho PeerNode.attach với vai trò TLS SERVER (Receiver)
            TransferSession session =
                    peerNode.attach(pairedSocket, PeerNode.TlsRole.SERVER, IO_TIMEOUT_MS);

            ActiveSessionContext installedCtx = null;
            boolean supersededBeforeInstall = false;
            synchronized (sessionLock) {
                if (relayCtx.cancelled.get()
                        || Thread.currentThread().isInterrupted()
                        || !running.get()
                        || activeGeneration.get() != relayCtx.generation) {
                    supersededBeforeInstall = true;
                } else {
                    if (this.activeRelayTicket == relayCtx) {
                        this.activeRelayTicket = null;
                    }
                    SessionInfo info = session.info();
                    installedCtx =
                            new ActiveSessionContext(
                                    relayCtx.generation,
                                    info.sessionId().toString(),
                                    "Relay (" + relayCtx.relayAddress + ")",
                                    session,
                                    true,
                                    true);
                    this.activeContext = installedCtx;
                    this.pendingIncomingOffer = null;
                }
            }
            if (supersededBeforeInstall) {
                closeQuietly(session);
                return;
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
        } catch (IOException | RuntimeException e) {
            boolean wasCancelled =
                    relayCtx.cancelled.get()
                            || Thread.currentThread().isInterrupted()
                            || !isCurrentRelayContext(relayCtx);
            closeRelayTicketIfMatches(relayCtx);
            if (!wasCancelled) {
                dispatchSessionUpdate(
                        relayCtx.generation,
                        new SessionViewModel(
                                "",
                                "Relay (" + relayCtx.relayAddress + ")",
                                peerNode.localFingerprint(),
                                "",
                                true,
                                SessionState.ERROR,
                                "Ghép nối Relay không thành công hoặc đã hết hạn chờ: " + e.getMessage(),
                                relayCtx.generation));
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
        CompletableFuture<SessionViewModel> resultFuture = new CompletableFuture<>();
        PendingOperationContext opCtx = startCancellableOperation(resultFuture);
        if (opCtx == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Adapter đã đóng, không thể kết nối Relay."));
        }
        final long attemptGeneration = opCtx.generation;

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

        submitCancellableWorker(
                opCtx,
                resultFuture,
                () -> {
                            if (isOperationSuperseded(opCtx)) {
                                cancelOperationIfNotCommitted(opCtx);
                                resultFuture.cancel(false);
                                return;
                            }
                            try {
                                Socket pairedSocket =
                                        RelayClient.join(
                                                new InetSocketAddress(trimmedHost, relayPort),
                                                canonicalCode,
                                                RELAY_CONTROL_TIMEOUT_MS);
                                if (isOperationSuperseded(opCtx)) {
                                    cancelOperationIfNotCommitted(opCtx);
                                    closeQuietly(pairedSocket);
                                    resultFuture.cancel(false);
                                    return;
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

                                ActiveSessionContext installedCtx = null;
                                boolean supersededBeforeInstall = false;
                                synchronized (sessionLock) {
                                    if (isOperationSuperseded(opCtx)
                                            || opCtx.phase != OperationPhase.PENDING) {
                                        opCtx.phase = OperationPhase.CANCELLED;
                                        clearOperationIfMatchesLocked(opCtx);
                                        supersededBeforeInstall = true;
                                    } else {
                                        SessionInfo info = session.info();
                                        installedCtx =
                                                new ActiveSessionContext(
                                                        attemptGeneration,
                                                        info.sessionId().toString(),
                                                        "Relay (" + relayAddress + ")",
                                                        session,
                                                        false,
                                                        true);
                                        opCtx.installedSession = installedCtx;
                                        opCtx.phase = OperationPhase.INSTALLED;
                                        this.activeContext = installedCtx;
                                        this.pendingIncomingOffer = null;
                                    }
                                }
                                if (supersededBeforeInstall) {
                                    closeQuietly(session);
                                    resultFuture.cancel(false);
                                    return;
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
                                if (!commitInstalledOperation(opCtx, resultFuture, vm)) {
                                    return;
                                }
                                dispatchSessionUpdate(installedCtx.generation(), vm);
                            } catch (IOException | RuntimeException e) {
                                SessionViewModel errVm =
                                        new SessionViewModel(
                                                "",
                                                "Relay (" + relayAddress + ")",
                                                peerNode.localFingerprint(),
                                                "",
                                                true,
                                                SessionState.ERROR,
                                                "Kết nối Relay thất bại (mã không hợp lệ/hết hạn hoặc máy chủ không sẵn sàng): "
                                                        + e.getMessage(),
                                                attemptGeneration);
                                failOperationExceptionally(opCtx, resultFuture, e, errVm);
                            }
                        });
        return resultFuture;
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

        return supplyAsyncOnWorker(
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
                });
    }

    private void startWaitingForOfferLoop(ActiveSessionContext ctx) {
        try {
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
        } catch (RejectedExecutionException rejected) {
            closeSessionIfMatches(ctx);
        }
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

        return supplyAsyncOnWorker(
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
                });
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

        return runAsyncOnWorker(
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
                });
    }

    @Override
    public CompletableFuture<Void> rejectTransfer(String transferId, String reason) {
        final ActiveSessionContext ctx;
        final PendingOfferContext offerCtx;
        synchronized (sessionLock) {
            ctx = this.activeContext;
            offerCtx = this.pendingIncomingOffer;
        }
        return runAsyncOnWorker(
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
                });
    }

    @Override
    public CompletableFuture<Void> cancelTransfer(String transferId) {
        final ActiveSessionContext ctx = this.activeContext;
        if (ctx == null) {
            return CompletableFuture.completedFuture(null);
        }
        return runAsyncOnWorker(
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
                });
    }

    @Override
    public CompletableFuture<Void> resumeTransfer(String transferId) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Tính năng Resume chưa được hỗ trợ tại phiên bản Core hiện tại (theo docs/CORE_API.md)."));
    }

    @Override
    public CompletableFuture<Void> disconnect(String sessionId) {
        if (!running.get()) {
            return CompletableFuture.completedFuture(null);
        }
        // Tách và đăng ký theo dõi ngay dưới sessionLock (non-blocking, an toàn trên EDT)
        DetachedResources detached = invalidateAndDetach(sessionId, false);
        if (detached == null) {
            return CompletableFuture.completedFuture(null);
        }
        Runnable beforeCancelHook = this.onBeforeCancelDetachedHandlesForTest;
        if (beforeCancelHook != null) {
            beforeCancelHook.run();
        }
        cancelDetachedAsyncHandles(detached);

        Runnable detachHook = this.onAfterDisconnectDetachedForTest;
        if (detachHook != null) {
            detachHook.run();
        }

        // Chạy cleanup trên cleanupPool độc lập (không phụ thuộc workerPool, có fallback nếu shutdown đã đóng)
        return scheduleDetachedCleanupAsync(detached, true);
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
        DetachedResources detached = invalidateAndDetach(null, true);
        if (detached != null) {
            Runnable beforeCancelHook = this.onBeforeCancelDetachedHandlesForTest;
            if (beforeCancelHook != null) {
                beforeCancelHook.run();
            }
            cancelDetachedAsyncHandles(detached);
            closeDetachedIoSafely(detached);
        }
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
        // Đóng ngay mọi tài nguyên đã được detach bởi disconnect()/cancel() đồng thời
        drainPendingCleanups();

        // Tắt cleanupPool theo chế độ graceful (không dùng shutdownNow) để không làm rơi tác vụ đang xếp hàng
        cleanupPool.shutdown();
        try {
            cleanupPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Quét lại lần cuối để đảm bảo không còn tài nguyên detach nào chưa đóng
        drainPendingCleanups();
    }

    public boolean isShutdown() {
        return !running.get() && workerPool.isShutdown() && cleanupPool.isShutdown();
    }

    private PendingOperationContext startCancellableOperation(CompletableFuture<?> resultFuture) {
        DetachedResources previous;
        PendingOperationContext opCtx;
        synchronized (sessionLock) {
            if (!running.get()) {
                return null;
            }
            long nextGen = generationSequence.incrementAndGet();
            activeGeneration.set(nextGen);
            PendingOperationContext oldOp = this.activeOperation;
            if (oldOp != null && oldOp.phase != OperationPhase.COMMITTED) {
                oldOp.phase = OperationPhase.CANCELLED;
            }
            PendingRelayContext oldTicket = this.activeRelayTicket;
            TransferSession oldSession =
                    (this.activeContext != null) ? this.activeContext.session() : null;

            this.activeContext = null;
            this.activeRelayTicket = null;
            this.pendingIncomingOffer = null;

            opCtx = new PendingOperationContext(nextGen, resultFuture);
            this.activeOperation = opCtx;
            previous = detachAndTrackLocked(nextGen, oldOp, oldTicket, oldSession);
        }

        cancelDetachedAsyncHandles(previous);
        closeDetachedIoSafely(previous);

        resultFuture.whenComplete(
                (res, err) -> {
                    if (resultFuture.isCancelled()) {
                        cancelOperationIfNotCommitted(opCtx);
                    }
                });
        return opCtx;
    }

    private <T> CompletableFuture<T> supplyAsyncOnWorker(Supplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(supplier, workerPool);
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Adapter đã đóng.", rejected));
        }
    }

    private CompletableFuture<Void> runAsyncOnWorker(Runnable action) {
        try {
            return CompletableFuture.runAsync(action, workerPool);
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Adapter đã đóng.", rejected));
        }
    }

    private <T> void submitCancellableWorker(
            PendingOperationContext opCtx, CompletableFuture<T> resultFuture, Runnable task) {
        try {
            Future<?> workerTask = workerPool.submit(task);
            opCtx.bindTask(workerTask);
        } catch (RejectedExecutionException rejected) {
            cancelOperationIfNotCommitted(opCtx);
            resultFuture.completeExceptionally(
                    new IllegalStateException("Adapter đã đóng, không thể khởi chạy thao tác mạng.", rejected));
        }
    }

    /**
     * Chuyển giao ownership từ trạng thái {@code INSTALLED} sang {@code COMMITTED} qua
     * điểm tuyến tính hóa (linearization point) của {@code resultFuture.complete(value)}.
     * Nếu {@code future.cancel(true)}, {@code disconnect()} hoặc {@code shutdown()} thắng race,
     * mọi tài nguyên đã cài đặt cho {@code opCtx} được dọn dẹp idempotent ngoài {@code sessionLock}.
     */
    private <T> boolean commitInstalledOperation(
            PendingOperationContext opCtx, CompletableFuture<T> resultFuture, T value) {
        Runnable testHook = this.onAfterOperationInstalledForTest;
        if (testHook != null) {
            testHook.run();
        }

        boolean abortedBeforeComplete = false;
        DetachedResources preCancelDetach = null;
        synchronized (sessionLock) {
            if (opCtx.phase != OperationPhase.INSTALLED
                    || !running.get()
                    || activeGeneration.get() != opCtx.generation
                    || resultFuture.isCancelled()) {
                abortedBeforeComplete = true;
                preCancelDetach = cancelOperationLocked(opCtx);
            }
        }
        if (abortedBeforeComplete) {
            if (preCancelDetach != null) {
                finishCancellationOutsideLock(preCancelDetach);
            } else {
                resultFuture.cancel(false);
            }
            return false;
        }

        // Linearization point giữa complete(value) và future.cancel(true)
        boolean wonFuture = resultFuture.complete(value);
        if (!wonFuture) {
            cancelOperationIfNotCommitted(opCtx);
            return false;
        }

        DetachedResources concurrentCancelDetach = null;
        synchronized (sessionLock) {
            if (opCtx.phase == OperationPhase.INSTALLED
                    && running.get()
                    && activeGeneration.get() == opCtx.generation) {
                opCtx.phase = OperationPhase.COMMITTED;
                clearOperationIfMatchesLocked(opCtx);
                return true;
            }
            concurrentCancelDetach = cancelOperationLocked(opCtx);
        }
        if (concurrentCancelDetach != null) {
            finishCancellationOutsideLock(concurrentCancelDetach);
        }
        return false;
    }

    private <T> void failOperationExceptionally(
            PendingOperationContext opCtx,
            CompletableFuture<T> resultFuture,
            Exception error,
            SessionViewModel errorVm) {
        boolean shouldReportError = false;
        DetachedResources detached = null;
        synchronized (sessionLock) {
            if (opCtx.phase == OperationPhase.COMMITTED || opCtx.phase == OperationPhase.CANCELLED) {
                return;
            }
            if (isOperationSuperseded(opCtx)) {
                detached = cancelOperationLocked(opCtx);
            } else {
                opCtx.phase = OperationPhase.CANCELLED;
                clearOperationIfMatchesLocked(opCtx);
                PendingRelayContext ticketToClose = null;
                TransferSession sessionToClose = null;
                if (opCtx.installedRelayTicket != null
                        && this.activeRelayTicket == opCtx.installedRelayTicket) {
                    ticketToClose = this.activeRelayTicket;
                    this.activeRelayTicket = null;
                }
                if (opCtx.installedSession != null && this.activeContext == opCtx.installedSession) {
                    sessionToClose = this.activeContext.session();
                    this.activeContext = null;
                    this.pendingIncomingOffer = null;
                }
                if (ticketToClose != null || sessionToClose != null) {
                    detached = detachAndTrackLocked(-1L, null, ticketToClose, sessionToClose);
                }
                shouldReportError = running.get() && activeGeneration.get() == opCtx.generation;
            }
        }

        if (!shouldReportError) {
            if (detached != null) {
                finishCancellationOutsideLock(detached);
            }
            resultFuture.cancel(false);
            return;
        }

        if (detached != null) {
            closeDetachedIoSafely(detached);
        }
        if (errorVm != null) {
            dispatchSessionUpdate(opCtx.generation, errorVm);
        }
        resultFuture.completeExceptionally(error);
    }

    private void cancelOperationIfNotCommitted(PendingOperationContext opCtx) {
        DetachedResources detached;
        synchronized (sessionLock) {
            detached = cancelOperationLocked(opCtx);
        }
        if (detached != null) {
            finishCancellationOutsideLock(detached);
        }
    }

    private DetachedResources cancelOperationLocked(PendingOperationContext opCtx) {
        if (opCtx.phase == OperationPhase.COMMITTED || opCtx.phase == OperationPhase.CANCELLED) {
            return null;
        }
        opCtx.phase = OperationPhase.CANCELLED;

        PendingRelayContext ticketToClose = null;
        TransferSession sessionToClose = null;
        long nextGen = -1L;

        clearOperationIfMatchesLocked(opCtx);
        if (opCtx.installedRelayTicket != null
                && this.activeRelayTicket == opCtx.installedRelayTicket) {
            ticketToClose = this.activeRelayTicket;
            this.activeRelayTicket = null;
        }
        if (opCtx.installedSession != null && this.activeContext == opCtx.installedSession) {
            sessionToClose = this.activeContext.session();
            this.activeContext = null;
            this.pendingIncomingOffer = null;
        }
        if (activeGeneration.get() == opCtx.generation) {
            nextGen = generationSequence.incrementAndGet();
            activeGeneration.set(nextGen);
        }
        return detachAndTrackLocked(nextGen, opCtx, ticketToClose, sessionToClose);
    }

    private void finishCancellationOutsideLock(DetachedResources detached) {
        cancelDetachedAsyncHandles(detached);
        closeDetachedIoSafely(detached);
        if (detached.nextGeneration > 0 && running.get()) {
            dispatchSessionUpdate(
                    detached.nextGeneration,
                    SessionViewModel.createDisconnected(detached.nextGeneration));
        }
    }

    private DetachedResources invalidateAndDetach(
            String onlyIfMatchingSessionId, boolean markStopped) {
        synchronized (sessionLock) {
            if (markStopped) {
                running.set(false);
            }
            if (onlyIfMatchingSessionId != null && !onlyIfMatchingSessionId.isBlank()) {
                if (activeContext == null
                        || !activeContext.sessionId().equals(onlyIfMatchingSessionId)) {
                    return null;
                }
            }
            long nextGen = generationSequence.incrementAndGet();
            activeGeneration.set(nextGen);
            PendingOperationContext oldOp = this.activeOperation;
            if (oldOp != null && oldOp.phase != OperationPhase.COMMITTED) {
                oldOp.phase = OperationPhase.CANCELLED;
            }
            PendingRelayContext oldTicket = this.activeRelayTicket;
            TransferSession oldSession =
                    (this.activeContext != null) ? this.activeContext.session() : null;

            this.activeOperation = null;
            this.activeRelayTicket = null;
            this.activeContext = null;
            this.pendingIncomingOffer = null;
            return detachAndTrackLocked(nextGen, oldOp, oldTicket, oldSession);
        }
    }

    /**
     * Phải được gọi bên trong {@code synchronized (sessionLock)}: đăng ký ngay vào {@code pendingCleanups}
     * trước khi nhả lock để {@code shutdown()} đồng thời luôn nhìn thấy và đóng được tài nguyên đã tách.
     */
    private DetachedResources detachAndTrackLocked(
            long nextGen,
            PendingOperationContext op,
            PendingRelayContext ticket,
            TransferSession session) {
        DetachedResources detached = new DetachedResources(nextGen, op, ticket, session);
        if (detached.hasCloseableIo()) {
            pendingCleanups.add(detached);
        }
        return detached;
    }

    private static void cancelDetachedAsyncHandles(DetachedResources detached) {
        if (detached.operationToCancel != null) {
            detached.operationToCancel.cancelOperation();
        }
        if (detached.relayTicketToClose != null) {
            detached.relayTicketToClose.cancelWaiter();
        }
    }

    private void closeDetachedIoAndUnregister(DetachedResources detached) {
        if (detached == null) {
            return;
        }
        try {
            detached.closeIoIdempotently();
        } finally {
            pendingCleanups.remove(detached);
        }
    }

    /**
     * Thực thi dọn dẹp tài nguyên đã tách trên {@code cleanupPool} độc lập với {@code workerPool}.
     * Nếu {@code cleanupPool} đã bị tắt bởi {@code shutdown()} đồng thời ({@link RejectedExecutionException}),
     * chuyển sang fallback Virtual Thread độc lập (và fallback cuối an toàn ngoài Swing EDT) để bảo đảm
     * socket luôn được đóng idempotent và không bao giờ ném {@code RejectedExecutionException} ra caller.
     */
    private CompletableFuture<Void> scheduleDetachedCleanupAsync(
            DetachedResources detached, boolean dispatchDisconnectedAfterClose) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable cleanupTask =
                () -> {
                    try {
                        closeDetachedIoAndUnregister(detached);
                        if (dispatchDisconnectedAfterClose
                                && detached.nextGeneration > 0
                                && running.get()) {
                            dispatchSessionUpdate(
                                    detached.nextGeneration,
                                    SessionViewModel.createDisconnected(detached.nextGeneration));
                        }
                    } finally {
                        done.complete(null);
                    }
                };
        try {
            cleanupPool.execute(cleanupTask);
        } catch (RejectedExecutionException rejected) {
            runCleanupFallbackOffEdt(cleanupTask);
        }
        return done;
    }

    private void runCleanupFallbackOffEdt(Runnable cleanupTask) {
        try {
            Thread.ofVirtual().name("p2p-cleanup-fallback").start(cleanupTask);
        } catch (Throwable virtualFailed) {
            if (!SwingUtilities.isEventDispatchThread()) {
                cleanupTask.run();
                return;
            }
            try {
                Thread daemon = new Thread(cleanupTask, "p2p-cleanup-fallback-platform");
                daemon.setDaemon(true);
                daemon.start();
            } catch (Throwable platformFailed) {
                // Giải pháp cuối nếu JVM không thể tạo bất kỳ luồng nào nữa
                cleanupTask.run();
            }
        }
    }

    /**
     * Đóng socket/ticket/session đã tách ngoài {@code sessionLock} và tuyệt đối không block Swing EDT.
     */
    private void closeDetachedIoSafely(DetachedResources detached) {
        if (detached == null) {
            return;
        }
        if (!detached.hasCloseableIo()) {
            pendingCleanups.remove(detached);
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            scheduleDetachedCleanupAsync(detached, false);
        } else {
            closeDetachedIoAndUnregister(detached);
        }
    }

    private void drainPendingCleanups() {
        for (DetachedResources pending : pendingCleanups) {
            closeDetachedIoSafely(pending);
        }
    }

    private boolean isOperationSuperseded(PendingOperationContext opCtx) {
        return opCtx.cancelled.get()
                || opCtx.resultFuture.isCancelled()
                || Thread.currentThread().isInterrupted()
                || !running.get()
                || activeGeneration.get() != opCtx.generation;
    }

    private void clearOperationIfMatchesLocked(PendingOperationContext opCtx) {
        if (this.activeOperation == opCtx) {
            this.activeOperation = null;
        }
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
        return current == relayCtx && activeGeneration.get() == relayCtx.generation;
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
        relayCtx.cancelWaiter();
        synchronized (sessionLock) {
            if (this.activeRelayTicket == relayCtx) {
                this.activeRelayTicket = null;
            }
        }
        closeQuietly(relayCtx.ticket);
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
