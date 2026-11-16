package vn.edu.p2p.desktop.model;

/**
 * ViewModel đại diện cho trạng thái của phiên kết nối mạng hiện hành.
 * Phản ánh chính xác kiến trúc bảo mật M2:
 * DISCONNECTED -> TCP_CONNECTED -> WAITING_FOR_FINGERPRINT -> TLS_HANDSHAKING -> SECURE_READY / ERROR.
 */
public record SessionViewModel(
        String sessionId,
        String remoteAddress,
        String localFingerprintRaw,
        String expectedRemoteFingerprintRaw,
        boolean isRelay,
        SessionState state,
        String errorMessage) {

    public enum SessionState {
        DISCONNECTED("Chưa kết nối (DISCONNECTED)"),
        TCP_CONNECTED("Đã kết nối TCP (Chưa bắt tay TLS)"),
        WAITING_FOR_FINGERPRINT("Chờ nhập vân tay đối tác (Kênh độc lập)"),
        TLS_HANDSHAKING("Đang bắt tay Mutual TLS 1.3..."),
        SECURE_READY("Kết nối an toàn đã xác thực (TLS 1.3)"),
        ERROR("Lỗi kết nối / Xác thực thất bại"),
        CLOSED("Đã đóng kết nối (CLOSED)");

        private final String displayName;

        SessionState(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static SessionViewModel createDisconnected() {
        return new SessionViewModel(
                "",
                "",
                "",
                "",
                false,
                SessionState.DISCONNECTED,
                "");
    }

    public boolean isReadyToTransfer() {
        return state == SessionState.SECURE_READY
                && sessionId != null
                && !sessionId.isBlank();
    }
}
