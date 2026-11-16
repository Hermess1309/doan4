# Kế hoạch và bằng chứng

15 ngày làm việc, 3–4 giờ/ngày, 45–60 giờ. Đây là ngày tương đối từ khi bắt đầu, không khẳng định đã hoàn tất theo lịch. Giảm chức năng phụ nếu thiếu thời gian, giữ yêu cầu bảo mật.

| Ngày | Mốc | Đầu ra và kiểm chứng | Trạng thái |
| --- | --- | --- | --- |
| 1 | M0 | Khung, tài liệu, 6 smoke test, CI 3 OS | Xong cục bộ; CI chưa chạy |
| 2 | M1 | Frame/codec; bounded length, partial read/EOF, malformed input | Build/test Windows PASS; chờ review |
| 3 | M2 | Identity, TLS 1.3, pinning hai chiều, negative tests | Chưa bắt đầu |
| 4–5 | M3 | Offer/accept/reject, streaming/chunk, hash, progress API | Chưa bắt đầu |
| 6 | M4 | Cancel, disconnect, resume an toàn và negative tests | Chưa bắt đầu |
| 7 | M5 | Demo LAN hai peer, timeout và lỗi filesystem | Chưa bắt đầu |
| 8–9 | M6 | Pairing, relay byte hai chiều, TLS xuyên relay | Chưa bắt đầu |
| 10–12 | M7 | Ổn định backend, hỗ trợ Antigravity tích hợp API | Chưa bắt đầu |
| 13 | M8 | Chạy demo/test thực Windows và Linux, lưu bằng chứng | Chưa bắt đầu |
| 14 | M9 | Benchmark, kiểm chứng E2E và negative tests bảo mật | Chưa bắt đầu |
| 15 | M10 | Sửa lỗi cuối, kịch bản demo và kiến thức bảo vệ | Chưa bắt đầu |

## Checklist M0

- [x] Clone đúng repository; branch riêng; giữ nguyên origin; không push/merge.
- [x] Gradle Wrapper 9.2.1, checksum distribution và kiểm tra checksum Wrapper JAR.
- [x] Java toolchain/release 21, version catalog, JUnit 5, Spotless.
- [x] Sáu module, placeholder, mỗi module một smoke test, cli/desktop run.
- [x] README, gitignore, AGENTS, SPEC, TASKS, DECISIONS.
- [x] Workflow Ubuntu/Windows/macOS với test report artifact.
- [x] Xác minh build/test/format và entry point trên Windows 11, JDK 21.
- [ ] GitHub Actions chạy thực trên cả 3 OS (cần người dùng push sau review).

## Nhật ký M0 — 2026-11-16

Workspace ban đầu trống, chưa có Git. Đã clone `https://github.com/Hermess1309/doan4.git`, repository remote trống, tạo branch `chore/m0-project-scaffold`. Không commit/push/merge. JDK hệ thống có 17/24, dùng JDK 21 portable tại `.tools/` đã đối chiếu checksum tải về; không đổi Java toàn hệ thống và không track công cụ này.

Đã chạy trên Windows 11 amd64 với Temurin 21.0.13+9 và Gradle 9.2.1:

```powershell
# Dùng JDK portable đã tải để tái kiểm tra tại workspace hiện tại.
# Với checkout mới: cài JDK 21 rồi đặt JAVA_HOME như README.
$env:JAVA_HOME = (Get-ChildItem -LiteralPath '.tools\jdk21' -Directory | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools\gradle-home'
java -version
.\gradlew.bat --version
.\gradlew.bat --no-daemon --console=plain clean spotlessCheck build
.\gradlew.bat --no-daemon --console=plain spotlessApply
.\gradlew.bat --no-daemon --console=plain clean spotlessCheck build :cli:run :desktop:run
```

Lần build đầu FAIL ở Spotless do newline thừa cuối một số file, trước bước test. `spotlessApply` PASS sau khi chuẩn hóa định dạng; không sửa logic test. Lần clean build tiếp theo PASS (54 task thực thi): 6 test chạy, 0 fail/error, cả hai entry point in thông báo M0 rồi thoát thành công. Đã đọc XML tại cả 6 thư mục `<module>/build/test-results/test/`; HTML tại `<module>/build/reports/tests/test/index.html`.

Đã đối chiếu SHA-256 Wrapper JAR và kiểm tra ignore cho `.tools/`, `.gradle/`, build output, `.env`, keystore. Không coi workflow là bằng chứng đã chạy Ubuntu/macOS; GitHub Actions chưa chạy, không có push. Smoke test M0 chỉ xác minh class/module và runtime Java 21, không chứng minh mạng hoặc bảo mật. Chưa kiểm thử UI, LAN, TLS, relay hoặc truyền file vì chưa triển khai.

Các kiến thức cần bảo vệ M0: dependency một chiều và `implementation`; toolchain so với `--release`; TCP là byte stream nên tương lai cần frame và partial-read handling; TLS endpoint khác TCP relay; fingerprint cần kênh độc lập; CI matrix khác kiểm thử thực trên hai máy.

## M1 — Protocol framing (2026-11-16)

Yêu cầu M1 được người dùng cho phép tiếp sau M0; chỉ sửa protocol và tài liệu backend. Worktree bắt buộc: `C:\Users\PC\Documents\Study\Do_an_4-codex`, branch `feat/core-network`. Không sửa desktop/UI_SPEC, crypto, relay hoặc build system.

Đã thêm MessageType, Message, FrameCodec, ProtocolException và unit tests; giữ nguyên smoke test M0. Các trường hợp test: wire vector, mọi type, empty payload, defensive copy, một byte mỗi lần đọc, nhiều frame liên tiếp, EOF tại từng byte, length unsigned sai/oversize, version/type sai trước payload, đúng giới hạn, trailing bytes, lỗi read/write và quyền sở hữu stream. Chưa triển khai/test TLS, socket timeout, file transfer, resume hoặc UI.

Đã chạy thực trên Windows 11 amd64, Temurin JDK 21.0.13+9, Gradle 9.2.1:

```powershell
Set-Location 'C:\Users\PC\Documents\Study\Do_an_4-codex'
$env:JAVA_HOME = 'C:\Users\PC\Documents\Study\Do_an_4\.tools\jdk21\jdk-21.0.13+9'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME = 'C:\Users\PC\Documents\Study\Do_an_4\.tools\gradle-home'
.\gradlew.bat --offline --no-daemon --console=plain :protocol:test
.\gradlew.bat --offline --no-daemon --console=plain :spotlessApply :protocol:spotlessApply
.\gradlew.bat --offline --no-daemon --console=plain clean spotlessCheck build
```

Kết quả: protocol 42/42 test PASS (41 ca M1 + 1 smoke M0); toàn bộ dự án 47/47 PASS, 0 skipped/failure/error. Clean build và Spotless PASS, 52 task thực thi. XML/HTML tại `<module>/build/test-results/test/` và `<module>/build/reports/tests/test/`. Không thay logic test cũ hoặc build system. Chưa tuyên bố M1 pass trên Linux/macOS tại thời điểm ghi log local này; CI sau push được báo riêng theo commit.

Kiến thức bảo vệ M1: TCP không giữ ranh giới message; length prefix và readFully; phân biệt EOF sạch với truncation; big-endian và unsigned u32 trong Java; giới hạn trước cấp phát; lỗi giữa frame phải đóng stream; framing không thay thế TLS, xác minh peer hoặc validation payload/state.
