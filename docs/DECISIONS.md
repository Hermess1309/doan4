# Nhật ký quyết định

## D001 — M0 giới hạn ở scaffold (2026-11-16, chấp nhận)

Không triển khai frame, TLS, truyền file hoặc relay. `*Module` và entry point chỉ là marker build. M0 chưa có public API truyền file, không hứa chữ ký phương thức cho Antigravity. Khởi tạo desktop placeholder theo yêu cầu M0; UI và UI_SPEC tiếp tục thuộc Antigravity.

## D002 — Build tập trung, dependency một chiều (chấp nhận)

Java 21, Kotlin DSL, 6 Gradle subprojects, classpath; chưa cần JPMS/buildSrc/convention plugin ở quy mô này. Root cấu hình toolchain, compiler, JUnit và Spotless; từng module khai báo dependency trực tiếp. `implementation` giữ chi tiết protocol/crypto sau core, UI chỉ phụ thuộc core. Nếu public API cần lộ kiểu module khác, phải review và ghi quyết định mới.

## D003 — Cố định phiên bản và công cụ (chấp nhận)

Gradle 9.2.1 (Wrapper), JUnit 5.14.1, Spotless 8.1.0 (version catalog). Đây là bộ phiên bản cố định để tái lập M0, không tuyên bố là phiên bản mới nhất. Wrapper JAR phải khớp SHA-256 `423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9`; distribution hash đặt trong wrapper properties.

Không thêm thư viện runtime ở M0. Spotless dùng bước khoảng trắng/import tích hợp, chưa thêm Google Java Format. Chưa thêm FlatLaf/JmDNS. Toolchain không tự tải JDK: người dùng/CI cài JDK 21 rõ ràng; JDK portable phục vụ xác minh cục bộ nằm trong `.tools/` bị Git ignore.

Nguồn phiên bản: [Gradle checksum](https://gradle.org/release-checksums/), [Wrapper](https://docs.gradle.org/9.2.1/userguide/gradle_wrapper.html), [Spotless 8.1.0](https://plugins.gradle.org/plugin/com.diffplug.spotless/8.1.0), [JUnit 5.14.1](https://docs.junit.org/5.14.1/_exports/junit-user-guide-5.14.1.html).

## D004 — Xác minh peer trước dữ liệu ứng dụng (thiết kế MVP, kiểm chứng ở M2)

Chọn nhập/đối chiếu đầy đủ SHA-256 fingerprint qua kênh độc lập trước handshake, pin chứng chỉ hai chiều trong JSSE và mutual TLS. Cách này tránh handshake trust-all để xem chứng chỉ. Tạo identity bằng `keytool` JDK, lưu cục bộ, không dùng `sun.security.*`. Luồng UI, thời hạn/rotation chứng chỉ, password/permissions keystore sẽ chốt tại M2. Không coi mã ghép nối là mật khẩu hay xác thực.

## D005 — Relay chỉ nối byte (thiết kế MVP, kiểm chứng ở M6)

Pairing/signaling tách khỏi dữ liệu file. Sau khi ghép, relay pump byte hai chiều, peer bọc socket bằng TLS; TLS kết thúc ở peer. Pairing có thể lộ metadata ghép phiên; E2E không che timing/IP/volume. M6 phải chốt timeout/TTL, giới hạn kết nối/rate, cleanup và chống cạn tài nguyên. Không NAT traversal ở MVP.

## D006 — Resume fail-closed (thiết kế MVP, chốt ở M4)

Không chấp nhận sidecar hoặc offset tự khai báo. Cần ràng buộc peer/file/offset và kiểm chứng dữ liệu cục bộ; phương án đơn giản dự kiến là kiểm tra hash prefix kết hợp full-file hash sau cùng. Nếu không bảo đảm an toàn thì vô hiệu resume và truyền lại. Thuật toán, lưu metadata, atomic update và chính sách file tạm chưa triển khai, phải có test trước bật.

## D007 — Tham số và API còn mở (đề xuất, chưa là wire contract)

Đề xuất frame tối đa 1 MiB, chunk 64 KiB, connect timeout 10 giây, read timeout 30 giây; phải cân bằng cancel responsiveness và đường truyền chậm bằng test ở M1/M3. Chốt framing/version/type/endianness, cách hash/nhận biết nguồn thay đổi, tên file đa nền tảng và giới hạn kích thước trước khi làm codec/transfer. Không hardcode contract qua placeholder.

Progress callback của core chạy trên worker, UI marshal sang EDT; cần chốt threading/lifetime, transfer state, error model và giới hạn tần suất callback tại M3. Mỗi thay đổi API phải báo người dùng và cập nhật tài liệu tích hợp trước khi Antigravity dùng.

## D008 — Tính khả thi 15 ngày (chấp nhận)

Ưu tiên một file/phiên, đường dẫn đích do receiver chọn, không ghi đè, SHA-256 trước completion. Cắt discovery, lịch sử, animation, hàng đợi nếu thiếu thời gian. CI kiểm tra 3 OS; demo sản phẩm bắt buộc Windows/Linux. Chỉ ghi pass khi có lệnh/kết quả thực tế; M0 không thể xác minh tính an toàn của thiết kế chưa có code.
