package vn.edu.p2p.desktop;

import javax.swing.SwingUtilities;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.view.MainFrame;

/**
 * Điểm khởi chạy chính của ứng dụng P2P File Transfer Desktop.
 * Đảm bảo mọi tác vụ khởi tạo giao diện diễn ra trên Swing Event Dispatch Thread (EDT).
 */
public final class DesktopApplication {

    private DesktopApplication() {}

    public static void main(String[] args) {
        // 1. Cài đặt FlatLaf Look and Feel trước khi vẽ các component Swing
        ThemeManager.initialize();

        // 2. Khởi tạo MainFrame trên Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = new MainFrame();
            mainFrame.setVisible(true);
        });
    }
}
