package vn.edu.p2p.desktop.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferDirection;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferState;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.view.MainFrame;

@Timeout(60)
class StaleSessionCallbackRegressionTest {

    @TempDir
    static Path identitiesDir;

    @TempDir
    Path workspaceDir;

    private static PeerNode aliceNode;
    private static PeerNode bob1Node;
    private static PeerNode bob2Node;

    private RealCoreApiAdapter aliceAdapter;
    private RealCoreApiAdapter bob1Adapter;
    private RealCoreApiAdapter bob2Adapter;
    private MainFrame aliceFrame;

    @BeforeAll
    static void initIdentities() throws Exception {
        aliceNode = LocalIdentityHelper.getOrCreateIdentity(identitiesDir.resolve("alice").resolve("alice.p12"));
        bob1Node = LocalIdentityHelper.getOrCreateIdentity(identitiesDir.resolve("bob1").resolve("bob1.p12"));
        bob2Node = LocalIdentityHelper.getOrCreateIdentity(identitiesDir.resolve("bob2").resolve("bob2.p12"));
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(),
                "Kiểm thử tương tác MainFrame trên Swing EDT yêu cầu display server");
        ThemeManager.initialize();
        aliceAdapter = RealCoreApiAdapter.create(aliceNode, 0);
        bob1Adapter = RealCoreApiAdapter.create(bob1Node, 0);
        bob2Adapter = RealCoreApiAdapter.create(bob2Node, 0);

        Path sampleFile = workspaceDir.resolve("demo.txt");
        Files.writeString(sampleFile, "test-content", StandardCharsets.UTF_8);

        SwingUtilities.invokeAndWait(
                () -> {
                    aliceFrame = new MainFrame(aliceAdapter);
                    aliceFrame.getDashboardPanel().setSelectedFile(sampleFile.toFile());
                });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (aliceFrame != null) {
            SwingUtilities.invokeAndWait(() -> aliceFrame.dispose());
        }
        if (aliceAdapter != null) {
            aliceAdapter.shutdown();
        }
        if (bob1Adapter != null) {
            bob1Adapter.shutdown();
        }
        if (bob2Adapter != null) {
            bob2Adapter.shutdown();
        }
    }

    @Test
    void testLateSecureReadyFromSessionACannotEnableSendOnUnverifiedSessionBAndSessionBVerifiesProperly()
            throws Exception {
        // 1. Session A kết nối tới Bob1
        SessionViewModel sessionA =
                aliceAdapter.connectDirect("127.0.0.1", bob1Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        long genA = sessionA.sessionGeneration();
        assertTrue(genA > 0);
        flushEdt();

        // 2. Đăng ký lắng nghe Session B trên Bob2
        AtomicReference<SessionViewModel> bob2SessionRef = new AtomicReference<>();
        CountDownLatch bob2ConnectedLatch = new CountDownLatch(1);
        bob2Adapter.registerListeners(
                vm -> {
                    if (vm.state() == SessionState.WAITING_FOR_FINGERPRINT) {
                        bob2SessionRef.set(vm);
                        bob2ConnectedLatch.countDown();
                    }
                },
                null);

        // 3. Session B thay thế Session A (chưa xác minh TLS)
        SessionViewModel sessionB =
                aliceAdapter.connectDirect("127.0.0.1", bob2Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        long genB = sessionB.sessionGeneration();
        assertTrue(genB > genA, "Session B phải có generation lớn hơn Session A");
        assertNotEquals(sessionA.sessionId(), sessionB.sessionId());
        assertTrue(bob2ConnectedLatch.await(10, TimeUnit.SECONDS));
        flushEdt();

        // 4. Giả lập callback SECURE_READY của Session A đến muộn (qua cả Adapter lẫn trực tiếp MainFrame)
        SessionViewModel lateSecureA =
                new SessionViewModel(
                        sessionA.sessionId(),
                        sessionA.remoteAddress(),
                        aliceAdapter.getLocalFingerprint(),
                        bob1Adapter.getLocalFingerprint(),
                        false,
                        SessionState.SECURE_READY,
                        "",
                        genA);
        aliceAdapter.dispatchSessionUpdate(genA, lateSecureA);
        SwingUtilities.invokeAndWait(() -> aliceFrame.updateSession(lateSecureA));
        flushEdt();

        // 5. Xác nhận Session B vẫn chưa xác minh và nút Send BẮT BUỘC vẫn disabled
        SwingUtilities.invokeAndWait(
                () -> {
                    assertEquals(sessionB.sessionId(), aliceFrame.getCurrentSession().sessionId());
                    assertEquals(SessionState.WAITING_FOR_FINGERPRINT, aliceFrame.getCurrentSession().state());
                    assertFalse(
                            aliceFrame.getDashboardPanel().getBtnSend().isEnabled(),
                            "Callback SECURE_READY đến muộn của Session A TUYỆT ĐỐI không được bật nút Send cho Session B");
                });

        // 6. Chỉ khi chính Session B bắt tay TLS 1.3 đúng fingerprint với Bob2 thì mới đạt SECURE_READY và bật Send
        CompletableFuture<SessionViewModel> aliceVerify =
                aliceAdapter.startTlsHandshake(sessionB.sessionId(), bob2Adapter.getLocalFingerprint());
        CompletableFuture<SessionViewModel> bob2Verify =
                bob2Adapter.startTlsHandshake(
                        bob2SessionRef.get().sessionId(), aliceAdapter.getLocalFingerprint());

        SessionViewModel verifiedB = aliceVerify.get(15, TimeUnit.SECONDS);
        bob2Verify.get(15, TimeUnit.SECONDS);
        assertEquals(SessionState.SECURE_READY, verifiedB.state());
        assertEquals(genB, verifiedB.sessionGeneration());
        flushEdt();

        SwingUtilities.invokeAndWait(
                () -> {
                    assertEquals(sessionB.sessionId(), aliceFrame.getCurrentSession().sessionId());
                    assertEquals(SessionState.SECURE_READY, aliceFrame.getCurrentSession().state());
                    assertTrue(
                            aliceFrame.getDashboardPanel().getBtnSend().isEnabled(),
                            "Khi chính Session B xác thực TLS thành công và đã chọn file, nút Send phải bật");
                });
    }

    @Test
    void testLateErrorAndTransferCallbacksFromSessionADoNotAffectSessionB() throws Exception {
        SessionViewModel sessionA =
                aliceAdapter.connectDirect("127.0.0.1", bob1Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        long genA = sessionA.sessionGeneration();
        flushEdt();

        // Session B thay thế Session A
        SessionViewModel sessionB =
                aliceAdapter.connectDirect("127.0.0.1", bob2Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        long genB = sessionB.sessionGeneration();
        assertTrue(genB > genA);
        flushEdt();

        // Callback ERROR đến muộn của Session A không được làm Session B bị lỗi
        SessionViewModel staleErrorA =
                new SessionViewModel(
                        sessionA.sessionId(),
                        sessionA.remoteAddress(),
                        aliceAdapter.getLocalFingerprint(),
                        "",
                        false,
                        SessionState.ERROR,
                        "Old Session A handshake failed",
                        genA);
        aliceAdapter.dispatchSessionUpdate(genA, staleErrorA);
        SwingUtilities.invokeAndWait(() -> aliceFrame.updateSession(staleErrorA));

        // Callback TRANSFER / OFFER / CANCEL đến muộn của Session A không được sửa tiến trình Session B
        TransferViewModel staleTransferA =
                new TransferViewModel(
                        "tx-old-a",
                        "old-secret.zip",
                        1024L,
                        512L,
                        256.0,
                        TransferDirection.UPLOAD,
                        TransferState.TRANSFERRING,
                        "",
                        sessionA.sessionId(),
                        genA);
        aliceAdapter.dispatchTransferUpdate(genA, staleTransferA);
        SwingUtilities.invokeAndWait(() -> aliceFrame.updateTransfer(staleTransferA));
        flushEdt();

        SwingUtilities.invokeAndWait(
                () -> {
                    assertEquals(sessionB.sessionId(), aliceFrame.getCurrentSession().sessionId());
                    assertEquals(SessionState.WAITING_FOR_FINGERPRINT, aliceFrame.getCurrentSession().state());
                    assertEquals(
                            TransferState.IDLE,
                            aliceFrame.getCurrentTransfer().state(),
                            "Callback transfer của Session A không được ghi đè transfer của Session B");
                    assertNotEquals("old-secret.zip", aliceFrame.getCurrentTransfer().fileName());
                });
    }

    @Test
    void testQueuedEdtCallbackEnqueuedBeforeSessionSwitchIsDroppedWhenExecuted() throws Exception {
        SessionViewModel sessionA =
                aliceAdapter.connectDirect("127.0.0.1", bob1Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        long genA = sessionA.sessionGeneration();
        flushEdt();

        // Tạm giữ EDT để xếp hàng callback SECURE_READY của Session A TRƯỚC KHI chuyển sang Session B
        CountDownLatch edtPaused = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(
                () -> {
                    edtPaused.countDown();
                    try {
                        releaseEdt.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(edtPaused.await(5, TimeUnit.SECONDS), "EDT phải tạm dừng đúng điểm kiểm thử");

        SessionViewModel queuedSecureA =
                new SessionViewModel(
                        sessionA.sessionId(),
                        sessionA.remoteAddress(),
                        aliceAdapter.getLocalFingerprint(),
                        bob1Adapter.getLocalFingerprint(),
                        false,
                        SessionState.SECURE_READY,
                        "",
                        genA);

        // Xếp hàng callback của Session A KHI genA VẪN ĐANG LÀ ACTIVE!
        aliceAdapter.dispatchSessionUpdate(genA, queuedSecureA);

        // Ngay sau đó (trước khi EDT kịp chạy callback trên), chuyển sang Session B
        SessionViewModel sessionB =
                aliceAdapter.connectDirect("127.0.0.1", bob2Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        assertTrue(sessionB.sessionGeneration() > genA);

        // Mở khóa EDT để toàn bộ hàng đợi EDT thực thi theo đúng thứ tự FIFO
        releaseEdt.countDown();
        flushEdt();

        // Callback của Session A xếp hàng trước nhưng chạy sau chuyển phiên PHẢI bị bỏ qua ngay trên EDT!
        SwingUtilities.invokeAndWait(
                () -> {
                    assertEquals(sessionB.sessionId(), aliceFrame.getCurrentSession().sessionId());
                    assertEquals(SessionState.WAITING_FOR_FINGERPRINT, aliceFrame.getCurrentSession().state());
                    assertFalse(
                            aliceFrame.getDashboardPanel().getBtnSend().isEnabled(),
                            "Callback xếp hàng trên EDT trước khi chuyển phiên phải bị bỏ qua khi chạy sau");
                });
    }

    @Test
    void testConcurrentInFlightHandshakeOfSessionACannotCorruptSupersededSessionB() throws Exception {
        SessionViewModel sessionA =
                aliceAdapter.connectDirect("127.0.0.1", bob1Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        flushEdt();

        // Bắt đầu handshake thật cho Session A (Bob1 chưa verify nên đang chờ trên socket)
        CompletableFuture<SessionViewModel> inFlightHandshakeA =
                aliceAdapter.startTlsHandshake(sessionA.sessionId(), bob1Adapter.getLocalFingerprint());

        // Trong lúc Session A đang handshake, chuyển sang kết nối Session B tới Bob2
        SessionViewModel sessionB =
                aliceAdapter.connectDirect("127.0.0.1", bob2Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        assertTrue(sessionB.sessionGeneration() > sessionA.sessionGeneration());

        // Đợi handshake Session A thất bại do socket A bị đóng khi chuyển phiên
        try {
            inFlightHandshakeA.get(10, TimeUnit.SECONDS);
        } catch (Exception expected) {
            // Đúng kỳ vọng: Session A bị hủy khi Session B thay thế
        }
        flushEdt();

        // Session B vẫn nguyên vẹn ở trạng thái WAITING_FOR_FINGERPRINT, không bị ERROR và không bật Send
        SwingUtilities.invokeAndWait(
                () -> {
                    assertEquals(sessionB.sessionId(), aliceFrame.getCurrentSession().sessionId());
                    assertEquals(SessionState.WAITING_FOR_FINGERPRINT, aliceFrame.getCurrentSession().state());
                    assertFalse(aliceFrame.getDashboardPanel().getBtnSend().isEnabled());
                });
    }

    @Test
    void testShutdownPreventsQueuedCallbacksFromMutatingUi() throws Exception {
        SessionViewModel sessionA =
                aliceAdapter.connectDirect("127.0.0.1", bob1Adapter.getListenPort()).get(10, TimeUnit.SECONDS);
        long genA = sessionA.sessionGeneration();
        flushEdt();

        CountDownLatch edtPaused = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(
                () -> {
                    edtPaused.countDown();
                    try {
                        releaseEdt.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(edtPaused.await(5, TimeUnit.SECONDS));

        SessionViewModel queuedSecureA =
                new SessionViewModel(
                        sessionA.sessionId(),
                        sessionA.remoteAddress(),
                        aliceAdapter.getLocalFingerprint(),
                        bob1Adapter.getLocalFingerprint(),
                        false,
                        SessionState.SECURE_READY,
                        "",
                        genA);
        aliceAdapter.dispatchSessionUpdate(genA, queuedSecureA);
        SwingUtilities.invokeLater(() -> aliceFrame.updateSession(queuedSecureA));

        // Shutdown trước khi callback kịp chạy trên EDT
        aliceFrame.shutdownResources();
        assertTrue(aliceAdapter.isShutdown());

        releaseEdt.countDown();
        flushEdt();

        SwingUtilities.invokeAndWait(
                () -> {
                    assertNotNull(aliceFrame.getCurrentSession());
                    assertNotEquals(
                            SessionState.SECURE_READY,
                            aliceFrame.getCurrentSession().state(),
                            "Sau shutdown, callback xếp hàng không được phép làm đổi trạng thái UI sang SECURE_READY");
                    assertFalse(aliceFrame.getDashboardPanel().getBtnSend().isEnabled());
                });
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }
}
