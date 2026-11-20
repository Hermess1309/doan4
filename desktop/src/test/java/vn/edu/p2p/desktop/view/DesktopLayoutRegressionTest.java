package vn.edu.p2p.desktop.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferDirection;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferState;
import vn.edu.p2p.desktop.view.screens.DevicesPanel;
import vn.edu.p2p.desktop.view.screens.TransfersPanel;

/**
 * Kiểm thử hồi quy bố cục Swing (chạy được cả headless trên Linux CI và desktop trên Windows):
 * đảm bảo TransfersPanel, DevicesPanel và SidebarPanel không bị tràn/ép nhỏ ở mọi kích thước cửa sổ.
 */
class DesktopLayoutRegressionTest {

    @Test
    void testTransfersPanelProgressBarAndControlsStayWithinBoundsAcrossWindowSizes() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    TransfersPanel panel = new TransfersPanel();
                    JProgressBar bar = panel.getProgressBar();
                    assertTrue(
                            bar.getPreferredSize().width > 0 && bar.getPreferredSize().width < 10_000,
                            "Preferred width của progress bar không được dùng Integer.MAX_VALUE trong GridBagLayout");

                    panel.updateTransferProgress(
                            new TransferViewModel(
                                    "tx-1",
                                    "demo-10mib.bin",
                                    10_485_760L,
                                    5_242_880L,
                                    12_500_000.0,
                                    TransferDirection.UPLOAD,
                                    TransferState.TRANSFERRING,
                                    "",
                                    "session-1",
                                    1L));

                    int[][] contentSizes = {{610, 460}, {770, 560}, {1230, 820}};
                    int prevBarWidth = 0;
                    for (int[] sz : contentSizes) {
                        JPanel host = new JPanel(new BorderLayout());
                        host.add(panel, BorderLayout.CENTER);
                        layoutRecursively(host, sz[0], sz[1]);

                        assertTrue(
                                bar.getWidth() >= 300 && bar.getWidth() <= sz[0],
                                "Progress bar phải co giãn hợp lý trong khung " + sz[0] + "px (thực tế: " + bar.getWidth() + ")");
                        assertTrue(
                                bar.getWidth() >= prevBarWidth,
                                "Progress bar phải mở rộng khi phóng to cửa sổ");
                        prevBarWidth = bar.getWidth();

                        for (JLabel label : findAll(panel, JLabel.class)) {
                            if (label.getText() == null || label.getText().isBlank()) {
                                continue;
                            }
                            Point p = SwingUtilities.convertPoint(label.getParent(), label.getLocation(), panel);
                            assertTrue(
                                    p.x >= 0 && p.x + label.getWidth() <= sz[0] + 2,
                                    "Nhãn '" + label.getText() + "' bị tràn ngang ở kích thước " + sz[0] + "x" + sz[1]);
                        }
                        for (JButton btn : findAll(panel, JButton.class)) {
                            Point p = SwingUtilities.convertPoint(btn.getParent(), btn.getLocation(), panel);
                            assertTrue(
                                    btn.getWidth() > 40 && p.x >= 0 && p.x + btn.getWidth() <= sz[0] + 2,
                                    "Nút '" + btn.getText() + "' bị đẩy khỏi khung ở kích thước " + sz[0] + "x" + sz[1]);
                        }
                    }
                });
    }

    @Test
    void testDevicesPanelInputsNeverCollapseAndStayUsableAcrossWindowSizes() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    DevicesPanel panel = new DevicesPanel();
                    int[][] contentSizes = {{610, 460}, {770, 560}, {1230, 820}};
                    for (int[] sz : contentSizes) {
                        JPanel host = new JPanel(new BorderLayout());
                        host.add(panel, BorderLayout.CENTER);
                        layoutRecursively(host, sz[0], sz[1]);

                        List<JTextField> fields = findAll(panel, JTextField.class);
                        // 0: txtHostIp, 1: txtPort, 2: txtRelayHost, 3: txtRelayPort, 4: txtGeneratedCode, 5: txtPairingCode
                        assertTrue(fields.size() >= 6, "DevicesPanel phải có đủ 6 ô JTextField");
                        int[] primaryIndices = {0, 2, 4, 5};
                        for (int idx : primaryIndices) {
                            JTextField tf = fields.get(idx);
                            assertTrue(
                                    tf.getWidth() >= 140,
                                    "Ô nhập liệu #" + idx + " bị co quá nhỏ ở " + sz[0] + "x" + sz[1]
                                            + " (width=" + tf.getWidth() + ")");
                        }
                        assertTrue(fields.get(1).getWidth() >= 60, "Ô cổng LAN bị co quá nhỏ");
                        assertTrue(fields.get(3).getWidth() >= 60, "Ô cổng Relay bị co quá nhỏ");
                        assertTrue(
                                fields.get(1).getY() <= 6 && fields.get(3).getY() <= 6,
                                "Ô cổng LAN/Relay không được rớt dòng dọc trong hàng ở " + sz[0] + "x" + sz[1]);

                        for (JButton btn : findAll(panel, JButton.class)) {
                            if (btn.getText() == null || btn.getText().isBlank()) {
                                continue;
                            }
                            Point p = SwingUtilities.convertPoint(btn.getParent(), btn.getLocation(), panel);
                            assertTrue(
                                    btn.getWidth() > 50 && p.x >= 0 && p.x + btn.getWidth() <= sz[0] + 2,
                                    "Nút '" + btn.getText() + "' bị tràn ngang ở " + sz[0] + "x" + sz[1]
                                            + " (right=" + (p.x + btn.getWidth()) + ", container=" + sz[0] + ")");
                        }
                    }
                });
    }

    @Test
    void testSidebarPanelShowsAccurateCoreM3RelayM6Label() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    SidebarPanel sidebar = new SidebarPanel();
                    List<String> labelTexts =
                            findAll(sidebar, JLabel.class).stream()
                                    .map(JLabel::getText)
                                    .filter(t -> t != null)
                                    .toList();
                    assertTrue(
                            labelTexts.stream().anyMatch(t -> t.contains("Core M3 · Relay M6")),
                            "Sidebar phải hiển thị nhãn 'Core M3 · Relay M6'");
                    assertFalse(
                            labelTexts.stream().anyMatch(t -> t.contains("M0 Scaffold")),
                            "Sidebar không được giữ nhãn cũ 'M0 Scaffold'");
                });
    }

    private static void layoutRecursively(Container root, int width, int height) {
        root.setSize(width, height);
        validateTree(root);
    }

    private static void validateTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container sub) {
                validateTree(sub);
            }
        }
    }

    private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Component> void collect(Container c, Class<T> type, List<T> out) {
        for (Component child : c.getComponents()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            if (child instanceof Container sub) {
                collect(sub, type, out);
            }
        }
    }
}
