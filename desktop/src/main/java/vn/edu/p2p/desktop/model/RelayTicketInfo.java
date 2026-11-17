package vn.edu.p2p.desktop.model;

/**
 * Thông tin mã ghép nối một lần trả về từ Relay Server sau lệnh CREATE.
 * Không ghi pairingCode ra toString/log; chỉ hiển thị trực tiếp cho người dùng sao chép.
 */
public record RelayTicketInfo(
        long sessionGeneration, String pairingCode, int ttlMillis, String relayAddress) {

    @Override
    public String toString() {
        return "RelayTicketInfo[sessionGeneration="
                + sessionGeneration
                + ", ttlMillis="
                + ttlMillis
                + ", relayAddress="
                + relayAddress
                + "]";
    }
}
