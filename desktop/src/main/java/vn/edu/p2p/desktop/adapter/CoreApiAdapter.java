package vn.edu.p2p.desktop.adapter;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel;

/**
 * Hợp đồng Adapter nội bộ của desktop/ kết nối với Core Backend.
 * Trong UI-03, adapter cụ thể (RealCoreApiAdapter) sẽ wrap instance P2pEngine
 * và chuyển đổi dữ liệu DTO của core sang ViewModel của Desktop.
 */
public interface CoreApiAdapter {

    /**
     * Bắt đầu kết nối trực tiếp qua LAN IP và Port.
     */
    CompletableFuture<SessionViewModel> connectDirect(String host, int port);

    /**
     * Bắt đầu kết nối qua Relay Server bằng mã ghép nối.
     */
    CompletableFuture<SessionViewModel> connectRelay(String pairingCode);

    /**
     * Ngắt kết nối phiên hiện tại.
     */
    CompletableFuture<Void> disconnect(String sessionId);

    /**
     * Xác nhận người dùng đã đối chiếu đúng vân tay chứng chỉ qua kênh độc lập.
     */
    boolean confirmFingerprint(String sessionId, String remoteFingerprint);

    /**
     * Gửi đề nghị truyền tệp tin đã chọn qua phiên đã xác minh.
     */
    CompletableFuture<String> offerFile(String sessionId, File file);

    /**
     * Chấp nhận tệp tin gửi đến và lưu vào thư mục đích.
     */
    CompletableFuture<Void> acceptTransfer(String transferId, File destinationDirectory);

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
