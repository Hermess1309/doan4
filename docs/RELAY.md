# Relay M6-MVP

M6 được ưu tiên trước Resume/M4 theo yêu cầu người dùng. Chạy Java 21 trên Windows 10/11 và Ubuntu; không thêm dependency, không sửa desktop. Đây là TCP byte relay, không phải TLS proxy hoặc một hệ thống truyền file mới.

## Kiến trúc và vai trò

```text
Receiver --outbound TCP--> Relay <--outbound TCP-- Sender
 TLS SERVER <========== mutual TLS 1.3 =========> TLS CLIENT
                    Relay không có private key
```

Receiver CREATE nhận mã, sender JOIN bằng mã đó. Relay gửi PAIRED tới cả hai trước khi copy byte; parser đọc đúng số byte, không BufferedInputStream/prefetch ăn mất đầu TLS. CLI chuyển socket cho PeerNode.attach; quyền sở hữu chuyển hoàn toàn cho TransferSession. TLS role độc lập với vai trò TCP outbound.

Hai bên trao đổi full SHA-256 fingerprint qua kênh đáng tin độc lập, cung cấp pin trước verify đúng session UUID. Pending là TCP chưa TLS; không OFFER/file data. M2 dùng context mới, pin certificate và mutual certificate authentication, TLSv1.3-only. Sau handshake mới dùng OFFER/ACCEPT/CHUNK/COMPLETE M3; receiver vẫn phải chấp nhận offer, hash disk và no-overwrite trước ACK. Không thay đổi crypto.

Mã pairing chỉ định tuyến; người biết mã có thể chiếm phiên/gây DoS nhưng không vượt pin peer để lấy file. Không lấy fingerprint từ chính relay hoặc coi việc biết code là xác thực. Relay thấy IP, code, timing, số byte; metadata pairing chưa được mã hóa. Relay không kiểm chứng một client tùy ý có chạy TLS: bảo mật nằm ở hai endpoint hợp lệ, luôn dùng core gate. Không có dữ liệu ứng dụng ở metadata protocol.

## Wire routing P2R1 (plaintext, trước TLS)

Tất cả số nguyên big-endian. Header chính xác 5 byte: magic/version ASCII P2R1 (50 32 52 31) + một u8 operation/status. Giao thức riêng với envelope file M1; không đổi file message IDs.

| Hướng | Header và phần sau |
| --- | --- |
| Client CREATE | operation 1, không payload |
| Client JOIN | operation 2 + đúng 12 ASCII code bytes |
| Server CREATED | status 1 + 12 code bytes + i32 TTL milliseconds |
| Server PAIRED | status 2, không payload; byte kế tiếp thuộc TLS tunnel |
| Server UNAVAILABLE | status 3 cho sai/hết hạn/đã dùng, sau đó close |
| Server BUSY | status 4 khi hết session capacity, sau đó close |

Code 12 ký tự từ alphabet ABCDEFGHJKLMNPQRSTUVWXYZ23456789 (32 ký tự), SecureRandom độc lập, 60 bit entropy. Canonical uppercase, không nhận code quá dài/ký tự khác. Chỉ giữ map hữu hạn; code bị consume atomically trước khi gửi PAIRED, không khôi phục khi handshake/handoff thất bại. Collision với code pending sinh lại; không hứa uniqueness vĩnh viễn. Header sai/truncated/type không biết -> close, không resync. Không log code; CLI chỉ hiển thị code một lần theo thao tác CREATE để người dùng gửi cho bên kia, tránh redirect stdout đó vào log công khai.

## Giới hạn mặc định và cleanup

- 64 socket được nhận, 32 phiên pending + active tổng cộng. Mỗi tunnel 2 virtual worker và 2 buffer 16 KiB, tối đa khoảng 1 MiB buffer chuyển tiếp ở 32 tunnel, ngoài JVM/kernel socket buffers. Không queue file hoặc đọc toàn bộ file vào RAM.
- Metadata deadline 5 s tính từ accept, kể cả client nhỏ giọt từng byte. Code TTL 120 s. Không có reader chờ host trong giai đoạn pending để tránh tranh đọc TLS: host ngắt khi chờ được thu hồi chậm nhất TTL + chu kỳ sweep; không chiếm worker chờ.
- Idle 60 s tính theo hoạt động chung hai chiều; không timeout sai chỉ vì chiều ACK im lặng khi file đi một chiều. Tunnel lifetime tối đa 15 phút kể từ pairing. Sweep 50 ms, read poll tối đa 1 s; close socket ngắt cả read và write bị block.
- Mỗi IP tối đa 30 kết nối thử / cửa sổ 30 s, kể cả CREATE/unknown/code sai; IP dùng chung NAT chia sẻ quota. Bảng tối đa 1024 IP, loại entries hết cửa sổ. Cap toàn cục 640 attempts/30 s; quá quota/cap socket -> close. Fixed window không phải chống DDoS quy mô lớn hoặc tài khoản.
- EOF, timeout, I/O failure, server shutdown đóng cả hai đầu, thu hồi session/socket. Không half-close/resume; lỗi phải tạo mã/phiên mới và xác minh lại. Close toàn server ngắt listener, scheduler, workers và sockets.
- Giới hạn là policy MVP, có thể cấu hình qua RelayConfig trong API Java, CLI dùng mặc định. File/hash/đối chiếu pin chậm hơn idle hoặc transfer lâu hơn lifetime sẽ thất bại; không tuyên bố mọi file 16 GiB truyền xong trong 15 phút. ACK mất vẫn có thể để lại file hợp lệ như M3; không retry/overwrite tự động.

## Chạy Windows — ba terminal

Cần JDK21, TCP relay có thể tiếp cận từ cả hai peer. Mỗi peer tạo **identity riêng** theo [SECURITY](SECURITY.md), không gửi keystore/private key cho nhau. Dùng fingerprint command hoặc keytool list và đối chiếu đầy đủ qua kênh độc lập.

```powershell
Set-Location 'C:\Users\PC\Documents\Study\Do_an_4-relay'
.\gradlew.bat --no-daemon --console=plain clean spotlessCheck build :cli:installDist
$cli = '.\cli\build\install\cli\bin\cli.bat'
& $cli fingerprint --store "$env:LOCALAPPDATA\P2pFileTransfer\identity.p12"
```

Mật khẩu được console hỏi, không nhận password argv. --password-env chỉ là tên biến đã chuẩn bị riêng khi automation, không phải giá trị password. Không lưu biến/log/keystore vào repo.

Terminal relay (không cần identity):
```powershell
& .\cli\build\install\cli\bin\cli.bat relay-server --host 127.0.0.1 --port 47000
```

Terminal receiver (thay pin bằng full fingerprint **sender** đã đối chiếu; chọn đích mới trong thư mục có sẵn, không dùng tên remote làm đường dẫn):
```powershell
& .\cli\build\install\cli\bin\cli.bat relay-receive --host 127.0.0.1 --port 47000 --store "$env:LOCALAPPDATA\P2pFileTransfer\identity.p12" --pin FULL_SENDER_SHA256 --output C:\Receive\received.bin
```

Lấy code vừa hiển thị cho sender, không coi code là trusted fingerprint. Terminal sender (identity **khác**, pin **receiver**):
```powershell
& .\cli\build\install\cli\bin\cli.bat relay-send --host 127.0.0.1 --port 47000 --code CODE_FROM_RECEIVER --store C:\Private\sender.p12 --pin FULL_RECEIVER_SHA256 --file C:\Send\source.bin
```

Receiver trả lời y để chấp nhận; automation có --accept hoặc --reject. Cả hai phải in VERIFIED TLSv1.3, sau cùng SUCCESS cùng bytes/SHA-256. So thêm Get-FileHash -Algorithm SHA256 source/destination. Ctrl+C dừng relay; không bật JSSE debug chứa nội dung nhạy cảm. Đảo hai máy bằng cách máy nhận mới CREATE và máy gửi mới JOIN.

## Ubuntu và triển khai public

```sh
./gradlew --no-daemon --console=plain clean spotlessCheck build :cli:installDist
./cli/build/install/cli/bin/cli relay-server --host 0.0.0.0 --port 47000
./cli/build/install/cli/bin/cli fingerprint --store "$HOME/.local/share/p2p/identity.p12"
# Dùng relay-receive / relay-send với cùng options như Windows, path theo Ubuntu.
sha256sum /path/to/source.bin /path/to/received.bin
```

Public relay cần VPS/host có public IP hoặc port forwarding, mở **TCP** 47000 ở firewall/security group, không đặt sau HTTP-only reverse proxy hoặc TLS terminator. Hai peer dùng cùng public relay host/port và kết nối outbound; không cần inbound port ở peer trong NAT thông thường. Mạng chặn outbound port/TCP proxy đặc biệt vẫn có thể không chạy. Bind 127.0.0.1 mặc định, chỉ dùng 0.0.0.0 khi chủ động triển khai.

Chạy relay bằng tài khoản không đặc quyền, JDK21 được cập nhật, service supervisor/restart, giới hạn OS fd/RAM/CPU/bandwidth và firewall/rate monitoring phù hợp. Không đưa identity peer lên VPS, không bật capture payload/log code. Endpoint cần NTFS/ext4 hoặc filesystem có hard link no-replace, đủ dung lượng và trusted destination directory. Relay chỉ giữ metadata tạm trong RAM, không file storage.

Chưa có public server trong task: localhost/CI **không chứng minh** Internet/NAT hoặc Windows->Ubuntu thực. Demo còn phải chạy Windows->Ubuntu, Ubuntu->Windows, Windows->Windows, hai mạng khác nhau qua public relay; ghi JDK/OS/commit/TLS/cipher/pins/size/hash/kết quả, thêm wrong-pin/disconnect. Không bỏ fingerprint, E2EE hoặc SHA-256 để demo.

## Kiểm thử và bằng chứng

```powershell
.\gradlew.bat --no-daemon --console=plain :protocol:test :relay:test :cli:test
.\gradlew.bat --no-daemon --console=plain clean spotlessCheck build
```

RelayProtocolTest: partial read, exact handoff, mọi truncation header/code, sai version/code. RelayServerTest: correct/wrong/expired/used code, quota, session/socket cap, malformed/slow metadata, shared idle, lifetime, backpressure, disconnect/close. RelayTransferTest: identity tạm keytool JDK, TLSv1.3 và hai certificate/pin đúng, 0 B/61 B/131199 B/1 MiB, disk hash/content bằng nhau, unverified gate, wrong pin hai phía, cutoff trước đủ file/dọn temp, invalid socket ownership.

Hai ca subprocess khởi động relay + receiver + sender ở **ba JVM riêng** (61 B và 1 MiB), assert exit 0, TLSv1.3, SHA-256 nguồn/đích, thêm subprocess wrong-pin exit 2/no offer/no file. Password ngẫu nhiên qua env của subprocess, không argv/log; certificate/keys chỉ thư mục @TempDir, không secrets máy cá nhân.

Test-only WireTap bắt byte TCP ở đường peer->relay và chiều về, lưu tối đa 2 MiB trong RAM. Test assert wire có overhead handshake/record, không marker nội dung file; endpoint xác nhận TLSv1.3 và actual certificate fingerprint. Đây là bằng chứng kết hợp với JSSE và relay không có TLS/key/file decoder, không phải lập luận rằng “không thấy chuỗi” tự nó chứng minh mật mã. Test capture không tồn tại trong relay sản phẩm. XML/HTML: cli/build/test-results/test và cli/build/reports/tests/test, stdout chỉ ghi hash/size/TLS/PID, không code/private key/password.

## Class và API

| Class | Chức năng |
| --- | --- |
| protocol.RelayProtocol | Wire header/code hữu hạn, đọc đúng bytes |
| relay.RelayConfig | Validate caps/timeouts |
| relay.RelayServer | Accept, giới hạn, one-shot pairing, opaque pumps, cleanup, aggregate Stats |
| relay.RelayClient / Pending | Outbound CREATE/JOIN, ticket có thể close để hủy, chuyển ownership socket |
| core.PeerNode.TlsRole / attach | Bọc transport hiện có bằng M2, trả TransferSession M3 |
| cli.RelayCommands | CLI orchestration, dùng lại verify/send/receive, không crypto mới |

Không triển khai M4/resume, discovery/JmDNS, UI/desktop adapter hoặc public hosting trong M6. Dependency graph giữ nguyên, core chỉ dùng JDK/protocol/crypto, không phụ thuộc relay/Swing.
