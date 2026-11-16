# Đặc tả Giao diện Người dùng — Desktop (Java Swing & FlatLaf)

## 1. Mục tiêu và Giới hạn

- **Công nghệ**: Java 21 LTS, Java Swing, FlatLaf 3.5+.
- **Hệ điều hành mục tiêu**: Windows 10/11 và Linux (Ubuntu 22.04+).
- **Mô hình kiến trúc**: MVP (Model - View - Presenter) kết hợp Adapter pattern.
  - View (`desktop/src/main/java/vn/edu/p2p/desktop/view`): Chỉ chứa các component giao diện, không chứa socket, crypto hay logic truyền file.
  - Adapter (`vn.edu.p2p.desktop.adapter`): Cầu nối gọi sang public API của `vn.edu.p2p.core`.
- **Nguyên tắc an toàn luồng (Concurrency & EDT Rules)**:
  - Mọi thao tác vẽ và cập nhật component Swing bắt buộc phải thực thi trên **Event Dispatch Thread (EDT)**.
  - Các callback từ Core Backend (`onProgress`, `onSessionStateChanged`, v.v.) chạy trên worker thread phải được bọc trong `SwingUtilities.invokeLater(...)`.
  - Nghiêm cấm chạy các tác vụ blocking I/O, tính toán mã hóa, handshake trên EDT.
  - Vô hiệu hóa nút bấm trong Swing bắt buộc dùng `component.setEnabled(false)`.

---

## 2. Chủ đề & Bảng màu (FlatLaf Theme)

Ứng dụng hỗ trợ chuyển đổi realtime giữa hai chủ đề mà không cần khởi động lại:
- **FlatDarkLaf** (Mặc định): Nền tối (`#1E1F22`), bề mặt card (`#2B2D30`), viền (`#3E4147`), chữ chính (`#E6E8EA`).
- **FlatLightLaf**: Nền sáng (`#F7F8FA`), bề mặt card (`#FFFFFF`), viền (`#E1E4E8`), chữ chính (`#1A1D20`).
- **Màu ngữ nghĩa**:
  - Primary Accent: `#3574F0` (Nút hành động chính, tab đang chọn).
  - Success: `#499C54` (Đã xác minh vân tay, hoàn thành truyền tải).
  - Warning: `#E5A83B` (Chưa xác minh vân tay, đang chờ kết nối).
  - Danger: `#E55753` (Lỗi mạng, sai vân tay, hủy truyền tải).

---

## 3. Cấu trúc Điều hướng & Layout

Cửa sổ chính `MainFrame` (980 x 640 px, min 820 x 540 px) chia thành 3 khu vực:
1. **SidebarPanel (WEST — 210px)**: Chứa Brand Logo ("P2P Transfer"), các nút chuyển tab (Dashboard, Devices, Transfers, Security, Settings) và trạng thái tóm tắt ở footer.
2. **Center Container (CENTER)**:
   - **HeaderPanel (NORTH)**: Tiêu đề màn hình đang chọn và badge trạng thái kết nối đối tác.
   - **CardsPanel (CENTER — CardLayout)**: Chứa 5 màn hình độc lập.
3. **StatusBarPanel (SOUTH — 28px)**: Hiển thị IP/Port cục bộ, tốc độ mạng (Up/Down) và trạng thái engine.

---

## 4. Chi tiết các Màn hình MVP

### 4.1. Dashboard (Tổng quan & Gửi tệp)
- **Thông tin phiên**: Tên thiết bị, trạng thái kết nối, trạng thái xác thực bảo mật.
- **Chọn tệp tin**: `JFileChooser` chọn 1 tệp tin cục bộ, hiển thị tên và kích thước (format B/KB/MB/GB).
- **Nút gửi tệp**: Mặc định `btnSend.setEnabled(false)`; chỉ bật khi phiên ở trạng thái `READY` (đã xác thực vân tay).
- **Tiến trình tệp**: Thẻ thu nhỏ hiển thị thanh tiến độ, tốc độ hiện thời và trạng thái.

### 4.2. Devices (Quản lý Thiết bị & Kết nối)
- **Kết nối LAN**: Nhập địa chỉ IPv4 và Port (mặc định 52001) + nút "Kết nối LAN".
- **Kết nối Relay**: Nhập mã ghép nối (Pairing Code) + nút "Ghép nối Relay".
- **Quản lý phiên**: Hiển thị trạng thái phiên hiện hành và nút "Ngắt kết nối".

### 4.3. Transfers (Quản lý Tiến trình Truyền tải)
- Quản lý truyền tải 1 tệp tin / 1 phiên:
  - Tên tệp tin, kích thước tổng.
  - Hướng truyền: Gửi đi (Upload) hoặc Nhận về (Download).
  - Tốc độ mạng (MB/s hoặc KB/s) và thời gian ước tính còn lại (ETA).
  - Thanh tiến độ `JProgressBar` (0–100%).
  - Nút Hủy (`btnCancel.setEnabled(false)` khi không có tệp).
  - Nút Tiếp tục (`btnResume.setEnabled(false)` khi backend chưa hỗ trợ an toàn).
  - Nút "Mở thư mục đích" khi hoàn tất.

### 4.4. Security (Bảo mật & Xác thực Vân tay)
- **Trạng thái xác thực**: Hiển thị badge màu (Xanh: Đã xác thực; Vàng: Chưa xác thực).
- **Vân tay máy này**: Hiển thị chuỗi SHA-256 DER fingerprint sinh từ keystore cục bộ dạng cụm hex có dấu hai chấm.
- **Hộp thoại `FingerprintVerificationDialog`**:
  - Bắt buộc đối chiếu vân tay qua kênh độc lập (gọi điện, gặp mặt) trước khi truyền file.
  - Hiển thị song song Remote Fingerprint và Local Fingerprint.
  - Hai nút hành động dứt khoát: `[✕ Từ chối & Ngắt kết nối]` và `[✓ Tôi đã xác thực trùng khớp]`.

### 4.5. Settings (Cài đặt Hệ thống)
- **Thư mục tải xuống**: Chọn thư mục lưu tệp nhận qua `JFileChooser` (chỉ chọn thư mục).
- **Chủ đề**: Radio button chuyển đổi giữa `FlatDarkLaf` và `FlatLightLaf`.
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

- **UI-01 (Hiện tại)**: Khung ứng dụng Java Swing, FlatLaf Dark/Light, Navigation CardLayout, các màn hình skeleton và dialog xác minh fingerprint.
- **UI-02**: Tinh chỉnh chi tiết các view, validation dữ liệu đầu vào (IP, port, file tồn tại) và các trạng thái hiển thị trung gian.
- **UI-03**: Nối `RealCoreApiAdapter` với API thật của `core/`, xử lý luồng sự kiện qua EDT và kiểm thử toàn diện trên Windows và Linux.
