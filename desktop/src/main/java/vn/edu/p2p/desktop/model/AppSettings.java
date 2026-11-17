package vn.edu.p2p.desktop.model;

import java.io.File;
import java.util.prefs.Preferences;
import vn.edu.p2p.desktop.util.ThemeManager.ThemeMode;
import vn.edu.p2p.desktop.util.ValidationUtils;

/**
 * Quản lý cấu hình cục bộ của ứng dụng (Thư mục tải về, Theme).
 * Lưu trữ bằng Java Preferences API tiêu chuẩn, tuyệt đối không lưu secrets hay private keys.
 */
public final class AppSettings {

    private static final String PREF_KEY_DOWNLOAD_DIR = "download_dir";
    private static final String PREF_KEY_THEME_MODE = "theme_mode";

    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(AppSettings.class);

    private static String downloadDirectory;
    private static ThemeMode themeMode;

    static {
        loadSettings();
    }

    private AppSettings() {}

    private static void loadSettings() {
        String defaultDir = System.getProperty("user.home") + File.separator + "Downloads";
        String savedDir = PREFERENCES.get(PREF_KEY_DOWNLOAD_DIR, defaultDir);

        File dirFile = new File(savedDir);
        if (ValidationUtils.isValidWritableDirectory(dirFile)) {
            downloadDirectory = dirFile.getAbsolutePath();
        } else {
            downloadDirectory = defaultDir;
        }

        String savedTheme = PREFERENCES.get(PREF_KEY_THEME_MODE, ThemeMode.DARK.name());
        try {
            themeMode = ThemeMode.valueOf(savedTheme);
        } catch (IllegalArgumentException e) {
            themeMode = ThemeMode.DARK;
        }
    }

    public static String getDownloadDirectory() {
        return downloadDirectory;
    }

    public static boolean setDownloadDirectory(String newDir) {
        if (newDir == null) {
            return false;
        }
        File file = new File(newDir.trim());
        if (ValidationUtils.isValidWritableDirectory(file)) {
            downloadDirectory = file.getAbsolutePath();
            PREFERENCES.put(PREF_KEY_DOWNLOAD_DIR, downloadDirectory);
            return true;
        }
        return false;
    }

    public static ThemeMode getThemeMode() {
        return themeMode;
    }

    public static void setThemeMode(ThemeMode mode) {
        if (mode != null) {
            themeMode = mode;
            PREFERENCES.put(PREF_KEY_THEME_MODE, mode.name());
        }
    }
}
