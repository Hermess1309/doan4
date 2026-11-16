package vn.edu.p2p.desktop.adapter;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel;

/**
 * Hợp đồng Adapter nội bộ của desktop/ kết nối với Core Backend.
 * Phản ánh đúng 2 giai đoạn bảo mật của M2:
 * 1. Kết nối TCP (LAN / Relay).
 * 2. Cung cấp expected remote fingerprint qua kênh độc lập để bắt tay Mutual TLS 1.3.
 */
public interface CoreApiAdapter {

    /**
     * Bắt đầu kết nối TCP trực tiếp qua LAN IP và Port.
     * Trả về session ở trạng thái TCP_CONNECTED / WAITING_FOR_FINGERPRINT (chưa chạy TLS).
     */
    CompletableFuture<SessionViewModel> connectDirect(String host, int port);

    /**
     * Bắt đầu kết nối qua Relay Server bằng mã ghép nối.
     */
    CompletableFuture<SessionViewModel> connectRelay(String pairingCode);

    /**
     * Lấy chuỗi SHA-256 fingerprint của danh tính máy này (Local Identity).
     */
    String getLocalFingerprint();

    /**
     * Cung cấp fingerprint của đối tác đã đối chiếu qua kênh độc lập,
     * khởi động quá trình bắt tay Mutual TLS 1.3 có pinning chứng chỉ.
     * Trả về session ở trạng thái SECURE_READY nếu thành công hoặc ERROR nếu thất bại.
     */
    CompletableFuture<SessionViewModel> startTlsHandshake(String sessionId, String expectedRemoteFingerprint);

    /**
     * Ngắt kết nối phiên hiện tại và đóng tài nguyên mạng/TLS.
     */
    CompletableFuture<Void> disconnect(String sessionId);

    /**
     * Gửi đề nghị truyền tệp tin đã chọn (chỉ khả dụng khi session ở trạng thái SECURE_READY).
     */
    CompletableFuture<String> offerFile(String sessionId, File file);

    /**
     * Chấp nhận tệp tin gửi đến và lưu vào thư mục đích.
     */
    CompletableFuture<Void> acceptTransfer(String transferId, File destinationDirectory);

    /**
     * Từ chối tệp tin gửi đến.
     */
    CompletableFuture<Void> rejectTransfer(String transferId, String reason);

    /**
     * Hủy bỏ tác vụ truyền tệp tin đang diễn ra.
     */
    CompletableFuture<Void> cancelTransfer(String transferId);

    /**
     * Tiếp tục tác vụ truyền tệp tin bị gián đoạn (nếu backend M4 hỗ trợ an toàn).
     */
    CompletableFuture<Void> resumeTransfer(String transferId);

    /**
     * Đăng ký callback nhận cập nhật trạng thái phiên và tiến độ truyền file.
     * Toàn bộ cập nhật sẽ được đẩy về Swing EDT an toàn.
     */
    void registerListeners(Consumer<SessionViewModel> onSessionUpdate, Consumer<TransferViewModel> onTransferUpdate);
}
