package vn.edu.p2p.desktop.model;

/**
 * ViewModel đại diện cho trạng thái của phiên kết nối mạng hiện hành.
 * Phản ánh chính xác kiến trúc bảo mật M2:
 * DISCONNECTED -> TCP_CONNECTED -> WAITING_FOR_FINGERPRINT -> TLS_HANDSHAKING -> SECURE_READY / ERROR.
 *
 * <p>Trường {@code sessionGeneration} là số thế hệ bất biến tăng đơn điệu cho mỗi phiên mới,
 * dùng để phát hiện và loại bỏ callback đến muộn (stale callback) từ phiên cũ trên cả Adapter lẫn EDT.
 */
public record SessionViewModel(
        String sessionId,
        String remoteAddress,
        String localFingerprintRaw,
        String expectedRemoteFingerprintRaw,
        boolean isRelay,
        SessionState state,
        String errorMessage,
        long sessionGeneration) {

    public SessionViewModel(
            String sessionId,
            String remoteAddress,
            String localFingerprintRaw,
            String expectedRemoteFingerprintRaw,
            boolean isRelay,
            SessionState state,
            String errorMessage) {
        this(
                sessionId,
                remoteAddress,
                localFingerprintRaw,
                expectedRemoteFingerprintRaw,
                isRelay,
                state,
                errorMessage,
                0L);
    }

    public enum SessionState {
        DISCONNECTED("Chưa kết nối (DISCONNECTED)"),
        RELAY_CONNECTING("Đang kết nối tới Relay Server (Connecting)"),
        WAITING_FOR_PEER("Đang chờ đối tác ghép nối qua Relay (Waiting for Peer)"),
        TCP_CONNECTED("Đã ghép nối TCP (TCP Paired — Chưa bắt tay TLS)"),
        WAITING_FOR_FINGERPRINT("Chờ nhập vân tay đối tác (Waiting for Fingerprint)"),
        TLS_HANDSHAKING("Đang bắt tay Mutual TLS 1.3 (TLS Handshaking)..."),
        SECURE_READY("Kết nối an toàn đã xác thực (Secure Ready — TLS 1.3)"),
        ERROR("Lỗi kết nối / Xác thực thất bại (Failed)"),
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
        return createDisconnected(0L);
    }

    public static SessionViewModel createDisconnected(long sessionGeneration) {
        return new SessionViewModel(
                "", "", "", "", false, SessionState.DISCONNECTED, "", sessionGeneration);
    }

    public boolean isReadyToTransfer() {
        return state == SessionState.SECURE_READY
                && sessionId != null
                && !sessionId.isBlank();
    }
}
