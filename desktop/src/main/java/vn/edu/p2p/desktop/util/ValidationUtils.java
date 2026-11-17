package vn.edu.p2p.desktop.util;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Tiện ích kiểm tra tính hợp lệ của dữ liệu đầu vào giao diện.
 * Toàn bộ các phương thức kiểm tra cú pháp thuần túy, tuyệt đối không thực hiện
 * tác vụ mạng (DNS, Socket) gây chặn Event Dispatch Thread (EDT).
 */
public final class ValidationUtils {

    // Regex kiểm tra IPv4: 4 octet từ 0-255
    private static final String IPV4_SEGMENT = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^" + IPV4_SEGMENT + "\\." + IPV4_SEGMENT + "\\." + IPV4_SEGMENT + "\\." + IPV4_SEGMENT + "$");

    // Regex kiểm tra Hostname theo RFC 1123 (nhãn 1-63 ký tự, không bắt đầu/kết thúc bằng dấu gạch ngang)
    private static final Pattern HOSTNAME_LABEL_PATTERN = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");

    // Regex kiểm tra mã ghép nối Relay (Pairing Code) tương thích ngược UI-02
    private static final Pattern PAIRING_CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9]{6,12}$|^[a-zA-Z0-9]{3,6}-[a-zA-Z0-9]{3,6}$");

    // Chuẩn mã ghép nối thực tế của giao thức Relay M6 (RelayProtocol): đúng 12 ký tự từ bảng 32 ký tự không gây nhầm lẫn
    public static final int RELAY_CODE_LENGTH = 12;
    public static final String RELAY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    // Regex kiểm tra SHA-256 fingerprint: đủ 64 ký tự hex hoặc 32 cặp hex phân tách bằng dấu hai chấm (32 bytes)
    private static final Pattern FINGERPRINT_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern FINGERPRINT_COLON_PATTERN = Pattern.compile("^([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}$");

    private ValidationUtils() {}

    /**
     * Kiểm tra địa chỉ Hostname hoặc IPv4 hợp lệ về mặt cú pháp.
     */
    public static boolean isValidHostOrIp(String host) {
        if (host == null) {
            return false;
        }
        String trimmed = host.trim();
        if (trimmed.isEmpty() || trimmed.length() > 253) {
            return false;
        }

        // 1. Kiểm tra IPv4 hợp lệ
        if (IPV4_PATTERN.matcher(trimmed).matches()) {
            return true;
        }

        // Nếu chuỗi chứa các nhãn toàn là số, nó phải là IPv4 hợp lệ; nếu không khớp IPv4 ở trên thì loại bỏ
        String[] labels = trimmed.split("\\.", -1);
        boolean allNumeric = true;
        for (String label : labels) {
            if (!label.matches("^[0-9]+$")) {
                allNumeric = false;
                break;
            }
        }
        if (allNumeric) {
            return false;
        }

        // Trong tên miền phân cấp (nhiều hơn 1 nhãn), TLD (nhãn cuối) không được là số thuần túy
        if (labels.length > 1 && labels[labels.length - 1].matches("^[0-9]+$")) {
            return false;
        }

        // 2. Kiểm tra Hostname theo RFC 1123 (ví dụ: localhost, laptop.local, my-pc)
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                return false;
            }
            if (!HOSTNAME_LABEL_PATTERN.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Kiểm tra cổng mạng hợp lệ (từ 1 đến 65535).
     */
    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /**
     * Kiểm tra cổng mạng hợp lệ (từ 1 đến 65535).
     */
    public static boolean isValidPort(String portStr) {
        if (portStr == null) {
            return false;
        }
        String trimmed = portStr.trim();
        if (trimmed.isEmpty() || !trimmed.matches("^[0-9]{1,5}$")) {
            return false;
        }
        try {
            int port = Integer.parseInt(trimmed);
            return isValidPort(port);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Kiểm tra mã ghép nối Relay có đúng định dạng quy định hay không.
     * Ví dụ hợp lệ: "782901", "782-901", "ABC-123", "a1b2c3d4".
     */
    public static boolean isValidPairingCode(String code) {
        if (code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty() || trimmed.length() < 6 || trimmed.length() > 13) {
            return false;
        }
        return PAIRING_CODE_PATTERN.matcher(trimmed).matches();
    }

    /**
     * Kiểm tra mã ghép nối Relay thực tế theo đặc tả M6 (đúng 12 ký tự từ bảng ABCDEFGHJKLMNPQRSTUVWXYZ23456789).
     */
    public static boolean isValidRelayPairingCode(String code) {
        if (code == null) {
            return false;
        }
        String normalized = code.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalized.length() != RELAY_CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (RELAY_CODE_ALPHABET.indexOf(normalized.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Chuẩn hóa mã ghép nối Relay M6 sang chữ hoa 12 ký tự. Trả về chuỗi rỗng nếu không hợp lệ.
     */
    public static String canonicalizeRelayPairingCode(String code) {
        if (!isValidRelayPairingCode(code)) {
            return "";
        }
        return code.strip().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Kiểm tra chuỗi SHA-256 certificate fingerprint có đúng chuẩn 32 byte (256 bit) hay không.
     * Chấp nhận dạng 64 ký tự hex liên tiếp hoặc 32 cặp hex phân tách bằng dấu hai chấm.
     * Khớp 1:1 với bộ phân tích cú pháp của vn.edu.p2p.crypto.Fingerprint trong M2.
     */
    public static boolean isValidFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        String stripped = fingerprint.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        return FINGERPRINT_HEX_PATTERN.matcher(stripped).matches()
                || FINGERPRINT_COLON_PATTERN.matcher(stripped).matches();
    }

    /**
     * Chuẩn hóa chuỗi vân tay về dạng 64 ký tự hex viết hoa (không chứa dấu hai chấm).
     * Trả về chuỗi rỗng nếu không hợp lệ.
     */
    public static String canonicalizeFingerprint(String fingerprint) {
        if (!isValidFingerprint(fingerprint)) {
            return "";
        }
        return fingerprint.strip().replace(":", "").toUpperCase();
    }

    /**
     * Kiểm tra thư mục có tồn tại và ứng dụng có quyền ghi hay không.
     */
    public static boolean isValidWritableDirectory(File directory) {
        if (directory == null) {
            return false;
        }
        return directory.exists() && directory.isDirectory() && directory.canWrite();
    }

    /**
     * Kiểm tra tệp tin có tồn tại, là tệp thông thường và có thể đọc được hay không.
     */
    public static boolean isValidReadableFile(File file) {
        if (file == null) {
            return false;
        }
        return file.exists() && file.isFile() && file.canRead();
    }

    /**
     * Chuẩn hóa tên tệp tin an toàn từ dữ liệu peer gửi tới (loại bỏ path traversal và ký tự cấm).
     */
    public static String sanitizeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "downloaded_file.bin";
        }
        String base = java.nio.file.Path.of(rawName).getFileName().toString();
        return base.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
