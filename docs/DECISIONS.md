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

Ưu tiên một file/phiên, đường dẫn đích do receiver chọn, không ghi đè, SHA-256 trước completion. Cắt discovery, lịch sử, animation, hàng đợi nếu thiếu thời gian. CI trước cập nhật kiểm tra 3 OS; theo D013 hiện chỉ Windows/Ubuntu, demo bắt buộc Windows 10/11 và Ubuntu Linux. Chỉ ghi pass khi có lệnh/kết quả thực tế; M0 không thể xác minh tính an toàn của thiết kế chưa có code.

## D009 — Chốt binary envelope M1 (2026-11-16, triển khai theo yêu cầu M1)

Hoàn tất phần framing còn mở tại D007: u32 big-endian body length, u8 version=1, u8 type, payload opaque; body tối đa 1 MiB, tối thiểu 2 byte. Không dùng magic/checksum tự chế: các lỗi cấu trúc bị từ chối, xác thực/toàn vẹn do TLS M2 đảm nhiệm. Không hỗ trợ tự resync sau lỗi.

Chọn 9 ID tường minh theo hành vi SPEC: OFFER/ACCEPT/REJECT/CHUNK/COMPLETE/CANCEL/RESUME_REQUEST/RESUME_RESPONSE/ERROR. Không dùng enum ordinal; không thêm pairing/relay messages ở M1. Wire table và API chi tiết tại SPEC. Payload schema và state validation được trì hoãn đúng M3/M4, không mở chức năng resume sớm. Thay đổi wire contract sau này cần quyết định/version phù hợp.

Chọn blocking stream API đơn giản cho MVP: encode/decode byte array hoặc write/read một frame trên stream; Optional.empty biểu thị EOF sạch, EOFException biểu thị frame bị cắt. ProtocolException kế thừa IOException; caller đóng connection sau mọi lỗi đọc/ghi. Không tự close/flush stream để adapter quản lý buffer và tài nguyên. Length unsigned được kiểm tra bằng long trước cấp phát payload; version/type cũng được kiểm tra trước cấp phát. Mỗi payload có defensive copy và giới hạn hữu hạn.

D007 còn mở timeout socket, schema metadata/chunk, nguồn file thay đổi và API progress của core; không coi những phần đó đã được triển khai. Không thay public API core hoặc thêm dependency/build plugin.

## D010 — M2 strict pre-handshake verification (2026-11-16, triển khai)

Giữ D004: không thiết lập TLS với cert chưa có full pin đã đối chiếu độc lập. Pending là TCP chưa chạy TLS, remote fingerprint chỉ có sau handshake. Không có trust-all/TOFU hay encrypted-preview mode; UI phải nói rõ trạng thái. verify nhận UUID phiên để chống áp dụng callback cũ sang connection mới. Một SSLContext mới mỗi connection tránh resumption vượt kiểm tra pin mới.

Identity MVP chọn profile hẹp: một private-key entry PKCS12, một cert X509v3 self-signed non-CA, RSA 3072–8192 bit, SHA256withRSA, digitalSignature + EKU hai vai trò, còn hạn. Loader kiểm tra key/cert khớp bằng JCA Signature và xóa bản sao password/buffer; khóa cần thiết vẫn tồn tại trong KeyManager/JVM. Không dùng thư viện ngoài hoặc API JDK nội bộ.

PinnedTrustManager không chỉ so pin: kiểm tra profile/chữ ký/thời hạn, rồi tạo trust anchor tạm chỉ từ cert đã khớp và delegate platform X509ExtendedTrustManager để giữ algorithm/role constraints. Peer identity dựa vào pin, không HTTPS hostname; relay/IP không phải identity. TLSv1.3-only và needClientAuth trên server.

PeerTlsSession sở hữu socket, gate mọi stream operation, không trả raw JSSE socket/session; snapshot metadata không tự cấp quyền truyền. Verification/handshake/I/O failure đóng phiên; close có thể hủy handshake. Local TLS completion không chứng minh remote đã xác minh xong/accept offer (flight cuối TLS1.3 có thể còn được server xử lý). M3 phải xử lý alerts, EOF và protocol errors, chỉ truyền sau state VERIFIED và offer accepted.

M2 diff có thể vượt mục tiêu mềm 500 dòng vì identity validation, six TrustManager entry points, lifecycle/cancel gate và negative tests phải được review cùng nhau; không thêm chức năng M3. Xem SECURITY.md về keytool, API, các test và giới hạn timeout/OS permissions.

## D011 — M3 single-file blocking core và payload schema (2026-11-16)

Tái sử dụng M1 envelope và M2 strict pre-pin TLS, không sửa crypto/trust policy. Thêm FilePayloads với OFFER/controls/CHUNK/COMPLETE; không thêm message ID. COMPLETE chiều sender là end-of-data; chiều receiver chỉ được gửi sau disk hash + publication. Sender chờ ACK trước success để tránh coi write socket thành công là file đã lưu.

Core dùng PeerNode/PeerListener/TransferSession và DTO core-only; chưa dựng đầy đủ P2pEngine async/relay/resume. Đã đọc interface đề xuất trong branch Antigravity: boolean confirm sau handshake sẽ deadlock/đi ngược M2. Adapter phải nhập trusted pin trước handshake, verify trên worker và map VERIFIED -> READY. Accept nhận đường dẫn file cục bộ đầy đủ thay vì tự dùng remote filename. Bảng mapping tại CORE_API.md, không sửa desktop/UI_SPEC.

Chọn 64 KiB buffer/chunk, max file 16 GiB để giới hạn metadata/offset và vẫn cho test 1 GiB. Hash nguồn trước offer và trong quá trình send trên cùng descriptor; receiver đọc lại temp sau force. Chi phí thêm I/O được chấp nhận để có hash kỳ vọng từ đầu và kiểm chứng file thực trên đĩa.

## D012 — No-overwrite publication và giới hạn M3 (2026-11-16)

Không dùng Files.move(ATOMIC_MOVE) do đích tồn tại có hành vi provider-dependent; dùng createLink(final, temp) rồi xóa temp trong cùng trusted local directory. Tạo final entry fail nếu đã có file/link kể cả xuất hiện sau khi check trước đó. Hard link không hỗ trợ -> fail closed, không fallback copy vào final chưa hoàn tất hoặc replace. Quyền OS/parent/temp vẫn thuộc trusted receiver, endpoint compromise ngoài threat model. Link publication không đồng nghĩa crash-safe fsync parent hoặc exactly-once hai máy.

ACK có thể mất sau final entry đã tạo; khi đó receiver có file hợp lệ nhưng sender fail. Lỗi cleanup sau link cũng có thể để lại final hợp lệ. Không xóa final, không retry/overwrite tự động. Partial temp được dọn khi lỗi trong điều kiện OS cho phép; không claim resume M4. Progress observer RuntimeException được cô lập; completion chỉ sau hash/publication và ACK, không từ tỷ lệ phần trăm.

M3 diff vượt mục tiêu mềm 500 dòng vì schema + public core boundary + streaming filesystem lifecycle + CLI và negative TLS integration tests là một milestone được yêu cầu; không thêm thư viện/build plugin hay chức năng M4. Test 1 GiB tách bằng P2P_LARGE_TEST=1, mặc định skip trên CI.

## D013 — Chốt Windows 10/11 + Ubuntu, bỏ macOS khỏi MVP/CI (2026-11-16)

Theo chỉ đạo người dùng sau khi M3 hoàn tất (f2037d6, CI run 43894605508 success), chỉ hai họ nền tảng mục tiêu: Windows 10/11 và Ubuntu Linux. Matrix mới chính xác ubuntu-latest/windows-latest; Java 21 không đổi. Các log M0–M3 có macOS là bằng chứng lịch sử, không còn là nghĩa vụ CI/nghiệm thu hiện hành. Không thêm tính năng riêng macOS hoặc Windows-only API vào core.

CI tạo cả CLI/desktop distributions, smoke launcher Unix/.bat; Ubuntu có Xvfb để chạy smoke UI khi desktop thật được tích hợp. Ubuntu runner-image công bố xvfb trong [installed software](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md). Build, launcher smoke, localhost TLS integration và GUI/mạng hai máy là các mức bằng chứng khác nhau; không suy diễn Windows 10/11 hoặc GUI thật từ tên runner.

Đã rà chỉ đọc source branch feat/desktop-ui: Swing/FlatLaf/JDK, không thấy command/API Windows riêng; DesktopApplication có --smoke-test và nhánh headless tường minh. Branch Codex hiện vẫn chứa desktop placeholder, không merge/chỉnh desktop/UI_SPEC. Ubuntu GUI thực và adapter transfer sau tích hợp vẫn phải nghiệm thu; Xvfb/CI không thay thế desktop session thật.

Giữ đủ bốn kịch bản Windows->Ubuntu, Ubuntu->Windows, Windows->Windows, hai mạng qua relay, với TLS1.3/pin/SHA-256 mỗi lần. Relay tiếp tục M6, không bị giảm phạm vi. Dùng NTFS/ext4 hoặc filesystem đã xác minh hard-link no-replace; không thêm fallback ghi đè hoặc thay security model M2/M3.

## D014 — M6 trước Resume, relay TCP opaque và API attach (2026-11-16)

Người dùng ưu tiên relay trước M4; origin/main thực tế còn M0, đã cho phép tạo feat/relay-mvp từ origin/main rồi fast-forward toàn bộ origin/feat/core-network. Không cherry-pick, không sửa main/desktop, không merge PR. Baseline clean spotlessCheck build PASS: 105 PASS, 1 optional skip, 0 lỗi.

Dùng metadata protocol P2R1 riêng trước TLS, code ngẫu nhiên 60 bit, TTL và one-shot routing; code không phải identity/key. Sau paired chỉ copy byte hữu hạn, không TLS termination. Bổ sung PeerNode.attach(Socket,TlsRole,int) để dùng nguyên TransferSession M3 và strict pre-pin M2, không tạo hệ thống truyền file thứ hai. Pending ticket/connection có deadline, giới hạn phiên/kết nối, rate-limit theo IP và toàn cục. Thất bại đóng hai đầu; không resume/half-close. Giới hạn MVP/định dạng/lệnh chi tiết ở RELAY.md.

M6 diff vượt mục tiêu mềm 500 dòng vì giới hạn tài nguyên/rate-limit, socket ownership, CLI và negative/subprocess/ciphertext tests thuộc cùng milestone được yêu cầu; không thêm dependency hoặc phạm vi M4. Ghi control reply của host dưới khóa riêng, không giữ khóa toàn server khi I/O, tránh JOIN sớm xen PAIRED vào CREATED/TTL.
