# Đặc tả Giao diện Người dùng — Desktop (Java Swing & FlatLaf)

## 1. Mục tiêu và Giới hạn

- **Công nghệ**: Java 21 LTS, Java Swing, FlatLaf 3.5+.
- **Hệ điều hành mục tiêu**: Windows 10/11 và Linux (Ubuntu 22.04+).
- **Mô hình kiến trúc**: MVP (Model - View - Presenter) kết hợp Adapter pattern.
  - View (`desktop/src/main/java/vn/edu/p2p/desktop/view`): Chỉ chứa các component giao diện, không chứa socket, crypto hay logic truyền file.
  - ViewModel (`vn.edu.p2p.desktop.model`): `TransferViewModel`, `SessionViewModel`, `AppSettings`.
  - Adapter (`vn.edu.p2p.desktop.adapter`): Interface `CoreApiAdapter` chuẩn bị kết nối với public API của `vn.edu.p2p.core`.
- **Nguyên tắc an toàn luồng (Concurrency & EDT Rules)**:
  - Mọi thao tác vẽ và cập nhật component Swing bắt buộc phải thực thi trên **Event Dispatch Thread (EDT)**.
  - Các callback từ Core Backend (`onProgress`, `onSessionStateChanged`, v.v.) chạy trên worker thread phải được bọc trong `SwingUtilities.invokeLater(...)`.
  - Nghiêm cấm chạy các tác vụ blocking I/O, DNS lookup, tính toán mã hóa, handshake trên EDT.
  - Vô hiệu hóa nút bấm trong Swing bắt buộc dùng `component.setEnabled(false)`.

---

## 2. Chủ đề & Bảng màu (FlatLaf Theme)

Ứng dụng hỗ trợ chuyển đổi realtime giữa hai chủ đề và lưu vào `java.util.prefs.Preferences`:
- **FlatDarkLaf** (Mặc định): Nền tối (`#1E1F22`), bề mặt card (`#2B2D30`), viền (`#3E4147`), chữ chính (`#E6E8EA`).
- **FlatLightLaf**: Nền sáng (`#F7F8FA`), bề mặt card (`#FFFFFF`), viền (`#E1E4E8`), chữ chính (`#1A1D20`).
- **Màu ngữ nghĩa**:
  - Primary Accent: `#3574F0` (Nút hành động chính, tab đang chọn).
  - Success: `#499C54` (Đã xác minh vân tay, hoàn thành truyền tải).
  - Warning: `#E5A83B` (Chưa xác minh vân tay, đang chờ kết nối).
  - Danger: `#E55753` (Lỗi mạng, sai vân tay, hủy truyền tải, lỗi nhập liệu).

---

## 3. Cấu trúc Điều hướng & Layout

Cửa sổ chính `MainFrame` (980 x 640 px, min 820 x 540 px) chia thành 3 khu vực:
1. **SidebarPanel (WEST — 210px)**: Chứa Brand Logo ("P2P Transfer"), các nút chuyển tab (Dashboard, Devices, Transfers, Security, Settings) và trạng thái tóm tắt ở footer.
2. **Center Container (CENTER)**:
   - **HeaderPanel (NORTH)**: Tiêu đề màn hình đang chọn và badge trạng thái kết nối đối tác.
   - **CardsPanel (CENTER — CardLayout)**: Chứa 5 màn hình độc lập.
3. **StatusBarPanel (SOUTH — 28px)**: Hiển thị IP/Port cục bộ, tốc độ mạng (Up/Down) và trạng thái engine.

---

## 4. Chi tiết các Màn hình MVP & Quy tắc Tương tác (UI-02)

### 4.1. Dashboard (Tổng quan & Gửi tệp)
- **Thông tin phiên**: Tên thiết bị, trạng thái kết nối, trạng thái xác thực bảo mật.
- **Chọn tệp tin**: `JFileChooser` chọn 1 tệp tin cục bộ, hiển thị tên, đường dẫn và kích thước (format B/KB/MB/GB).
- **Kiểm tra tệp tin**: Xử lý an toàn khi đóng hộp thoại không chọn file (giữ nguyên tệp cũ); nếu file không tồn tại hoặc không thể đọc -> báo lỗi bằng chữ đỏ và không cho gửi. Hỗ trợ file rỗng (0 bytes) theo đúng SPEC.
- **Nút gửi tệp**: Mặc định `btnSend.setEnabled(false)`; chỉ bật khi phiên ở trạng thái `READY` (đã xác thực vân tay) VÀ tệp tin hợp lệ.
- **Tiến trình tệp**: Thẻ thu nhỏ hiển thị thanh tiến độ, tốc độ hiện thời và trạng thái nhận từ `TransferViewModel`. Tuyệt đối không mô phỏng gửi thành công giả.

### 4.2. Devices (Quản lý Thiết bị & Kết nối)
- **Kiểm tra cú pháp đầu vào (ValidationUtils)**:
  - IPv4: 4 octet từ 0-255.
  - Hostname: RFC 1123 (nhãn 1-63 ký tự, không bắt đầu/kết thúc bằng dấu gạch ngang).
  - Port: Số nguyên từ 1 đến 65535.
  - Pairing Code: 6-12 ký tự chữ/số, cho phép 1 dấu gạch ngang phân tách (ví dụ: `782-901`).
- **An toàn luồng**: Không thực hiện DNS lookup hay socket connect trên EDT; toàn bộ kiểm tra là chuỗi ký tự.
- **Trạng thái nút**: Nút "Ngắt kết nối" bị disabled khi chưa có phiên kết nối.

### 4.3. Transfers (Quản lý Tiến trình Truyền tải)
- Quản lý truyền tải 1 tệp tin / 1 phiên qua `TransferViewModel`:
  - Tên tệp tin, kích thước tổng và bytes đã truyền.
  - Hướng truyền: Gửi đi (Upload) hoặc Nhận về (Download).
  - Tốc độ mạng (MB/s hoặc KB/s) và thời gian ước tính còn lại (ETA: mm:ss hoặc hh:mm:ss).
  - Thanh tiến độ `JProgressBar` (0–100%) hiển thị số phần trăm và tốc độ.
  - Trạng thái: Sẵn sàng, Đã gửi đề nghị (Offered), Đang truyền, Đang xác thực SHA-256, Hoàn tất, Thất bại, Đã hủy.
  - Nút Hủy và Resume: Giữ disabled khi chưa có backend thật.
  - Nút "Mở thư mục đích": Bật khi hoàn thành truyền tải.

### 4.4. Security (Bảo mật & Xác thực Vân tay)
- **Trạng thái xác thực**: Hiển thị badge màu (Xanh: Đã xác thực; Vàng: Chưa xác thực).
- **Vân tay máy này**: Hiển thị chuỗi SHA-256 DER fingerprint sinh từ keystore cục bộ dạng cụm hex có dấu hai chấm.
- **Hộp thoại `FingerprintVerificationDialog`**:
  - Bắt buộc đối chiếu vân tay qua kênh độc lập (gọi điện, gặp mặt) trước khi truyền file.
  - Nút "Sao chép" (Copy to Clipboard) cho cả Local và Remote Fingerprint, kèm phản hồi thị giác ("✓ Đã sao chép") và bọc try-catch chống lỗi `IllegalStateException`.
  - Nếu ở chế độ xem trước (Preview Mode): Hiển thị cảnh báo màu đỏ và vô hiệu hóa nút xác minh thật (`btnVerify.setEnabled(false)`).
  - Nếu thiếu session ID hoặc thiếu remote fingerprint: Nút xác minh bắt buộc disabled.
  - Không lưu trạng thái xác thực giả vào hệ thống.

### 4.5. Settings (Cài đặt Hệ thống)
- **Thư mục tải xuống**: Chọn thư mục lưu tệp nhận qua `JFileChooser` (DIRECTORIES_ONLY).
  - Kiểm tra thư mục phải tồn tại và có quyền ghi (`canWrite()`). Nếu không hợp lệ, hiển thị lỗi và không lưu.
  - Tự động lưu và nạp lại cấu hình qua `java.util.prefs.Preferences`.
- **Chủ đề**: Radio button chuyển đổi giữa `FlatDarkLaf` và `FlatLightLaf`, lưu cấu hình vào Preferences.
- **Thông tin đồ án**: Phiên bản, công nghệ và nhóm tác giả.

---

## 5. Hợp đồng API Công khai với `core/`

Module `desktop/` kết nối với `core/` thông qua interface `vn.edu.p2p.core.P2pEngine`:

```java
package vn.edu.p2p.core;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface P2pEngine {
    CompletableFuture<SessionInfo> connectDirect(String host, int port);
    CompletableFuture<SessionInfo> connectRelay(String pairingCode);
    CompletableFuture<Void> disconnect(String sessionId);

    SessionSecurityInfo getSecurityInfo(String sessionId);
    boolean confirmFingerprintVerification(String sessionId, String expectedRemoteFingerprint);

    CompletableFuture<String> offerFile(String sessionId, Path filePath);
    CompletableFuture<Void> acceptTransfer(String transferId, Path destinationDirectory);
    CompletableFuture<Void> rejectTransfer(String transferId, String reason);
    CompletableFuture<Void> cancelTransfer(String transferId);
    CompletableFuture<Void> resumeTransfer(String transferId);

    void setSessionEventListener(SessionEventListener listener);
    void setTransferEventListener(TransferEventListener listener);
}
```

---

## 6. Lộ trình Triển khai UI

- **UI-01**: Khung ứng dụng Java Swing, FlatLaf Dark/Light, Navigation CardLayout, các màn hình skeleton và dialog xác minh fingerprint. *(Hoàn thành — Commit 0ea73f4)*.
- **UI-02**: Tinh chỉnh tương tác giao diện, validation dữ liệu đầu vào (IP, Port, Pairing Code, Directory, File), ViewModels, xử lý trạng thái disabled an toàn và kiểm thử tự động toàn diện. *(Hoàn thành — Commit 8cf4ecd)*.
- **UI-QA-01 (Hiện tại)**: Rà soát QA, bảo vệ an toàn luồng và clipboard, chuẩn bị adapter `CoreApiAdapter`, hoàn thiện bảng mapping tích hợp cho UI-03.
- **UI-03**: Nối `RealCoreApiAdapter` với API thật của `core/`, xử lý luồng sự kiện qua EDT và kiểm thử toàn diện trên Windows và Linux.

---

## 7. Bảng Mapping Tích hợp UI ↔ Core API (UI-03 Blueprint)

| Thành phần UI (UI Action) | Phương thức Core API cần gọi | Callback / Event nhận về | Điều kiện bảo mật bắt buộc | Quy tắc điều phối luồng (Threading) |
| :--- | :--- | :--- | :--- | :--- |
| **Devices — Bấm "Kết nối LAN"** | `engine.connectDirect(host, port)` | `onSessionStateChanged(SessionInfo)` | Nhập đúng cú pháp IPv4/Host và Port hợp lệ | Trả về `CompletableFuture`; callback đẩy về EDT qua `SwingUtilities.invokeLater` |
| **Devices — Bấm "Ghép nối Relay"** | `engine.connectRelay(pairingCode)` | `onSessionStateChanged(SessionInfo)` | Mã ghép nối đúng định dạng 6–12 ký tự | Trả về `CompletableFuture`; cập nhật UI trên EDT |
| **Security — Mở hộp thoại xác minh** | `engine.getSecurityInfo(sessionId)` | N/A (Truy vấn đồng bộ) | Phải có `sessionId` và phiên đã hoàn tất handshake TLS 1.3 | Đọc dữ liệu từ DTO; mở modal dialog trên EDT |
| **Security — Bấm "✓ Đã xác thực"** | `engine.confirmFingerprintVerification(sessionId, remoteFp)` | `onSessionStateChanged(SessionInfo)` | Người dùng đã đối chiếu đầy đủ 64 ký tự SHA-256 qua kênh độc lập | Core chuyển trạng thái sang `SessionState.READY`; Desktop bật nút gửi file |
| **Dashboard — Bấm "Bắt đầu gửi tệp"** | `engine.offerFile(sessionId, filePath)` | `onIncomingOffer(offer)`, `onProgress(progress)` | Bắt buộc phiên ở trạng thái `READY` và tệp tin đọc được | Trả về transferId; callback `onProgress` cập nhật UI (giới hạn tần suất 100ms) |
| **Transfers — Bấm "Hủy truyền"** | `engine.cancelTransfer(transferId)` | `onTransferFailed(transferId, "Cancelled")` | Có tác vụ đang ở trạng thái `TRANSFERRING` | Core giải phóng socket/stream; UI reset về trạng thái `CANCELLED` trên EDT |
| **Transfers — Bấm "Tiếp tục"** | `engine.resumeTransfer(transferId)` | `onProgress(progress)` | Core M4 xác thực đủ điều kiện resume an toàn | Nếu core từ chối an toàn, UI thông báo truyền lại từ đầu |
| **Hộp thoại nhận file gửi tới** | `engine.acceptTransfer(id, dir)` hoặc `rejectTransfer(id, reason)` | `onTransferCompleted` hoặc `onTransferFailed` | Người dùng chủ động chọn thư mục đích hợp lệ có quyền ghi | Bật popup trên EDT khi có sự kiện `onIncomingOffer` |
