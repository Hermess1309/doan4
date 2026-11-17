package vn.edu.p2p.desktop.adapter;

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
