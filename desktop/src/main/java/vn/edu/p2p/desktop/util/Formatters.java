package vn.edu.p2p.desktop.util;

import java.text.DecimalFormat;

/**
 * Tiện ích định dạng dữ liệu kích thước, tốc độ và vân tay chứng chỉ.
 */
public final class Formatters {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.0");

    private Formatters() {}

    /**
     * Định dạng số byte thành chuỗi dễ đọc (B, KB, MB, GB).
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        int unitIndex = (int) (Math.log(bytes) / Math.log(1024));
        if (unitIndex >= UNITS.length) {
            unitIndex = UNITS.length - 1;
        }
        double value = bytes / Math.pow(1024, unitIndex);
        return DECIMAL_FORMAT.format(value) + " " + UNITS[unitIndex];
    }

    /**
     * Định dạng tốc độ truyền tải byte/giây sang MB/s hoặc KB/s.
     */
    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0.0 KB/s";
        }
        if (bytesPerSec < 1024 * 1024) {
            return DECIMAL_FORMAT.format(bytesPerSec / 1024.0) + " KB/s";
        }
        return DECIMAL_FORMAT.format(bytesPerSec / (1024.0 * 1024.0)) + " MB/s";
    }

    /**
     * Định dạng chuỗi SHA-256 fingerprint hex thành các cụm 2 byte viết hoa có dấu hai chấm.
     * Ví dụ: E4:7B:9A:12:...
     */
    public static String formatFingerprint(String rawHex) {
        if (rawHex == null || rawHex.isBlank()) {
            return "Chưa có thông tin chứng chỉ";
        }
        String clean = rawHex.replaceAll("[^a-fA-F0-9]", "").toUpperCase();
        if (clean.isEmpty()) {
            return "Chưa có thông tin chứng chỉ";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clean.length(); i += 2) {
            if (i > 0) {
                if (i % 16 == 0) {
                    sb.append("\n");
                } else if (i % 8 == 0) {
                    sb.append("   ");
                } else {
                    sb.append(":");
                }
            }
            int end = Math.min(i + 2, clean.length());
            sb.append(clean, i, end);
        }
        return sb.toString();
    }
}
