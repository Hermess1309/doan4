package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.desktop.adapter.DummyCoreApiAdapter;
import vn.edu.p2p.desktop.adapter.LocalIdentityHelper;
import vn.edu.p2p.desktop.adapter.RealCoreApiAdapter;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.view.MainFrame;
import vn.edu.p2p.desktop.view.MainFrame.BackendState;

class DesktopStartupRegressionTest {

    private static final String DUMMY_FAKE_FINGERPRINT_PREFIX = "E47B";

    @Test
    void testStartupRunsOffEdtShowsInitializingAndTransitionsToReady(@TempDir Path tempDir)
            throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        ThemeManager.initialize();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean ranOnEdt = new AtomicBoolean(true);
        AtomicReference<DesktopApplication.StartupHandle> handleRef = new AtomicReference<>();
        AtomicReference<RealCoreApiAdapter> createdAdapterRef = new AtomicReference<>();

        Path storePath = tempDir.resolve("startup-id").resolve("startup-ok.p12");

        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        DesktopApplication.StartupHandle handle =
                                DesktopApplication.startProductionOnEdt(
                                        () -> {
                                            ranOnEdt.set(SwingUtilities.isEventDispatchThread());
                                            workerStarted.countDown();
                                            assertTrue(
                                                    releaseWorker.await(10, TimeUnit.SECONDS),
                                                    "Worker phải được mở khóa trong giới hạn timeout");
                                            PeerNode node = LocalIdentityHelper.getOrCreateIdentity(storePath);
                                            RealCoreApiAdapter adapter = RealCoreApiAdapter.create(node, 0);
                                            createdAdapterRef.set(adapter);
                                            return adapter;
                                        },
                                        false,
                                        false,
                                        null);
                        handleRef.set(handle);
                    });

            assertTrue(
                    workerStarted.await(5, TimeUnit.SECONDS),
                    "Tác vụ khởi tạo backend phải chạy trên background thread trong khi EDT vẫn tự do");
            assertFalse(ranOnEdt.get(), "Tạo identity và khởi tạo adapter TUYỆT ĐỐI không được chạy trên Swing EDT");

            // Trong lúc worker đang chạy, kiểm tra ngay trên EDT: không bị block, đang ở trạng thái INITIALIZING
            SwingUtilities.invokeAndWait(
                    () -> {
                        MainFrame frame = handleRef.get().frame();
                        assertEquals(BackendState.INITIALIZING, frame.getBackendState());
                        assertNull(frame.getAttachedAdapter());
                        assertTrue(
                                frame.getStatusBarPanel().getEngineStatusText().contains("Đang khởi tạo ứng dụng"),
                                "Status bar phải hiển thị 'Đang khởi tạo ứng dụng...'");
                        assertFalse(
                                frame.getDevicesPanel().getBtnConnectLan().isEnabled(),
                                "Nút kết nối LAN phải bị khóa khi đang khởi tạo");
                        assertFalse(
                                frame.getDashboardPanel().getBtnSend().isEnabled(),
                                "Nút gửi file phải bị khóa khi đang khởi tạo");
                        assertFalse(
                                frame.getSecurityPanel()
                                        .getTxtLocalFingerprint()
                                        .getText()
                                        .contains(DUMMY_FAKE_FINGERPRINT_PREFIX),
                                "Không được hiển thị fingerprint giả trong lúc khởi tạo");
                    });

            // Cho worker hoàn tất
            releaseWorker.countDown();
            handleRef.get().initFuture().get(20, TimeUnit.SECONDS);

            // Kiểm tra chuyển sang READY trên EDT
            SwingUtilities.invokeAndWait(
                    () -> {
                        MainFrame frame = handleRef.get().frame();
                        assertEquals(BackendState.READY, frame.getBackendState());
                        assertNotNull(frame.getAttachedAdapter());
                        assertTrue(frame.getAttachedAdapter() instanceof RealCoreApiAdapter);
                        assertTrue(
                                frame.getDevicesPanel().getBtnConnectLan().isEnabled(),
                                "Khi backend READY mới cho phép kết nối LAN");
                        assertFalse(
                                frame.getDashboardPanel().getBtnSend().isEnabled(),
                                "Nút gửi file vẫn phải tắt khi chưa kết nối và chưa SECURE_READY");
                        assertTrue(
                                frame.getStatusBarPanel()
                                        .getLocalInfoText()
                                        .contains(String.valueOf(createdAdapterRef.get().getListenPort())));
                    });
        } finally {
            releaseWorker.countDown();
            if (handleRef.get() != null) {
                SwingUtilities.invokeAndWait(() -> handleRef.get().frame().dispose());
                assertTrue(
                        handleRef.get().frame().isResourcesClosed(),
                        "dispose() phải đánh dấu đã đóng tài nguyên");
                assertTrue(
                        handleRef.get().startupExecutor().isShutdown(),
                        "Shutdown phải đóng startup executor");
            }
            if (createdAdapterRef.get() != null) {
                assertTrue(
                        createdAdapterRef.get().isShutdown(),
                        "dispose() cửa sổ phải đóng luôn RealCoreApiAdapter và listener");
            }
        }
    }

    @Test
    void testStartupFailureNeverFallsBackToDummyAndShowsNoFakeSecurity() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        ThemeManager.initialize();
        AtomicReference<DesktopApplication.StartupHandle> handleRef = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        DesktopApplication.StartupHandle handle =
                                DesktopApplication.startProductionOnEdt(
                                        () -> {
                                            throw new IOException("Simulated keystore permission denied");
                                        },
                                        false,
                                        false,
                                        null);
                        handleRef.set(handle);
                    });

            handleRef.get().initFuture().get(10, TimeUnit.SECONDS);

            SwingUtilities.invokeAndWait(
                    () -> {
                        MainFrame frame = handleRef.get().frame();
                        assertEquals(BackendState.FAILED, frame.getBackendState());
                        assertNull(
                                frame.getAttachedAdapter(),
                                "Khi khởi tạo lỗi tuyệt đối KHÔNG được fallback sang DummyCoreApiAdapter");
                        assertTrue(
                                frame.getInitializationError().contains("Simulated keystore permission denied"));
                        assertFalse(
                                frame.getDevicesPanel().getBtnConnectLan().isEnabled(),
                                "Chức năng kết nối mạng phải bị khóa khi khởi tạo lỗi");
                        assertFalse(
                                frame.getDevicesPanel().getBtnConnectRelay().isEnabled(),
                                "Chức năng Relay phải bị khóa khi khởi tạo lỗi");
                        assertFalse(
                                frame.getDashboardPanel().getBtnSend().isEnabled(),
                                "Chức năng gửi tệp phải bị khóa khi khởi tạo lỗi");
                        assertFalse(
                                frame.getSecurityPanel().getBtnOpenVerificationDialog().isEnabled(),
                                "Nút xác minh phải bị khóa khi khởi tạo lỗi");
                        assertFalse(
                                frame.getSecurityPanel().getBtnPreviewDialog().isEnabled(),
                                "Nút preview phải bị khóa khi khởi tạo lỗi để tránh hiển thị vân tay giả");

                        String displayedFp = frame.getSecurityPanel().getTxtLocalFingerprint().getText();
                        assertFalse(
                                displayedFp.contains(DUMMY_FAKE_FINGERPRINT_PREFIX),
                                "Không được hiển thị fingerprint giả khi khởi tạo thất bại");
                        String badge = frame.getSecurityPanel().getLblVerificationBadge().getText();
                        assertFalse(
                                badge.contains("SECURE_READY") || badge.contains("ĐÃ XÁC THỰC"),
                                "Không được hiển thị trạng thái bảo mật giả khi khởi tạo lỗi");
                    });
        } finally {
            if (handleRef.get() != null) {
                SwingUtilities.invokeAndWait(() -> handleRef.get().frame().dispose());
            }
        }
    }

    @Test
    void testMainFrameRejectsNullAndRejectsDummyInProductionMode() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(
                () -> {
                    assertThrows(NullPointerException.class, () -> new MainFrame(null));

                    MainFrame prodFrame = MainFrame.createProductionWindow();
                    try {
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> prodFrame.attachBackendAdapter(new DummyCoreApiAdapter()),
                                "Cửa sổ production phải từ chối DummyCoreApiAdapter");
                    } finally {
                        prodFrame.dispose();
                    }
                });
    }

    @Test
    void testConnectionErrorWithBlankSessionIdIsDisplayedInUi() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(
                () -> {
                    MainFrame frame = new MainFrame();
                    try {
                        SessionViewModel connectErr =
                                new SessionViewModel(
                                        "",
                                        "127.0.0.1:52999",
                                        "",
                                        "",
                                        false,
                                        SessionState.ERROR,
                                        "Kết nối TCP thất bại: Connection refused");
                        frame.updateSession(connectErr);

                        assertTrue(
                                frame.getStatusBarPanel().getEngineStatusText().contains("Connection refused"),
                                "Status bar phải hiển thị lỗi kết nối kể cả khi sessionId rỗng");
                        assertTrue(
                                frame.getDevicesPanel().getLblInputError().getText().contains("Connection refused"),
                                "DevicesPanel phải hiển thị lỗi kết nối");
                        assertTrue(
                                frame.getDashboardPanel().getLblSecurityStatus().getText().contains("Connection refused"),
                                "DashboardPanel phải hiển thị lỗi kết nối");
                        assertFalse(frame.getDashboardPanel().getBtnSend().isEnabled());
                    } finally {
                        frame.dispose();
                    }
                });
    }
}
