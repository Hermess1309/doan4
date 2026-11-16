# Đặc tả Giao diện Người dùng — Desktop (Java Swing & FlatLaf)

## 1. Mục tiêu và Giới hạn

- **Công nghệ**: Java 21 LTS, Java Swing, FlatLaf 3.5+.
- **Hệ điều hành mục tiêu**: Windows 10/11 và Linux (Ubuntu 22.04+).
- **Mô hình kiến trúc**: MVP (Model - View - Presenter) kết hợp Adapter pattern.
  - View (`desktop/src/main/java/vn/edu/p2p/desktop/view`): Chỉ chứa các component giao diện, không chứa socket, crypto hay logic truyền file.
  - ViewModel (`vn.edu.p2p.desktop.model`): `TransferViewModel`, `SessionViewModel`, `AppSettings`.
  - Adapter (`vn.edu.p2p.desktop.adapter`): Interface `CoreApiAdapter` kết nối với public API của `vn.edu.p2p.core`.
- **Nguyên tắc an toàn luồng (Concurrency & EDT Rules)**:
  - Mọi thao tác vẽ và cập nhật component Swing bắt buộc phải thực thi trên **Event Dispatch Thread (EDT)**.
  - Các callback từ Core Backend (`onProgress`, `onSessionStateChanged`, v.v.) chạy trên worker thread phải được bọc trong `SwingUtilities.invokeLater(...)`.
  - Nghiêm cấm chạy các tác vụ blocking I/O, DNS lookup, tính toán mã hóa, TLS handshake trên EDT.
  - Vô hiệu hóa nút bấm trong Swing bắt buộc dùng `component.setEnabled(false)`.

---

## 2. Chủ đề & Bảng màu (FlatLaf Theme)

Ứng dụng hỗ trợ chuyển đổi realtime giữa hai chủ đề và lưu vào `java.util.prefs.Preferences`:
- **FlatDarkLaf** (Mặc định): Nền tối (`#1E1F22`), bề mặt card (`#2B2D30`), viền (`#3E4147`), chữ chính (`#E6E8EA`).
- **FlatLightLaf**: Nền sáng (`#F7F8FA`), bề mặt card (`#FFFFFF`), viền (`#E1E4E8`), chữ chính (`#1A1D20`).
- **Màu ngữ nghĩa**:
  - Primary Accent: `#3574F0` (Nút hành động chính, tab đang chọn).
  - Success: `#499C54` (Đã xác minh vân tay, kết nối an toàn, hoàn thành truyền tải).
  - Warning: `#E5A83B` (Đã nối TCP chưa có TLS, chờ nhập vân tay).
  - Danger: `#E55753` (Lỗi mạng, sai vân tay, bắt tay TLS thất bại, lỗi nhập liệu).

---

## 3. Cấu trúc Điều hướng & Layout

Cửa sổ chính `MainFrame` (980 x 640 px, min 820 x 540 px) chia thành 3 khu vực:
1. **SidebarPanel (WEST — 210px)**: Chứa Brand Logo ("P2P Transfer"), các nút chuyển tab (Dashboard, Devices, Transfers, Security, Settings) và trạng thái tóm tắt ở footer.
2. **Center Container (CENTER)**:
   - **HeaderPanel (NORTH)**: Tiêu đề màn hình đang chọn và badge trạng thái kết nối đối tác.
   - **CardsPanel (CENTER — CardLayout)**: Chứa 5 màn hình độc lập.
3. **StatusBarPanel (SOUTH — 28px)**: Hiển thị IP/Port cục bộ, tốc độ mạng (Up/Down) và trạng thái engine.

---

## 4. Chi tiết các Màn hình MVP & Luồng Bảo mật TLS 1.3 (UI-SEC-01)

### 4.1. Dashboard (Tổng quan & Gửi tệp)
- **Thông tin phiên**: Tên thiết bị, trạng thái kết nối, trạng thái xác thực bảo mật.
- **Chọn tệp tin**: `JFileChooser` chọn 1 tệp tin cục bộ, hiển thị tên, đường dẫn và kích thước (format B/KB/MB/GB). Hỗ trợ tệp 0 bytes theo đúng SPEC.
- **Khóa nút gửi**: Nút `btnSend` **bắt buộc bị vô hiệu hóa (disabled)** trong các trạng thái `DISCONNECTED`, `TCP_CONNECTED`, `WAITING_FOR_FINGERPRINT`, `TLS_HANDSHAKING`. Chỉ được kích hoạt khi phiên chuyển sang `SECURE_READY` (Mutual TLS 1.3 đã xác thực) VÀ tệp tin đọc được.
- **Tiến trình tệp**: Thẻ thu nhỏ hiển thị thanh tiến độ, tốc độ hiện thời và trạng thái nhận từ `TransferViewModel`. Tuyệt đối không mô phỏng gửi thành công giả.

### 4.2. Devices (Quản lý Thiết bị & Kết nối)
- **Kiểm tra cú pháp đầu vào (ValidationUtils)**:
  - IPv4: 4 octet từ 0-255.
  - Hostname: RFC 1123 (nhãn 1-63 ký tự, không bắt đầu/kết thúc bằng dấu gạch ngang).
  - Port: Số nguyên từ 1 đến 65535.
  - Pairing Code: 6-12 ký tự chữ/số, cho phép 1 dấu gạch ngang phân tách (ví dụ: `782-901`).
- **Phân tách trạng thái mạng**: Sau khi bấm kết nối LAN, phiên chuyển sang `TCP_CONNECTED` (chưa thiết lập TLS). Giao diện thông báo rõ: *"Đã nối TCP — Vui lòng vào mục 'Bảo mật' để đối chiếu vân tay trước khi bắt tay TLS."* Tuyệt đối không hiển thị "Đã bảo mật" sau khi nối TCP.

### 4.3. Transfers (Quản lý Tiến trình Truyền tải)
- Quản lý truyền tải 1 tệp tin / 1 phiên qua `TransferViewModel`:
  - Tên tệp tin, kích thước tổng và bytes đã truyền.
  - Hướng truyền: Gửi đi (Upload) hoặc Nhận về (Download).
  - Tốc độ mạng (MB/s hoặc KB/s) và thời gian ước tính còn lại (ETA: mm:ss hoặc hh:mm:ss).
  - Thanh tiến độ `JProgressBar` (0–100%) hiển thị số phần trăm và tốc độ.
  - Trạng thái: Sẵn sàng, Đã gửi đề nghị (Offered), Đang truyền, Đang xác thực SHA-256, Hoàn tất, Thất bại, Đã hủy.
  - Nút Hủy và Resume: Giữ disabled khi chưa có backend thật.

### 4.4. Security & Luồng Xác minh Vân tay M2 (Tâm điểm bảo mật)
Khớp 1:1 với kiến trúc bảo mật M2 của Codex:
1. **Mỗi peer có Certificate và Fingerprint SHA-256 riêng**:
   - Máy này hiển thị chuỗi SHA-256 fingerprint sinh từ danh tính cục bộ (`LocalIdentity`).
   - Có nút "Sao chép" kèm phản hồi thị giác ("✓ Đã sao chép") và bọc try-catch an toàn.
   - Hướng dẫn: *"Đọc chuỗi SHA-256 này cho đối tác qua điện thoại hoặc gặp mặt trực tiếp để họ đối chiếu."*
2. **Nhập Expected Remote Fingerprint**:
   - Ở giai đoạn `TCP_CONNECTED` / `WAITING_FOR_FINGERPRINT`, JSSE chưa chạy handshake và chưa có chứng chỉ remote thực tế.
   - Hộp thoại cung cấp ô nhập/dán vân tay của đối tác (Expected Remote Fingerprint).
   - Kiểm tra chặt chẽ: Chuỗi vân tay phải đủ 32 byte (64 ký tự hex liên tiếp hoặc 32 cặp byte phân tách bằng dấu hai chấm), không chấp nhận mã rút gọn.
3. **Bắt tay TLS 1.3 có Pinning**:
   - Khi người dùng bấm "✓ Pin vân tay & Bắt tay TLS 1.3", Desktop gửi fingerprint sang core để cấu hình `PinnedTrustManager` và bắt đầu bắt tay Mutual TLS 1.3.
   - Trạng thái chuyển sang `TLS_HANDSHAKING`. Nút gửi file vẫn **disabled**.
   - Chỉ khi handshake thành công và chứng chỉ đối tác khớp đúng vân tay đã pin, core mới phát sự kiện chuyển sang `SECURE_READY`.
   - Nếu chứng chỉ sai hoặc handshake thất bại, phiên chuyển sang `ERROR` và đóng kết nối ngay lập tức.
4. **Quy tắc an toàn giao diện**:
   - Nút xác minh trong Preview Dialog bị khóa (`disabled`).
   - Tuyệt đối không tự ý chuyển sang `SECURE_READY` chỉ vì người dùng bấm nút trên giao diện.

### 4.5. Settings (Cài đặt Hệ thống)
- **Thư mục tải xuống**: Chọn thư mục lưu tệp nhận qua `JFileChooser` (DIRECTORIES_ONLY), kiểm tra quyền ghi `canWrite()`, lưu Preferences.
- **Chủ đề**: Radio button chuyển đổi giữa `FlatDarkLaf` và `FlatLightLaf`, lưu Preferences.

---

## 5. Hợp đồng API Công khai với `core/`

Module `desktop/` kết nối với `core/` thông qua interface `vn.edu.p2p.desktop.adapter.CoreApiAdapter`:

```java
package vn.edu.p2p.desktop.adapter;

public interface CoreApiAdapter {
    CompletableFuture<SessionViewModel> connectDirect(String host, int port);
    CompletableFuture<SessionViewModel> connectRelay(String pairingCode);
    CompletableFuture<Void> disconnect(String sessionId);

    String getLocalFingerprint();
    CompletableFuture<SessionViewModel> startTlsHandshake(String sessionId, String expectedRemoteFingerprint);

    CompletableFuture<String> offerFile(String sessionId, File file);
    CompletableFuture<Void> acceptTransfer(String transferId, File destinationDirectory);
    CompletableFuture<Void> cancelTransfer(String transferId);
    CompletableFuture<Void> resumeTransfer(String transferId);

    void registerListeners(Consumer<SessionViewModel> onSessionUpdate, Consumer<TransferViewModel> onTransferUpdate);
}
```

---

## 6. Lộ trình Triển khai UI

- **UI-01**: Khung ứng dụng Java Swing, FlatLaf Dark/Light, Navigation CardLayout, các màn hình skeleton và dialog xác minh fingerprint. *(Hoàn thành — Commit 0ea73f4)*.
- **UI-02**: Tinh chỉnh tương tác giao diện, validation dữ liệu đầu vào (IP, Port, Pairing Code, Directory, File), ViewModels, xử lý trạng thái disabled an toàn và kiểm thử tự động toàn diện. *(Hoàn thành — Commit 8cf4ecd)*.
- **UI-QA-01**: Rà soát QA, bảo vệ an toàn luồng và clipboard, chuẩn bị adapter `CoreApiAdapter`, hoàn thiện bảng mapping tích hợp cho UI-03. *(Hoàn thành — Commit a592fa5)*.
- **UI-SEC-01 (Hiện tại)**: Tinh chỉnh luồng bảo mật khớp 1:1 với M2 TLS 1.3 (TCP_CONNECTED -> WAITING_FOR_FINGERPRINT -> TLS_HANDSHAKING -> SECURE_READY), bổ sung ô nhập Expected Remote Fingerprint, kiểm tra 32 byte chuẩn xác.
- **UI-03**: Nối `RealCoreApiAdapter` với API thật của `core/`, xử lý luồng sự kiện qua EDT và kiểm thử toàn diện trên Windows và Linux.

---

## 7. Bảng Mapping Tích hợp UI ↔ Core API (UI-03 Blueprint)

| Thành phần UI (UI Action) | Phương thức Core API cần gọi | Callback / Event nhận về | Điều kiện bảo mật bắt buộc | Quy tắc điều phối luồng (Threading) |
| :--- | :--- | :--- | :--- | :--- |
| **Devices — Bấm "Kết nối LAN"** | `engine.connectDirect(host, port)` | `onSessionStateChanged(SessionInfo)` | Nhập đúng cú pháp IPv4/Host và Port hợp lệ | Trả về `SessionState.TCP_CONNECTED`; chưa chạy TLS |
| **Security — Mở dialog đối chiếu** | `engine.getLocalFingerprint()` | N/A (Truy vấn đồng bộ) | Phải có `sessionId` và socket TCP đã kết nối | Hiển thị local fingerprint; mở ô nhập remote fingerprint |
| **Security — Bấm "Pin vân tay & Bắt tay TLS"** | `engine.verify(sessionId, expectedPin)` | `onSessionStateChanged(SessionInfo)` | Chuỗi vân tay đúng 32 byte SHA-256 (64 hex) | Core chạy mutual TLS handshake; nếu khớp chuyển `SECURE_READY`, nếu sai ném `ERROR` |
| **Dashboard — Bấm "Bắt đầu gửi tệp"** | `engine.offerFile(sessionId, filePath)` | `onIncomingOffer(offer)`, `onProgress(progress)` | Bắt buộc phiên ở trạng thái `SECURE_READY` và tệp tin đọc được | Trả về transferId; callback `onProgress` cập nhật UI (giới hạn tần suất 100ms) |
| **Transfers — Bấm "Hủy truyền"** | `engine.cancelTransfer(transferId)` | `onTransferFailed(transferId, "Cancelled")` | Có tác vụ đang ở trạng thái `TRANSFERRING` | Core giải phóng socket/stream; UI reset về trạng thái `CANCELLED` trên EDT |
| **Transfers — Bấm "Tiếp tục"** | `engine.resumeTransfer(transferId)` | `onProgress(progress)` | Core M4 xác thực đủ điều kiện resume an toàn | Nếu core từ chối an toàn, UI thông báo truyền lại từ đầu |
| **Hộp thoại nhận file gửi tới** | `engine.acceptTransfer(id, dir)` hoặc `rejectTransfer(id, reason)` | `onTransferCompleted` hoặc `onTransferFailed` | Người dùng chủ động chọn thư mục đích hợp lệ có quyền ghi | Bật popup trên EDT khi có sự kiện `onIncomingOffer` |
