# P2P File Transfer

Đồ án 4: ứng dụng truyền tệp ngang hàng đa nền tảng, mã hóa đầu cuối bằng TLS 1.3 giữa hai peer. Java 21, Gradle multi-module; desktop dự kiến dùng Swing + FlatLaf. Repository chính thức: [Hermess1309/doan4](https://github.com/Hermess1309/doan4).

**Trạng thái M1:** có khung build 6 module, binary frame codec version 1 và unit tests cho protocol (xem wire contract trong SPEC). Chưa có kết nối mạng, TLS, truyền file, relay hoạt động hoặc UI. Không dùng scaffold này để truyền dữ liệu thật.

## Chuẩn bị

Cài JDK **21**, đặt `JAVA_HOME` tới thư mục JDK và đưa `JAVA_HOME/bin` vào PATH. Không cần cài Gradle toàn hệ thống. Wrapper tải Gradle 9.2.1 ở lần chạy đầu; cần Internet tới Gradle, Plugin Portal và Maven Central.

Build khóa toolchain và `--release` ở 21; không tự tải JDK. JDK khác không thay thế yêu cầu JDK 21. Không đưa đường dẫn JDK riêng của máy vào file build.

## Build và chạy trên Windows (PowerShell)

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat --no-daemon --console=plain clean spotlessCheck build
.\gradlew.bat :cli:run :desktop:run
```

## Build và chạy trên Linux/macOS

```sh
java -version
chmod +x gradlew
./gradlew --version
./gradlew --no-daemon --console=plain clean spotlessCheck build
./gradlew :cli:run :desktop:run
```

Hai lệnh `run` chỉ in thông báo M0 rồi thoát, không mở cửa sổ hay socket. Relay chưa có entry point. `build` chạy test và Spotless ở cả 6 module; `:core:test` chạy riêng test core. `spotlessApply` sửa khoảng trắng, tab, thứ tự import và newline Java; M0 chưa dùng formatter Java bên ngoài.

Báo cáo test tại `<module>/build/reports/tests/test/index.html`, XML tại `<module>/build/test-results/test/`. CI cấu hình Ubuntu, Windows, macOS với JDK 21 và lưu test report; trạng thái thực thi ghi trong [TASKS](docs/TASKS.md).

## Cấu trúc và dependency

| Module | Trách nhiệm dự kiến | Phụ thuộc trực tiếp |
| --- | --- | --- |
| protocol | Frame, message, encoder/decoder có giới hạn | Không |
| crypto | JSSE TLS 1.3, identity, fingerprint, pinning | Không |
| core | Session, file streaming, progress, cancel, resume | protocol, crypto |
| relay | Pairing, signaling, chuyển tiếp byte | protocol |
| cli | Adapter dòng lệnh để demo/kiểm thử | core, relay |
| desktop | Adapter Swing do Antigravity sở hữu | core |

Dependency đều là `implementation`; chưa có kiểu protocol/crypto được lộ ra qua API core. Nếu API tương lai cần đổi điều này, phải ghi quyết định trước. M0 dùng classpath, chưa dùng JPMS `module-info.java`. `*Module.NAME` chỉ là marker để smoke test.

Đọc [SPEC](docs/SPEC.md) để hiểu phạm vi và bảo mật, [TASKS](docs/TASKS.md) để theo dõi 15 ngày, [DECISIONS](docs/DECISIONS.md) để biết quyết định và điểm còn mở. Build/CI không chứng minh hệ thống đã có E2E; các test bảo mật chỉ được bổ sung ở milestone tương ứng.
