package vn.edu.p2p.desktop.model;

import vn.edu.p2p.desktop.util.Formatters;

/**
 * ViewModel đại diện cho trạng thái hiển thị của một tác vụ truyền tải tệp tin.
 * Khớp với vòng đời truyền file: IDLE -> OFFERED -> TRANSFERRING -> VERIFYING_FILE -> COMPLETED.
 *
 * <p>Mang theo {@code sessionId} và {@code sessionGeneration} của phiên sở hữu tác vụ
 * để loại bỏ các callback transfer/offer/cancel đến muộn từ phiên cũ.
 */
public record TransferViewModel(
        String transferId,
        String fileName,
        long totalBytes,
        long bytesTransferred,
        double speedBytesPerSec,
        TransferDirection direction,
        TransferState state,
        String errorMessage,
        String sessionId,
        long sessionGeneration) {

    public TransferViewModel(
            String transferId,
            String fileName,
            long totalBytes,
            long bytesTransferred,
            double speedBytesPerSec,
            TransferDirection direction,
            TransferState state,
            String errorMessage) {
        this(
                transferId,
                fileName,
                totalBytes,
                bytesTransferred,
                speedBytesPerSec,
                direction,
                state,
                errorMessage,
                "",
                0L);
    }

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
        HASHING("Đang kiểm tra băm SHA-256..."),
        OFFERED("Đã gửi/nhận đề nghị truyền tệp (Offered)..."),
        QUEUED("Đang chờ gửi..."),
        TRANSFERRING("Đang truyền dữ liệu..."),
        PAUSED("Đã tạm dừng"),
        VERIFYING_FILE("Đang xác thực toàn vẹn SHA-256..."),
        COMPLETED("Hoàn tất truyền tải"),
        REJECTED("Bị từ chối (Rejected)"),
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
        return createIdle(0L);
    }

    public static TransferViewModel createIdle(long sessionGeneration) {
        return new TransferViewModel(
                "",
                "(Chưa có tệp tin đang truyền)",
                0L,
                0L,
                0.0,
                TransferDirection.UPLOAD,
                TransferState.IDLE,
                "",
                "",
                sessionGeneration);
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
