# Demo M3 — hai peer localhost trên Windows

Mục tiêu: một file thật qua TLS 1.3, identity riêng, pin đầy đủ trước handshake, người nhận chủ động chấp nhận và chọn file đích. Không có UI/relay/resume. Chạy trong hai PowerShell terminal thật để Console.readPassword/readLine hoạt động; Gradle run thường không có console.

## Build

Từ checkout Codex, đặt JAVA_HOME tới JDK 21 và đưa bin vào PATH theo README. Sau đó:

```powershell
.\gradlew.bat --no-daemon --console=plain spotlessCheck build :cli:installDist
```

Launcher: .\cli\build\install\cli\bin\cli.bat. Chạy help để xem tùy chọn. CLI mặc định localhost, timeout 30 giây; demo dưới dùng 120 giây để có thời gian xác nhận. Không truyền password như argument. Với script kiểm thử, --password-env NAME chỉ tên một biến môi trường đã chứa password; không ghi giá trị password vào Git/log.

## Tạo hai identity một lần

Mỗi peer tự tạo identity; trên localhost demo dùng hai file độc lập. Không chạy đè identity hiện có. Password được keytool hỏi tương tác; dùng password riêng cho mỗi file. Profile giống SECURITY.md.

```powershell
$identityDirectory = Join-Path $env:LOCALAPPDATA 'P2pFileTransfer'
New-Item -ItemType Directory -Force -Path $identityDirectory | Out-Null
foreach ($peerName in @('alice', 'bob')) {
    $storePath = Join-Path $identityDirectory ($peerName + '.p12')
    if (Test-Path -LiteralPath $storePath) { throw 'Identity exists; reuse it rather than overwrite' }
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -alias peer -storetype PKCS12 -keystore $storePath -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 365 -dname "CN=$peerName" -ext 'BC=ca:false' -ext 'KU=digitalSignature' -ext 'EKU=serverAuth,clientAuth'
}
.\cli\build\install\cli\bin\cli.bat fingerprint --store "$identityDirectory\alice.p12"
.\cli\build\install\cli\bin\cli.bat fingerprint --store "$identityDirectory\bob.p12"
```

Đối chiếu đầy đủ hai fingerprint qua kênh độc lập. Trong demo cùng máy có thể trực tiếp xem output của hai identity do mình vừa tạo; khi dùng hai máy phải xác định đúng người qua gặp mặt/cuộc gọi tin cậy. Không lấy pin chỉ từ relay hay kết nối chưa xác minh.

## Terminal B — nhận

```powershell
$identityDirectory = Join-Path $env:LOCALAPPDATA 'P2pFileTransfer'
$senderPin = Read-Host 'Full trusted SHA-256 of Alice'
$outputPath = Read-Host 'Absolute destination FILE path, must not exist'
.\cli\build\install\cli\bin\cli.bat receive --store "$identityDirectory\bob.p12" --pin $senderPin --port 9000 --output $outputPath --timeout-ms 120000
```

Chờ LISTENING 9000. Sau khi TLS VERIFIED và OFFER xuất hiện, nhập y để đồng ý. Có thể dùng --accept để chấp nhận rõ ràng từ command line (automation) hoặc --reject để kiểm tra từ chối; không dùng đồng thời hai cờ. Listener chỉ nhận một connection và một file trong lần chạy này.

## Terminal A — gửi

```powershell
$identityDirectory = Join-Path $env:LOCALAPPDATA 'P2pFileTransfer'
$receiverPin = Read-Host 'Full trusted SHA-256 of Bob'
$sourcePath = Read-Host 'Absolute existing source FILE path'
.\cli\build\install\cli\bin\cli.bat send --store "$identityDirectory\alice.p12" --pin $receiverPin --host 127.0.0.1 --port 9000 --file $sourcePath --timeout-ms 120000
```

Kết quả mong đợi: PENDING_VERIFICATION (TCP only), VERIFIED TLSv1.3, OFFERED/TRANSFERRING/VERIFYING/COMPLETED và SUCCESS bytes=... SHA-256=... ở hai phía. Sender không báo SUCCESS chỉ vì đã ghi hết vào socket. CLI exit 2 khi fail; chỉ in loại lỗi, không dump secret/exception chain hay file content.

```powershell
# Chạy từng lệnh tại terminal có biến đường dẫn tương ứng; so sánh hai hash.
Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256
Get-FileHash -LiteralPath $outputPath -Algorithm SHA256
```

File đích do receiver chọn, không ghi đè. Filesystem cần hỗ trợ hard link cùng thư mục để công bố no-replace; NTFS được kiểm tra cục bộ. Nếu filesystem không hỗ trợ (ví dụ một số removable/network filesystem), thao tác fail an toàn thay vì fallback ghi đè. Mất kết nối sau commit nhưng trước ACK có thể để lại file hợp lệ ở receiver trong khi sender báo lỗi; kiểm tra hash, không tự ghi đè để retry.

## Chạy tests

```powershell
.\gradlew.bat --no-daemon --console=plain :protocol:test :core:test
.\gradlew.bat --no-daemon --console=plain clean spotlessCheck build
# Bài 1 GiB riêng, cần hơn 2 GiB disk trống, không bật mặc định trên CI.
$env:P2P_LARGE_TEST = '1'
try {
    .\gradlew.bat --no-daemon --console=plain :core:test --tests 'vn.edu.p2p.core.SecureFileTransferTest.optionalOneGiBStreaming'
} finally {
    Remove-Item Env:\P2P_LARGE_TEST
}
```

Test dữ liệu lớn tự sinh tại @TempDir, giữ buffer cố định; không có file test lớn hoặc keystore cố định trong Git. Suite mặc định có 10 MiB và một case 1 GiB skip có chủ đích; test riêng bật env đã được chạy local, bằng chứng ghi tại TASKS.md.

## Ubuntu Linux — launcher và truyền hai máy (phạm vi bắt buộc)

Cài **JDK 21 đầy đủ có hỗ trợ desktop**, đặt JAVA_HOME và bin trong PATH. Chạy từ checkout đã review, không mang keystore/private key từ máy Windows sang máy Ubuntu.

```bash
export PATH="$JAVA_HOME/bin:$PATH"
java -version
chmod +x gradlew
./gradlew --no-daemon --console=plain clean spotlessCheck build :cli:installDist :desktop:installDist
./cli/build/install/cli/bin/cli help

umask 077
identity_dir="${XDG_DATA_HOME:-$HOME/.local/share}/p2p-file-transfer"
mkdir -p "$identity_dir"
store="$identity_dir/identity.p12"
if [ ! -e "$store" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -alias peer -storetype PKCS12 -keystore "$store" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 365 -dname "CN=Ubuntu Peer" -ext "BC=ca:false" -ext "KU=digitalSignature" -ext "EKU=serverAuth,clientAuth"
fi
./cli/build/install/cli/bin/cli fingerprint --store "$store"
```

Đối chiếu full pin qua kênh độc lập trước handshake như M2. Password vẫn nhập console. Ví dụ **Ubuntu nhận từ Windows**:

```bash
read -r -p 'Full trusted fingerprint of Windows peer: ' peer_pin
read -r -p 'New absolute destination FILE path: ' destination
./cli/build/install/cli/bin/cli receive --store "$store" --pin "$peer_pin" --host 0.0.0.0 --port 9000 --output "$destination" --timeout-ms 120000
sha256sum -- "$destination"
```

Máy Windows gửi dùng lệnh send đã hướng dẫn nhưng --host phải là IP LAN thực của Ubuntu, không phải 127.0.0.1/0.0.0.0. Cho phép TCP port đã chọn theo cấu hình mạng/firewall demo; không tự động đổi firewall trong ứng dụng.

Đảo chiều **Ubuntu gửi sang Windows**: Windows chạy receive với --host 0.0.0.0, chọn output mới; Ubuntu dùng:

```bash
read -r -p 'Full trusted fingerprint of Windows peer: ' peer_pin
read -r -p 'Windows receiver LAN IP: ' receiver_ip
read -r -p 'Existing absolute source FILE path: ' source
./cli/build/install/cli/bin/cli send --store "$store" --pin "$peer_pin" --host "$receiver_ip" --port 9000 --file "$source" --timeout-ms 120000
sha256sum -- "$source"
```

Windows->Windows làm tương tự với receiver bind LAN và host IP đúng. Các lệnh trên là hướng dẫn, **chưa phải bằng chứng đã chạy hai host**. Chụp/lưu OS version, Java version, SHA commit, TLSv1.3/peer fingerprints, bytes và hash hai phía theo checklist TASKS. Không dùng một runner Ubuntu hoặc localhost làm bằng chứng liên hệ điều hành.

Desktop Ubuntu: chạy ./desktop/build/install/desktop/bin/desktop trong desktop session X11/XWayland có display và fonts; --smoke-test chỉ kiểm tra khởi tạo khi implementation UI có mặt. Branch Codex hiện là placeholder, UI thật thuộc Antigravity và cần review/tích hợp rồi kiểm thử cửa sổ, file chooser, EDT và transfer thật. Không chỉnh desktop trong task phạm vi này.

Relay hai mạng: chờ M6 triển khai signaling/byte tunnel và CLI/API relay rồi thêm lệnh demo cụ thể. Không giả lập một lệnh connectRelay đã chạy khi chưa có backend; đây vẫn là tiêu chí bắt buộc, TLS kết thúc ở peer, pinning và SHA-256 giữ nguyên.
