# Kế hoạch và bằng chứng

15 ngày làm việc, 3–4 giờ/ngày, 45–60 giờ. Đây là ngày tương đối từ khi bắt đầu, không khẳng định đã hoàn tất theo lịch. Giảm chức năng phụ nếu thiếu thời gian, giữ yêu cầu bảo mật.

| Ngày | Mốc | Đầu ra và kiểm chứng | Trạng thái |
| --- | --- | --- | --- |
| 1 | M0 | Khung, tài liệu, 6 smoke test, CI 3 OS | Xong cục bộ; CI chưa chạy |
| 2 | M1 | Frame/codec; bounded length, partial read/EOF, malformed input | Chưa bắt đầu |
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
