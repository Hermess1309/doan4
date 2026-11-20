# P2P File Transfer

Đồ án 4: **Ứng dụng truyền tải tệp tin ngang hàng đa nền tảng bảo mật mã hóa đầu cuối** bằng Java 21, Java Swing + FlatLaf và Mutual TLS 1.3. Repository chính thức: [Hermess1309/doan4](https://github.com/Hermess1309/doan4).

## Tính năng hiện có

- **Mã hóa đầu cuối & Xác thực hai chiều (Mutual TLS 1.3):** Mỗi thiết bị tự sinh cặp khóa và chứng chỉ PKCS#12 độc lập; hai peer đối chiếu và pin toàn bộ chuỗi **SHA-256 fingerprint** trước khi mở phiên truyền tệp (không trust-all, không mật mã tự chế).
- **Truyền tệp tin đơn an toàn (Streaming):** Truyền trực tiếp theo luồng qua TCP socket, kiểm tra toàn vẹn **SHA-256** nguồn/đích, xác nhận thủ công phía nhận (accept/reject), chống ghi đè tệp tồn tại (**no-overwrite**) và tự dọn tệp tạm khi hủy hoặc mất kết nối đột ngột.
- **Hai chế độ kết nối:**
  - **Direct LAN (P2P trực tiếp):** Kết nối thẳng qua IP và cổng TCP trong mạng nội bộ.
  - **Relay Server (P2R1):** Ghép nối qua mã ngắn hạn (`create-pair` / `join-pair`, có TTL, giới hạn phiên và rate-limit); relay chỉ chuyển tiếp byte mù và **không** thể giải mã nội dung TLS 1.3 giữa hai peer.
- **Ứng dụng Desktop (Java Swing + FlatLaf 3.5.4):** Giao diện đầy đủ gồm 4 khu vực (*Tổng quan*, *Thiết bị & Mạng LAN/Relay*, *Bảo mật vân tay SHA-256*, *Tiến trình truyền tải*), bảo vệ thư mục danh tính theo cơ chế **fail-closed** (POSIX `0700`/`0600` trên Linux và kiểm tra ACL trên Windows).
- **CLI độc lập:** Hỗ trợ đầy đủ `fingerprint`, `receive`, `send`, `relay-server`, `relay-receive`, `relay-send` cho kiểm thử và vận hành Relay Server.

## Nền tảng & Trạng thái kiểm thử

- **Nền tảng mục tiêu:** Windows 10/11 và Ubuntu Linux (Java 21). macOS không thuộc phạm vi bắt buộc.
- **Kiểm thử đã hoàn tất:**
  - Bộ kiểm thử tự động 6 module trên Windows 11, Ubuntu 24.04.5 LTS và GitHub Actions CI (`windows-latest`, `ubuntu-latest`).
  - Nghiệm thu thực tế giữa **hai cửa sổ Swing GUI** Windows 11 ↔ Ubuntu 24.04.5 LTS trên mạng ảo VMware NAT cho cả **Direct LAN** và **Relay**, khớp 100% SHA-256 nguồn/đích cùng các kịch bản sai fingerprint, reject, no-overwrite, hủy kết nối và mất kết nối đột ngột.
- **Giới hạn hiện tại:**
  - Chưa triển khai **Resume/Pause truyền tệp dở (M4)** và tự động phát hiện thiết bị LAN (**LAN discovery**).
  - **Relay qua Public Internet (VPS công cộng):** Chưa kiểm thử do chưa có hạ tầng VPS có IPv4 công cộng (`NOT TESTED — INFRASTRUCTURE REQUIRED`).

## Yêu cầu môi trường

Cài **JDK 21** đầy đủ (có `keytool`, ví dụ Eclipse Temurin 21 trên Windows hoặc `openjdk-21-jdk` trên Ubuntu), đặt `JAVA_HOME` tới thư mục JDK và đưa `JAVA_HOME/bin` vào `PATH`. Không cần cài Gradle toàn hệ thống; Gradle Wrapper (`9.2.1`) sẽ tự tải ở lần chạy đầu.

Build khóa Java toolchain và `--release` ở phiên bản **21**; không tự tải JDK và không hardcode đường dẫn JDK cục bộ vào script build.

## Build và chạy trên Windows (PowerShell)

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat --no-daemon --console=plain clean spotlessCheck build
.\gradlew.bat --no-daemon :desktop:installDist :cli:installDist
```

Khởi chạy ứng dụng sau khi `installDist`:

```powershell
# Desktop GUI (Swing + FlatLaf)
.\desktop\build\install\desktop\bin\desktop.bat

# Smoke test khởi tạo & giải phóng MainFrame
.\desktop\build\install\desktop\bin\desktop.bat --smoke-test

# CLI (xem hướng dẫn đầy đủ các lệnh Direct & Relay)
.\cli\build\install\cli\bin\cli.bat --help
```

## Build và chạy trên Ubuntu Linux

```sh
java -version
chmod +x gradlew
./gradlew --version
./gradlew --no-daemon --console=plain clean spotlessCheck build
./gradlew --no-daemon :desktop:installDist :cli:installDist
```

Khởi chạy ứng dụng sau khi `installDist`:

```sh
# Desktop GUI (Swing + FlatLaf)
./desktop/build/install/desktop/bin/desktop

# Smoke test khởi tạo & giải phóng MainFrame
./desktop/build/install/desktop/bin/desktop --smoke-test

# CLI (xem hướng dẫn đầy đủ các lệnh Direct & Relay)
./cli/build/install/cli/bin/cli --help
```

> **Lưu ý:** Dùng bản launcher từ `:cli:installDist` / `:desktop:installDist` thay vì `gradlew :cli:run` khi cần tương tác bàn phím/nhập mật khẩu. Chạy `./gradlew spotlessApply` nếu cần chuẩn hóa định dạng rồi kiểm tra lại bằng `./gradlew spotlessCheck build`.

Báo cáo JUnit nằm tại `<module>/build/reports/tests/test/index.html` và XML tại `<module>/build/test-results/test/`. GitHub Actions CI chạy trên `windows-latest` và `ubuntu-latest` với JDK 21.

## Cấu trúc module và phụ thuộc

| Module | Trách nhiệm | Phụ thuộc trực tiếp |
| --- | --- | --- |
| `protocol` | Khung bản tin (frame), thông điệp, encoder/decoder có giới hạn kích thước | Không |
| `crypto` | JSSE Mutual TLS 1.3, danh tính PKCS#12, SHA-256 fingerprint & pinning | Không |
| `core` | Quản lý phiên TLS, truyền tệp streaming, SHA-256 integrity, hủy kết nối, no-overwrite | `protocol`, `crypto` |
| `relay` | Giao thức P2R1, mã ghép nối có TTL/rate-limit, chuyển tiếp byte mù | `protocol` |
| `cli` | Giao diện dòng lệnh (`fingerprint`, `send`, `receive`, `relay-server`, `relay-send`, `relay-receive`) | `core`, `relay` |
| `desktop` | Giao diện đồ họa Java Swing + FlatLaf 3.5.4 tích hợp `core` và `relay` | `core`, `relay`, `FlatLaf 3.5.4` |

Các phụ thuộc giữa module nội bộ dùng `implementation`; `core` không import Swing/AWT và `protocol` không phụ thuộc UI.

## Tài liệu kỹ thuật

- [Đặc tả hệ thống & bảo mật (`docs/SPEC.md`)](docs/SPEC.md)
- [Public API tầng `core` (`docs/CORE_API.md`)](docs/CORE_API.md)
- [Thiết kế bảo mật & danh tính (`docs/SECURITY.md`)](docs/SECURITY.md)
- [Hướng dẫn demo Direct LAN (`docs/DEMO.md`)](docs/DEMO.md)
- [Kiến trúc, demo & giới hạn Relay (`docs/RELAY.md`)](docs/RELAY.md)
- [Đặc tả giao diện Desktop (`docs/UI_SPEC.md`)](docs/UI_SPEC.md)
- [Quyết định kiến trúc (`docs/DECISIONS.md`)](docs/DECISIONS.md)
- [Nhật ký công việc & bằng chứng (`docs/TASKS.md`)](docs/TASKS.md)
