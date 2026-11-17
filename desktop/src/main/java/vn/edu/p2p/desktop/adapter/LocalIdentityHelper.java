package vn.edu.p2p.desktop.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import vn.edu.p2p.core.PeerNode;

/**
 * Tiện ích quản lý danh tính cục bộ PKCS12 (RSA 3072-bit, SHA256withRSA)
 * theo đúng chính sách bảo mật M2/M3 quy định tại docs/SECURITY.md.
 *
 * <p>Nguyên tắc bảo mật (Fail-Closed):
 * <ul>
 *   <li>Keystore và tệp mật khẩu mặc định được lưu ngoài repository (AppData/XDG data).</li>
 *   <li>Không hardcode mật khẩu mặc định; không ghi mật khẩu ra log hoặc Preferences;
 *       không truyền mật khẩu qua tham số dòng lệnh.</li>
 *   <li>Kiểm tra toàn bộ thành phần đường dẫn cha, thư mục identity, keystore và tệp mật khẩu
 *       để chặn symbolic-link attack và chống ghi đè tệp bí mật.</li>
 *   <li>Xác minh chủ sở hữu và quyền truy cập của <b>thư mục identity</b> dù là thư mục mới tạo
 *       hay đã tồn tại trước khi tạo tệp bí mật hoặc chạy {@code keytool}:
 *       <ul>
 *         <li>Linux/POSIX: chỉ chấp nhận thư mục {@code 0700} thuộc chủ sở hữu hiện tại;
 *             từ chối world-writable ({@code 0777}) hoặc group-accessible.</li>
 *         <li>Windows ACL: kiểm tra owner và mọi ACE hiệu lực (kể cả ACE kế thừa); từ chối
 *             mọi quyền nhạy cảm cấp cho Everyone, Guests, Users, Authenticated Users hoặc
 *             principal không tin cậy khác. Không tự động sửa ACL thư mục cũ không hợp lệ.</li>
 *       </ul>
 *   </li>
 *   <li>Xác minh quyền tệp bí mật hiện có ({@code .p12}, {@code .pass}) trước khi nạp;
 *       tạo tệp mới nguyên tử ({@code CREATE_NEW}) với quyền hạn chế ngay khi tạo.</li>
 * </ul>
 *
 * <p><b>Giới hạn bảo mật &amp; TOCTOU:</b>
 * <ul>
 *   <li>Quyền tệp OS ({@code 0600} / Windows ACL) bảo vệ tệp {@code .pass} khỏi tài khoản OS khác,
 *       nhưng <i>không tương đương mã hóa secret bằng OS credential manager</i> (DPAPI / Secret Service).</li>
 *   <li>Do {@code keytool} là tiến trình con nhận đường dẫn tệp thay vì file descriptor, vẫn tồn tại
 *       cửa sổ TOCTOU hẹp nếu một tiến trình có đặc quyền cao hoặc cùng tài khoản thay đổi quyền
 *       thư mục đúng thời điểm {@code keytool} chạy. Rủi ro này được giảm thiểu bằng việc xác minh
 *       quyền thư mục trước khi gọi {@code keytool}, không theo symlink và kiểm tra/siết quyền ngay
 *       sau khi {@code keytool} hoàn tất (xóa tệp tạo dở nếu kiểm tra thất bại).</li>
 * </ul>
 */
public final class LocalIdentityHelper {

    private static final String DEFAULT_ALIAS = "peer";
    private static final long MAX_PASS_FILE_BYTES = 4096L;
    private static final int KEYTOOL_TIMEOUT_SECONDS = 45;
    private static final Pattern SAFE_IDENTITY_NAME = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    static final Set<PosixFilePermission> OWNER_READ_WRITE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    static final Set<PosixFilePermission> OWNER_DIR_PERMS =
            EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    private static final Set<AclEntryPermission> WINDOWS_OWNER_FILE_PERMS =
            EnumSet.of(
                    AclEntryPermission.READ_DATA,
                    AclEntryPermission.WRITE_DATA,
                    AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.READ_NAMED_ATTRS,
                    AclEntryPermission.WRITE_NAMED_ATTRS,
                    AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.WRITE_ATTRIBUTES,
                    AclEntryPermission.DELETE,
                    AclEntryPermission.READ_ACL,
                    AclEntryPermission.WRITE_ACL,
                    AclEntryPermission.SYNCHRONIZE);
    private static final Set<AclEntryPermission> WINDOWS_OWNER_DIR_PERMS =
            EnumSet.allOf(AclEntryPermission.class);
    private static final Set<AclEntryPermission> SENSITIVE_ACL_PERMISSIONS =
            EnumSet.complementOf(EnumSet.of(AclEntryPermission.SYNCHRONIZE));

    static final PermissionEnforcer DEFAULT_PERMISSION_ENFORCER = new OsPermissionEnforcer();
    static final KeytoolRunner DEFAULT_KEYTOOL_RUNNER =
            (storePath, password, alias) ->
                    runKeytoolProcess(resolveDefaultKeytoolExecutable(), storePath, password, alias);

    private LocalIdentityHelper() {}

    interface PermissionEnforcer {
        void restrictFile(Path file) throws IOException;

        void restrictDirectory(Path directory) throws IOException;

        void verifyExistingFile(Path file) throws IOException;

        void verifyExistingDirectory(Path directory) throws IOException;
    }

    interface KeytoolRunner {
        void generate(Path storePath, char[] password, String alias) throws IOException;
    }

    /**
     * Nạp danh tính cục bộ từ đường dẫn và mật khẩu được chỉ định sau khi xác minh đường dẫn an toàn.
     */
    public static PeerNode load(Path storePath, char[] password, String alias)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(storePath, "Đường dẫn keystore không được null");
        Objects.requireNonNull(password, "Mật khẩu keystore không được null");
        Path normalized = storePath.toAbsolutePath().normalize();
        ensureNoSymlinkInPath(normalized, "tệp danh tính");
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tệp danh tính không tồn tại: " + normalized);
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Đường dẫn danh tính không phải tệp thường: " + normalized);
        }
        Path parentDir = normalized.getParent();
        if (parentDir != null) {
            DEFAULT_PERMISSION_ENFORCER.verifyExistingDirectory(parentDir);
        }
        DEFAULT_PERMISSION_ENFORCER.verifyExistingFile(normalized);
        return PeerNode.loadIdentity(
                normalized, password, (alias != null && !alias.isBlank()) ? alias : DEFAULT_ALIAS);
    }

    /**
     * Nạp danh tính mặc định hoặc tự động sinh danh tính mới nếu chưa tồn tại.
     */
    public static PeerNode getOrCreateDefaultIdentity() throws IOException, GeneralSecurityException {
        Path storePath = resolveDefaultStorePath();
        return getOrCreateIdentity(storePath, DEFAULT_PERMISSION_ENFORCER, DEFAULT_KEYTOOL_RUNNER);
    }

    /**
     * Nạp hoặc tự động khởi tạo danh tính cục bộ tại đường dẫn {@code storePath}.
     */
    public static PeerNode getOrCreateIdentity(Path storePath)
            throws IOException, GeneralSecurityException {
        return getOrCreateIdentity(storePath, DEFAULT_PERMISSION_ENFORCER, DEFAULT_KEYTOOL_RUNNER);
    }

    static PeerNode getOrCreateIdentity(
            Path storePath, PermissionEnforcer permissionEnforcer, KeytoolRunner keytoolRunner)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(storePath, "Đường dẫn keystore không được null");
        Objects.requireNonNull(permissionEnforcer, "permissionEnforcer không được null");
        Objects.requireNonNull(keytoolRunner, "keytoolRunner không được null");

        Path normalizedStore = storePath.toAbsolutePath().normalize();
        Path parentDir = normalizedStore.getParent();
        if (parentDir == null) {
            throw new IOException("Đường dẫn keystore không có thư mục cha hợp lệ: " + normalizedStore);
        }
        ensureNoSymlinkInPath(normalizedStore, "tệp keystore");

        Path passFile = resolvePasswordFile(normalizedStore);
        ensureNoSymlinkInPath(passFile, "tệp mật khẩu cục bộ");

        // Luôn xác minh an toàn thư mục identity (mới hoặc đã tồn tại) TRƯỚC mọi thao tác đọc/ghi bí mật
        prepareOrVerifySecureDirectory(parentDir, permissionEnforcer);

        boolean storeExists = Files.exists(normalizedStore, LinkOption.NOFOLLOW_LINKS);
        if (storeExists && !Files.isRegularFile(normalizedStore, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Đường dẫn keystore không phải tệp thường: " + normalizedStore);
        }

        char[] envPassword = resolveEnvironmentPassword();
        if (envPassword != null) {
            try {
                if (!storeExists) {
                    generateAndRestrictKeystore(
                            normalizedStore, envPassword, DEFAULT_ALIAS, permissionEnforcer, keytoolRunner);
                } else {
                    permissionEnforcer.verifyExistingFile(normalizedStore);
                }
                return PeerNode.loadIdentity(normalizedStore, envPassword, DEFAULT_ALIAS);
            } finally {
                Arrays.fill(envPassword, '\0');
            }
        }

        boolean passExists = Files.exists(passFile, LinkOption.NOFOLLOW_LINKS);
        if (storeExists) {
            if (!passExists) {
                throw new IOException(
                        "Tệp keystore đã tồn tại nhưng không tìm thấy tệp mật khẩu cục bộ tương ứng"
                                + " (hoặc biến môi trường P2P_IDENTITY_PASSWORD / P2P_PASSWORD_ENV).");
            }
            permissionEnforcer.verifyExistingFile(normalizedStore);
            char[] existingPassword = readSecurePasswordFile(passFile, permissionEnforcer);
            try {
                return PeerNode.loadIdentity(normalizedStore, existingPassword, DEFAULT_ALIAS);
            } finally {
                Arrays.fill(existingPassword, '\0');
            }
        }

        if (passExists) {
            throw new IOException(
                    "Phát hiện tệp mật khẩu tồn tại sẵn trong khi chưa có keystore; từ chối ghi đè: "
                            + passFile);
        }

        char[] generatedPassword = generateRandomPasswordChars();
        boolean passFileCreated = false;
        try {
            writeSecureNewPasswordFile(passFile, generatedPassword, permissionEnforcer);
            passFileCreated = true;
            generateAndRestrictKeystore(
                    normalizedStore, generatedPassword, DEFAULT_ALIAS, permissionEnforcer, keytoolRunner);
            return PeerNode.loadIdentity(normalizedStore, generatedPassword, DEFAULT_ALIAS);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            if (passFileCreated && !Files.exists(normalizedStore, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (!Files.isSymbolicLink(passFile)) {
                        Files.deleteIfExists(passFile);
                    }
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
            }
            throw e;
        } finally {
            Arrays.fill(generatedPassword, '\0');
        }
    }

    public static Path resolveDefaultStorePath() {
        String prop = System.getProperty("p2p.identity.store");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop.trim()).toAbsolutePath().normalize();
        }
        String env = System.getenv("P2P_IDENTITY_STORE");
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim()).toAbsolutePath().normalize();
        }

        String identityName = System.getProperty("p2p.identity.name", "identity").trim();
        validateIdentityName(identityName);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path baseDir;
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            baseDir =
                    (localAppData != null && !localAppData.isBlank())
                            ? Path.of(localAppData, "P2pFileTransfer")
                            : Path.of(System.getProperty("user.home"), ".p2p-file-transfer");
        } else {
            String xdgData = System.getenv("XDG_DATA_HOME");
            baseDir =
                    (xdgData != null && !xdgData.isBlank())
                            ? Path.of(xdgData, "p2p-file-transfer")
                            : Path.of(System.getProperty("user.home"), ".local", "share", "p2p-file-transfer");
        }
        return baseDir.resolve(identityName + ".p12").toAbsolutePath().normalize();
    }

    public static Path resolvePasswordFile(Path storePath) throws IOException {
        Objects.requireNonNull(storePath, "Đường dẫn keystore không được null");
        Path fileNamePath = storePath.getFileName();
        if (fileNamePath == null) {
            throw new IOException("Đường dẫn keystore không có tên tệp hợp lệ: " + storePath);
        }
        String fileName = fileNamePath.toString();
        String passName;
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".p12") && fileName.length() > 4) {
            passName = fileName.substring(0, fileName.length() - 4) + ".pass";
        } else {
            passName = fileName + ".pass";
        }
        Path passFile = storePath.resolveSibling(passName);
        if (passFile.equals(storePath)) {
            throw new IOException("Tệp mật khẩu không được trùng đường dẫn với keystore.");
        }
        return passFile;
    }

    public static void generateIdentityWithKeytool(Path storePath, char[] password, String alias)
            throws IOException {
        generateAndRestrictKeystore(
                storePath.toAbsolutePath().normalize(),
                password,
                alias,
                DEFAULT_PERMISSION_ENFORCER,
                DEFAULT_KEYTOOL_RUNNER);
    }

    public static void restrictFileToCurrentUser(Path file) throws IOException {
        DEFAULT_PERMISSION_ENFORCER.restrictFile(file);
    }

    public static void restrictDirectoryToCurrentUser(Path directory) throws IOException {
        DEFAULT_PERMISSION_ENFORCER.restrictDirectory(directory);
    }

    static void generateAndRestrictKeystore(
            Path storePath,
            char[] password,
            String alias,
            PermissionEnforcer permissionEnforcer,
            KeytoolRunner keytoolRunner)
            throws IOException {
        Objects.requireNonNull(storePath, "Đường dẫn keystore không được null");
        Objects.requireNonNull(password, "Mật khẩu không được null");
        if (password.length < 6) {
            throw new IOException("Mật khẩu keystore phải có tối thiểu 6 ký tự.");
        }
        ensureNoSymlinkInPath(storePath, "tệp keystore");
        if (Files.exists(storePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tệp keystore đã tồn tại; từ chối ghi đè: " + storePath);
        }
        Path parentDir = storePath.getParent();
        if (parentDir == null) {
            throw new IOException("Đường dẫn keystore không có thư mục cha hợp lệ: " + storePath);
        }
        // Xác minh/chuẩn bị thư mục an toàn TRƯỚC KHI keytool ghi keystore
        prepareOrVerifySecureDirectory(parentDir, permissionEnforcer);

        try {
            keytoolRunner.generate(storePath, password, alias);
            ensureNoSymlinkInPath(storePath, "tệp keystore vừa tạo");
            if (!Files.isRegularFile(storePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Keytool không tạo ra tệp keystore hợp lệ tại: " + storePath);
            }
            permissionEnforcer.restrictFile(storePath);
            permissionEnforcer.verifyExistingFile(storePath);
        } catch (IOException | RuntimeException e) {
            try {
                if (!Files.isSymbolicLink(storePath)) {
                    Files.deleteIfExists(storePath);
                }
            } catch (IOException cleanupEx) {
                e.addSuppressed(cleanupEx);
            }
            throw e;
        }
    }

    static Path resolveDefaultKeytoolExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String executable = os.contains("win") ? "keytool.exe" : "keytool";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    static void runKeytoolProcess(Path keytoolPath, Path storePath, char[] password, String alias)
            throws IOException {
        if (!Files.exists(keytoolPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Không tìm thấy tiện ích JDK keytool tại: " + keytoolPath);
        }
        String effectiveAlias = (alias != null && !alias.isBlank()) ? alias : DEFAULT_ALIAS;

        List<String> command =
                List.of(
                        keytoolPath.toAbsolutePath().toString(),
                        "-genkeypair",
                        "-alias",
                        effectiveAlias,
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "3072",
                        "-sigalg",
                        "SHA256withRSA",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=P2P Desktop Peer",
                        "-ext",
                        "BC=ca:false",
                        "-ext",
                        "KU=digitalSignature",
                        "-ext",
                        "EKU=serverAuth,clientAuth",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        storePath.toAbsolutePath().toString(),
                        "-storepass:env",
                        "P2P_KEYTOOL_PASS",
                        "-noprompt");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("P2P_KEYTOOL_PASS", new String(password));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder outputCollector = new StringBuilder();
        Thread outputDrainer =
                Thread.ofVirtual()
                        .name("keytool-output-drainer")
                        .start(
                                () -> {
                                    try {
                                        byte[] rawOutput = process.getInputStream().readAllBytes();
                                        outputCollector.append(new String(rawOutput, StandardCharsets.UTF_8));
                                    } catch (IOException ignored) {
                                        // Stream đóng khi process kết thúc hoặc bị hủy
                                    }
                                });

        try {
            boolean completed = process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IOException(
                        "Quá trình sinh danh tính bằng keytool đã quá thời gian chờ ("
                                + KEYTOOL_TIMEOUT_SECONDS
                                + "s).");
            }
            outputDrainer.join(2000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Quá trình sinh danh tính bằng keytool bị gián đoạn.", e);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        String output = outputCollector.toString().trim();
        if (process.exitValue() != 0) {
            String summary =
                    output.isBlank() ? ("exitCode=" + process.exitValue()) : sanitizeKeytoolError(output);
            throw new IOException("Sinh danh tính bằng keytool thất bại: " + summary);
        }
    }

    private static String sanitizeKeytoolError(String rawOutput) {
        String compact = rawOutput.replace('\r', ' ').replace('\n', ' ').trim();
        if (compact.length() > 240) {
            return compact.substring(0, 240) + "...";
        }
        return compact;
    }

    private static void validateIdentityName(String identityName) {
        if (identityName.isEmpty()
                || ".".equals(identityName)
                || "..".equals(identityName)
                || !SAFE_IDENTITY_NAME.matcher(identityName).matches()) {
            throw new IllegalArgumentException(
                    "Tên danh tính không hợp lệ (chỉ chấp nhận chữ, số, '.', '-', '_', tối đa 64 ký tự): "
                            + identityName);
        }
    }

    private static char[] resolveEnvironmentPassword() {
        String envName = System.getenv("P2P_PASSWORD_ENV");
        if (envName != null && !envName.isBlank()) {
            String val = System.getenv(envName.trim());
            if (val != null && !val.isBlank()) {
                return val.toCharArray();
            }
        }
        String env = System.getenv("P2P_IDENTITY_PASSWORD");
        if (env != null && !env.isBlank()) {
            return env.toCharArray();
        }
        return null;
    }

    private static char[] generateRandomPasswordChars() {
        byte[] randomBytes = new byte[16];
        new SecureRandom().nextBytes(randomBytes);
        try {
            return HexFormat.of().formatHex(randomBytes).toCharArray();
        } finally {
            Arrays.fill(randomBytes, (byte) 0);
        }
    }

    private static char[] readSecurePasswordFile(Path passFile, PermissionEnforcer permissionEnforcer)
            throws IOException {
        ensureNoSymlinkInPath(passFile, "tệp mật khẩu cục bộ");
        if (!Files.isRegularFile(passFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tệp mật khẩu cục bộ không phải tệp thường: " + passFile);
        }
        long size = Files.size(passFile);
        if (size <= 0 || size > MAX_PASS_FILE_BYTES) {
            throw new IOException("Kích thước tệp mật khẩu cục bộ không hợp lệ.");
        }
        permissionEnforcer.verifyExistingFile(passFile);

        byte[] raw = Files.readAllBytes(passFile);
        try {
            String content = new String(raw, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                throw new IOException("Tệp mật khẩu cục bộ đang rỗng.");
            }
            return content.toCharArray();
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static void writeSecureNewPasswordFile(
            Path passFile, char[] password, PermissionEnforcer permissionEnforcer) throws IOException {
        ensureNoSymlinkInPath(passFile, "tệp mật khẩu cục bộ");
        if (Files.exists(passFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tệp mật khẩu cục bộ đã tồn tại; từ chối ghi đè: " + passFile);
        }

        Set<String> views = passFile.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) {
            Files.createFile(passFile, PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
        } else if (views.contains("acl")) {
            UserPrincipal owner = lookupCurrentUserPrincipal(passFile);
            if (owner != null) {
                Files.createFile(passFile, buildWindowsOwnerAclAttribute(owner, false));
            } else {
                Files.createFile(passFile);
            }
        } else {
            Files.createFile(passFile);
        }

        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] exactBytes = new byte[encoded.remaining()];
        encoded.get(exactBytes);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }

        try {
            permissionEnforcer.restrictFile(passFile);
            permissionEnforcer.verifyExistingFile(passFile);
            try (FileChannel channel =
                    FileChannel.open(
                            passFile,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(exactBytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException | RuntimeException e) {
            try {
                if (!Files.isSymbolicLink(passFile)) {
                    Files.deleteIfExists(passFile);
                }
            } catch (IOException cleanupEx) {
                e.addSuppressed(cleanupEx);
            }
            throw e;
        } finally {
            Arrays.fill(exactBytes, (byte) 0);
        }
    }

    /**
     * Chuẩn bị thư mục identity mới (với quyền riêng tư) hoặc xác minh nghiêm ngặt thư mục đã tồn tại.
     * Tuyệt đối KHÔNG tự động sửa ACL/chmod của thư mục cũ không hợp lệ (fail-closed).
     */
    private static void prepareOrVerifySecureDirectory(
            Path parentDir, PermissionEnforcer permissionEnforcer) throws IOException {
        ensureNoSymlinkInPath(parentDir, "thư mục chứa danh tính");

        if (Files.exists(parentDir, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(parentDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Đường dẫn cha không phải thư mục hợp lệ: " + parentDir);
            }
            permissionEnforcer.verifyExistingDirectory(parentDir);
            return;
        }

        // Tạo lần lượt các thư mục chưa tồn tại; thư mục identity đích được tạo với quyền riêng tư
        Deque<Path> missingDirs = new ArrayDeque<>();
        Path cursor = parentDir;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missingDirs.push(cursor);
            cursor = cursor.getParent();
        }
        if (cursor != null) {
            ensureNoSymlinkInPath(cursor, "thư mục gốc của danh tính");
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Thành phần cha không phải thư mục hợp lệ: " + cursor);
            }
        }

        Set<String> views = parentDir.getFileSystem().supportedFileAttributeViews();
        while (!missingDirs.isEmpty()) {
            Path dirToCreate = missingDirs.pop();
            boolean isTargetDir = dirToCreate.equals(parentDir);
            if (isTargetDir && views.contains("posix")) {
                Files.createDirectory(dirToCreate, PosixFilePermissions.asFileAttribute(OWNER_DIR_PERMS));
            } else if (isTargetDir && views.contains("acl")) {
                UserPrincipal owner = lookupCurrentUserPrincipal(dirToCreate);
                if (owner != null) {
                    Files.createDirectory(dirToCreate, buildWindowsOwnerAclAttribute(owner, true));
                } else {
                    Files.createDirectory(dirToCreate);
                }
            } else {
                Files.createDirectory(dirToCreate);
            }
            if (isTargetDir) {
                permissionEnforcer.restrictDirectory(dirToCreate);
                permissionEnforcer.verifyExistingDirectory(dirToCreate);
            }
        }
    }

    /**
     * Kiểm tra toàn bộ các thành phần đường dẫn đang tồn tại từ gốc đến {@code path}
     * để bảo đảm không có symbolic link nào nằm trên đường dẫn nhạy cảm.
     */
    static void ensureNoSymlinkInPath(Path path, String label) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path current = normalized.getRoot();
        for (Path part : normalized) {
            current = (current == null) ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException(
                        "Từ chối truy cập đường dẫn đi qua symbolic link (" + label + "): " + current);
            }
        }
    }

    private static FileAttribute<List<AclEntry>> buildWindowsOwnerAclAttribute(
            UserPrincipal owner, boolean isDirectory) {
        AclEntry.Builder builder =
                AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(isDirectory ? WINDOWS_OWNER_DIR_PERMS : WINDOWS_OWNER_FILE_PERMS);
        if (isDirectory) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        List<AclEntry> acl = List.of(builder.build());
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }

    private static UserPrincipal lookupCurrentUserPrincipal(Path path) {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isBlank()) {
            return null;
        }
        try {
            return path.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(userName);
        } catch (IOException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static UserPrincipal lookupPrincipalQuietly(
            UserPrincipalLookupService lookup, String name) {
        try {
            return lookup.lookupPrincipalByName(name);
        } catch (IOException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    static void validatePosixDirectoryPermissions(
            Path directory,
            UserPrincipal actualOwner,
            UserPrincipal expectedOwner,
            Set<PosixFilePermission> permissions)
            throws IOException {
        if (expectedOwner == null || !expectedOwner.equals(actualOwner)) {
            throw new IOException(
                    "Thư mục danh tính không thuộc tài khoản hiện tại (owner="
                            + actualOwner
                            + ", expected="
                            + expectedOwner
                            + "): "
                            + directory);
        }
        if (permissions == null || !OWNER_DIR_PERMS.equals(permissions)) {
            String permStr =
                    (permissions == null) ? "null" : PosixFilePermissions.toString(permissions);
            throw new IOException(
                    "Thư mục danh tính có quyền POSIX không an toàn (yêu cầu 0700 / rwx------, hiện tại: "
                            + permStr
                            + "): "
                            + directory);
        }
    }

    static void validatePosixFilePermissions(
            Path file,
            UserPrincipal actualOwner,
            UserPrincipal expectedOwner,
            Set<PosixFilePermission> permissions)
            throws IOException {
        if (expectedOwner == null || !expectedOwner.equals(actualOwner)) {
            throw new IOException(
                    "Tệp bí mật không thuộc tài khoản hiện tại (owner="
                            + actualOwner
                            + ", expected="
                            + expectedOwner
                            + "): "
                            + file);
        }
        if (permissions == null
                || !permissions.contains(PosixFilePermission.OWNER_READ)
                || !OWNER_READ_WRITE.containsAll(permissions)) {
            String permStr =
                    (permissions == null) ? "null" : PosixFilePermissions.toString(permissions);
            throw new IOException(
                    "Tệp bí mật có quyền POSIX không an toàn (yêu cầu 0600 / rw-------, hiện tại: "
                            + permStr
                            + "): "
                            + file);
        }
    }

    static void validateWindowsAcl(
            Path path,
            boolean isDirectory,
            UserPrincipal actualOwner,
            UserPrincipal currentUser,
            UserPrincipal systemPrincipal,
            UserPrincipal adminsPrincipal,
            UserPrincipal creatorOwnerPrincipal,
            List<AclEntry> acl)
            throws IOException {
        String targetType = isDirectory ? "Thư mục danh tính" : "Tệp bí mật";
        if (actualOwner == null) {
            throw new IOException(targetType + " không có thông tin chủ sở hữu ACL hợp lệ: " + path);
        }
        boolean ownerValid =
                (currentUser != null && currentUser.equals(actualOwner))
                        || (adminsPrincipal != null && adminsPrincipal.equals(actualOwner));
        if (!ownerValid) {
            throw new IOException(
                    targetType
                            + " không thuộc quyền sở hữu tài khoản hiện tại (owner="
                            + actualOwner.getName()
                            + "): "
                            + path);
        }

        if (acl == null || acl.isEmpty()) {
            throw new IOException(targetType + " có danh sách Windows ACL rỗng hoặc không hợp lệ: " + path);
        }

        boolean currentUserHasAccess = false;
        for (AclEntry entry : acl) {
            if (entry.type() != AclEntryType.ALLOW) {
                continue;
            }
            Set<AclEntryPermission> perms = entry.permissions();
            boolean grantsSensitive = false;
            for (AclEntryPermission perm : perms) {
                if (SENSITIVE_ACL_PERMISSIONS.contains(perm)) {
                    grantsSensitive = true;
                    break;
                }
            }
            if (!grantsSensitive) {
                continue;
            }

            UserPrincipal principal = entry.principal();
            if (currentUser != null && currentUser.equals(principal)) {
                currentUserHasAccess = true;
                continue;
            }
            if (systemPrincipal != null && systemPrincipal.equals(principal)) {
                continue;
            }
            if (adminsPrincipal != null && adminsPrincipal.equals(principal)) {
                if (adminsPrincipal.equals(actualOwner)) {
                    currentUserHasAccess = true;
                }
                continue;
            }
            if (isDirectory
                    && creatorOwnerPrincipal != null
                    && creatorOwnerPrincipal.equals(principal)
                    && entry.flags().contains(AclEntryFlag.INHERIT_ONLY)) {
                continue;
            }

            throw new IOException(
                    targetType
                            + " có quyền Windows ACL không an toàn cho đối tượng không tin cậy ("
                            + (principal != null ? principal.getName() : "unknown")
                            + "): "
                            + path);
        }

        if (!currentUserHasAccess) {
            throw new IOException(
                    targetType + " không cấp quyền truy cập hợp lệ cho tài khoản hiện tại: " + path);
        }
    }

    private static final class OsPermissionEnforcer implements PermissionEnforcer {

        @Override
        public void restrictFile(Path file) throws IOException {
            ensureNoSymlinkInPath(file, "tệp nhạy cảm");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Không thể đặt quyền cho đường dẫn không phải tệp thường: " + file);
            }

            Set<String> views = file.getFileSystem().supportedFileAttributeViews();
            if (views.contains("posix")) {
                Files.setPosixFilePermissions(file, OWNER_READ_WRITE);
                verifyExistingFile(file);
                return;
            }

            AclFileAttributeView aclView =
                    Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (aclView != null) {
                UserPrincipal owner = resolveCurrentUserForWrite(file, aclView);
                AclEntry ownerEntry =
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(owner)
                                .setPermissions(WINDOWS_OWNER_FILE_PERMS)
                                .build();
                aclView.setAcl(List.of(ownerEntry));

                List<AclEntry> applied = aclView.getAcl();
                if (applied.isEmpty() || !applied.stream().allMatch(e -> owner.equals(e.principal()))) {
                    throw new IOException(
                            "Không thể xác nhận giới hạn Windows ACL chỉ cho tài khoản hiện tại trên: "
                                    + file);
                }
                return;
            }

            throw new IOException(
                    "Hệ thống tệp không hỗ trợ thiết lập quyền truy cập an toàn (POSIX/ACL) cho: " + file);
        }

        @Override
        public void restrictDirectory(Path directory) throws IOException {
            ensureNoSymlinkInPath(directory, "thư mục nhạy cảm");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Không phải thư mục hợp lệ: " + directory);
            }

            Set<String> views = directory.getFileSystem().supportedFileAttributeViews();
            if (views.contains("posix")) {
                Files.setPosixFilePermissions(directory, OWNER_DIR_PERMS);
                verifyExistingDirectory(directory);
                return;
            }

            AclFileAttributeView aclView =
                    Files.getFileAttributeView(
                            directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (aclView != null) {
                UserPrincipal owner = resolveCurrentUserForWrite(directory, aclView);
                AclEntry ownerEntry =
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(owner)
                                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                                .setPermissions(WINDOWS_OWNER_DIR_PERMS)
                                .build();
                aclView.setAcl(List.of(ownerEntry));

                List<AclEntry> applied = aclView.getAcl();
                if (applied.isEmpty() || !applied.stream().allMatch(e -> owner.equals(e.principal()))) {
                    throw new IOException(
                            "Không thể xác nhận giới hạn Windows ACL chỉ cho tài khoản hiện tại trên thư mục: "
                                    + directory);
                }
                return;
            }

            throw new IOException(
                    "Hệ thống tệp không hỗ trợ thiết lập quyền an toàn (POSIX/ACL) cho thư mục: "
                            + directory);
        }

        @Override
        public void verifyExistingFile(Path file) throws IOException {
            ensureNoSymlinkInPath(file, "tệp bí mật");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Tệp bí mật không phải tệp thường: " + file);
            }
            verifyPathSecurity(file, false);
        }

        @Override
        public void verifyExistingDirectory(Path directory) throws IOException {
            ensureNoSymlinkInPath(directory, "thư mục danh tính");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Đường dẫn danh tính không phải thư mục hợp lệ: " + directory);
            }
            verifyPathSecurity(directory, true);
        }

        private static void verifyPathSecurity(Path path, boolean isDirectory) throws IOException {
            Set<String> views = path.getFileSystem().supportedFileAttributeViews();
            if (views.contains("posix")) {
                UserPrincipal actualOwner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
                UserPrincipal expectedOwner = lookupCurrentUserPrincipal(path);
                Set<PosixFilePermission> perms =
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                if (isDirectory) {
                    validatePosixDirectoryPermissions(path, actualOwner, expectedOwner, perms);
                } else {
                    validatePosixFilePermissions(path, actualOwner, expectedOwner, perms);
                }
                return;
            }

            AclFileAttributeView aclView =
                    Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (aclView != null) {
                UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
                UserPrincipal currentUser = lookupCurrentUserPrincipal(path);
                UserPrincipal systemPrincipal = lookupPrincipalQuietly(lookup, "NT AUTHORITY\\SYSTEM");
                UserPrincipal adminsPrincipal = lookupPrincipalQuietly(lookup, "BUILTIN\\Administrators");
                UserPrincipal creatorOwnerPrincipal = lookupPrincipalQuietly(lookup, "CREATOR OWNER");
                UserPrincipal actualOwner = aclView.getOwner();
                List<AclEntry> acl = aclView.getAcl();

                validateWindowsAcl(
                        path,
                        isDirectory,
                        actualOwner,
                        currentUser,
                        systemPrincipal,
                        adminsPrincipal,
                        creatorOwnerPrincipal,
                        acl);
                return;
            }

            throw new IOException(
                    "Hệ thống tệp không hỗ trợ kiểm tra quyền truy cập an toàn (POSIX/ACL) cho: " + path);
        }

        private static UserPrincipal resolveCurrentUserForWrite(
                Path path, AclFileAttributeView aclView) throws IOException {
            UserPrincipal currentUser = lookupCurrentUserPrincipal(path);
            if (currentUser != null) {
                return currentUser;
            }
            UserPrincipal owner = aclView.getOwner();
            if (owner == null) {
                throw new IOException("Không thể xác định chủ sở hữu tài khoản OS cho: " + path);
            }
            return owner;
        }
    }
}
