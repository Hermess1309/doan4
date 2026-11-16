# API core M3 và bàn giao Antigravity

Đây là API đã có thật trên feat/core-network sau M3. Không tạo interface P2pEngine có các phương thức chưa chạy. Tất cả public signature chỉ dùng kiểu core và JDK; desktop không cần import crypto/protocol hoặc đổi dependency.

Đã đọc CoreApiAdapter và đề xuất P2pEngine trong docs/UI_SPEC.md trên branch feat/desktop-ui (chỉ đọc, không sửa/merge). Đề xuất đó là hướng tích hợp, không phải API backend đã tồn tại. M3 dùng façade nhỏ để hoàn tất một file/connection và giữ thread/lifetime rõ ràng.

| Public API thực tế | Hành vi / quyền sở hữu |
| --- | --- |
| PeerNode.loadIdentity(Path, char[], String alias) | Blocking PKCS12 load; caller xóa password char[] trong finally |
| PeerNode.localFingerprint() | Full SHA-256 local, có thể hiển thị trước khi kết nối |
| PeerNode.connect(InetSocketAddress, int connectTimeoutMs, int ioTimeoutMs) | TCP connect, trả TransferSession PENDING_VERIFICATION; caller sở hữu |
| PeerNode.listen(InetSocketAddress, int acceptTimeoutMs, int ioTimeoutMs) | Trả PeerListener; port 0 cho phép OS cấp port |
| PeerListener.port(), accept(), close() | accept blocking, trả session server pending; đóng listener không đóng session đã accept |
| TransferSession.info() | SessionInfo snapshot: UUID, state, local/remote fingerprints, protocol, cipher |
| TransferSession.verify(UUID, String trustedFingerprint) | Blocking mutual TLS; pin đã đối chiếu độc lập trước handshake, trả snapshot VERIFIED hoặc đóng khi lỗi |
| TransferSession.send(Path, Consumer<TransferProgress>) | Hash nguồn, offer, chờ accept, stream chunk, chờ xác nhận receiver; trả TransferResult sau cùng |
| TransferSession.receiveOffer() | Blocking nhận/validate offer, trả FileOffer; chưa ghi file/chưa gửi ACCEPT |
| TransferSession.accept(UUID transferId, Path destinationFile, Consumer<TransferProgress>) | Một quyết định, nhận vào temp, verify/publish, ACK, trả kết quả |
| TransferSession.reject(UUID transferId) | Gửi REJECT, đóng; sender nhận TransferRejectedException |
| TransferSession.close() | Đóng TLS/transport; có thể gọi từ luồng khác để ngắt network I/O |
| FileOffer | transferId, fileName hiển thị, size, sha256; không chứa đường dẫn lưu tin cậy |
| TransferProgress | transferId, direction SEND/RECEIVE, state, bytesTransferred/totalBytes |
| TransferResult | Chỉ có kết quả thành công: ID, bytes, SHA-256, savedTo Optional (rỗng ở sender) |

Core không có event dispatcher/background thread riêng. Các thao tác blocking chạy trên worker của CLI/adapter; hai peer phải chạy accept/connect và verify đồng thời trên hai worker/process để handshake tiến triển. Dùng một worker operation/connection, một file duy nhất. send/accept/reject kết thúc connection; không tái dùng để gửi file thứ hai. Accept/reject tiêu thụ quyết định một lần; gọi trùng không tạo hai receiver. Caller luôn dùng try-with-resources và close khi bỏ offer/đóng UI.

## Mapping với hợp đồng UI đề xuất

| UI / đề xuất P2pEngine | Mapping M3 / thay đổi cần làm trong adapter |
| --- | --- |
| connectDirect(host, port) | Worker gọi node.connect; future trả session pending, giữ object theo session UUID |
| getSecurityInfo(sessionId) | Map session.info sang ViewModel; remoteFingerprint đang pending là Optional.empty |
| confirmFingerprintVerification boolean | Phải đổi thành thao tác async: worker gọi verify(UUID, fullPin), sau thành công mới map VERIFIED -> READY; không block EDT |
| Hộp thoại chỉ bật xác minh sau TLS/remote cert có sẵn | Không tương thích D004/M2: cần nhập full fingerprint lấy qua kênh độc lập trước handshake. Không điền một fingerprint “preview” giả để mở nút |
| offerFile trả transferId | Worker gọi send; callback HASHING/OFFERED cung cấp transferId sớm; send chỉ trả khi receiver ACK hoàn tất, không coi future gửi offer là future hoàn tất |
| onIncomingOffer | Worker phía nhận gọi receiveOffer, post DTO lên EDT, khi người dùng quyết định thì schedule accept/reject lên worker với UUID đúng session; không block EDT |
| acceptTransfer(id, directory) | M3 nhận destinationFile đầy đủ do người nhận chọn. Có thể gợi ý basename đã validate trong thư mục cục bộ nhưng phải xác nhận đường dẫn cuối, không truyền raw path từ peer |
| rejectTransfer(id, reason) | reject(id); reason chỉ hiển thị cục bộ, wire REJECT không gửi nội dung tùy ý |
| disconnect(sessionId) | close session và listener tương ứng, bỏ reference/map; không áp dụng trạng thái VERIFIED cũ cho session mới |
| cancelTransfer / resumeTransfer | M4 chưa có. close chỉ ngắt connection, không có resume/ack cancel hay hủy tức thời disk hashing; không bật nút như tính năng đã hoàn tất |
| connectRelay | M6 chưa có; không cung cấp stub báo thành công |
| Progress / completion | Callback trên worker, adapter SwingUtilities.invokeLater; chỉ state COMPLETED là thành công, không suy ra từ bytes == total |
| Error handling | IOException biểu thị network/protocol/storage/integrity failure; TransferRejectedException là user từ chối. Adapter chuyển sang FAILED/REJECTED, không cần import protocol/crypto |

Callback progress TRANSFERRING giới hạn khoảng 100 ms (vẫn phát 0 và mốc cuối); state khác phát ngay. bytes tăng đơn điệu trong một lần truyền. HASHING nguồn là bước đọc trước, bytesTransferred vẫn 0; VERIFYING và bytes == total chưa phải hoàn tất. File 0 byte có total=0, UI không chia cho 0. RuntimeException từ observer được cô lập, không làm sai kết quả truyền; callback không được thực hiện tác vụ blocking dài. Thông tin tốc độ/ETA do adapter tính từ byte delta và thời gian, không giả lập.

## Giới hạn thành công và lỗi mạng

Sender chỉ nhận TransferResult sau COMPLETE phản hồi có đúng ID/size/hash; receiver chỉ ACK sau file tạm đã hash lại và công bố thành tên đích. Nếu ACK mất sau publication, receiver có thể đã có file hợp lệ trong khi sender báo lỗi: không có exactly-once/distributed commit ở M3. Không tự retry/ghi đè/resume để che trạng thái không chắc chắn; người dùng kiểm tra file/SHA-256 rồi quyết định.

Phân công không đổi: Antigravity sửa desktop/UI_SPEC và adapter, Codex sửa core/backend. Tài liệu này thông báo API mới; chưa có RealCoreApiAdapter được nối trong branch Codex.
