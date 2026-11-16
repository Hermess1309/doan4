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
