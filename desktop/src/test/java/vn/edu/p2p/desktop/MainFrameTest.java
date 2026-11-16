package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.view.MainFrame;

class MainFrameTest {

    @Test
    void testMainFrameInitializationOnEdt() throws InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.initialize();
            MainFrame frame = new MainFrame();
            assertNotNull(frame.getDashboardPanel());
            assertNotNull(frame.getDevicesPanel());
            assertNotNull(frame.getTransfersPanel());
            assertNotNull(frame.getSecurityPanel());
            assertNotNull(frame.getSettingsPanel());
            assertNotNull(frame.getHeaderPanel());
            assertNotNull(frame.getStatusBarPanel());
            frame.dispose();
        });
    }
}
