package vn.edu.p2p.desktop.model;

/**
 * ViewModel đại diện cho trạng thái của phiên kết nối mạng hiện hành.
 * Bám sát vòng đời trạng thái quy định trong docs/SPEC.md.
 */
public record SessionViewModel(
        String sessionId,
        String remoteAddress,
        String localFingerprintRaw,
        String remoteFingerprintRaw,
        boolean isRelay,
        boolean isTlsHandshakeDone,
        boolean isFingerprintVerified,
        SessionState state,
        String stateDescription) {

    public enum SessionState {
        DISCONNECTED("Chưa kết nối (DISCONNECTED)"),
        CONNECTING("Đang kết nối..."),
        VERIFYING("Chờ xác minh vân tay (TLS 1.3)"),
        READY("Đã xác thực vân tay, sẵn sàng truyền dữ liệu"),
        CLOSED("Đã đóng kết nối");

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
                false,
                false,
                SessionState.DISCONNECTED,
                SessionState.DISCONNECTED.getDisplayName());
    }

    public boolean isReadyToTransfer() {
        return state == SessionState.READY
                && isTlsHandshakeDone
                && isFingerprintVerified
                && sessionId != null
                && !sessionId.isBlank();
    }
}
