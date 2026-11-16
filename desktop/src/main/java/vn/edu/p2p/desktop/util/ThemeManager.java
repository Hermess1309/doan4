package vn.edu.p2p.desktop.util;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Window;
import javax.swing.SwingUtilities;

/**
 * Quản lý chủ đề FlatLaf (Dark / Light) và cập nhật giao diện thời gian thực.
 */
public final class ThemeManager {

    public enum ThemeMode {
        DARK("Giao diện tối (Dark)"),
        LIGHT("Giao diện sáng (Light)");

        private final String displayName;

        ThemeMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static ThemeMode currentMode = ThemeMode.DARK;

    private ThemeManager() {}

    /**
     * Khởi tạo theme mặc định khi ứng dụng khởi động.
     */
    public static void initialize() {
        FlatDarkLaf.setup();
        currentMode = ThemeMode.DARK;
    }

    /**
     * Chuyển đổi theme và cập nhật toàn bộ component trên cây giao diện.
     */
    public static void setTheme(ThemeMode mode, Component rootComponent) {
        if (mode == currentMode) {
            return;
        }
        currentMode = mode;
        SwingUtilities.invokeLater(() -> {
            if (mode == ThemeMode.DARK) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
            FlatLaf.updateUI();
            if (rootComponent != null) {
                Window window = SwingUtilities.getWindowAncestor(rootComponent);
                if (window != null) {
                    SwingUtilities.updateComponentTreeUI(window);
                } else if (rootComponent instanceof Window win) {
                    SwingUtilities.updateComponentTreeUI(win);
                }
            }
        });
    }

    public static ThemeMode getCurrentMode() {
        return currentMode;
    }

    public static boolean isDark() {
        return currentMode == ThemeMode.DARK;
    }
}
