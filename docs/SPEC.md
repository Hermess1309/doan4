# Đặc tả MVP

## Phạm vi và trạng thái

Sinh viên làm cá nhân trong 15 ngày làm việc, 3–4 giờ/ngày (45–60 giờ), có Codex phụ trách backend và Antigravity phụ trách desktop. Mục tiêu bắt buộc: CLI/desktop và demo truyền file thật trên Windows 10/11 và Ubuntu Linux, có test và bằng chứng bảo mật. macOS ngoài phạm vi MVP 15 ngày; CI chỉ Windows/Ubuntu.

M0–M3 đã hoàn tất trên backend; M6-MVP (ưu tiên trước M4) bổ sung TCP relay có giới hạn và truyền file qua TLS 1.3 đầu cuối. Resume và UI tích hợp chưa triển khai trong branch này; public Internet và demo hai OS còn chờ nghiệm thu. Xem RELAY.md; không tự chuyển sang M4.

## Kiến trúc

Luồng LAN: `Peer A (core/crypto) <--- TCP + TLS 1.3 ---> Peer B (core/crypto)`.

Luồng Internet: `Peer A <-- TCP --> Relay <-- TCP --> Peer B`; hai TCP socket được relay ghép thành đường byte hai chiều. Sau ghép phiên, TLS 1.3 chạy xuyên đường này, đầu cuối vẫn là A và B. Relay không tạo SSLSocket để kết thúc phiên TLS peer.

Dependency tĩnh (`A -> B`: A dùng B): `core -> protocol, crypto`; `relay -> protocol`; `cli -> core, relay`; `desktop -> core`. protocol/crypto không phụ thuộc module khác. Không vòng dependency, không Swing/AWT trong core, không UI trong protocol.

Trong M0 mọi module dùng chung cấu hình Java/JUnit/Spotless ở root. cli/desktop có entry point placeholder; không có socket được mở. Không thêm FlatLaf trước công việc UI.

## Mô hình đe dọa và xác minh

- Mạng và relay có thể bị nghe lén, sửa dữ liệu hoặc giả mạo. TLS bảo vệ nội dung, tính toàn vẹn và xác thực peer sau khi pin đúng chứng chỉ.
- Mỗi peer có chứng chỉ tự ký và private key sinh/lưu cục bộ. Dự kiến dùng `keytool` đi kèm JDK, không dùng API nội bộ `sun.*` hoặc hardcoded key. Private key không rời máy, không vào Git/log.
- Hiển thị fingerprint SHA-256 của chứng chỉ DER. Hai bên trao đổi/so sánh đầy đủ fingerprint qua kênh tin cậy độc lập trước khi cho phép truyền. Không dùng chính relay hay mã ghép nối để xác nhận fingerprint.
- Phương án MVP: cả hai peer cung cấp fingerprint đã xác minh trước handshake; pinning trong TrustManager và mutual TLS, server yêu cầu client certificate. Không có chế độ trust-all hoặc tự chấp nhận lần đầu. Thay chứng chỉ phải xác minh lại.
- Chỉ TLS 1.3 được bật. Không gửi offer, metadata file hay nội dung file trước khi hoàn tất xác minh peer và handshake. Người nhận vẫn phải chấp nhận offer riêng trước chunk đầu tiên.
- Mã ngắn chỉ định tuyến ghép phiên. Relay thấy địa chỉ IP, timing, kích thước lưu lượng và thông tin pairing; không thấy tên/nội dung file hay private key. Không tuyên bố che giấu metadata mạng.
- Không bảo vệ máy đầu cuối đã nhiễm mã độc, người dùng tự xác minh sai fingerprint hoặc tấn công từ chối dịch vụ quy mô lớn. TLS không mã hóa file sau khi lưu trên đĩa.

## Vòng đời dự kiến

`DISCONNECTED -> CONNECTING -> VERIFYING -> READY -> OFFERED -> TRANSFERRING -> VERIFYING_FILE -> COMPLETED`.

Reject kết thúc offer; cancel, timeout, lỗi I/O hoặc lỗi hash đưa transfer về trạng thái kết thúc tương ứng và đóng tài nguyên. State machine chi tiết, wire messages và public API sẽ được chốt theo milestone, không coi các nhãn trên là API M0.

## Truyền file và xử lý lỗi

- Một phiên truyền một file tại một thời điểm. File lớn đọc/ghi streaming theo chunk hữu hạn, không đọc toàn bộ vào RAM. Hỗ trợ file rỗng; phát hiện file nguồn thay đổi trong quá trình gửi.
- Offer gồm định danh transfer, tên hiển thị, kích thước và dữ liệu hash cần thiết; receiver phải chấp nhận/từ chối. Chi tiết định dạng thuộc M1/M3.
- Decoder giới hạn độ dài trước cấp phát, từ chối version/type/length không hợp lệ, xử lý partial read và EOF giữa frame. Writer phải xử lý partial write nếu dùng NIO. Có timeout connect/read và đóng kết nối khi lỗi.
- Tên từ mạng không được quyết định đường dẫn lưu. Receiver chọn thư mục/tên đích cục bộ, từ chối path traversal và đường dẫn tuyệt đối; tránh symlink/reparse escape và race. Không ghi đè file có sẵn ngoài ý muốn.
- Ghi file tạm trong vị trí do receiver quản lý; chỉ công bố file hoàn tất sau kiểm tra SHA-256 và thao tác hoàn tất không ghi đè. Khi hash sai không báo thành công, không công bố file hỏng.
- Cancel có hiệu lực trong lúc streaming, có trạng thái rõ, giải phóng socket/stream/file; file tạm chỉ giữ lại nếu đủ điều kiện resume an toàn.
- Resume phải gắn với peer đã xác minh, định danh transfer, kích thước/hash file, offset giới hạn và phần dữ liệu cục bộ đã kiểm chứng. Không tin metadata từ mạng hoặc sidecar đơn thuần; không cho offset vượt EOF, khác file hoặc khác peer.
- Nếu chưa chứng minh metadata resume an toàn, resume bị vô hiệu hóa và bắt đầu lại từ đầu. Không dùng fallback resume thiếu xác minh để chạy kịp tiến độ.
- Không log secrets, pairing code, private key hoặc nội dung file. Log trạng thái/lỗi tối thiểu; kiểm soát tên file/đường dẫn để tránh log dữ liệu nhạy cảm.

## Ranh giới API core cho desktop

M3 đã phát hành public API nhỏ gọn cho direct connect/listen, xác minh phiên, nhận offer, accept/reject, gửi/nhận file và progress callback (CORE_API.md). Đóng connection chỉ là network abort; cancel/resume hoàn chỉnh thuộc M4. Adapter desktop chỉ thấy kiểu public của core và JDK, không gọi trực tiếp relay/crypto/protocol.

Callback dự kiến cung cấp transfer ID, state, bytes đã xử lý, tổng bytes và lỗi có kiểu. Progress đơn điệu trong một lần truyền; completion chỉ sau hash và hoàn tất file. Callback có thể chạy trên worker thread, UI phải chuyển sang EDT bằng `SwingUtilities.invokeLater`; core không phụ thuộc Swing. Chốt quy tắc threading/lifetime và thông báo thay đổi API trước khi Antigravity tích hợp.

## Tiêu chí nghiệm thu theo giai đoạn

- M0: Wrapper + Java 21, đủ module, 6 smoke test, format/build pass, CI Windows/Ubuntu theo phạm vi đã chốt và tài liệu đúng phạm vi.
- M1: round-trip, frame quá lớn, malformed frame, partial read, EOF và version không hỗ trợ.
- M2: TLS 1.3 thật, hai chiều pin đúng, sai/thiếu chứng chỉ hoặc fingerprint bị từ chối; không có trust-all.
- M3–M5: file rỗng/nhỏ/lớn, streaming, accept/reject, hash mismatch, path traversal, file đích tồn tại, timeout, disconnect, cancel và metadata resume sai.
- M6: hai peer trao đổi file qua relay; xác nhận endpoint TLS là peer; relay chỉ chuyển byte mã hóa. Negative test pin sai qua relay phải fail, không gửi file.
- M7–M9: demo thực Windows/Linux; benchmark thời gian, throughput và bộ nhớ với tham số công bố; lưu phiên bản/lệnh/kết quả, không lưu khóa hay nội dung nhạy cảm.

## Công nghệ và phần cắt giảm

Cho phép Java 21, Gradle Wrapper, Socket/ServerSocket/NIO, JSSE, TLS 1.3, SHA-256, JUnit 5, Spotless, Swing/FlatLaf. JmDNS ưu tiên thấp. Thư viện khác cần giải thích và người dùng đồng ý trước khi thêm.

Loại khỏi MVP: NAT hole punching, STUN/TURN, tài khoản/cloud storage, mã hóa tự chế, nhiều transfer song song. Hàng đợi nhiều file chỉ làm khi còn thời gian. Cắt JmDNS, lịch sử, animation và chức năng phụ trước khi giảm bảo mật.

## Wire contract M1 — version 1

Đây là phần chốt framing được giao ở D007, không bổ sung chức năng truyền file hay thay đổi yêu cầu bảo mật. Payload hiện là byte array opaque; schema metadata/chunk và kiểm tra trạng thái thuộc M3/M4. Module khác không được coi payload tùy ý là một offer/resume hợp lệ.

| Offset | Trường | Quy tắc |
| --- | --- | --- |
| 0–3 | bodyLength | u32 big-endian, tính version + type + payload, không tính 4 byte prefix |
| 4 | version | u8, hiện chỉ chấp nhận 1 |
| 5 | type | u8, ID cố định theo bảng dưới |
| 6… | payload | bodyLength - 2 byte, giữ nguyên nội dung binary |

Giới hạn bodyLength: 2 đến 1.048.576 byte (1 MiB), payload tối đa 1.048.574 byte. Dùng long để kiểm tra length unsigned trước khi cấp phát payload, kể cả 0x80000000 và 0xffffffff. Validate version/type trước cấp phát. Chunk file 64 KiB vẫn là mục tiêu M3; giới hạn frame không phải kích thước file.

| ID | MessageType | Ý nghĩa dành cho milestone tiếp theo |
| --- | --- | --- |
| 1 | OFFER | Đề nghị truyền một file |
| 2 | ACCEPT | Chấp nhận offer |
| 3 | REJECT | Từ chối offer |
| 4 | CHUNK | Một phần dữ liệu file |
| 5 | COMPLETE | Sender kết thúc dữ liệu; chưa chứng minh receiver kiểm tra hash thành công |
| 6 | CANCEL | Hủy transfer |
| 7 | RESUME_REQUEST | Yêu cầu resume; chưa bật chức năng ở M1 |
| 8 | RESUME_RESPONSE | Phản hồi resume; chỉ hợp lệ sau kiểm tra an toàn M4 |
| 9 | ERROR | Báo lỗi ứng dụng |

Public API protocol: `MessageType.id()/fromId(int)`, `Message(type, payload)` với defensive copy, `FrameCodec.encode/decode` cho đúng một frame byte array và `FrameCodec.write/read` cho stream nhiều frame. Không có Java object serialization, Swing, socket, TLS hoặc relay trong codec. Public API core không thay đổi; desktop tiếp tục chỉ gọi core.

`read(InputStream)` trả Optional.empty chỉ khi EOF trước byte đầu frame; EOF giữa header/payload ném EOFException. Length/version/type sai hoặc trailing bytes trong `decode(byte[])` ném ProtocolException (IOException). Lỗi stream I/O được truyền nguyên cho caller. Không bỏ qua frame lỗi hoặc cố resync: caller phải hủy kết nối sau lỗi, kể cả timeout giữa frame. `read` tiêu thụ đúng một frame, không dùng available() để quyết định dữ liệu TCP đã đủ.

Chỉ hỗ trợ blocking InputStream/OutputStream; readFully xử lý partial read. Blocking OutputStream.write hoàn thành dữ liệu được yêu cầu hoặc ném lỗi; chưa cung cấp NIO channel API. Caller cấu hình timeout, đồng bộ truy cập chung stream, flush khi phù hợp và đóng tài nguyên. Codec không tự close/flush và không có state chung. Mỗi frame có bộ nhớ hữu hạn (gồm các bản sao defensive), không tải toàn bộ file vào RAM.

Codec không cung cấp mã hóa/xác thực, kiểm tra tên file, hash hoặc thứ tự state machine. Chỉ được dùng cho dữ liệu file sau xác minh peer/TLS theo SPEC. Ví dụ OFFER payload ASCII "AB": `00 00 00 04 01 01 41 42`; đây chỉ là ví dụ framing, không phải schema offer hợp lệ của M3.

## Nền tảng TLS M2

M2 hiện thực phương án pin trước handshake tại D004, chỉ Java 21/JCA/JSSE, không thêm dependency. Profile PKCS12/self-signed và lệnh tạo bằng keytool, API và giới hạn chi tiết tại [SECURITY](SECURITY.md). Có loader LocalIdentity, SHA-256 Fingerprint, CertificatePolicy/PinnedTrustManager và PeerTlsSession/TlsSessionInfo.

Pending verification diễn ra trước TLS: chưa có certificate remote thực tế, không truyền TLS/app bytes và không cấp stream. Hai bên lấy full fingerprint qua kênh tin cậy độc lập rồi verify đúng UUID của phiên hiện tại trên worker. Không hỗ trợ handshake “preview” trust-all để thu chứng chỉ lạ. Pin, profile chứng chỉ, platform JSSE validation và mutual client authentication được kiểm tra trong handshake TLS 1.3.

Stream ứng dụng chỉ được cấp sau handshake và kiểm tra certificate thực tế của SSLSession; không expose socket/session JSSE thô. Snapshot có state, local/remote fingerprints, protocol/cipher và UUID; không chứa keys/password. Mỗi connection dùng context/state mới, không tự áp dụng xác minh cũ khi đổi cert hoặc reconnect. Lỗi đóng socket; close từ UI có thể hủy handshake.

Crypto M2 hiện đã được bọc qua public core API M3; chưa thêm P2pEngine async đầy đủ. Desktop tiếp tục chỉ gọi core. SO_TIMEOUT hiện giới hạn từng read; deadline handshake/chờ người dùng, connect timeout và giới hạn số phiên thuộc core sau này. TLS localhost được kiểm thử ở M2; byte tunnel relay thực và demo Windows/Linux hai máy thuộc M6/M8.

## Truyền file M3 — payload và state contract

Framing/version/type IDs M1 giữ nguyên. FilePayloads bổ sung schema binary big-endian trong payload, không Java serialization. Giới hạn file 16 GiB, chunk 1–65.536 byte, offset/size long không âm; tên display UTF-8 strict 1–255 byte là portable basename, từ chối separators, traversal, absolute/drive/ADS, control chars, tên Windows reserved và trailing dot/space. Tên remote không quyết định đường dẫn đích.

| Message | Payload M3 |
| --- | --- |
| OFFER | UUID 16 byte + size i64 8 byte + SHA-256 32 byte + nameLength u16 + UTF-8 name |
| ACCEPT / REJECT / ERROR | UUID 16 byte; ERROR được parse như lỗi, không gửi diagnostics tùy ý |
| CHUNK | UUID 16 byte + offset i64 8 byte + data 1–64 KiB |
| COMPLETE | UUID 16 byte + size i64 8 byte + SHA-256 32 byte; dùng hai chiều theo state |
| CANCEL / RESUME_* | Chưa được xử lý ở M3, không tự bật tính năng |

Một phiên một file: verify theo M2 -> sender hash trước trên file handle -> OFFER -> receiver validate và chờ user chọn đích/accept/reject -> ACCEPT -> CHUNK đúng UUID/offset, không vượt size -> sender COMPLETE -> receiver kiểm tra size/hash trên file tạm -> publish -> receiver COMPLETE xác nhận -> sender mới success. Type/size/UUID/offset/state sai, EOF thiếu hoặc hash mismatch đều fail, đóng connection và dọn temp; không resync/resume.

Nguồn mở regular file với NOFOLLOW_LINKS, hash trước và hash lại trong lúc gửi trên cùng handle, phát hiện thay đổi kích thước/nội dung trước COMPLETE. Không hứa atomic snapshot chống tiến trình local độc hại. Receiver kiểm tra bytes, force và đọc lại file tạm để hash trước publish. Buffer 64 KiB và frame hữu hạn, không đọc toàn bộ file vào RAM.

Đường dẫn đích là Path cục bộ đầy đủ do receiver chọn, parent có sẵn và được tin cậy; không ghép raw path từ mạng. File tạm ở cùng parent, final entry được tạo bằng Files.createLink không có replace rồi xóa tên tạm. Không dùng ATOMIC_MOVE vì hành vi target đã tồn tại có thể replace theo provider; filesystem không hỗ trợ hard links sẽ fail closed. Đây là atomic no-replace visibility trên filesystem được hỗ trợ, không phải bảo đảm crash durability/distributed commit.

Nếu ACK mất sau publication hoặc cleanup tên tạm gặp lỗi, file hợp lệ có thể đã hiện diện dù sender/operation báo lỗi; không tự retry/ghi đè. Các quy tắc quyền thư mục cục bộ/endpoint của SECURITY vẫn áp dụng; không bảo vệ chống tiến trình local có quyền thay thế parent/temp. M3 chưa có quota, wall-clock deadline hay resume/cancel handshake.

Public API thật và mapping với đề xuất Antigravity tại [CORE_API](CORE_API.md), lệnh CLI tại [DEMO](DEMO.md). Callback trên caller worker, progress khoảng 100ms; bytes==total chưa có nghĩa hoàn tất. Chưa triển khai P2pEngine async đầy đủ, relay, queue hoặc UI.

## Phạm vi hệ điều hành đã chốt sau M3 (2026-11-16)

Nền tảng MVP bắt buộc: **Windows 10/11 và Ubuntu Linux**. macOS không thuộc phạm vi nghiệm thu/CI của MVP 15 ngày; không phát triển tính năng riêng macOS. Giữ Java 21, JSSE/TLS 1.3, portable Path/Socket/stream API; không thêm API, lệnh hệ thống hoặc native binding riêng Windows vào core. Hỗ trợ CLI và desktop trên Ubuntu là yêu cầu sản phẩm, không chỉ compile backend.

CI hiện dùng đúng ubuntu-latest và windows-latest, build/test, tạo application distributions và smoke chạy launcher của CLI/desktop. Ubuntu dùng Xvfb cho smoke entry point để có display khi UI được tích hợp; Windows dùng .bat, Ubuntu dùng Unix launcher. Không thay Java toolchain hoặc implementation core. Runner windows-latest không chứng minh đã kiểm thử Windows 10/11 trên máy đích; runner Ubuntu cũng không phải một cặp máy Windows/Ubuntu trao đổi file.

| Kịch bản nghiệm thu thực tế bắt buộc | Điều kiện / bằng chứng |
| --- | --- |
| Windows 10/11 gửi -> Ubuntu Linux nhận | Hai host riêng, receiver bind LAN interface, TLSv1.3 + full pin đúng + file size/SHA-256 khớp |
| Ubuntu Linux gửi -> Windows 10/11 nhận | Đảo vai trò trên hai host, cùng kiểm tra accept/reject/no-overwrite |
| Windows gửi -> Windows nhận | Ưu tiên cặp Windows 10 và Windows 11 để bao phủ cả hai phiên bản; chạy hai chiều |
| Hai máy khác mạng qua Relay | Thực hiện sau M6, đổi vai trò khi demo; TLS vẫn giữa peer, relay chỉ pump bytes, không kết thúc TLS |
| Negative security trong các kịch bản | Sai/đổi fingerprint phải fail; chưa xác minh không gửi file; file lỗi hash/thiếu không được báo complete |

Ghi chính xác phiên bản OS/JDK, commit, hướng truyền, size, fingerprint public, TLS protocol/cipher, SHA-256 nguồn/đích và kết quả; không lưu password/private key/file content. Bao phủ file 0 byte, text UTF-8, binary nhiều chunk, 10–100 MiB; bài lớn riêng khi có tài nguyên. Windows 10 và 11 đều phải được ghi nhận khi tuyên bố đã kiểm thử hai phiên bản.

M3 đã có localhost integration/CLI demo và CI, **chưa có bằng chứng truyền file giữa hai máy** cho các hàng trên. Ở thời điểm M3 relay chưa được triển khai; M6 nay có relay localhost theo RELAY.md, demo public/hai máy vẫn chưa được chứng minh. Không cắt relay, E2E TLS hoặc fingerprint để rút phạm vi. GUI Ubuntu thật phải kiểm tra trên desktop session sau khi tích hợp branch Antigravity; headless/launcher placeholder không thay thế kiểm thử GUI.

## Relay M6-MVP — socket handoff

M6 được ưu tiên trước M4 theo yêu cầu người dùng. Giữ nguyên M2/M3: relay chỉ routing và byte forwarding, hai peer pin chứng chỉ độc lập và mutual TLS 1.3 trước OFFER. Core bổ sung `PeerNode.TlsRole { CLIENT, SERVER }` và `attach(Socket, TlsRole, int ioTimeoutMillis)` nhận quyền sở hữu socket đã kết nối (kể cả khi thất bại), trả TransferSession PENDING_VERIFICATION. Không expose crypto types, không thêm dependency core -> relay; CLI gọi RelayClient rồi handoff vào core. Vai trò CREATE/receiver là TLS SERVER, JOIN/sender là TLS CLIENT; cả hai TCP đều outbound. Desktop không sửa trong M6.
