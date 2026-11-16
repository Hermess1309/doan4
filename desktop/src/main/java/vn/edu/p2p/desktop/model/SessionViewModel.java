package vn.edu.p2p.desktop.model;

/**
 * ViewModel đại diện cho trạng thái của phiên kết nối mạng hiện hành.
 */
public record SessionViewModel(
        String sessionId,
        String remoteAddress,
        String localFingerprintRaw,
        String remoteFingerprintRaw,
        boolean isRelay,
        boolean isTlsHandshakeDone,
        boolean isFingerprintVerified,
        String stateDescription) {

    public static SessionViewModel createDisconnected() {
        return new SessionViewModel(
                "",
                "",
                "",
                "",
                false,
                false,
                false,
                "CHƯA KẾT NỐI (DISCONNECTED)");
    }

    public boolean isReadyToTransfer() {
        return isTlsHandshakeDone && isFingerprintVerified && sessionId != null && !sessionId.isBlank();
    }
}
