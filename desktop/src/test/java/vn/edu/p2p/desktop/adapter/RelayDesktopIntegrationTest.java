package vn.edu.p2p.desktop.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.desktop.model.RelayTicketInfo;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferState;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.util.ValidationUtils;
import vn.edu.p2p.desktop.view.MainFrame;
import vn.edu.p2p.relay.RelayConfig;
import vn.edu.p2p.relay.RelayServer;

@Timeout(90)
class RelayDesktopIntegrationTest {

    @TempDir
    static Path identitiesDir;

    @TempDir
    Path workspaceDir;

    private static PeerNode receiverNode;
    private static PeerNode senderNode;

    private RelayServer relayServer;
    private RealCoreApiAdapter receiverAdapter;
    private RealCoreApiAdapter senderAdapter;

    @BeforeAll
    static void generateIdentities() throws Exception {
        receiverNode =
                LocalIdentityHelper.getOrCreateIdentity(
                        identitiesDir.resolve("receiver-id").resolve("receiver.p12"));
        senderNode =
                LocalIdentityHelper.getOrCreateIdentity(
                        identitiesDir.resolve("sender-id").resolve("sender.p12"));
    }

    @BeforeEach
    void setUp() throws Exception {
        RelayConfig testConfig = new RelayConfig(64, 32, 5000, 120000, 60000, 900000, 200);
        relayServer = new RelayServer(new InetSocketAddress("127.0.0.1", 0), testConfig);
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
        if (relayServer != null) {
            relayServer.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 128, 262200})
    void testRelayCreateJoinMutualTlsAndFileTransfer(int fileSizeBytes) throws Exception {
        int relayPort = relayServer.port();
        assertTrue(relayPort > 0);

        AtomicReference<SessionViewModel> receiverPairedRef = new AtomicReference<>();
        CountDownLatch receiverPairedLatch = new CountDownLatch(1);
        receiverAdapter.registerListeners(
                vm -> {
                    if (vm.isRelay() && vm.state() == SessionState.WAITING_FOR_FINGERPRINT) {
                        receiverPairedRef.set(vm);
                        receiverPairedLatch.countDown();
                    }
                },
                null);

        // 1. Receiver CREATE mã ghép nối thật từ Relay
        RelayTicketInfo ticket =
                receiverAdapter.createRelaySession("127.0.0.1", relayPort).get(10, TimeUnit.SECONDS);
        assertNotNull(ticket);
        assertTrue(ValidationUtils.isValidRelayPairingCode(ticket.pairingCode()));
        assertTrue(ticket.ttlMillis() >= 1000);

        // 2. Sender JOIN bằng mã ghép nối thật
        SessionViewModel senderSession =
                senderAdapter
                        .joinRelaySession("127.0.0.1", relayPort, ticket.pairingCode())
                        .get(10, TimeUnit.SECONDS);
        assertTrue(senderSession.isRelay());
        assertEquals(SessionState.WAITING_FOR_FINGERPRINT, senderSession.state());

        assertTrue(
                receiverPairedLatch.await(10, TimeUnit.SECONDS),
                "Receiver phải chuyển sang WAITING_FOR_FINGERPRINT sau khi Sender JOIN");
        SessionViewModel receiverSession = receiverPairedRef.get();
        assertNotNull(receiverSession);
        assertTrue(receiverSession.isRelay());

        // 3. Hai bên xác minh mutual TLS 1.3 bằng fingerprint đối chiếu độc lập
        CompletableFuture<SessionViewModel> receiverVerify =
                receiverAdapter.startTlsHandshake(
                        receiverSession.sessionId(), senderAdapter.getLocalFingerprint());
        CompletableFuture<SessionViewModel> senderVerify =
                senderAdapter.startTlsHandshake(
                        senderSession.sessionId(), receiverAdapter.getLocalFingerprint());

        SessionViewModel receiverSecure = receiverVerify.get(15, TimeUnit.SECONDS);
        SessionViewModel senderSecure = senderVerify.get(15, TimeUnit.SECONDS);
        assertEquals(SessionState.SECURE_READY, receiverSecure.state());
        assertEquals(SessionState.SECURE_READY, senderSecure.state());
        assertTrue(receiverSecure.isRelay());
        assertTrue(senderSecure.isRelay());

        // 4. Tạo tệp nguồn (0 byte, file nhỏ, hoặc file nhiều chunk)
        Path sourceFile = workspaceDir.resolve("relay-src-" + fileSizeBytes + ".bin");
        byte[] payload = new byte[fileSizeBytes];
        if (fileSizeBytes > 0) {
            new SecureRandom().nextBytes(payload);
        }
        Files.write(sourceFile, payload);
        String expectedSha256 = sha256(sourceFile);

        Path destFile = workspaceDir.resolve("relay-dst-" + fileSizeBytes + ".bin");
        assertFalse(Files.exists(destFile));

        CountDownLatch completedLatch = new CountDownLatch(2);
        receiverAdapter.setIncomingOfferHandler(
                (offer, decisionCallback) -> {
                    assertEquals(sourceFile.getFileName().toString(), offer.fileName());
                    assertEquals(fileSizeBytes, offer.size());
                    assertEquals(expectedSha256, offer.sha256());
                    decisionCallback.accept(destFile.toFile());
                });

        senderAdapter.registerListeners(
                null,
                tvm -> {
                    if (tvm.state() == TransferState.COMPLETED) {
                        completedLatch.countDown();
                    }
                });
        receiverAdapter.registerListeners(
                null,
                tvm -> {
                    if (tvm.state() == TransferState.COMPLETED) {
                        completedLatch.countDown();
                    }
                });

        // 5. Gửi tệp thật qua Relay
        String transferId =
                senderAdapter.offerFile(senderSecure.sessionId(), sourceFile.toFile()).get(30, TimeUnit.SECONDS);
        assertNotNull(transferId);
        assertTrue(completedLatch.await(20, TimeUnit.SECONDS), "Cả 2 đầu phải hoàn tất truyền tải");

        // 6. Kiểm tra toàn vẹn SHA-256 và nội dung byte
        assertTrue(Files.exists(destFile));
        assertEquals(fileSizeBytes, Files.size(destFile));
        assertEquals(expectedSha256, sha256(destFile));
        assertArrayEquals(payload, Files.readAllBytes(destFile));
    }

    @Test
    void testRelayWrongCodeAndExpiredCodeFailSafely() throws Exception {
        // 1. Mã 12 ký tự không tồn tại -> JOIN thất bại sạch sẽ
        CompletableFuture<SessionViewModel> wrongJoin =
                senderAdapter.joinRelaySession("127.0.0.1", relayServer.port(), "AAAAAAAAAAAA");
        assertThrows(ExecutionException.class, () -> wrongJoin.get(10, TimeUnit.SECONDS));

        // 2. Mã hết hạn TTL -> Receiver hết hạn chờ và Sender JOIN thất bại
        RelayConfig shortTtlConfig = new RelayConfig(16, 8, 5000, 300, 60000, 900000, 100);
        try (RelayServer shortServer =
                new RelayServer(new InetSocketAddress("127.0.0.1", 0), shortTtlConfig)) {
            CountDownLatch errorLatch = new CountDownLatch(1);
            receiverAdapter.registerListeners(
                    vm -> {
                        if (vm.state() == SessionState.ERROR) {
                            errorLatch.countDown();
                        }
                    },
                    null);

            RelayTicketInfo expiredTicket =
                    receiverAdapter
                            .createRelaySession("127.0.0.1", shortServer.port())
                            .get(10, TimeUnit.SECONDS);
            assertNotNull(expiredTicket);

            assertTrue(
                    errorLatch.await(5, TimeUnit.SECONDS),
                    "Receiver phải báo ERROR khi mã ghép nối Relay hết hạn TTL");

            CompletableFuture<SessionViewModel> lateJoin =
                    senderAdapter.joinRelaySession(
                            "127.0.0.1", shortServer.port(), expiredTicket.pairingCode());
            assertThrows(ExecutionException.class, () -> lateJoin.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void testRelayCancelWhileWaitingForPeerClosesTicketCleanly() throws Exception {
        CountDownLatch waitingLatch = new CountDownLatch(1);
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        AtomicReference<SessionState> lastState = new AtomicReference<>();

        receiverAdapter.registerListeners(
                vm -> {
                    lastState.set(vm.state());
                    if (vm.state() == SessionState.WAITING_FOR_PEER) {
                        waitingLatch.countDown();
                    } else if (vm.state() == SessionState.DISCONNECTED && waitingLatch.getCount() == 0) {
                        disconnectedLatch.countDown();
                    }
                },
                null);

        RelayTicketInfo ticket =
                receiverAdapter
                        .createRelaySession("127.0.0.1", relayServer.port())
                        .get(10, TimeUnit.SECONDS);
        assertTrue(waitingLatch.await(5, TimeUnit.SECONDS));

        // Hủy khi đang chờ peer
        receiverAdapter.disconnect("").get(5, TimeUnit.SECONDS);
        assertTrue(
                disconnectedLatch.await(5, TimeUnit.SECONDS),
                "Hủy khi đang chờ peer phải chuyển về DISCONNECTED sạch sẽ");
        assertEquals(SessionState.DISCONNECTED, lastState.get());

        // Mã đã hủy không còn ghép nối được nữa (hoặc sẽ fail ngay do đầu receiver đã đóng)
        CompletableFuture<SessionViewModel> joinCancelled =
                senderAdapter.joinRelaySession("127.0.0.1", relayServer.port(), ticket.pairingCode());
        try {
            SessionViewModel paired = joinCancelled.get(5, TimeUnit.SECONDS);
            // Nếu relay chưa sweep kịp khi JOIN vào đúng mili-giây đó, handshake vẫn phải thất bại ngay
            CompletableFuture<SessionViewModel> verify =
                    senderAdapter.startTlsHandshake(
                            paired.sessionId(), receiverAdapter.getLocalFingerprint());
            assertThrows(ExecutionException.class, () -> verify.get(5, TimeUnit.SECONDS));
        } catch (ExecutionException expected) {
            // Đúng kỳ vọng: mã đã bị hủy/đóng
        }
    }

    @Test
    void testRelayWrongFingerprintRejectedBeforeAnyFileOffer() throws Exception {
        PairedRelaySessions paired = pairOverRelay();

        String wrongPin = "0000000000000000000000000000000000000000000000000000000000000000";
        CompletableFuture<SessionViewModel> receiverVerify =
                receiverAdapter.startTlsHandshake(
                        paired.receiver().sessionId(), senderAdapter.getLocalFingerprint());
        CompletableFuture<SessionViewModel> senderVerify =
                senderAdapter.startTlsHandshake(paired.sender().sessionId(), wrongPin);

        assertThrows(ExecutionException.class, () -> senderVerify.get(10, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> receiverVerify.get(10, TimeUnit.SECONDS));

        Path sample = workspaceDir.resolve("never-sent.txt");
        Files.writeString(sample, "secret", StandardCharsets.UTF_8);
        assertThrows(
                ExecutionException.class,
                () ->
                        senderAdapter
                                .offerFile(paired.sender().sessionId(), sample.toFile())
                                .get(5, TimeUnit.SECONDS));
    }

    @Test
    void testRelayReceiverRejectsOfferWithoutCreatingFile() throws Exception {
        PairedRelaySessions secure = pairAndVerifyOverRelay();

        Path sourceFile = workspaceDir.resolve("rejected-relay.txt");
        Files.writeString(sourceFile, "reject-me", StandardCharsets.UTF_8);
        Path destFile = workspaceDir.resolve("should-not-exist.txt");

        CountDownLatch rejectedLatch = new CountDownLatch(1);
        receiverAdapter.setIncomingOfferHandler((offer, decisionCallback) -> decisionCallback.accept(null));
        senderAdapter.registerListeners(
                null,
                tvm -> {
                    if (tvm.state() == TransferState.REJECTED) {
                        rejectedLatch.countDown();
                    }
                });

        assertThrows(
                ExecutionException.class,
                () ->
                        senderAdapter
                                .offerFile(secure.sender().sessionId(), sourceFile.toFile())
                                .get(15, TimeUnit.SECONDS));
        assertTrue(rejectedLatch.await(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(destFile), "Khi từ chối không được tạo tệp đích");
    }

    @Test
    void testRelayDoesNotOverwriteExistingDestinationFile() throws Exception {
        PairedRelaySessions secure = pairAndVerifyOverRelay();

        Path sourceFile = workspaceDir.resolve("overwrite-src.txt");
        Files.writeString(sourceFile, "new-data", StandardCharsets.UTF_8);
        Path existingDest = workspaceDir.resolve("existing-dest.txt");
        Files.writeString(existingDest, "original-protected-content", StandardCharsets.UTF_8);

        CountDownLatch offerReceived = new CountDownLatch(1);
        AtomicReference<Throwable> acceptFailure = new AtomicReference<>();
        receiverAdapter.setIncomingOfferHandler(
                (offer, decisionCallback) -> {
                    receiverAdapter
                            .acceptTransfer(offer.transferId().toString(), existingDest.toFile())
                            .whenComplete(
                                    (v, err) -> {
                                        acceptFailure.set(err);
                                        offerReceived.countDown();
                                    });
                });

        CompletableFuture<String> sendFuture =
                senderAdapter.offerFile(secure.sender().sessionId(), sourceFile.toFile());
        assertTrue(offerReceived.await(15, TimeUnit.SECONDS));
        assertNotNull(acceptFailure.get(), "acceptTransfer phải từ chối ghi đè tệp đã tồn tại");
        assertEquals(
                "original-protected-content", Files.readString(existingDest, StandardCharsets.UTF_8));

        // Đóng phiên gửi sạch sẽ sau khi receiver từ chối ghi đè
        receiverAdapter.disconnect(secure.receiver().sessionId()).get(5, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> sendFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    void testRelayAbruptPeerDisconnectFailsSafely() throws Exception {
        PairedRelaySessions secure = pairAndVerifyOverRelay();

        CountDownLatch receiverDisconnected = new CountDownLatch(1);
        receiverAdapter.registerListeners(
                vm -> {
                    if (vm.state() == SessionState.DISCONNECTED || vm.state() == SessionState.ERROR) {
                        receiverDisconnected.countDown();
                    }
                },
                null);

        // Sender ngắt đột ngột
        senderAdapter.shutdown();
        assertTrue(
                receiverDisconnected.await(10, TimeUnit.SECONDS),
                "Receiver phải phát hiện peer ngắt đột ngột qua Relay mà không treo");
    }

    @Test
    void testRelayStaleCallbackCannotEnableSendAndUiDisposeCleansResources() throws Exception {
        Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(),
                "Kiểm thử MainFrame trên Swing EDT yêu cầu display server");
        ThemeManager.initialize();

        Path sample = workspaceDir.resolve("ui-relay-sample.txt");
        Files.writeString(sample, "hello-relay-ui", StandardCharsets.UTF_8);

        AtomicReference<MainFrame> senderFrameRef = new AtomicReference<>();
        AtomicReference<MainFrame> receiverFrameRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        MainFrame sf = new MainFrame(senderAdapter);
                        sf.getDashboardPanel().setSelectedFile(sample.toFile());
                        senderFrameRef.set(sf);

                        MainFrame rf = new MainFrame(receiverAdapter);
                        receiverFrameRef.set(rf);
                    });

            // 1. Tạo Relay Session A
            PairedRelaySessions sessionA = pairOverRelay();
            long genA = sessionA.sender().sessionGeneration();
            flushEdt();

            // 2. Tạo Relay Session B (chưa xác minh TLS) thay thế Session A
            PairedRelaySessions sessionB = pairOverRelay();
            assertTrue(sessionB.sender().sessionGeneration() > genA);
            flushEdt();

            // 3. Callback SECURE_READY đến muộn của Session A không được bật nút Send cho Session B
            SessionViewModel staleSecureA =
                    new SessionViewModel(
                            sessionA.sender().sessionId(),
                            sessionA.sender().remoteAddress(),
                            senderAdapter.getLocalFingerprint(),
                            receiverAdapter.getLocalFingerprint(),
                            true,
                            SessionState.SECURE_READY,
                            "",
                            genA);
            senderAdapter.dispatchSessionUpdate(genA, staleSecureA);
            SwingUtilities.invokeAndWait(() -> senderFrameRef.get().updateSession(staleSecureA));
            flushEdt();

            SwingUtilities.invokeAndWait(
                    () -> {
                        assertEquals(
                                sessionB.sender().sessionId(),
                                senderFrameRef.get().getCurrentSession().sessionId());
                        assertEquals(
                                SessionState.WAITING_FOR_FINGERPRINT,
                                senderFrameRef.get().getCurrentSession().state());
                        assertFalse(
                                senderFrameRef.get().getDashboardPanel().getBtnSend().isEnabled(),
                                "Callback SECURE_READY cũ trên Relay không được bật Send cho Session B");
                    });

            // 4. Tạo pending Relay ticket rồi dispose MainFrame -> không rò rỉ socket/thread
            receiverAdapter
                    .createRelaySession("127.0.0.1", relayServer.port())
                    .get(10, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> receiverFrameRef.get().dispose());
            assertTrue(
                    receiverAdapter.isShutdown(),
                    "Đóng MainFrame phải đóng luôn adapter, pending ticket và worker pool");
        } finally {
            if (senderFrameRef.get() != null) {
                SwingUtilities.invokeAndWait(() -> senderFrameRef.get().dispose());
            }
            if (receiverFrameRef.get() != null) {
                SwingUtilities.invokeAndWait(() -> receiverFrameRef.get().dispose());
            }
        }
    }

    private record PairedRelaySessions(SessionViewModel receiver, SessionViewModel sender) {}

    private PairedRelaySessions pairOverRelay() throws Exception {
        AtomicReference<SessionViewModel> receiverSessionRef = new AtomicReference<>();
        CountDownLatch pairedLatch = new CountDownLatch(1);
        long prevReceiverGen = receiverAdapter.getActiveGeneration();

        receiverAdapter.registerListeners(
                vm -> {
                    if (vm.isRelay()
                            && vm.sessionGeneration() > prevReceiverGen
                            && vm.state() == SessionState.WAITING_FOR_FINGERPRINT) {
                        receiverSessionRef.set(vm);
                        pairedLatch.countDown();
                    }
                },
                null);

        RelayTicketInfo ticket =
                receiverAdapter
                        .createRelaySession("127.0.0.1", relayServer.port())
                        .get(10, TimeUnit.SECONDS);
        SessionViewModel senderSession =
                senderAdapter
                        .joinRelaySession("127.0.0.1", relayServer.port(), ticket.pairingCode())
                        .get(10, TimeUnit.SECONDS);
        assertTrue(pairedLatch.await(10, TimeUnit.SECONDS));
        return new PairedRelaySessions(receiverSessionRef.get(), senderSession);
    }

    private PairedRelaySessions pairAndVerifyOverRelay() throws Exception {
        PairedRelaySessions paired = pairOverRelay();
        CompletableFuture<SessionViewModel> rVerify =
                receiverAdapter.startTlsHandshake(
                        paired.receiver().sessionId(), senderAdapter.getLocalFingerprint());
        CompletableFuture<SessionViewModel> sVerify =
                senderAdapter.startTlsHandshake(
                        paired.sender().sessionId(), receiverAdapter.getLocalFingerprint());
        return new PairedRelaySessions(
                rVerify.get(15, TimeUnit.SECONDS), sVerify.get(15, TimeUnit.SECONDS));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().withUpperCase().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }
}
