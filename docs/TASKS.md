# Kế hoạch và bằng chứng

15 ngày làm việc, 3–4 giờ/ngày, 45–60 giờ. Đây là ngày tương đối từ khi bắt đầu, không khẳng định đã hoàn tất theo lịch. Giảm chức năng phụ nếu thiếu thời gian, giữ yêu cầu bảo mật.

| Ngày | Mốc | Đầu ra và kiểm chứng | Trạng thái |
| --- | --- | --- | --- |
| 1 | M0 | Khung, tài liệu, 6 smoke test, CI 3 OS | PASS local + CI 3 OS |
| 2 | M1 | Frame/codec; bounded length, partial read/EOF, malformed input | PASS local + CI 3 OS |
| 3 | M2 | Identity, TLS 1.3, pinning hai chiều, negative tests | PASS local + CI 3 OS |
| 4–5 | M3 | Offer/accept/reject, streaming/chunk, hash, progress API | PASS Windows + CLI + 1 GiB; chờ review |
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

## M2 — TLS 1.3 security foundation (2026-11-16)

Preflight tại worktree Codex: feat/core-network sạch ở 55e1072c5aca0df84b90ceda6bf5b2199607300d, fetch origin thành công. Đã đọc code protocol, không thấy lỗi nghiêm trọng cần sửa; giữ nguyên protocol và test M1. M1 CI đã success 3 OS theo [run 43883946559](https://github.com/Hermess1309/doan4/actions/runs/43883946559).

Thêm 6 class crypto và test tự sinh identity bằng JDK keytool trong thư mục tạm; profile/key loading, TLS13-only mutual auth, certificate pin, UUID session và gated streams. Không sửa desktop/UI_SPEC, core, relay hoặc build system. API/mô hình xác minh và lệnh keytool tại SECURITY.md. Không triển khai M3.

Đã chạy thực trên Windows 11 amd64, Temurin JDK 21.0.13+9, Gradle 9.2.1, dùng JAVA_HOME/GRADLE_USER_HOME portable như mục M1:

```powershell
.\gradlew.bat --offline --no-daemon --console=plain :crypto:test
.\gradlew.bat --offline --no-daemon --console=plain :spotlessApply :crypto:spotlessApply
.\gradlew.bat --offline --no-daemon --console=plain spotlessCheck
.\gradlew.bat --offline --no-daemon --console=plain clean build
```

Tất cả PASS. Crypto 16/16 test (15 test M2 + smoke M0); toàn dự án 62/62, 0 failure/error/skipped; clean build thực thi 52 task. Đã đọc XML của cả 6 module và rà source/output không log secrets. Không dùng cert/secret cá nhân, không sửa/xóa test M1, không thay build dependency. Test bao gồm localhost TLS13 mutual auth, pin sai hai chiều, cert đổi, chưa verify không có TLS/app bytes, truyền binary nhỏ sau verify, stale UUID, explicit reject, timeout/cancel, expired/weak cert, wrong password/path/oversize PKCS12, missing client cert, TLS12 rejection và không emit console/JUL log.

Kết quả CI M2 sau push được báo theo SHA trong phản hồi; tại thời điểm ghi log này mới xác minh Windows local. Chưa chạy relay byte tunnel, demo hai máy, UI hoặc file transfer. Những giới hạn và yêu cầu core M3 được ghi tại SECURITY.md; không tự bắt đầu M3.
## M3 — Secure single-file transfer (2026-11-16)

Preflight đúng Do_an_4-codex/feat/core-network, HEAD M2 2f5ac27c50903e5a7ff885b53499d3b99f72aa8c, working tree sạch, fetch origin thành công. Đã đọc protocol/crypto thực tế và API đề xuất của Antigravity từ feat/desktop-ui (read-only). CI M2 run 43888321036 có ba job Windows/Ubuntu/macOS success khi kiểm tra. Không merge main hoặc sửa desktop/UI_SPEC.

Đã thêm FilePayloads, public core API và helper streaming/publication, CLI thật, unit/integration tests, CORE_API.md và DEMO.md. Không đổi dependency, build system hoặc crypto. UI API cần chỉnh thứ tự xác minh trước handshake và chạy verify async; mapping đã thông báo tại CORE_API.md.

Các lệnh đã chạy với JDK/cache portable như mục M1:

```powershell
.\gradlew.bat --offline --no-daemon --console=plain :protocol:compileJava :core:compileJava :cli:compileJava
.\gradlew.bat --offline --no-daemon --console=plain :protocol:test :core:test
$env:P2P_LARGE_TEST = '1'
try {
    .\gradlew.bat --offline --no-daemon --console=plain :core:test --tests 'vn.edu.p2p.core.SecureFileTransferTest.optionalOneGiBStreaming' :cli:installDist
} finally {
    Remove-Item Env:\P2P_LARGE_TEST
}
```

Các bước trên PASS trên Windows 11/JDK21. Suite core mặc định kiểm tra 10 MiB, và test riêng optionalOneGiBStreaming đã chạy PASS với file 1.073.741.824 byte, so sánh Files.mismatch + SHA-256 độc lập hai file, data sinh tạm/cleanup bởi JUnit. Bài 1 GiB không bật mặc định trên CI. Không đo peak RAM/benchmark đa máy; code dùng buffer 64 KiB/frame hữu hạn.

Demo hai tiến trình CLI Java độc lập đã chạy thực bằng identity riêng sinh ngẫu nhiên trong .tools (Git ignore), password truyền qua tên env chỉ cho subprocess, không hardcode/commit/log. Receiver nghe port do OS cấp, sender kết nối 127.0.0.1, cả hai VERIFIED TLSv1.3, truyền 1.048.576 byte; sender/receiver exit 0 và SUCCESS, hash nguồn/đích cùng 694787E0CE4DD09DA50FEAC8920718ED497AE137280D2E68272934D1D3D93DDF. Log bằng chứng cục bộ ở .tools/m3-demo-39631c151bda414e8ff211d3ff9ffebb, không commit keys/log/data. Đã xóa biến password khỏi môi trường sau demo.

Clean build cuối đã chạy: .\gradlew.bat --offline --no-daemon --console=plain clean spotlessCheck build :cli:installDist. PASS, 53 task thực thi; XML toàn dự án 106 case = 105 PASS, 0 failure/error, 1 SKIP có chủ đích (1 GiB). Riêng protocol có 59 PASS, core có 27 PASS + 1 SKIP. M3 bổ sung 43 case mặc định pass; bài 1 GiB đã bật chạy riêng PASS trước clean build. Tất cả test M1/M2 giữ nguyên và pass. Đã kiểm tra source không Swing/object serialization/readAllBytes trong core/protocol, API công khai không lộ crypto types, diff không sửa desktop/UI_SPEC/crypto/build system. Không bắt đầu M4. Các rủi ro publication/ACK mất, local directory trust, timeout/hash cost và API handoff đã ghi trong SPEC/CORE_API/DEMO.
