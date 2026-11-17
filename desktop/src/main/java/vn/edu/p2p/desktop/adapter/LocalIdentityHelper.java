package vn.edu.p2p.desktop.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;
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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
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
 * <p>Nguyên tắc bảo mật:
 * <ul>
 *   <li>Keystore và tệp mật khẩu mặc định được lưu ngoài repository (AppData/XDG data).</li>
 *   <li>Không hardcode mật khẩu mặc định; không ghi mật khẩu ra log hoặc Preferences;
 *       không truyền mật khẩu qua tham số dòng lệnh.</li>
 *   <li>Chống symbolic-link attack và chống ghi đè tệp nhạy cảm bằng {@code NOFOLLOW_LINKS}
 *       và {@code CREATE_NEW}.</li>
 *   <li>Bắt buộc giới hạn quyền truy cập tệp nhạy cảm (POSIX {@code 0600} trên Linux,
 *       Windows ACL chỉ cho phép tài khoản hiện tại). Nếu không thể thiết lập quyền an toàn,
 *       phương thức sẽ ném {@link IOException} (fail-closed) thay vì tiếp tục không an toàn.</li>
 * </ul>
 *
 * <p><b>Giới hạn bảo mật MVP:</b> Khi người dùng không cung cấp biến môi trường mật khẩu,
 * mật khẩu ngẫu nhiên 128-bit được lưu tại tệp {@code .pass} cục bộ được bảo vệ bởi quyền OS
 * (0600 / Windows ACL). Cơ chế quyền tệp hạn chế tài khoản OS khác trên cùng máy, nhưng
 * <i>không tương đương với mã hóa secret bằng OS credential manager</i> (Windows DPAPI/Credential
 * Manager hoặc Linux Secret Service).
 */
public final class LocalIdentityHelper {

    private static final String DEFAULT_ALIAS = "peer";
    private static final long MAX_PASS_FILE_BYTES = 4096L;
    private static final int KEYTOOL_TIMEOUT_SECONDS = 45;
    private static final Pattern SAFE_IDENTITY_NAME = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");
    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIR_PERMS =
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

    static final PermissionEnforcer DEFAULT_PERMISSION_ENFORCER = new OsPermissionEnforcer();
    static final KeytoolRunner DEFAULT_KEYTOOL_RUNNER =
            (storePath, password, alias) ->
                    runKeytoolProcess(resolveDefaultKeytoolExecutable(), storePath, password, alias);

    private LocalIdentityHelper() {}

    interface PermissionEnforcer {
        void restrictFile(Path file) throws IOException;

        void restrictDirectory(Path directory) throws IOException;
    }

    interface KeytoolRunner {
        void generate(Path storePath, char[] password, String alias) throws IOException;
    }

    /**
     * Nạp danh tính cục bộ từ đường dẫn và mật khẩu được chỉ định.
     */
    public static PeerNode load(Path storePath, char[] password, String alias)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(storePath, "Đường dẫn keystore không được null");
        Objects.requireNonNull(password, "Mật khẩu keystore không được null");
        ensureNotSymlink(storePath, "tệp danh tính");
        if (!Files.exists(storePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tệp danh tính không tồn tại: " + storePath);
        }
        if (!Files.isRegularFile(storePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Đường dẫn danh tính không phải tệp thường: " + storePath);
        }
        return PeerNode.loadIdentity(
                storePath, password, (alias != null && !alias.isBlank()) ? alias : DEFAULT_ALIAS);
    }

    /**
     * Nạp danh tính mặc định hoặc tự động sinh danh tính mới nếu chưa tồn tại.
     * Thứ tự ưu tiên xác định đường dẫn (mặc định luôn nằm ngoài repository):
     * 1. System property: -Dp2p.identity.store=/path/to/identity.p12
     * 2. Environment variable: P2P_IDENTITY_STORE
     * 3. Mặc định: %LOCALAPPDATA%/P2pFileTransfer/&lt;name&gt;.p12 (Windows)
     *    hoặc ~/.local/share/p2p-file-transfer/&lt;name&gt;.p12 (Linux)
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
        ensureNotSymlink(parentDir, "thư mục chứa danh tính");
        ensureNotSymlink(normalizedStore, "tệp keystore");

        Path passFile = resolvePasswordFile(normalizedStore);
        ensureNotSymlink(passFile, "tệp mật khẩu cục bộ");

        boolean storeExists = Files.exists(normalizedStore, LinkOption.NOFOLLOW_LINKS);
        if (storeExists && !Files.isRegularFile(normalizedStore, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Đường dẫn keystore không phải tệp thường: " + normalizedStore);
        }

        char[] envPassword = resolveEnvironmentPassword();
        if (envPassword != null) {
            try {
                if (!storeExists) {
                    prepareSecureParentDirectory(parentDir, permissionEnforcer);
                    generateAndRestrictKeystore(
                            normalizedStore, envPassword, DEFAULT_ALIAS, permissionEnforcer, keytoolRunner);
                } else {
                    permissionEnforcer.restrictFile(normalizedStore);
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
            permissionEnforcer.restrictFile(normalizedStore);
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

        prepareSecureParentDirectory(parentDir, permissionEnforcer);
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
                    Files.deleteIfExists(passFile);
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
        ensureNotSymlink(storePath, "tệp keystore");
        if (Files.exists(storePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tệp keystore đã tồn tại; từ chối ghi đè: " + storePath);
        }
        Path parentDir = storePath.getParent();
        if (parentDir != null) {
            ensureNotSymlink(parentDir, "thư mục chứa keystore");
            prepareSecureParentDirectory(parentDir, permissionEnforcer);
        }

        try {
            keytoolRunner.generate(storePath, password, alias);
            ensureNotSymlink(storePath, "tệp keystore vừa tạo");
            if (!Files.isRegularFile(storePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Keytool không tạo ra tệp keystore hợp lệ tại: " + storePath);
            }
            permissionEnforcer.restrictFile(storePath);
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
        ensureNotSymlink(passFile, "tệp mật khẩu cục bộ");
        if (!Files.isRegularFile(passFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tệp mật khẩu cục bộ không phải tệp thường: " + passFile);
        }
        long size = Files.size(passFile);
        if (size <= 0 || size > MAX_PASS_FILE_BYTES) {
            throw new IOException("Kích thước tệp mật khẩu cục bộ không hợp lệ.");
        }
        permissionEnforcer.restrictFile(passFile);

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
        ensureNotSymlink(passFile, "tệp mật khẩu cục bộ");
        if (Files.exists(passFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tệp mật khẩu cục bộ đã tồn tại; từ chối ghi đè: " + passFile);
        }

        boolean posixSupported =
                passFile.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posixSupported) {
            Files.createFile(passFile, PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
        } else {
            // Tạo tệp rỗng nguyên tử (CREATE_NEW) rồi giới hạn ACL trước khi ghi byte mật khẩu
            Files.createFile(passFile);
        }

        ByteBuffer encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
        byte[] exactBytes = new byte[encoded.remaining()];
        encoded.get(exactBytes);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }

        try {
            permissionEnforcer.restrictFile(passFile);
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

    private static void prepareSecureParentDirectory(
            Path parentDir, PermissionEnforcer permissionEnforcer) throws IOException {
        ensureNotSymlink(parentDir, "thư mục chứa danh tính");
        boolean existedBefore = Files.exists(parentDir, LinkOption.NOFOLLOW_LINKS);
        if (!existedBefore) {
            Files.createDirectories(parentDir);
            permissionEnforcer.restrictDirectory(parentDir);
        } else if (!Files.isDirectory(parentDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Đường dẫn cha không phải thư mục hợp lệ: " + parentDir);
        }
    }

    private static void ensureNotSymlink(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Từ chối truy cập symbolic link cho " + label + ": " + path);
        }
    }

    private static final class OsPermissionEnforcer implements PermissionEnforcer {

        @Override
        public void restrictFile(Path file) throws IOException {
            ensureNotSymlink(file, "tệp nhạy cảm");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Không thể đặt quyền cho đường dẫn không phải tệp thường: " + file);
            }

            Set<String> views = file.getFileSystem().supportedFileAttributeViews();
            if (views.contains("posix")) {
                Files.setPosixFilePermissions(file, OWNER_READ_WRITE);
                Set<PosixFilePermission> actual =
                        Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
                if (!OWNER_READ_WRITE.equals(actual)) {
                    throw new IOException(
                            "Không thể xác nhận quyền POSIX 0600 cho tệp nhạy cảm: " + file);
                }
                return;
            }

            AclFileAttributeView aclView =
                    Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (aclView != null) {
                UserPrincipal owner = resolveCurrentUserPrincipal(file, aclView);
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
            ensureNotSymlink(directory, "thư mục nhạy cảm");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Không phải thư mục hợp lệ: " + directory);
            }

            Set<String> views = directory.getFileSystem().supportedFileAttributeViews();
            if (views.contains("posix")) {
                Files.setPosixFilePermissions(directory, OWNER_DIR_PERMS);
                Set<PosixFilePermission> actual =
                        Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
                if (!OWNER_DIR_PERMS.equals(actual)) {
                    throw new IOException("Không thể xác nhận quyền POSIX 0700 cho thư mục: " + directory);
                }
                return;
            }

            AclFileAttributeView aclView =
                    Files.getFileAttributeView(
                            directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (aclView != null) {
                UserPrincipal owner = resolveCurrentUserPrincipal(directory, aclView);
                AclEntry ownerEntry =
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(owner)
                                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                                .setPermissions(WINDOWS_OWNER_DIR_PERMS)
                                .build();
                aclView.setAcl(List.of(ownerEntry));
                return;
            }

            throw new IOException(
                    "Hệ thống tệp không hỗ trợ thiết lập quyền an toàn (POSIX/ACL) cho thư mục: "
                            + directory);
        }

        private static UserPrincipal resolveCurrentUserPrincipal(
                Path path, AclFileAttributeView aclView) throws IOException {
            String userName = System.getProperty("user.name");
            if (userName != null && !userName.isBlank()) {
                try {
                    UserPrincipal principal =
                            path.getFileSystem()
                                    .getUserPrincipalLookupService()
                                    .lookupPrincipalByName(userName);
                    if (principal != null) {
                        return principal;
                    }
                } catch (IOException ignored) {
                    // Fallback về owner hiện tại của tệp nếu lookup theo tên rút gọn không khớp
                }
            }
            UserPrincipal owner = aclView.getOwner();
            if (owner == null) {
                throw new IOException("Không thể xác định chủ sở hữu tài khoản OS cho: " + path);
            }
            return owner;
        }
    }
}
