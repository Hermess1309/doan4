package vn.edu.p2p.desktop.model;

import vn.edu.p2p.desktop.util.Formatters;

/**
 * ViewModel đại diện cho trạng thái hiển thị của một tác vụ truyền tải tệp tin.
 */
public record TransferViewModel(
        String transferId,
        String fileName,
        long totalBytes,
        long bytesTransferred,
        double speedBytesPerSec,
        TransferDirection direction,
        TransferState state,
        String errorMessage) {

    public enum TransferDirection {
        UPLOAD("Gửi đi (Upload)"),
        DOWNLOAD("Nhận về (Download)");

        private final String displayName;

        TransferDirection(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum TransferState {
        IDLE("Sẵn sàng (Chưa có tệp)"),
        QUEUED("Đang chờ gửi..."),
        TRANSFERRING("Đang truyền dữ liệu..."),
        PAUSED("Đã tạm dừng"),
        VERIFYING_FILE("Đang xác thực toàn vẹn SHA-256..."),
        COMPLETED("Hoàn tất truyền tải"),
        FAILED("Thất bại"),
        CANCELLED("Đã hủy bỏ");

        private final String displayName;

        TransferState(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static TransferViewModel createIdle() {
        return new TransferViewModel(
                "",
                "(Chưa có tệp tin đang truyền)",
                0L,
                0L,
                0.0,
                TransferDirection.UPLOAD,
                TransferState.IDLE,
                "");
    }

    public int getProgressPercentage() {
        return Formatters.calculateProgressPercentage(bytesTransferred, totalBytes);
    }

    public String getFormattedSizeProgress() {
        return Formatters.formatBytes(bytesTransferred) + " / " + Formatters.formatBytes(totalBytes);
    }

    public String getFormattedSpeed() {
        return Formatters.formatSpeed(speedBytesPerSec);
    }

    public String getFormattedEta() {
        long remaining = Math.max(0, totalBytes - bytesTransferred);
        return Formatters.formatEta(remaining, speedBytesPerSec);
    }
}
