# Hướng dẫn cộng tác

- Nền tảng bắt buộc: Windows 10/11 và Ubuntu Linux; CI chỉ ubuntu-latest/windows-latest, không thêm Windows-only API vào core hoặc tính năng riêng macOS. CI/localhost không thay demo hai máy; giữ relay E2E, fingerprint và SHA-256. Không sửa phần Antigravity trong task phạm vi/CI này.
- Java 21, Gradle Wrapper; chỉ thực hiện milestone được người dùng yêu cầu. Hiện tại là M3 (secure file transfer); không tự chuyển sang M4.
- M3 chỉ triển khai protocol payload tối thiểu, core single-file streaming, CLI localhost và tài liệu backend. Giữ mô hình pre-pin M2; không triển khai resume, relay, discovery hoặc UI.
- Codex sở hữu `protocol/`, `crypto/`, `core/`, `relay/`, `cli/`, build, CI, file này và `docs/{SPEC,TASKS,DECISIONS}.md`.
- Antigravity sở hữu `desktop/` và `docs/UI_SPEC.md`. M0 được phép bootstrap placeholder/test/build của desktop; sau đó không sửa nếu chưa có yêu cầu. Không tạo UI_SPEC thay Antigravity.
- `desktop` chỉ gọi public API của `core`. `core` không import Swing/AWT, `protocol` không phụ thuộc UI. Không tạo dependency vòng.
- Chỉ dùng công nghệ trong SPEC; giải thích và xin đồng ý trước khi thêm thư viện ngoài danh sách. Không thêm framework vì tiện.
- Giữ private key, certificates, file nhận, metadata resume, secrets và nội dung file ngoài Git/log. Không dùng trust-all, ECB hoặc mật mã tự chế.
- Một task một branch, PR một phạm vi; mục tiêu diff dưới 500 dòng khi khả thi. M0 có ngoại lệ kích thước do scaffold và Wrapper sinh sẵn.
- Không đổi origin; chỉ commit/push khi người dùng ủy quyền. Không force push, merge hoặc xóa/sửa test để che lỗi.
- Trước task: nêu mục tiêu, kiến thức mạng, file sửa, bước làm, test và tiêu chí hoàn thành. Sau task: nêu thay đổi, pass/fail, lệnh đã chạy, phần chưa kiểm thử, cách tự kiểm tra và kiến thức bảo vệ.
- Chạy `./gradlew spotlessCheck build` (PowerShell: `.\gradlew.bat spotlessCheck build`). Khi cần sửa format, chạy `spotlessApply`, đọc diff rồi kiểm tra lại.
- Không nói đã kiểm thử hệ điều hành nếu chưa thực sự chạy trên đó. Ghi bằng chứng trong TASKS, không suy diễn CI xanh từ file workflow.
- Trước khi thay public API core, cập nhật SPEC/DECISIONS và thông báo cụ thể cho người dùng để Antigravity tích hợp. Marker M0 không phải API truyền file.
