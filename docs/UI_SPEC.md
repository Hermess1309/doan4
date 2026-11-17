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
- **UI-SEC-01**: Tinh chỉnh luồng bảo mật khớp 1:1 với M2 TLS 1.3 (TCP_CONNECTED -> WAITING_FOR_FINGERPRINT -> TLS_HANDSHAKING -> SECURE_READY), bổ sung ô nhập Expected Remote Fingerprint, kiểm tra 32 byte chuẩn xác. *(Hoàn thành — Commit 38b7d2c)*.
- **UI-03A (Hiện tại)**: Tích hợp Core M3 thực tế (`PeerNode`, `PeerListener`, `TransferSession`) qua `RealCoreApiAdapter`, hỗ trợ truyền nhận file thật qua Mutual TLS 1.3 trên localhost/LAN, xác thực SHA-256, tự sinh danh tính bảo mật `LocalIdentityHelper`, xử lý an toàn EDT và kiểm thử toàn diện.

---

## 7. Bảng Mapping Tích hợp UI ↔ Core API (UI-03A Thực tế)

| Thành phần UI (UI Action) | Phương thức Core API thực tế | Trạng thái / Event nhận về | Điều kiện bảo mật bắt buộc | Quy tắc điều phối luồng (Threading) |
| :--- | :--- | :--- | :--- | :--- |
| **Khởi động ứng dụng** | `PeerNode.loadIdentity`, `node.listen(port)` | Lắng nghe kết nối đến qua `PeerListener.accept()` | Sinh chứng chỉ X.509 RSA-3072 + EKU/KU nếu chưa có | Listener loop chạy trên Virtual Thread nền; không block EDT |
| **Devices — Bấm "Kết nối LAN"** | `node.connect(host, port, timeout)` | `SessionState.TCP_CONNECTED` | Host hợp lệ (IPv4/Hostname) và Port từ 1-65535 | Chạy async trên worker pool; cập nhật ViewModel trên EDT |
| **Security — Hiển thị Local Fingerprint** | `node.localFingerprint()` | Chuỗi Hex SHA-256 (64 ký tự) | Certificate SHA-256 từ `LocalIdentity` | Hiển thị dạng nhóm 4 ký tự dễ đọc kèm nút sao chép |
| **Security — Pin vân tay & Bắt tay TLS** | `session.verify(expectedPin)` | `SessionState.SECURE_READY` | Chuỗi kỳ vọng đủ 32 byte SHA-256 (64 hex) | Bắt tay mTLS 1.3 trên worker thread; nếu khớp pin mới chuyển `SECURE_READY`, nếu sai ném lỗi và đóng socket |
| **Dashboard — Bấm "Bắt đầu gửi tệp"** | `session.send(filePath, callback)` | `TransferState.OFFERED` -> `TRANSFERRING` -> `HASHING` -> `COMPLETED` | Bắt buộc phiên ở trạng thái `SECURE_READY` | Chạy stream chunked và hash SHA-256 trên worker thread; callback bọc `SwingUtilities.invokeLater` throttle 100ms |
| **Dashboard — Có yêu cầu nhận tệp** | `session.receiveOffer()` | `onIncomingOffer(offer)` | Đề nghị gửi từ đối tác đã mTLS | Kích hoạt popup xác nhận trên EDT, yêu cầu người dùng chọn file đích chưa tồn tại |
| **Xác nhận nhận tệp** | `session.accept(transferId, destPath, callback)` | `TransferState.TRANSFERRING` -> `HASHING` -> `COMPLETED` | File đích chưa tồn tại trên đĩa (chống ghi đè) | Stream ghi file và kiểm tra SHA-256 trên worker thread; cập nhật `TransferViewModel` |
| **Từ chối nhận tệp** | `session.reject(transferId, reason)` | `TransferState.REJECTED` | Session hợp lệ | Báo cho đối tác qua protocol và đóng phiên an toàn |
| **Transfers — Hủy truyền tệp** | `session.close()` / `adapter.cancelTransfer()` | `TransferState.CANCELLED` | Có tác vụ đang truyền | Ngắt socket, đóng luồng, cập nhật trạng thái trên EDT |
| **Đóng ứng dụng** | `adapter.shutdown()` | Giải phóng toàn bộ listener, session, executor | Window closing event | Đóng `PeerListener`, ngắt active `TransferSession`, tắt Virtual Thread executor |

---

## 8. Kiến trúc Tích hợp Kỹ thuật (Technical Integration Architecture)

### 8.1. Adapter Layer, Non-Blocking Startup & Fail-Safe Policy
- **`CoreApiAdapter`**: Interface chuẩn hóa giữa tầng UI Swing và Core Backend.
- **`DummyCoreApiAdapter`**: Chỉ được sử dụng trong unit test, preview UI và chế độ `--smoke-test` được chỉ định rõ. **Tuyệt đối không tự fallback sang `DummyCoreApiAdapter` trong chế độ production**.
- **Khởi động không chặn EDT (`DesktopApplication.startProductionOnEdt`)**:
  - `MainFrame.createProductionWindow()` dựng cửa sổ ngay lập tức trên EDT với trạng thái `"Đang khởi tạo ứng dụng..."` và khóa toàn bộ nút kết nối/gửi tệp/bắt tay TLS.
  - Quá trình nạp/tạo keystore (`keytool`) và mở cổng lắng nghe (`PeerNode.listen`) chạy hoàn toàn trên background worker (`p2p-desktop-startup`), đảm bảo GUI không bị đơ ở lần chạy đầu.
  - Khi thành công, backend thật được gắn trên EDT (`attachBackendAdapter`), hiển thị fingerprint thật, cổng LAN thật và mở khóa kết nối.
  - Khi thất bại, ứng dụng chuyển sang trạng thái lỗi (`FAILED`), báo lỗi rõ ràng, giữ nguyên khóa toàn bộ chức năng mạng, không hiển thị fingerprint giả và không giả lập `SECURE_READY`.
  - Đóng cửa sổ (`windowClosing` / `dispose`) tự động hủy worker khởi tạo và gọi `adapter.shutdown()`.
- **`RealCoreApiAdapter` & Chống Stale Callback theo Generation**:
  - Quản lý vòng đời `PeerNode`, `PeerListener` và `TransferSession` gắn với `sessionGeneration` bất biến tăng đơn điệu cho từng phiên.
  - Mọi tác vụ I/O, handshake, truyền tải chạy trên `Executors.newVirtualThreadPerTaskExecutor()`.
  - Khi ngắt kết nối, thay phiên hoặc shutdown, thế hệ cũ bị vô hiệu hóa ngay lập tức và `closeSessionIfMatches` chỉ đóng phiên tương ứng (không bao giờ đóng nhầm phiên mới).
  - Mọi callback (`SECURE_READY`, `ERROR`, `OFFER`, `TRANSFER`, `CANCEL`) đều kiểm tra `sessionGeneration`/`sessionId` cả trước khi xếp hàng và **tại thời điểm thực thi thực sự trên Swing EDT** (trên cả `RealCoreApiAdapter` lẫn `MainFrame`), đảm bảo callback đến muộn của phiên A không bao giờ bật nút Send hay ghi đè trạng thái của phiên B.

### 8.2. Quản lý Danh tính Cục bộ & Mật khẩu An toàn (`LocalIdentityHelper`)
- Vị trí lưu trữ mặc định (luôn nằm ngoài repository): `%LOCALAPPDATA%/P2pFileTransfer/` (Windows) hoặc `~/.local/share/p2p-file-transfer/` (Linux).
- Có thể phân biệt tên instance hợp lệ (`[a-zA-Z0-9._-]`, chống path traversal) qua cờ `--name=alice` hoặc `--name=bob`.
- **Chính sách bảo vệ thư mục & tệp nhạy cảm (Fail-Closed)**:
  - Không hardcode mật khẩu; không ghi mật khẩu ra log hoặc Preferences; không nhận mật khẩu qua tham số dòng lệnh (`argv`).
  - Hỗ trợ cung cấp mật khẩu qua biến môi trường (`P2P_PASSWORD_ENV` hoặc `P2P_IDENTITY_PASSWORD`). Nếu không đặt biến môi trường, hệ thống tự sinh mật khẩu ngẫu nhiên 128-bit entropy (32 ký tự hex) bằng `SecureRandom`.
  - Kiểm tra symbolic link trên **toàn bộ thành phần đường dẫn cha**, thư mục identity, `.p12` và `.pass`; tạo tệp bí mật mới nguyên tử (`CREATE_NEW`), không ghi đè tệp hiện có.
  - **Xác minh thư mục identity (cả thư mục mới lẫn thư mục đã tồn tại) TRƯỚC KHI ghi `.pass` hoặc chạy `keytool`**:
    - **Linux/POSIX**: Thư mục phải thuộc owner hiện tại và đúng quyền `0700` (`rwx------`); từ chối `0777` hoặc group-accessible. Tệp `.p12`/`.pass` hiện có phải đúng owner và quyền `<= 0600`.
    - **Windows ACL**: Xác minh owner và toàn bộ ACE hiệu lực (kể cả ACE kế thừa `INHERIT_ONLY`/`FILE_INHERIT`); từ chối mọi quyền nhạy cảm cấp cho `Everyone`, `Guests`, `Users`, `Authenticated Users` hoặc principal không tin cậy khác. Không tự động sửa ACL của thư mục cũ không hợp lệ.
  - Nếu kiểm tra hoặc siết quyền thất bại, hệ thống xóa tệp tạo dở (không xóa identity cũ) và ném `IOException` (fail-closed).
  - `keytool` nhận mật khẩu qua biến môi trường cục bộ `-storepass:env P2P_KEYTOOL_PASS`; toàn bộ mảng `char[]`/`byte[]` mật khẩu được xóa trong `finally`.
- **Giới hạn bảo mật & TOCTOU còn lại**:
  - Lưu mật khẩu trong tệp `.pass` cục bộ kèm quyền OS (`0600` / Windows ACL) chỉ ngăn chặn tài khoản OS khác trên cùng máy đọc tệp, **không tương đương với mã hóa secret bằng OS Credential Manager** (Windows DPAPI/Credential Manager, Linux Secret Service/libsecret).
  - Do `keytool` là tiến trình con bên ngoài nhận đường dẫn tệp thay vì file descriptor, vẫn tồn tại cửa sổ TOCTOU hẹp nếu một tiến trình đặc quyền cao hoặc cùng tài khoản sửa đổi quyền thư mục ngay trong lúc `keytool` chạy; rủi ro này được giảm thiểu bằng việc xác minh thư mục trước khi gọi `keytool`, kiểm tra lại `NOFOLLOW_LINKS` và xác nhận ACL/0600 ngay khi `keytool` kết thúc.

### 8.3. Phương thức Khởi chạy Ứng dụng Thực tế (Đã xác minh)
Module `desktop` là ứng dụng đa module phụ thuộc vào `:core`, `:crypto`, `:protocol` và FlatLaf. Tệp `desktop.jar` thông thường không chứa fat-jar bundle, do đó **không sử dụng `java -jar desktop.jar`**. Sử dụng một trong hai phương thức chuẩn hóa sau:

#### Cách 1: Native Launcher qua `installDist` (Khuyên dùng cho Demo)
Biên dịch bản phân phối đầy đủ:
```bash
# Windows
.\gradlew.bat :desktop:installDist

# Ubuntu / Linux
./gradlew :desktop:installDist
```

Khởi chạy 2 Node (Alice và Bob) trên 2 cổng và danh tính riêng biệt:
- **Node A (Alice — Cổng 52001)**:
  - Windows: `.\desktop\build\install\desktop\bin\desktop.bat --name=alice --port=52001`
  - Linux: `./desktop/build/install/desktop/bin/desktop --name=alice --port=52001`
- **Node B (Bob — Cổng 52002)**:
  - Windows: `.\desktop\build\install\desktop\bin\desktop.bat --name=bob --port=52002`
  - Linux: `./desktop/build/install/desktop/bin/desktop --name=bob --port=52002`

#### Cách 2: Trực tiếp qua Gradle
- Node A (Alice): `.\gradlew.bat :desktop:run --args="--name=alice --port=52001"`
- Node B (Bob): `.\gradlew.bat :desktop:run --args="--name=bob --port=52002"`

#### Chế độ Smoke Test (Tự động thoát sau kiểm tra)
- `.\desktop\build\install\desktop\bin\desktop.bat --smoke-test`
- Hoặc kết hợp tham số: `.\desktop\build\install\desktop\bin\desktop.bat --name=alice --port=52001 --smoke-test` (Tự kiểm tra khởi tạo, giải phóng giao diện và thoát sạch trong < 5 giây với exit code 0).

### 8.4. Kịch bản Demo Truyền Tệp Hai Node (Localhost / LAN)
1. **Xác định IP & Cổng**:
   - Khởi chạy Alice và Bob theo lệnh ở mục 8.3.
   - Quan sát thanh Status Bar: Alice lắng nghe cổng `52001`, Bob lắng nghe cổng `52002`.
2. **Đối chiếu Vân tay (Out-of-band Fingerprint)**:
   - Trên cả hai ứng dụng, chuyển sang tab **Bảo mật**.
   - Bấm nút **Sao chép** để lấy chuỗi SHA-256 Fingerprint cục bộ của mỗi bên.
3. **Kết nối LAN**:
   - Từ Bob, chuyển sang tab **Thiết bị**, nhập IP `127.0.0.1` (hoặc IP LAN của máy Alice nếu chạy 2 máy) và cổng `52001`.
   - Bấm nút **Kết nối LAN**. Cả hai ứng dụng chuyển sang trạng thái `TCP_CONNECTED` (viền vàng, thông báo chưa có TLS).
4. **Pinning & Bắt tay Mutual TLS 1.3**:
   - Tại Alice: Dán Fingerprint của Bob vào ô *Expected Remote Fingerprint*, bấm **Pin vân tay & Bắt tay TLS 1.3**.
   - Tại Bob: Dán Fingerprint của Alice vào ô *Expected Remote Fingerprint*, bấm **Pin vân tay & Bắt tay TLS 1.3**.
   - Hai bên hoàn tất Mutual TLS 1.3 và chuyển sang **BẢO MẬT (SECURE_READY)** với viền xanh. Nút "Bắt đầu gửi tệp" ở Dashboard được kích hoạt.
5. **Gửi & Nhận Tệp**:
   - Từ Dashboard của Alice: chọn tệp tin cần gửi và bấm **Bắt đầu gửi tệp**.
   - Bob nhận được thông báo yêu cầu nhận tệp kèm tên, kích thước và mã băm SHA-256; bấm Yes và chọn đường dẫn lưu tệp (chưa tồn tại).
   - Thanh tiến trình trên cả hai máy hiển thị tốc độ, % truyền và chuyển sang `COMPLETED` sau khi mã băm SHA-256 được đối chiếu khớp tuyệt đối.
6. **Lưu ý Giới hạn M3**:
   - Kênh chuyển tiếp Relay (M6) và tính năng Resume tệp dở dang (M4) chưa được kích hoạt trong giao diện Desktop UI tại mốc này. Kết nối hiện tại tập trung hoàn toàn vào Direct LAN/Localhost Mutual TLS 1.3.
