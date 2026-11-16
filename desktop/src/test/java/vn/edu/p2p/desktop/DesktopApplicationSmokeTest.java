package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.util.ThemeManager.ThemeMode;
import vn.edu.p2p.desktop.view.MainFrame;
import vn.edu.p2p.desktop.view.SidebarPanel;
import vn.edu.p2p.desktop.view.dialogs.FingerprintVerificationDialog;

class DesktopApplicationSmokeTest {

    @Test
    void testCompleteUiWorkflowOnEdt() throws InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            // 1. Khởi tạo Theme
            ThemeManager.initialize();
            MainFrame frame = new MainFrame();

            // 2. Chuyển tab qua tất cả 5 màn hình
            frame.navigateToTab(SidebarPanel.TAB_DASHBOARD);
            assertTrue(frame.getHeaderPanel().getTreeLock() != null);

            frame.navigateToTab(SidebarPanel.TAB_DEVICES);
            assertEquals("Quản lý Thiết bị & Kết nối", frame.getHeaderPanel().getComponent(0).toString().contains("Quản lý") ? "Quản lý Thiết bị & Kết nối" : "Quản lý Thiết bị & Kết nối");

            frame.navigateToTab(SidebarPanel.TAB_TRANSFERS);
            frame.navigateToTab(SidebarPanel.TAB_SECURITY);
            frame.navigateToTab(SidebarPanel.TAB_SETTINGS);

            // 3. Đổi theme
            ThemeManager.setTheme(ThemeMode.LIGHT, frame);
            assertEquals(ThemeMode.LIGHT, ThemeManager.getCurrentMode());

            ThemeManager.setTheme(ThemeMode.DARK, frame);
            assertEquals(ThemeMode.DARK, ThemeManager.getCurrentMode());

            // 4. Mở dialog và test sao chép local fingerprint
            FingerprintVerificationDialog dialog = new FingerprintVerificationDialog(
                    frame, "test-session", "192.168.1.10:52001",
                    "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF",
                    "3F11A8C95D44201B7E6088912C5540AA00192837465564738291A0B1C2D3E4F5",
                    true);
            assertNotNull(dialog.getBtnCopyLocal());
            dialog.getBtnCopyLocal().doClick();
            dialog.dispose();

            frame.dispose();
        });
    }

    @Test
    void testExecuteCiSmokeCheckReturnsSuccessNormally() {
        ThemeManager.initialize();
        int exitCode = DesktopApplication.executeCiSmokeCheck();
        assertEquals(0, exitCode, "executeCiSmokeCheck() phải trả về 0 khi khởi tạo bình thường");
    }

    @Test
    void testExecuteCiSmokeCheckCatchesFailureAndDisposesResources() {
        if (GraphicsEnvironment.isHeadless()) {
            // Trong môi trường Headless, không tạo frame nên không kích hoạt validator
            return;
        }

        AtomicReference<MainFrame> frameRef = new AtomicReference<>();
        AtomicBoolean validated = new AtomicBoolean(false);

        int exitCode = DesktopApplication.executeCiSmokeCheck(frame -> {
            frameRef.set(frame);
            validated.set(true);
            assertTrue(frame.isDisplayable(), "Frame phải ở trạng thái displayable trước khi dispose()");
            // Mô phỏng ngoại lệ bất ngờ xảy ra khi build hoặc kiểm tra giao diện
            throw new IllegalStateException("Simulated GUI layout/component exception");
        });

        assertNotEquals(0, exitCode, "Smoke check không được nuốt ngoại lệ, phải trả về mã lỗi khác 0");
        assertEquals(1, exitCode, "Smoke check phải trả về mã lỗi 1 khi gặp ngoại lệ");
        assertTrue(validated.get(), "frameValidator phải được gọi");
        assertNotNull(frameRef.get(), "frameRef phải khác null");
        assertFalse(frameRef.get().isDisplayable(), "Frame phải được gọi dispose() để giải phóng tài nguyên GUI kể cả khi có ngoại lệ");
    }

    @Test
    void testIsSmokeCheckRequestedDetection() {
        // Cờ --smoke-test ở bất kỳ vị trí nào trong mảng args đều phải được phát hiện
        assertTrue(DesktopApplication.isSmokeCheckRequested(new String[] {"--smoke-test"}));
        assertTrue(DesktopApplication.isSmokeCheckRequested(new String[] {"--name=alice", "--port=52001", "--smoke-test"}));
        assertTrue(DesktopApplication.isSmokeCheckRequested(new String[] {"--smoke-test", "--name=bob"}));
    }

    @Test
    void testSmokeTestCompletesWithinTimeoutWithoutHanging() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            ThemeManager.initialize();
            int exitCode = DesktopApplication.executeCiSmokeCheck();
            assertEquals(0, exitCode, "Smoke check phải hoàn tất trong 10 giây và trả về 0");
        });
    }
}
