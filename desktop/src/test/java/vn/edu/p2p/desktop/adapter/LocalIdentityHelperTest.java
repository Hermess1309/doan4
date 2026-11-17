package vn.edu.p2p.desktop.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.core.PeerNode;

class LocalIdentityHelperTest {

    @Test
    void testFirstRunCreatesIdentityAndRestrictedFiles(@TempDir Path tempDir) throws Exception {
        Path storePath = tempDir.resolve("identity-dir").resolve("alice.p12");
        Path passFile = LocalIdentityHelper.resolvePasswordFile(storePath);

        PeerNode node = LocalIdentityHelper.getOrCreateIdentity(storePath);

        assertNotNull(node);
        assertNotNull(node.localFingerprint());
        assertEquals(64, node.localFingerprint().length(), "SHA-256 fingerprint phải dài đúng 64 ký tự hex");
        assertTrue(Files.isRegularFile(storePath, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(passFile, LinkOption.NOFOLLOW_LINKS));

        assertRestrictedPermissions(storePath);
        assertRestrictedPermissions(passFile);

        // Tệp mật khẩu phải là chuỗi 32 hex ngẫu nhiên (128-bit entropy), không phải mật khẩu yếu mặc định
        String passContent = Files.readString(passFile, StandardCharsets.UTF_8).trim();
        assertEquals(32, passContent.length());
        assertTrue(passContent.matches("^[0-9a-f]{32}$"));
    }

    @Test
    void testExistingIdentityReloadsSameFingerprint(@TempDir Path tempDir) throws Exception {
        Path storePath = tempDir.resolve("bob.p12");

        PeerNode firstLoad = LocalIdentityHelper.getOrCreateIdentity(storePath);
        PeerNode secondLoad = LocalIdentityHelper.getOrCreateIdentity(storePath);

        assertEquals(
                firstLoad.localFingerprint(),
                secondLoad.localFingerprint(),
                "Khởi động lại với identity đã tồn tại phải giữ nguyên cùng fingerprint");
    }

    @Test
    void testFailsClosedWhenPermissionRestrictionFails(@TempDir Path tempDir) throws IOException {
        Path storePath = tempDir.resolve("perm-fail.p12");
        Path passFile = LocalIdentityHelper.resolvePasswordFile(storePath);

        LocalIdentityHelper.PermissionEnforcer failingEnforcer =
                new LocalIdentityHelper.PermissionEnforcer() {
                    @Override
                    public void restrictFile(Path file) throws IOException {
                        throw new IOException("Simulated OS permission enforcement failure");
                    }

                    @Override
                    public void restrictDirectory(Path directory) throws IOException {
                        // Cho phép tạo thư mục để kiểm tra lỗi quyền trên file nhạy cảm
                    }
                };

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                LocalIdentityHelper.getOrCreateIdentity(
                                        storePath, failingEnforcer, LocalIdentityHelper.DEFAULT_KEYTOOL_RUNNER));

        assertTrue(ex.getMessage().contains("Simulated OS permission enforcement failure"));
        assertFalse(Files.exists(storePath, LinkOption.NOFOLLOW_LINKS), "Không được để lại keystore lỗi");
        assertFalse(Files.exists(passFile, LinkOption.NOFOLLOW_LINKS), "Không được để lại tệp .pass lỗi");
    }

    @Test
    void testCorruptedKeystoreOrMissingPasswordFailsSafely(@TempDir Path tempDir) throws Exception {
        // Trường hợp 1: Keystore tồn tại nhưng thiếu tệp .pass
        Path missingPassStore = tempDir.resolve("orphan.p12");
        Files.writeString(missingPassStore, "dummy-keystore-bytes", StandardCharsets.UTF_8);

        IOException missingPassEx =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(missingPassStore));
        assertTrue(missingPassEx.getMessage().contains("không tìm thấy tệp mật khẩu"));

        // Trường hợp 2: Keystore bị hỏng dữ liệu nhưng có .pass
        Path corruptedStore = tempDir.resolve("corrupted.p12");
        Path corruptedPass = LocalIdentityHelper.resolvePasswordFile(corruptedStore);
        Files.writeString(corruptedStore, "not-a-valid-pkcs12-file", StandardCharsets.UTF_8);
        Files.writeString(corruptedPass, "0123456789abcdef0123456789abcdef", StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> LocalIdentityHelper.getOrCreateIdentity(corruptedStore));

        // Trường hợp 3: Tệp .pass tồn tại sẵn nhưng chưa có keystore -> từ chối ghi đè
        Path orphanPassStore = tempDir.resolve("preplanted.p12");
        Path orphanPass = LocalIdentityHelper.resolvePasswordFile(orphanPassStore);
        Files.writeString(orphanPass, "preplanted-secret", StandardCharsets.UTF_8);

        IOException overwriteEx =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(orphanPassStore));
        assertTrue(overwriteEx.getMessage().contains("từ chối ghi đè"));
    }

    @Test
    void testKeytoolFailureCleansUpAndFailsSafely(@TempDir Path tempDir) throws IOException {
        // 1. Lỗi keytool runner ném IOException (mô phỏng exit code != 0)
        Path storePath = tempDir.resolve("keytool-fail.p12");
        Path passFile = LocalIdentityHelper.resolvePasswordFile(storePath);

        LocalIdentityHelper.KeytoolRunner failingKeytool =
                (target, password, alias) -> {
                    Files.writeString(target, "partial-corrupt", StandardCharsets.UTF_8);
                    throw new IOException("Sinh danh tính bằng keytool thất bại: exitCode=1");
                };

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                LocalIdentityHelper.getOrCreateIdentity(
                                        storePath, LocalIdentityHelper.DEFAULT_PERMISSION_ENFORCER, failingKeytool));

        assertTrue(ex.getMessage().contains("keytool thất bại"));
        assertFalse(Files.exists(storePath, LinkOption.NOFOLLOW_LINKS), "Phải xóa keystore tạo dở");
        assertFalse(Files.exists(passFile, LinkOption.NOFOLLOW_LINKS), "Phải xóa tệp .pass khi tạo thất bại");

        // 2. Đường dẫn keytool không tồn tại
        Path missingKeytool = tempDir.resolve("non-existent-keytool");
        Path anotherStore = tempDir.resolve("missing-keytool.p12");
        IOException missingToolEx =
                assertThrows(
                        IOException.class,
                        () ->
                                LocalIdentityHelper.runKeytoolProcess(
                                        missingKeytool, anotherStore, "0123456789abcdef".toCharArray(), "peer"));
        assertTrue(missingToolEx.getMessage().contains("Không tìm thấy tiện ích JDK keytool"));
    }

    @Test
    void testResolvePasswordFileNeverCollidesWithNonP12StoreName(@TempDir Path tempDir) throws IOException {
        Path customStore = tempDir.resolve("peer.keystore");
        Path passFile = LocalIdentityHelper.resolvePasswordFile(customStore);

        assertFalse(customStore.equals(passFile), "Tệp mật khẩu tuyệt đối không được trùng đường dẫn keystore");
        assertEquals("peer.keystore.pass", passFile.getFileName().toString());
    }

    @Test
    void testRejectsSymbolicLinkForSecretFiles(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("victim.txt");
        Files.writeString(target, "do-not-touch", StandardCharsets.UTF_8);

        Path symlinkStore = tempDir.resolve("symlink-peer.p12");
        try {
            Files.createSymbolicLink(symlinkStore, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "OS hiện tại không cấp quyền tạo symbolic link cho test: " + e.getMessage());
        }

        IOException ex =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(symlinkStore));
        assertTrue(ex.getMessage().contains("symbolic link"));
        assertEquals("do-not-touch", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void testDefaultStorePathIsOutsideRepositoryAndRejectsTraversalName() {
        String prevStore = System.getProperty("p2p.identity.store");
        String prevName = System.getProperty("p2p.identity.name");
        try {
            System.clearProperty("p2p.identity.store");
            System.setProperty("p2p.identity.name", "valid_peer-1");
            Path defaultPath = LocalIdentityHelper.resolveDefaultStorePath();
            Path repoDir = Path.of("").toAbsolutePath().normalize();
            assertFalse(
                    defaultPath.startsWith(repoDir),
                    "Keystore mặc định phải nằm ngoài thư mục repository: " + defaultPath);

            System.setProperty("p2p.identity.name", "../escape");
            assertThrows(IllegalArgumentException.class, LocalIdentityHelper::resolveDefaultStorePath);
        } finally {
            restoreProperty("p2p.identity.store", prevStore);
            restoreProperty("p2p.identity.name", prevName);
        }
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static void assertRestrictedPermissions(Path file) throws IOException {
        Set<String> views = file.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) {
            Set<PosixFilePermission> expected =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Set<PosixFilePermission> actual =
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            assertEquals(expected, actual, "File nhạy cảm trên Linux/POSIX phải có quyền đúng 0600");
        } else {
            AclFileAttributeView aclView =
                    Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertNotNull(aclView, "Windows phải hỗ trợ AclFileAttributeView");
            List<AclEntry> entries = aclView.getAcl();
            assertFalse(entries.isEmpty(), "ACL không được rỗng");
            UserPrincipal expectedPrincipal = entries.get(0).principal();
            assertTrue(
                    entries.stream().allMatch(e -> expectedPrincipal.equals(e.principal())),
                    "Windows ACL chỉ được phép chứa quyền của tài khoản hiện tại: " + entries);
        }
    }
}
