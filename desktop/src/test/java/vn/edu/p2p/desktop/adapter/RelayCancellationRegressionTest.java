package vn.edu.p2p.desktop.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.desktop.model.RelayTicketInfo;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.relay.RelayConfig;
import vn.edu.p2p.relay.RelayServer;

@Timeout(45)
class RelayCancellationRegressionTest {

    private static final int CANCEL_DEADLINE_MS = 750;
    private static final int RELAY_MAGIC = 0x50325231; // P2R1
    private static final int RELAY_STATUS_CREATED = 1;
    private static final int RELAY_STATUS_PAIRED = 2;

    @TempDir
    static Path identitiesDir;

    private static PeerNode receiverNode;
    private static PeerNode senderNode;

    private RealCoreApiAdapter receiverAdapter;
    private RealCoreApiAdapter senderAdapter;

    @BeforeAll
    static void initIdentities() throws Exception {
        receiverNode =
                LocalIdentityHelper.getOrCreateIdentity(
                        identitiesDir.resolve("cancel-recv").resolve("recv.p12"));
        senderNode =
                LocalIdentityHelper.getOrCreateIdentity(
                        identitiesDir.resolve("cancel-send").resolve("send.p12"));
    }

    @BeforeEach
    void setUp() throws Exception {
        receiverAdapter = RealCoreApiAdapter.create(receiverNode, 0);
        senderAdapter = RealCoreApiAdapter.create(senderNode, 0);
    }

    @AfterEach
    void tearDown() {
        if (receiverAdapter != null) {
            receiverAdapter.shutdown();
        }
        if (senderAdapter != null) {
            senderAdapter.shutdown();
        }
    }

    @Test
    void testCancelCreateWhileRelayServerDoesNotRespondClosesSocketWithin750ms() throws Exception {
        List<SessionViewModel> events = new CopyOnWriteArrayList<>();
        receiverAdapter.registerListeners(events::add, null);

        try (ServerSocket silentServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(silentServer);

            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", silentServer.getLocalPort());

            // Đợi server nhận đúng 5 byte header CREATE và cố tình không phản hồi
            try (Socket serverSideSocket = acceptedRef.get(5, TimeUnit.SECONDS)) {
                byte[] header = serverSideSocket.getInputStream().readNBytes(5);
                assertEquals(5, header.length, "Server phải nhận đủ 5 byte header CREATE");

                long staleGen = receiverAdapter.getActiveGeneration();
                assertFalse(createFuture.isDone(), "Future đang chờ phản hồi server");

                // Người dùng nhấn Hủy kết nối
                long cancelStartNanos = System.nanoTime();
                receiverAdapter.disconnect("").get(CANCEL_DEADLINE_MS, TimeUnit.MILLISECONDS);

                // 1. Future phải kết thúc ngay với CancellationException, không còn pending
                assertTrue(createFuture.isDone(), "CREATE future không được còn pending sau disconnect");
                assertTrue(createFuture.isCancelled(), "CREATE future phải mang trạng thái CANCELLED");
                assertThrows(CancellationException.class, () -> createFuture.get(100, TimeUnit.MILLISECONDS));

                // 2. Socket phía server phải nhận EOF/close trong giới hạn 750 ms
                assertSocketClosedWithin(serverSideSocket, cancelStartNanos, CANCEL_DEADLINE_MS);

                // 3. Phân biệt CANCELLED với FAILED: không phát ERROR, chuyển sang DISCONNECTED
                flushEdt();
                assertFalse(
                        events.stream().anyMatch(e -> e.state() == SessionState.ERROR),
                        "Hủy bởi người dùng không được báo nhầm là ERROR");
                assertEquals(SessionState.DISCONNECTED, events.get(events.size() - 1).state());

                // 4. Callback cũ mang staleGen không được bật SECURE_READY
                receiverAdapter.dispatchSessionUpdate(
                        staleGen,
                        new SessionViewModel(
                                "old-id",
                                "Relay",
                                receiverAdapter.getLocalFingerprint(),
                                senderAdapter.getLocalFingerprint(),
                                true,
                                SessionState.SECURE_READY,
                                "",
                                staleGen));
                flushEdt();
                assertFalse(
                        events.stream().anyMatch(e -> e.state() == SessionState.SECURE_READY),
                        "Callback cũ của phiên đã hủy không được bật SECURE_READY");
            }
        }
    }

    @Test
    void testCancelJoinWhileRelayServerDoesNotRespondClosesSocketWithin750ms() throws Exception {
        List<SessionViewModel> events = new CopyOnWriteArrayList<>();
        senderAdapter.registerListeners(events::add, null);

        try (ServerSocket silentServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(silentServer);

            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", silentServer.getLocalPort(), "ABCDEFGH2345");

            // Đợi server nhận đúng 17 byte JOIN (5 byte header + 12 byte mã) và cố tình không phản hồi
            try (Socket serverSideSocket = acceptedRef.get(5, TimeUnit.SECONDS)) {
                byte[] request = serverSideSocket.getInputStream().readNBytes(17);
                assertEquals(17, request.length, "Server phải nhận đủ 17 byte JOIN");
                assertFalse(joinFuture.isDone(), "JOIN future đang chờ phản hồi server");

                long cancelStartNanos = System.nanoTime();
                senderAdapter.disconnect("").get(CANCEL_DEADLINE_MS, TimeUnit.MILLISECONDS);

                // 1. Future hoàn thành với CancellationException, không còn pending
                assertTrue(joinFuture.isDone(), "JOIN future không được còn pending sau disconnect");
                assertTrue(joinFuture.isCancelled(), "JOIN future phải mang trạng thái CANCELLED");
                assertThrows(CancellationException.class, () -> joinFuture.get(100, TimeUnit.MILLISECONDS));

                // 2. Socket phía server đóng trong giới hạn 750 ms
                assertSocketClosedWithin(serverSideSocket, cancelStartNanos, CANCEL_DEADLINE_MS);

                // 3. Không báo lỗi ERROR giả khi người dùng chủ động hủy
                flushEdt();
                assertFalse(
                        events.stream().anyMatch(e -> e.state() == SessionState.ERROR),
                        "Hủy JOIN không được báo nhầm là ERROR");
                assertEquals(SessionState.DISCONNECTED, events.get(events.size() - 1).state());
            }
        }
    }

    @Test
    void testCancelDuringAwaitPairClosesTicketSocketWithin750ms() throws Exception {
        List<SessionViewModel> events = new CopyOnWriteArrayList<>();
        receiverAdapter.registerListeners(events::add, null);

        try (ServerSocket controlledServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(controlledServer);

            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", controlledServer.getLocalPort());

            try (Socket serverSideSocket = acceptedRef.get(5, TimeUnit.SECONDS)) {
                byte[] header = serverSideSocket.getInputStream().readNBytes(5);
                assertEquals(5, header.length);

                // Server trả lời CREATED hợp lệ, sau đó giữ socket không gửi PAIRED (client đang awaitPair)
                DataOutputStream out = new DataOutputStream(serverSideSocket.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_CREATED);
                out.write("ABCDEFGH2345".getBytes(StandardCharsets.US_ASCII));
                out.writeInt(60_000);
                out.flush();

                RelayTicketInfo ticket = createFuture.get(5, TimeUnit.SECONDS);
                assertEquals("ABCDEFGH2345", ticket.pairingCode());

                long cancelStartNanos = System.nanoTime();
                receiverAdapter.disconnect("").get(CANCEL_DEADLINE_MS, TimeUnit.MILLISECONDS);

                assertSocketClosedWithin(serverSideSocket, cancelStartNanos, CANCEL_DEADLINE_MS);
                flushEdt();
                assertFalse(events.stream().anyMatch(e -> e.state() == SessionState.ERROR));
                assertEquals(SessionState.DISCONNECTED, events.get(events.size() - 1).state());
            }
        }
    }

    @Test
    void testCancelImmediatelyAfterStartBeforeHandshakeCompletes() throws Exception {
        try (ServerSocket silentServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            // Hủy ngay lập tức trước hoặc trong khi TCP connect đang diễn ra
            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", silentServer.getLocalPort());
            receiverAdapter.disconnect("").get(CANCEL_DEADLINE_MS, TimeUnit.MILLISECONDS);

            assertTrue(createFuture.isDone());
            assertTrue(createFuture.isCancelled());
            assertThrows(CancellationException.class, () -> createFuture.get(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void testCancelConcurrentlyWithSuccessResponseDoesNotLeakSocketOrMutateState() throws Exception {
        try (ServerSocket raceServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(raceServer);

            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession("127.0.0.1", raceServer.getLocalPort(), "ABCDEFGH2345");

            try (Socket serverSideSocket = acceptedRef.get(5, TimeUnit.SECONDS)) {
                byte[] request = serverSideSocket.getInputStream().readNBytes(17);
                assertEquals(17, request.length);

                // Đồng thời gửi PAIRED và gọi disconnect()
                CountDownLatch go = new CountDownLatch(1);
                CompletableFuture<Void> serverReply =
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        go.await(2, TimeUnit.SECONDS);
                                        DataOutputStream out =
                                                new DataOutputStream(serverSideSocket.getOutputStream());
                                        out.writeInt(RELAY_MAGIC);
                                        out.writeByte(RELAY_STATUS_PAIRED);
                                        out.flush();
                                    } catch (Exception ignored) {
                                    }
                                });

                long startNanos = System.nanoTime();
                go.countDown();
                senderAdapter.disconnect("").get(CANCEL_DEADLINE_MS, TimeUnit.MILLISECONDS);
                serverReply.get(2, TimeUnit.SECONDS);

                // Dù thắng hay thua race, socket phải được đóng sạch và future không còn pending
                assertSocketClosedWithin(serverSideSocket, startNanos, CANCEL_DEADLINE_MS);
                assertTrue(joinFuture.isDone(), "Future không được treo sau race cancel/response");
            }
        }
    }

    @Test
    void testCancelSessionAThenImmediatelyConnectSessionBOverRealRelay() throws Exception {
        RelayConfig cfg = new RelayConfig(32, 16, 5000, 120000, 60000, 900000, 100);
        try (ServerSocket silentServer =
                        new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                RelayServer realRelay =
                        new RelayServer(new InetSocketAddress("127.0.0.1", 0), cfg)) {

            // 1. Session A treo trên silentServer (CREATE)
            CompletableFuture<Socket> silentAccepted = acceptAsync(silentServer);
            CompletableFuture<RelayTicketInfo> staleCreateA =
                    receiverAdapter.createRelaySession("127.0.0.1", silentServer.getLocalPort());
            try (Socket silentSockA = silentAccepted.get(5, TimeUnit.SECONDS)) {
                assertEquals(5, silentSockA.getInputStream().readNBytes(5).length);

                // 2. Bắt đầu ngay Session B trên realRelay (tự hủy Session A)
                long replaceStartNanos = System.nanoTime();
                AtomicReference<SessionViewModel> receiverSessionB = new AtomicReference<>();
                CountDownLatch pairedB = new CountDownLatch(1);
                receiverAdapter.registerListeners(
                        vm -> {
                            if (vm.isRelay() && vm.state() == SessionState.WAITING_FOR_FINGERPRINT) {
                                receiverSessionB.set(vm);
                                pairedB.countDown();
                            }
                        },
                        null);

                RelayTicketInfo ticketB =
                        receiverAdapter
                                .createRelaySession("127.0.0.1", realRelay.port())
                                .get(5, TimeUnit.SECONDS);
                assertNotNull(ticketB);

                // Session A phải bị hủy và đóng socket trong 750 ms mà KHÔNG ảnh hưởng Session B
                assertTrue(staleCreateA.isCancelled());
                assertSocketClosedWithin(silentSockA, replaceStartNanos, CANCEL_DEADLINE_MS);

                // Session B ghép nối + xác minh mutual TLS 1.3 thành công bình thường
                SessionViewModel senderSessionB =
                        senderAdapter
                                .joinRelaySession("127.0.0.1", realRelay.port(), ticketB.pairingCode())
                                .get(5, TimeUnit.SECONDS);
                assertTrue(pairedB.await(5, TimeUnit.SECONDS));

                CompletableFuture<SessionViewModel> rVerify =
                        receiverAdapter.startTlsHandshake(
                                receiverSessionB.get().sessionId(), senderAdapter.getLocalFingerprint());
                CompletableFuture<SessionViewModel> sVerify =
                        senderAdapter.startTlsHandshake(
                                senderSessionB.sessionId(), receiverAdapter.getLocalFingerprint());

                assertEquals(SessionState.SECURE_READY, rVerify.get(10, TimeUnit.SECONDS).state());
                assertEquals(SessionState.SECURE_READY, sVerify.get(10, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void testShutdownWhileCreateAndJoinPendingReleasesAllSocketsWithin750ms() throws Exception {
        try (ServerSocket silentServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedCreate = acceptAsync(silentServer);
            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", silentServer.getLocalPort());
            Socket sockCreate = acceptedCreate.get(5, TimeUnit.SECONDS);
            assertEquals(5, sockCreate.getInputStream().readNBytes(5).length);

            CompletableFuture<Socket> acceptedJoin = acceptAsync(silentServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", silentServer.getLocalPort(), "ABCDEFGH2345");
            Socket sockJoin = acceptedJoin.get(5, TimeUnit.SECONDS);
            assertEquals(17, sockJoin.getInputStream().readNBytes(17).length);

            try (sockCreate;
                    sockJoin) {
                long startNanos = System.nanoTime();
                receiverAdapter.shutdown();
                senderAdapter.shutdown();

                assertTrue(createFuture.isDone() && createFuture.isCancelled());
                assertTrue(joinFuture.isDone() && joinFuture.isCancelled());
                assertTrue(receiverAdapter.isShutdown());
                assertTrue(senderAdapter.isShutdown());

                assertSocketClosedWithin(sockCreate, startNanos, CANCEL_DEADLINE_MS);
                assertSocketClosedWithin(sockJoin, startNanos, CANCEL_DEADLINE_MS);
            }
        }
    }

    @Test
    void testDirectFutureCancelOnCreateAndJoinClosesSocketsWithin750ms() throws Exception {
        try (ServerSocket silentServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            // 1. Direct cancel trên CREATE future
            CompletableFuture<Socket> a1 = acceptAsync(silentServer);
            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", silentServer.getLocalPort());
            try (Socket s1 = a1.get(5, TimeUnit.SECONDS)) {
                assertEquals(5, s1.getInputStream().readNBytes(5).length);
                long start1 = System.nanoTime();
                assertTrue(createFuture.cancel(true));
                assertSocketClosedWithin(s1, start1, CANCEL_DEADLINE_MS);
            }

            // 2. Direct cancel trên JOIN future
            CompletableFuture<Socket> a2 = acceptAsync(silentServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", silentServer.getLocalPort(), "ABCDEFGH2345");
            try (Socket s2 = a2.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, s2.getInputStream().readNBytes(17).length);
                long start2 = System.nanoTime();
                assertTrue(joinFuture.cancel(true));
                assertSocketClosedWithin(s2, start2, CANCEL_DEADLINE_MS);
            }
        }
    }

    @Test
    void testStaleDisconnectWithOldSessionIdDoesNotCancelPendingSessionB() throws Exception {
        RelayConfig cfg = new RelayConfig(32, 16, 5000, 120000, 60000, 900000, 100);
        try (RelayServer realRelay = new RelayServer(new InetSocketAddress("127.0.0.1", 0), cfg)) {
            RelayTicketInfo ticketB =
                    receiverAdapter
                            .createRelaySession("127.0.0.1", realRelay.port())
                            .get(5, TimeUnit.SECONDS);

            // Gọi disconnect với sessionId của phiên cũ không còn tồn tại -> KHÔNG được hủy ticketB
            receiverAdapter.disconnect("stale-session-id-from-old-session").get(2, TimeUnit.SECONDS);

            // Sender vẫn ghép nối được vào ticketB bình thường
            SessionViewModel senderSession =
                    senderAdapter
                            .joinRelaySession("127.0.0.1", realRelay.port(), ticketB.pairingCode())
                            .get(5, TimeUnit.SECONDS);
            assertEquals(SessionState.WAITING_FOR_FINGERPRINT, senderSession.state());
        }
    }

    @Test
    void testCreateHandoffRaceCancelAfterTicketInstalledBeforeFutureComplete() throws Exception {
        List<SessionViewModel> events = new CopyOnWriteArrayList<>();
        receiverAdapter.registerListeners(events::add, null);

        CountDownLatch installedLatch = new CountDownLatch(1);
        CountDownLatch releaseCommitLatch = new CountDownLatch(1);
        receiverAdapter.setOnAfterOperationInstalledForTest(
                () -> {
                    installedLatch.countDown();
                    try {
                        releaseCommitLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        try (ServerSocket controlledServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(controlledServer);
            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", controlledServer.getLocalPort());

            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(5, serverSock.getInputStream().readNBytes(5).length);

                // Trả lời CREATED hợp lệ để adapter cài đặt Pending ticket vào trạng thái INSTALLED
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_CREATED);
                out.write("ABCDEFGH2345".getBytes(StandardCharsets.US_ASCII));
                out.writeInt(60_000);
                out.flush();

                // Đợi đúng điểm Pending đã INSTALLED nhưng createFuture chưa complete()
                assertTrue(
                        installedLatch.await(5, TimeUnit.SECONDS),
                        "CREATE phải đạt trạng thái INSTALLED trước khi hủy");
                assertFalse(createFuture.isDone(), "Future chưa được complete tại điểm handoff");

                // Caller gọi future.cancel(true) đúng cửa sổ race
                long cancelStartNanos = System.nanoTime();
                assertTrue(createFuture.cancel(true), "cancel(true) phải thắng race tại trạng thái INSTALLED");
                releaseCommitLatch.countDown();

                assertTrue(createFuture.isCancelled());
                assertThrows(CancellationException.class, () -> createFuture.get(100, TimeUnit.MILLISECONDS));

                // Socket/ticket đã cài đặt PHẢI bị đóng trong 750 ms
                assertSocketClosedWithin(serverSock, cancelStartNanos, CANCEL_DEADLINE_MS);

                flushEdt();
                assertFalse(
                        events.stream().anyMatch(e -> e.state() == SessionState.WAITING_FOR_PEER),
                        "CREATE bị hủy trước commit không được phát WAITING_FOR_PEER");
                assertFalse(
                        events.stream().anyMatch(e -> e.state() == SessionState.SECURE_READY),
                        "CREATE bị hủy không được bật SECURE_READY");
            }
        } finally {
            releaseCommitLatch.countDown();
            receiverAdapter.setOnAfterOperationInstalledForTest(null);
        }
    }

    @Test
    void testJoinHandoffRaceCancelAfterSessionInstalledBeforeFutureComplete() throws Exception {
        List<SessionViewModel> events = new CopyOnWriteArrayList<>();
        senderAdapter.registerListeners(events::add, null);

        CountDownLatch installedLatch = new CountDownLatch(1);
        CountDownLatch releaseCommitLatch = new CountDownLatch(1);
        senderAdapter.setOnAfterOperationInstalledForTest(
                () -> {
                    installedLatch.countDown();
                    try {
                        releaseCommitLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        try (ServerSocket controlledServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(controlledServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", controlledServer.getLocalPort(), "ABCDEFGH2345");

            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, serverSock.getInputStream().readNBytes(17).length);

                // Trả lời PAIRED hợp lệ để PeerNode.attach(CLIENT) tạo và cài đặt TransferSession (INSTALLED)
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_PAIRED);
                out.flush();

                // Đợi đúng điểm TransferSession đã INSTALLED nhưng joinFuture chưa complete()
                assertTrue(
                        installedLatch.await(5, TimeUnit.SECONDS),
                        "JOIN phải đạt trạng thái INSTALLED trước khi hủy");
                assertFalse(joinFuture.isDone(), "Future chưa được complete tại điểm handoff");

                // Caller gọi future.cancel(true) đúng cửa sổ race
                long cancelStartNanos = System.nanoTime();
                assertTrue(joinFuture.cancel(true), "cancel(true) phải thắng race tại trạng thái INSTALLED");
                releaseCommitLatch.countDown();

                assertTrue(joinFuture.isCancelled());
                assertThrows(CancellationException.class, () -> joinFuture.get(100, TimeUnit.MILLISECONDS));

                // Socket/TransferSession đã cài đặt PHẢI bị đóng trong 750 ms
                assertSocketClosedWithin(serverSock, cancelStartNanos, CANCEL_DEADLINE_MS);

                flushEdt();
                assertFalse(
                        events.stream().anyMatch(e -> e.state() == SessionState.WAITING_FOR_FINGERPRINT),
                        "JOIN bị hủy trước commit không được phát WAITING_FOR_FINGERPRINT");
                assertFalse(
                        events.stream().anyMatch(e -> e.state() == SessionState.SECURE_READY),
                        "JOIN bị hủy không được bật SECURE_READY");
            }
        } finally {
            releaseCommitLatch.countDown();
            senderAdapter.setOnAfterOperationInstalledForTest(null);
        }
    }

    @Test
    void testLateCancelAfterSuccessfulFutureCompleteDoesNotDestroyValidSession() throws Exception {
        RelayConfig cfg = new RelayConfig(32, 16, 5000, 120000, 60000, 900000, 100);
        try (RelayServer realRelay = new RelayServer(new InetSocketAddress("127.0.0.1", 0), cfg)) {
            AtomicReference<SessionViewModel> receiverSessionRef = new AtomicReference<>();
            CountDownLatch receiverPaired = new CountDownLatch(1);
            receiverAdapter.registerListeners(
                    vm -> {
                        if (vm.isRelay() && vm.state() == SessionState.WAITING_FOR_FINGERPRINT) {
                            receiverSessionRef.set(vm);
                            receiverPaired.countDown();
                        }
                    },
                    null);

            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", realRelay.port());
            RelayTicketInfo ticket = createFuture.get(5, TimeUnit.SECONDS);

            // Hủy muộn sau khi createFuture đã complete -> phải trả false và KHÔNG phá hủy ticket
            assertFalse(createFuture.cancel(true), "cancel muộn sau complete phải trả false");

            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession("127.0.0.1", realRelay.port(), ticket.pairingCode());
            SessionViewModel senderSession = joinFuture.get(5, TimeUnit.SECONDS);

            // Hủy muộn sau khi joinFuture đã complete -> phải trả false và KHÔNG phá hủy TransferSession
            assertFalse(joinFuture.cancel(true), "cancel muộn sau complete phải trả false");
            assertTrue(receiverPaired.await(5, TimeUnit.SECONDS));

            // Cả hai bên vẫn bắt tay Mutual TLS 1.3 thành công bình thường
            CompletableFuture<SessionViewModel> rVerify =
                    receiverAdapter.startTlsHandshake(
                            receiverSessionRef.get().sessionId(), senderAdapter.getLocalFingerprint());
            CompletableFuture<SessionViewModel> sVerify =
                    senderAdapter.startTlsHandshake(
                            senderSession.sessionId(), receiverAdapter.getLocalFingerprint());

            assertEquals(SessionState.SECURE_READY, rVerify.get(10, TimeUnit.SECONDS).state());
            assertEquals(SessionState.SECURE_READY, sVerify.get(10, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void testSimultaneousDisconnectAndFutureCancelDuringHandoffIsIdempotentAndAllowsReconnect()
            throws Exception {
        CountDownLatch installedLatch = new CountDownLatch(1);
        CountDownLatch releaseCommitLatch = new CountDownLatch(1);
        senderAdapter.setOnAfterOperationInstalledForTest(
                () -> {
                    installedLatch.countDown();
                    try {
                        releaseCommitLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        try (ServerSocket controlledServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(controlledServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", controlledServer.getLocalPort(), "ABCDEFGH2345");

            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, serverSock.getInputStream().readNBytes(17).length);
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_PAIRED);
                out.flush();

                assertTrue(installedLatch.await(5, TimeUnit.SECONDS));

                // Chạy đồng thời disconnect("") và future.cancel(true) từ 2 luồng
                CountDownLatch startRace = new CountDownLatch(1);
                CompletableFuture<Void> t1 =
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        startRace.await(2, TimeUnit.SECONDS);
                                    } catch (InterruptedException ignored) {
                                    }
                                    senderAdapter.disconnect("");
                                });
                CompletableFuture<Void> t2 =
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        startRace.await(2, TimeUnit.SECONDS);
                                    } catch (InterruptedException ignored) {
                                    }
                                    joinFuture.cancel(true);
                                });

                long startNanos = System.nanoTime();
                startRace.countDown();
                CompletableFuture.allOf(t1, t2).get(5, TimeUnit.SECONDS);
                releaseCommitLatch.countDown();

                assertTrue(joinFuture.isCancelled());
                assertSocketClosedWithin(serverSock, startNanos, CANCEL_DEADLINE_MS);
            }
        } finally {
            releaseCommitLatch.countDown();
            senderAdapter.setOnAfterOperationInstalledForTest(null);
        }

        // Kết nối lại ngay sau cancel đồng thời vẫn hoạt động bình thường trên real RelayServer
        RelayConfig cfg = new RelayConfig(32, 16, 5000, 120000, 60000, 900000, 100);
        try (RelayServer realRelay = new RelayServer(new InetSocketAddress("127.0.0.1", 0), cfg)) {
            RelayTicketInfo ticket =
                    receiverAdapter
                            .createRelaySession("127.0.0.1", realRelay.port())
                            .get(5, TimeUnit.SECONDS);
            SessionViewModel reconnected =
                    senderAdapter
                            .joinRelaySession("127.0.0.1", realRelay.port(), ticket.pairingCode())
                            .get(5, TimeUnit.SECONDS);
            assertEquals(SessionState.WAITING_FOR_FINGERPRINT, reconnected.state());
        }
    }

    @Test
    void testShutdownDuringHandoffInstalledPhaseClosesSocketWithin750ms() throws Exception {
        CountDownLatch installedLatch = new CountDownLatch(1);
        CountDownLatch releaseCommitLatch = new CountDownLatch(1);
        receiverAdapter.setOnAfterOperationInstalledForTest(
                () -> {
                    installedLatch.countDown();
                    try {
                        releaseCommitLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        try (ServerSocket controlledServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(controlledServer);
            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", controlledServer.getLocalPort());

            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(5, serverSock.getInputStream().readNBytes(5).length);
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_CREATED);
                out.write("ABCDEFGH2345".getBytes(StandardCharsets.US_ASCII));
                out.writeInt(60_000);
                out.flush();

                assertTrue(installedLatch.await(5, TimeUnit.SECONDS));
                long startNanos = System.nanoTime();
                receiverAdapter.shutdown();
                releaseCommitLatch.countDown();

                assertTrue(receiverAdapter.isShutdown());
                assertTrue(createFuture.isDone() && createFuture.isCancelled());
                assertSocketClosedWithin(serverSock, startNanos, CANCEL_DEADLINE_MS);
            }
        } finally {
            releaseCommitLatch.countDown();
            receiverAdapter.setOnAfterOperationInstalledForTest(null);
        }
    }

    @Test
    void testCancelOnSwingEdtDuringHandoffDoesNotBlockEdtAndClosesSocketWithin750ms()
            throws Exception {
        CountDownLatch installedLatch = new CountDownLatch(1);
        CountDownLatch releaseCommitLatch = new CountDownLatch(1);
        senderAdapter.setOnAfterOperationInstalledForTest(
                () -> {
                    installedLatch.countDown();
                    try {
                        releaseCommitLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        try (ServerSocket controlledServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(controlledServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", controlledServer.getLocalPort(), "ABCDEFGH2345");

            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, serverSock.getInputStream().readNBytes(17).length);
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_PAIRED);
                out.flush();

                assertTrue(installedLatch.await(5, TimeUnit.SECONDS));

                long startNanos = System.nanoTime();
                SwingUtilities.invokeAndWait(() -> assertTrue(joinFuture.cancel(true)));
                releaseCommitLatch.countDown();

                assertSocketClosedWithin(serverSock, startNanos, CANCEL_DEADLINE_MS);
            }
        } finally {
            releaseCommitLatch.countDown();
            senderAdapter.setOnAfterOperationInstalledForTest(null);
        }
    }

    @Test
    void testDisconnectPausedInWhenCompleteWhileShutdownClosesExecutorsStillClosesSocketWithin750ms()
            throws Exception {
        CountDownLatch installedLatch = new CountDownLatch(1);
        CountDownLatch releaseJoinWorkerLatch = new CountDownLatch(1);
        CountDownLatch inWhenCompleteLatch = new CountDownLatch(1);
        CountDownLatch releaseWhenCompleteLatch = new CountDownLatch(1);
        AtomicBoolean secureReadyObserved = new AtomicBoolean(false);

        senderAdapter.registerListeners(
                vm -> {
                    if (vm.state() == SessionState.SECURE_READY) {
                        secureReadyObserved.set(true);
                    }
                },
                null);
        senderAdapter.setOnAfterOperationInstalledForTest(
                () -> {
                    installedLatch.countDown();
                    try {
                        releaseJoinWorkerLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        try (ServerSocket controlledServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(controlledServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", controlledServer.getLocalPort(), "ABCDEFGH2345");

            // Mô phỏng chính xác kịch bản Codex CompletionWindowProbe.shutdownDuringDisconnect:
            // callback đồng bộ chặn luồng disconnect() ngay sau khi invalidateAndDetach() chạy
            joinFuture.whenComplete(
                    (res, err) -> {
                        if (joinFuture.isCancelled()) {
                            inWhenCompleteLatch.countDown();
                            try {
                                releaseWhenCompleteLatch.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });

            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, serverSock.getInputStream().readNBytes(17).length);
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_PAIRED);
                out.flush();

                assertTrue(installedLatch.await(5, TimeUnit.SECONDS));

                CompletableFuture<CompletableFuture<Void>> disconnectCall =
                        CompletableFuture.supplyAsync(() -> senderAdapter.disconnect(null));

                assertTrue(
                        inWhenCompleteLatch.await(5, TimeUnit.SECONDS),
                        "disconnect() phải kích hoạt callback hủy joinFuture sau khi detach");

                // Thả worker JOIN và gọi shutdown() đóng cả workerPool lẫn cleanupPool
                releaseJoinWorkerLatch.countDown();
                long startNanos = System.nanoTime();
                senderAdapter.shutdown();
                assertTrue(senderAdapter.isShutdown());

                // Ngay cả trước khi callback disconnect() được thả, shutdown() đã đóng socket thật trong <= 750ms
                assertSocketClosedWithin(serverSock, startNanos, CANCEL_DEADLINE_MS);

                // Thả callback disconnect(): không được ném RejectedExecutionException
                releaseWhenCompleteLatch.countDown();
                CompletableFuture<Void> disconnectFuture = disconnectCall.get(5, TimeUnit.SECONDS);
                assertDoesNotThrow(() -> disconnectFuture.get(5, TimeUnit.SECONDS));
                assertTrue(joinFuture.isCancelled());
                flushEdt();
                assertFalse(
                        secureReadyObserved.get(),
                        "Không bao giờ được phát callback SECURE_READY sau shutdown");
            }
        } finally {
            releaseJoinWorkerLatch.countDown();
            releaseWhenCompleteLatch.countDown();
            senderAdapter.setOnAfterOperationInstalledForTest(null);
        }
    }

    @Test
    void testDisconnectDetachesCommittedSessionThenShutdownClosesExecutorsBeforeCleanupSubmit()
            throws Exception {
        CountDownLatch detachedLatch = new CountDownLatch(1);
        CountDownLatch releaseDisconnectLatch = new CountDownLatch(1);
        AtomicBoolean secureReadyAfterShutdown = new AtomicBoolean(false);

        senderAdapter.registerListeners(
                vm -> {
                    if (vm.state() == SessionState.SECURE_READY) {
                        secureReadyAfterShutdown.set(true);
                    }
                },
                null);

        try (ServerSocket controlledServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(controlledServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", controlledServer.getLocalPort(), "ABCDEFGH2345");

            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, serverSock.getInputStream().readNBytes(17).length);
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_PAIRED);
                out.flush();

                SessionViewModel committedVm = joinFuture.get(5, TimeUnit.SECONDS);
                assertNotNull(committedVm);

                senderAdapter.setOnAfterDisconnectDetachedForTest(
                        () -> {
                            detachedLatch.countDown();
                            try {
                                releaseDisconnectLatch.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });

                CompletableFuture<CompletableFuture<Void>> disconnectCall =
                        CompletableFuture.supplyAsync(() -> senderAdapter.disconnect(committedVm.sessionId()));

                assertTrue(
                        detachedLatch.await(5, TimeUnit.SECONDS),
                        "disconnect() phải tách xong tài nguyên trước khi shutdown()");

                long startNanos = System.nanoTime();
                senderAdapter.shutdown();
                assertTrue(senderAdapter.isShutdown());

                // Thả disconnect() sau khi executor đã shutdown hoàn toàn
                releaseDisconnectLatch.countDown();
                CompletableFuture<Void> disconnectFuture = disconnectCall.get(5, TimeUnit.SECONDS);
                assertDoesNotThrow(() -> disconnectFuture.get(5, TimeUnit.SECONDS));

                assertSocketClosedWithin(serverSock, startNanos, CANCEL_DEADLINE_MS);
                flushEdt();
                assertFalse(secureReadyAfterShutdown.get());
            }
        } finally {
            releaseDisconnectLatch.countDown();
            senderAdapter.setOnAfterDisconnectDetachedForTest(null);
        }
    }

    @Test
    void testRepeatedShutdownAndConcurrentDisconnectShutdownAreIdempotentWithoutRejection()
            throws Exception {
        try (ServerSocket silentCreateServer =
                        new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                ServerSocket pairedJoinServer =
                        new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> createAccept = acceptAsync(silentCreateServer);
            CompletableFuture<Socket> joinAccept = acceptAsync(pairedJoinServer);

            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", silentCreateServer.getLocalPort());
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", pairedJoinServer.getLocalPort(), "ABCDEFGH2345");

            try (Socket createSock = createAccept.get(5, TimeUnit.SECONDS);
                    Socket joinSock = joinAccept.get(5, TimeUnit.SECONDS)) {
                assertEquals(5, createSock.getInputStream().readNBytes(5).length);
                assertEquals(17, joinSock.getInputStream().readNBytes(17).length);
                DataOutputStream out = new DataOutputStream(joinSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_PAIRED);
                out.flush();

                SessionViewModel committedJoin = joinFuture.get(5, TimeUnit.SECONDS);
                assertNotNull(committedJoin);

                long startNanos = System.nanoTime();
                CompletableFuture<Void> d1 =
                        CompletableFuture.runAsync(() -> receiverAdapter.disconnect(null).join());
                CompletableFuture<Void> d2 =
                        CompletableFuture.runAsync(
                                () -> senderAdapter.disconnect(committedJoin.sessionId()).join());
                CompletableFuture<Void> s1 = CompletableFuture.runAsync(receiverAdapter::shutdown);
                CompletableFuture<Void> s2 = CompletableFuture.runAsync(senderAdapter::shutdown);

                CompletableFuture.allOf(d1, d2, s1, s2).get(5, TimeUnit.SECONDS);

                // Gọi shutdown() lặp lại nhiều lần phải hoàn toàn idempotent
                assertDoesNotThrow(
                        () -> {
                            receiverAdapter.shutdown();
                            receiverAdapter.shutdown();
                            senderAdapter.shutdown();
                            senderAdapter.shutdown();
                        });
                assertTrue(receiverAdapter.isShutdown());
                assertTrue(senderAdapter.isShutdown());
                assertTrue(createFuture.isDone());

                assertSocketClosedWithin(createSock, startNanos, CANCEL_DEADLINE_MS);
                assertSocketClosedWithin(joinSock, startNanos, CANCEL_DEADLINE_MS);
            }
        }
    }

    @Test
    void testConcurrentCancelAndShutdownAcrossPendingInstalledAndCommittedPhases()
            throws Exception {
        // 1. PENDING phase: cancel(true) + shutdown() đồng thời khi server im lặng
        try (ServerSocket silentServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(silentServer);
            CompletableFuture<RelayTicketInfo> createFuture =
                    receiverAdapter.createRelaySession("127.0.0.1", silentServer.getLocalPort());
            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(5, serverSock.getInputStream().readNBytes(5).length);
                long startNanos = System.nanoTime();
                CompletableFuture<Void> cancelTask =
                        CompletableFuture.runAsync(() -> createFuture.cancel(true));
                CompletableFuture<Void> shutdownTask =
                        CompletableFuture.runAsync(receiverAdapter::shutdown);
                CompletableFuture.allOf(cancelTask, shutdownTask).get(5, TimeUnit.SECONDS);
                assertSocketClosedWithin(serverSock, startNanos, CANCEL_DEADLINE_MS);
                assertTrue(createFuture.isDone());
            }
        }

        // 2. COMMITTED phase: cancel(true) + shutdown() đồng thời sau khi JOIN đã COMMITTED
        try (ServerSocket pairedServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(pairedServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", pairedServer.getLocalPort(), "ABCDEFGH2345");
            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, serverSock.getInputStream().readNBytes(17).length);
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_PAIRED);
                out.flush();

                SessionViewModel committed = joinFuture.get(5, TimeUnit.SECONDS);
                assertNotNull(committed);

                long startNanos = System.nanoTime();
                CompletableFuture<Void> cancelTask =
                        CompletableFuture.runAsync(() -> joinFuture.cancel(true));
                CompletableFuture<Void> shutdownTask =
                        CompletableFuture.runAsync(senderAdapter::shutdown);
                CompletableFuture.allOf(cancelTask, shutdownTask).get(5, TimeUnit.SECONDS);
                assertSocketClosedWithin(serverSock, startNanos, CANCEL_DEADLINE_MS);
                assertTrue(senderAdapter.isShutdown());
            }
        }
    }

    @Test
    void testCleanupOnSwingEdtDuringConcurrentDisconnectAndShutdownDoesNotBlockEdt()
            throws Exception {
        try (ServerSocket pairedServer =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedRef = acceptAsync(pairedServer);
            CompletableFuture<SessionViewModel> joinFuture =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", pairedServer.getLocalPort(), "ABCDEFGH2345");

            try (Socket serverSock = acceptedRef.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, serverSock.getInputStream().readNBytes(17).length);
                DataOutputStream out = new DataOutputStream(serverSock.getOutputStream());
                out.writeInt(RELAY_MAGIC);
                out.writeByte(RELAY_STATUS_PAIRED);
                out.flush();

                SessionViewModel committed = joinFuture.get(5, TimeUnit.SECONDS);
                assertNotNull(committed);

                AtomicReference<CompletableFuture<Void>> disconnectFutureRef = new AtomicReference<>();
                long startNanos = System.nanoTime();
                CompletableFuture<Void> shutdownAsync =
                        CompletableFuture.runAsync(senderAdapter::shutdown);
                long edtStartNanos = System.nanoTime();
                SwingUtilities.invokeAndWait(
                        () -> disconnectFutureRef.set(senderAdapter.disconnect(committed.sessionId())));
                long edtElapsedMs = (System.nanoTime() - edtStartNanos) / 1_000_000L;

                assertTrue(
                        edtElapsedMs < 250,
                        "disconnect() trên Swing EDT không được block (thực tế: " + edtElapsedMs + "ms)");
                shutdownAsync.get(5, TimeUnit.SECONDS);
                assertDoesNotThrow(() -> disconnectFutureRef.get().get(5, TimeUnit.SECONDS));
                assertSocketClosedWithin(serverSock, startNanos, CANCEL_DEADLINE_MS);
            }
        }
    }

    @Test
    void testDelayedCleanupOfSessionADoesNotCloseReplacementSessionB() throws Exception {
        CountDownLatch detachedALatch = new CountDownLatch(1);
        CountDownLatch releaseCleanupALatch = new CountDownLatch(1);

        try (ServerSocket fakeServerA =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Socket> acceptedARef = acceptAsync(fakeServerA);
            CompletableFuture<SessionViewModel> joinA =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", fakeServerA.getLocalPort(), "ABCDEFGH2345");

            try (Socket sockA = acceptedARef.get(5, TimeUnit.SECONDS)) {
                assertEquals(17, sockA.getInputStream().readNBytes(17).length);
                DataOutputStream outA = new DataOutputStream(sockA.getOutputStream());
                outA.writeInt(RELAY_MAGIC);
                outA.writeByte(RELAY_STATUS_PAIRED);
                outA.flush();

                SessionViewModel sessionA = joinA.get(5, TimeUnit.SECONDS);
                assertNotNull(sessionA);

                // Tạm dừng luồng disconnect(sessionA) ngay sau khi detach để Session B kịp tạo trước khi cleanup A chạy
                senderAdapter.setOnAfterDisconnectDetachedForTest(
                        () -> {
                            senderAdapter.setOnAfterDisconnectDetachedForTest(null);
                            detachedALatch.countDown();
                            try {
                                releaseCleanupALatch.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });

                CompletableFuture<CompletableFuture<Void>> disconnectACall =
                        CompletableFuture.supplyAsync(() -> senderAdapter.disconnect(sessionA.sessionId()));
                assertTrue(detachedALatch.await(5, TimeUnit.SECONDS));

                // Lập tức thiết lập Session B thật qua RelayServer + mutual TLS 1.3
                RelayConfig config = new RelayConfig(16, 8, 5000, 60000, 60000, 900000, 100);
                try (RelayServer realRelay =
                        new RelayServer(new InetSocketAddress("127.0.0.1", 0), config)) {
                    AtomicReference<SessionViewModel> receiverSessionBRef = new AtomicReference<>();
                    CountDownLatch receiverWaitingFp = new CountDownLatch(1);
                    receiverAdapter.registerListeners(
                            vm -> {
                                if (vm.state() == SessionState.WAITING_FOR_FINGERPRINT
                                        && !vm.sessionId().isBlank()) {
                                    receiverSessionBRef.set(vm);
                                    receiverWaitingFp.countDown();
                                }
                            },
                            null);

                    RelayTicketInfo ticketB =
                            receiverAdapter
                                    .createRelaySession("127.0.0.1", realRelay.port())
                                    .get(5, TimeUnit.SECONDS);
                    SessionViewModel senderSessionB =
                            senderAdapter
                                    .joinRelaySession("127.0.0.1", realRelay.port(), ticketB.pairingCode())
                                    .get(5, TimeUnit.SECONDS);
                    assertTrue(receiverWaitingFp.await(5, TimeUnit.SECONDS));

                    // Thả cleanup trễ của Session A ngay lúc Session B đang chuẩn bị bắt tay TLS
                    long startCloseANanos = System.nanoTime();
                    releaseCleanupALatch.countDown();
                    disconnectACall.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);

                    // Socket Session A đóng trong <= 750ms
                    assertSocketClosedWithin(sockA, startCloseANanos, CANCEL_DEADLINE_MS);

                    // Session B vẫn nguyên vẹn và bắt tay mutual TLS 1.3 đạt SECURE_READY thành công
                    CompletableFuture<SessionViewModel> receiverHandshake =
                            receiverAdapter.startTlsHandshake(
                                    receiverSessionBRef.get().sessionId(), senderAdapter.getLocalFingerprint());
                    CompletableFuture<SessionViewModel> senderHandshake =
                            senderAdapter.startTlsHandshake(
                                    senderSessionB.sessionId(), receiverAdapter.getLocalFingerprint());

                    assertEquals(
                            SessionState.SECURE_READY, receiverHandshake.get(10, TimeUnit.SECONDS).state());
                    assertEquals(
                            SessionState.SECURE_READY, senderHandshake.get(10, TimeUnit.SECONDS).state());
                }
            }
        } finally {
            releaseCleanupALatch.countDown();
            senderAdapter.setOnAfterDisconnectDetachedForTest(null);
        }
    }

    private static CompletableFuture<Socket> acceptAsync(ServerSocket serverSocket) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return serverSocket.accept();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static void assertSocketClosedWithin(
            Socket serverSideSocket, long startNanos, int deadlineMillis) throws IOException {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        int remainingMs = Math.max(50, deadlineMillis - (int) elapsedMs);
        serverSideSocket.setSoTimeout(remainingMs);
        try {
            int read = serverSideSocket.getInputStream().read();
            assertEquals(-1, read, "Socket client phải đóng sạch (EOF=-1 trên server)");
        } catch (SocketException reset) {
            // Connection reset by peer trên Windows cũng xác nhận phía client đã đóng socket
        }
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(
                totalMs <= deadlineMillis,
                "Socket phải đóng trong vòng " + deadlineMillis + "ms (thực tế: " + totalMs + "ms)");
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }
}
