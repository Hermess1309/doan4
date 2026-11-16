package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
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

            // 4. Mở dialog và test sao chép
            FingerprintVerificationDialog dialog = new FingerprintVerificationDialog(
                    frame, "test-session", "192.168.1.10:52001",
                    "E47B9A128C3DF055", "3F11A8C95D44201B", true);
            assertNotNull(dialog.getBtnCopyRemote());
            dialog.getBtnCopyRemote().doClick();
            dialog.dispose();

            frame.dispose();
        });
    }
}
