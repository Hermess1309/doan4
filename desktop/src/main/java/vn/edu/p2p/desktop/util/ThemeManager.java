package vn.edu.p2p.desktop.util;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Window;
import javax.swing.SwingUtilities;
import vn.edu.p2p.desktop.model.AppSettings;

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
     * Khởi tạo theme dựa theo cấu hình đã lưu trong AppSettings khi khởi động.
     */
    public static void initialize() {
        ThemeMode savedMode = AppSettings.getThemeMode();
        currentMode = (savedMode != null) ? savedMode : ThemeMode.DARK;
        if (currentMode == ThemeMode.DARK) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
    }

    /**
     * Chuyển đổi theme, lưu vào cấu hình và cập nhật toàn bộ cây component.
     */
    public static void setTheme(ThemeMode mode, Component rootComponent) {
        if (mode == currentMode) {
            return;
        }
        currentMode = mode;
        AppSettings.setThemeMode(mode);

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
