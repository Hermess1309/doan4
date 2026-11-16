package vn.edu.p2p.desktop.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import vn.edu.p2p.core.PeerNode;

/**
 * Tiện ích hỗ trợ nạp hoặc tự động sinh danh tính PKCS12 (RSA 3072-bit, SHA256withRSA)
 * theo đúng chính sách bảo mật M2/M3 quy định tại docs/SECURITY.md.
 * Tuyệt đối không lưu mật khẩu dạng plaintext trong Preferences.
 */
public final class LocalIdentityHelper {

    private static final String DEFAULT_ALIAS = "peer";

    private LocalIdentityHelper() {}

    /**
     * Nạp danh tính cục bộ từ đường dẫn và mật khẩu được chỉ định.
     */
    public static PeerNode load(Path storePath, char[] password, String alias)
            throws IOException, GeneralSecurityException {
        if (!Files.exists(storePath)) {
            throw new IOException("Tệp danh tính không tồn tại: " + storePath);
        }
        return PeerNode.loadIdentity(storePath, password, (alias != null && !alias.isBlank()) ? alias : DEFAULT_ALIAS);
    }

    /**
     * Nạp danh tính mặc định hoặc tự động sinh danh tính mới nếu chưa tồn tại.
     * Thứ tự ưu tiên xác định đường dẫn:
     * 1. System property: -Dp2p.identity.store=/path/to/identity.p12
     * 2. Environment variable: P2P_IDENTITY_STORE
     * 3. Mặc định: %LOCALAPPDATA%/P2pFileTransfer/identity.p12 (Windows) hoặc ~/.local/share/p2p-file-transfer/identity.p12
     */
    public static PeerNode getOrCreateDefaultIdentity() throws Exception {
        Path storePath = resolveDefaultStorePath();
        char[] password = resolvePassword(storePath);
        try {
            if (!Files.exists(storePath)) {
                generateIdentityWithKeytool(storePath, password, DEFAULT_ALIAS);
            }
            return PeerNode.loadIdentity(storePath, password, DEFAULT_ALIAS);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public static Path resolveDefaultStorePath() {
        String prop = System.getProperty("p2p.identity.store");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("P2P_IDENTITY_STORE");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }

        // Tùy chọn phân biệt tên instance cho demo localhost (ví dụ -Dp2p.identity.name=alice)
        String identityName = System.getProperty("p2p.identity.name", "identity");
        String os = System.getProperty("os.name", "").toLowerCase();
        Path baseDir;
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            baseDir = (localAppData != null && !localAppData.isBlank())
                    ? Path.of(localAppData, "P2pFileTransfer")
                    : Path.of(System.getProperty("user.home"), ".p2p-file-transfer");
        } else {
            String xdgData = System.getenv("XDG_DATA_HOME");
            baseDir = (xdgData != null && !xdgData.isBlank())
                    ? Path.of(xdgData, "p2p-file-transfer")
                    : Path.of(System.getProperty("user.home"), ".local", "share", "p2p-file-transfer");
        }
        return baseDir.resolve(identityName + ".p12");
    }

    private static char[] resolvePassword(Path storePath) throws IOException {
        String prop = System.getProperty("p2p.identity.password");
        if (prop != null && !prop.isBlank()) {
            return prop.toCharArray();
        }
        String env = System.getenv("P2P_IDENTITY_PASSWORD");
        if (env != null && !env.isBlank()) {
            return env.toCharArray();
        }
        String envName = System.getenv("P2P_PASSWORD_ENV");
        if (envName != null && !envName.isBlank()) {
            String val = System.getenv(envName);
            if (val != null && !val.isBlank()) {
                return val.toCharArray();
            }
        }

        // Kiểm tra tệp mật khẩu cục bộ được sinh ngẫu nhiên cùng thư mục
        Path passFile = storePath.resolveSibling(storePath.getFileName().toString().replace(".p12", ".pass"));
        if (Files.exists(passFile)) {
            String pass = Files.readString(passFile, StandardCharsets.UTF_8).trim();
            if (!pass.isEmpty()) {
                return pass.toCharArray();
            }
        }

        // Nếu chưa có, tạo mật khẩu ngẫu nhiên an toàn 32 ký tự hex (128-bit entropy)
        byte[] randomBytes = new byte[16];
        new SecureRandom().nextBytes(randomBytes);
        String generatedPassword = HexFormat.of().formatHex(randomBytes);

        if (storePath.getParent() != null) {
            Files.createDirectories(storePath.getParent());
        }
        Files.writeString(passFile, generatedPassword, StandardCharsets.UTF_8);
        setRestrictedPermissionsIfSupported(passFile);
        return generatedPassword.toCharArray();
    }

    private static void setRestrictedPermissionsIfSupported(Path file) {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
                // Best effort trên các môi trường POSIX hỗ trợ
            }
        }
    }

    public static void generateIdentityWithKeytool(Path storePath, char[] password, String alias) throws Exception {
        if (storePath.getParent() != null) {
            Files.createDirectories(storePath.getParent());
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        String executable = os.contains("win") ? "keytool.exe" : "keytool";
        Path keytoolPath = Path.of(System.getProperty("java.home"), "bin", executable);
        if (!Files.exists(keytoolPath)) {
            throw new IOException("Không tìm thấy tiện ích JDK keytool tại: " + keytoolPath);
        }

        List<String> command = List.of(
                keytoolPath.toAbsolutePath().toString(),
                "-genkeypair",
                "-alias", (alias != null && !alias.isBlank()) ? alias : DEFAULT_ALIAS,
                "-keyalg", "RSA",
                "-keysize", "3072",
                "-sigalg", "SHA256withRSA",
                "-validity", "365",
                "-dname", "CN=P2P Desktop Peer",
                "-ext", "BC=ca:false",
                "-ext", "KU=digitalSignature",
                "-ext", "EKU=serverAuth,clientAuth",
                "-storetype", "PKCS12",
                "-keystore", storePath.toAbsolutePath().toString(),
                "-storepass:env", "P2P_KEYTOOL_PASS",
                "-noprompt"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("P2P_KEYTOOL_PASS", new String(password));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        boolean completed = process.waitFor(45, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("Quá trình sinh danh tính bằng keytool đã quá thời gian chờ (45s).");
        }

        if (process.exitValue() != 0) {
            String errorMsg = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Sinh danh tính thất bại: " + errorMsg);
        }
        setRestrictedPermissionsIfSupported(storePath);
    }
}
