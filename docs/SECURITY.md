# Nền tảng bảo mật M2

M2 cung cấp identity/kênh TLS; M3 đã tích hợp vào core và CLI truyền file localhost (xem CORE_API.md/DEMO.md). Chưa có P2pEngine async đầy đủ hoặc relay server. Không chỉnh desktop. Hướng dẫn này mô tả đúng API hiện tại, không hứa một kênh đã xác thực khi người dùng chưa kiểm tra fingerprint.

## Tạo identity trên Windows với JDK 21

Mỗi máy/peer tự chạy một lần, tạo khóa riêng; không sao chép keystore sang peer khác. Kiểm tra JAVA_HOME trỏ JDK 21. Mật khẩu do keytool hỏi ở console, không đặt vào command line, source code hoặc Git.

```powershell
$identityDirectory = Join-Path $env:LOCALAPPDATA 'P2pFileTransfer'
New-Item -ItemType Directory -Force -Path $identityDirectory | Out-Null
$identityStore = Join-Path $identityDirectory 'identity.p12'
if (Test-Path -LiteralPath $identityStore) { throw 'Identity already exists; do not overwrite it' }
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -alias peer -storetype PKCS12 -keystore $identityStore -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 365 -dname 'CN=P2P Peer' -ext 'BC=ca:false' -ext 'KU=digitalSignature' -ext 'EKU=serverAuth,clientAuth'
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -alias peer -keystore $identityStore -storetype PKCS12
```

Lệnh list hiển thị SHA256 đầy đủ (có dấu hai chấm), không xuất private key. Chọn mật khẩu mạnh riêng cho identity, ít nhất 6 ký tự theo yêu cầu keytool; bảo vệ thư mục bằng quyền tài khoản cục bộ/Windows ACL. LocalIdentity không sửa ACL thay người dùng. Không commit .p12, mật khẩu, private key hoặc log chứa secrets; gitignore đã chặn định dạng keystore/key.

Linux: dùng cùng keytool arguments, bỏ .exe; chọn thư mục cục bộ riêng, đặt umask 077 trước khi sinh và chmod 600 keystore. Đây là hướng dẫn, không phải tuyên bố đã chạy demo sản phẩm trên Linux.

## Profile chứng chỉ hiện được hỗ trợ

PKCS12 chỉ chứa một private-key entry đúng alias được chọn và một chứng chỉ X.509 v3 tự ký. RSA 3072–8192 bit, chữ ký chứng chỉ SHA256withRSA, non-CA, KeyUsage digitalSignature và EKU gồm cả serverAuth/clientAuth; thời hạn phải còn hiệu lực. Không hỗ trợ CA chain, EC hoặc thuật toán khác ở MVP này. Cả identity local và chứng chỉ remote đều qua chính sách trên; local key còn được kiểm tra khớp certificate bằng JCA Signature, không thêm handshake mật mã riêng.

LocalIdentity.load(Path, char[], alias) chỉ đọc file thường, không theo symlink ở thành phần file cuối; giới hạn file PKCS12 1 MiB trước khi parse. Đường dẫn/alias phải từ cấu hình cục bộ đáng tin, không từ peer. Thư mục cha và quyền truy cập vẫn do OS/người dùng bảo vệ. Dùng một password cho store và key theo profile keytool này. Bản sao password và buffer PKCS12 do loader tạo được xóa trong finally; caller phải xóa char[] của mình. KeyManager/JVM vẫn giữ khóa trong bộ nhớ để vận hành TLS, không cam kết xóa được mọi bản sao trong managed heap.

## Xác minh lần đầu và giới hạn JSSE

Giữ thiết kế D004: A và B hiển thị fingerprint identity local, trao đổi/đối chiếu đủ SHA-256 qua kênh độc lập đáng tin (ví dụ trực tiếp hoặc cuộc gọi xác định đúng người). CN, IP, tên máy, mã ghép nối hay fingerprint nhận từ relay không xác thực peer.

PeerTlsSession.prepare nhận TCP Socket đã kết nối và chuyển quyền sở hữu cho lớp phiên. Trạng thái PENDING_VERIFICATION là **TCP chưa chạy handshake**, không phải TLS đã mã hóa. Không có remote certificate/remote fingerprint thực tế lúc này; UI phải hiển thị “chờ fingerprint từ kênh độc lập”, không hiển thị “đã bảo mật”. Không gửi TLS/app bytes; input()/output() bị chặn.

Core gọi verify(sessionId, trustedFingerprint) sau khi người dùng đối chiếu đầy đủ. Hai peer gọi trên worker thread, một Role.CLIENT và một Role.SERVER. Nếu chưa có fingerprint tin cậy, phải chờ hoặc close, không thử lấy certificate bằng một handshake trust-all. JSSE xác minh chứng chỉ trong handshake; M2 không cung cấp chế độ preview chứng chỉ lạ qua TLS rồi tự động coi nó là tin cậy.

Fingerprint.sha256 lấy SHA-256 trên DER certificate, trả 64 ký tự hex uppercase. Parser chấp nhận đủ 64 ký tự hoặc 32 cặp hex phân tách bằng dấu hai chấm (không chấp nhận mã rút gọn); so sánh bằng MessageDigest.isEqual trên hai mảng 32 byte. Fingerprint là thông tin công khai, không phải secret.

## Trình tự bắt tay và gate dữ liệu

1. Đối chiếu ID phiên hiện tại; chỉ cho verify một lần ở trạng thái pending. Callback dùng ID cũ/sai bị từ chối và đóng kết nối.
2. Tạo SSLContext mới cho từng connection, dùng key managers của identity và PinnedTrustManager riêng cho fingerprint đã xác minh. Không dùng SSLContext/TrustStore mặc định của máy để tự chấp nhận peer.
3. Bọc connected TCP Socket bằng SSLSocketFactory.createSocket(..., autoClose=true); đặt vai trò, chỉ bật TLSv1.3, server setNeedClientAuth(true), cấu hình SO_TIMEOUT hữu hạn.
4. TrustManager kiểm tra profile/thời hạn/chữ ký tự ký và pin certificate. Chỉ certificate đã khớp pin mới được đưa vào trust store tạm để platform X509ExtendedTrustManager tiếp tục áp dụng kiểm tra JSSE/algorithm constraints tương ứng vai trò.
5. Sau startHandshake, kiểm tra lại protocol, certificate/fingerprint remote và certificate local từ SSLSession thực tế. Chỉ lúc đó trạng thái VERIFIED và stream ứng dụng mới mở.
6. Snapshot TlsSessionInfo gồm UUID phiên, state, local/remote fingerprints, protocol và cipher suite. Không trả raw SSLSocket/SSLSession/private key. close() là thao tác từ chối/ngắt; stream wrapper kiểm tra trạng thái mỗi lần dùng, kể cả reference đã giữ từ trước. Lỗi handshake/xác minh hoặc IOException trên TLS stream đóng phiên và transport; EOF sạch phải được core xử lý bằng close.

Mỗi connection có UUID, SSLContext và trạng thái xác minh mới; không có trust cache/TOFU tự động hoặc dùng lại context để resume TLS. Đổi chứng chỉ phải có fingerprint mới được xác minh; pin cũ làm handshake fail. TlsSessionInfo là snapshot metadata, không phải capability thay thế session object.

Chỉ trạng thái VERIFIED trên mỗi peer cho biết peer đó đã xác minh identity đối diện trong handshake; không chứng minh người dùng phía bên kia đã bấm nút hay đã chấp nhận một offer. TLS 1.3 client có thể hoàn tất cục bộ trước khi server xử lý flight cuối; core phải xử lý TLS alerts/I/O failure và offer/accept riêng ở M3. Không gửi nội dung file trước xác minh, không dùng “handshake thành công” thay cho “offer được chấp nhận”.

## Ranh giới LAN/relay

TCP socket có thể nối thẳng peer LAN hoặc nối một byte tunnel do relay tạo sau này. Hai đầu chạy JSSE theo vai trò client/server; identity được kiểm bằng certificate pin, không bằng hostname/IP của relay (đây không phải HTTPS hostname authentication). Không tin cert của relay như cert peer.

Relay đúng thiết kế chỉ pump byte TLS; không có traffic keys hoặc private key của peer và không kết thúc handshake. Nó có thể thấy IP, timing, volume và pairing metadata, có thể ngắt/sửa byte gây lỗi, nhưng không đọc plaintext hay giả mạo peer qua pin đúng. Relay thực và kiểm thử xuyên relay thuộc M6, chưa triển khai ở M2.

## Ví dụ tích hợp cho core

Đoạn dưới nằm trong worker có TCP socket đã kết nối, identity và fingerprint được người dùng đối chiếu qua kênh độc lập. Đây là API usage, chưa phải P2pEngine/CLI hoàn chỉnh.

```java
try (var session = PeerTlsSession.prepare(
        connectedSocket, identity, PeerTlsSession.Role.CLIENT, 10_000)) {
    TlsSessionInfo pending = session.info();
    TlsSessionInfo verified = session.verify(pending.sessionId(), independentlyVerifiedPin);
    // Từ đây core mới được dùng FrameCodec với session.input()/session.output().
    // Core xử lý framing failure/EOF bằng đóng session, và flush output khi cần.
}
```

Nạp password từ Console.readPassword hoặc nguồn tương tác cục bộ an toàn, truyền char[] vào LocalIdentity.load rồi Arrays.fill(password, '\0') trong finally. Không dùng password hardcode trong ví dụ. Crypto API mới không đổi public API core; Antigravity tiếp tục gọi core. M3 sẽ chuyển snapshot/state sang public API core mà không để desktop import crypto trực tiếp.

## Test và giới hạn

Chạy từ worktree Codex với JAVA_HOME=JDK 21:

```powershell
.\gradlew.bat --no-daemon --console=plain :crypto:test
.\gradlew.bat --no-daemon --console=plain clean spotlessCheck build
```

Test tự sinh keystore cho các identity khác nhau bằng keytool JDK ở @TempDir; password ngẫu nhiên truyền qua env chỉ cho tiến trình keytool con, không nằm trong argv/log. Không cần personal keystore, network Internet hay secret CI. Có TLS localhost thật, sai pin hai chiều, đổi cert, thiếu client cert, TLS 1.2 bị từ chối, expiry/weak RSA, stream gate, truyền binary nhỏ, timeout/cancel và kiểm tra không emit console/JUL output. Không bật JSSE debug/key logging ngoài component rồi kỳ vọng component có thể kiểm soát log toàn JVM.

SO_TIMEOUT là giới hạn chờ từng read, không phải deadline toàn bộ handshake. Connect timeout, deadline chờ người dùng, giới hạn số connection/slowloris, UI integration, truyền file, resume, relay và kiểm thử hai máy thật chưa thuộc M2. Caller dùng một reader/một writer, close khi EOF hoặc framing failure; không ghi raw transport sau khi giao quyền sở hữu cho session. TLS không bảo vệ file trên đĩa, endpoint bị xâm nhập hoặc người dùng xác minh nhầm fingerprint.

Nguồn chuẩn: [keytool JDK 21](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html), [SSLSocketFactory layering](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/SSLSocketFactory.html), [X509ExtendedTrustManager](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/X509ExtendedTrustManager.html), [X509Certificate](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/cert/X509Certificate.html).

M3 giữ nguyên toàn bộ mô hình pre-pin/TLS M2. File transfer không thay thế pinning; full schema/state/đường dẫn và no-overwrite tại SPEC/CORE_API. CLI chỉ hỗ trợ password console hoặc tên biến môi trường do người dùng chỉ định, không nhận giá trị password trong argv. Chỉ file đã hash/size đúng mới được công bố; data trên đĩa không được TLS mã hóa sau khi lưu.
