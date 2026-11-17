package vn.edu.p2p.desktop.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
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

        String passContent = Files.readString(passFile, StandardCharsets.UTF_8).trim();
        assertEquals(32, passContent.length());
        assertTrue(passContent.matches("^[0-9a-f]{32}$"));
    }

    @Test
    void testExistingIdentityReloadsSameFingerprint(@TempDir Path tempDir) throws Exception {
        Path storePath = tempDir.resolve("bob-dir").resolve("bob.p12");

        PeerNode firstLoad = LocalIdentityHelper.getOrCreateIdentity(storePath);
        PeerNode secondLoad = LocalIdentityHelper.getOrCreateIdentity(storePath);

        assertEquals(
                firstLoad.localFingerprint(),
                secondLoad.localFingerprint(),
                "Khởi động lại với identity đã tồn tại phải giữ nguyên cùng fingerprint");
    }

    @Test
    void testWindowsExistingDirectoryWithEveryoneFullControlIsRejected(@TempDir Path tempDir)
            throws Exception {
        Assumptions.assumeTrue(
                tempDir.getFileSystem().supportedFileAttributeViews().contains("acl")
                        && !tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "Test Windows ACL chỉ chạy trên hệ thống tệp hỗ trợ Windows ACL");

        Path insecureDir = Files.createDirectory(tempDir.resolve("everyone-full-dir"));
        AclFileAttributeView aclView =
                Files.getFileAttributeView(insecureDir, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal everyone =
                insecureDir.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
        UserPrincipal currentUser =
                insecureDir
                        .getFileSystem()
                        .getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));

        List<AclEntry> insecureAcl =
                List.of(
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(currentUser)
                                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .build(),
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(everyone)
                                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .build());
        aclView.setAcl(insecureAcl);

        Path storePath = insecureDir.resolve("alice.p12");
        Path passFile = LocalIdentityHelper.resolvePasswordFile(storePath);

        IOException ex =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(storePath));
        assertTrue(
                ex.getMessage().contains("ACL không an toàn"),
                "Phải từ chối thư mục Windows Everyone Full Control: " + ex.getMessage());
        assertFalse(
                Files.exists(storePath, LinkOption.NOFOLLOW_LINKS),
                "Không được tạo keystore trong thư mục Everyone Full Control");
        assertFalse(
                Files.exists(passFile, LinkOption.NOFOLLOW_LINKS),
                "Không được tạo tệp .pass trong thư mục Everyone Full Control");
    }

    @Test
    void testWindowsExistingDirectoryWithSecureAclIsAccepted(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(
                tempDir.getFileSystem().supportedFileAttributeViews().contains("acl")
                        && !tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "Test Windows ACL chỉ chạy trên hệ thống tệp hỗ trợ Windows ACL");

        Path secureDir = Files.createDirectory(tempDir.resolve("secure-existing-win-dir"));
        LocalIdentityHelper.restrictDirectoryToCurrentUser(secureDir);

        Path storePath = secureDir.resolve("secure-peer.p12");
        PeerNode node = LocalIdentityHelper.getOrCreateIdentity(storePath);

        assertNotNull(node);
        assertEquals(64, node.localFingerprint().length());
        assertTrue(Files.isRegularFile(storePath, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void testWindowsExistingSecretFileWithEveryoneAccessIsRejected(@TempDir Path tempDir)
            throws Exception {
        Assumptions.assumeTrue(
                tempDir.getFileSystem().supportedFileAttributeViews().contains("acl")
                        && !tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "Test Windows ACL chỉ chạy trên hệ thống tệp hỗ trợ Windows ACL");

        Path secureDir = tempDir.resolve("secret-exposed-dir");
        Path storePath = secureDir.resolve("exposed.p12");
        Path passFile = LocalIdentityHelper.resolvePasswordFile(storePath);

        // Tạo identity hợp lệ trước
        LocalIdentityHelper.getOrCreateIdentity(storePath);

        // Cố tình cấp Everyone quyền đọc trên tệp .pass
        AclFileAttributeView passAcl =
                Files.getFileAttributeView(passFile, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal everyone =
                passFile.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
        List<AclEntry> widened = new ArrayList<>(passAcl.getAcl());
        widened.add(
                AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(everyone)
                        .setPermissions(AclEntryPermission.READ_DATA)
                        .build());
        passAcl.setAcl(widened);

        IOException ex =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(storePath));
        assertTrue(
                ex.getMessage().contains("ACL không an toàn"),
                "Phải từ chối tệp .pass đã bị mở quyền cho Everyone: " + ex.getMessage());
    }

    @Test
    void testLinuxPosixExistingDirectory0777RejectedAnd0700Accepted(@TempDir Path tempDir)
            throws Exception {
        Assumptions.assumeTrue(
                tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "Test POSIX thật chạy trên Ubuntu/Linux");

        // 1. Thư mục 0777 phải bị từ chối và không tạo bất kỳ file bí mật nào
        Path worldWritableDir = Files.createDirectory(tempDir.resolve("posix-0777-dir"));
        Files.setPosixFilePermissions(worldWritableDir, PosixFilePermissions.fromString("rwxrwxrwx"));

        Path rejectedStore = worldWritableDir.resolve("alice.p12");
        Path rejectedPass = LocalIdentityHelper.resolvePasswordFile(rejectedStore);

        IOException ex =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(rejectedStore));
        assertTrue(ex.getMessage().contains("POSIX không an toàn"));
        assertFalse(Files.exists(rejectedStore, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(rejectedPass, LinkOption.NOFOLLOW_LINKS));

        // 2. Thư mục 0700 đúng owner phải được chấp nhận
        Path privateDir = Files.createDirectory(tempDir.resolve("posix-0700-dir"));
        Files.setPosixFilePermissions(privateDir, PosixFilePermissions.fromString("rwx------"));

        Path acceptedStore = privateDir.resolve("bob.p12");
        PeerNode node = LocalIdentityHelper.getOrCreateIdentity(acceptedStore);
        assertNotNull(node);
        assertEquals(64, node.localFingerprint().length());

        // 3. Tệp .pass hiện có bị đổi sang 0644 phải bị từ chối khi nạp lại
        Path acceptedPass = LocalIdentityHelper.resolvePasswordFile(acceptedStore);
        Files.setPosixFilePermissions(acceptedPass, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(acceptedStore));
    }

    @Test
    void testPosixValidationRulesDirectlyAcrossPlatforms(@TempDir Path tempDir) {
        UserPrincipal owner = () -> "alice";
        UserPrincipal otherUser = () -> "mallory";

        // 0700 đúng owner -> hợp lệ
        assertDoesNotThrow(
                () ->
                        LocalIdentityHelper.validatePosixDirectoryPermissions(
                                tempDir, owner, owner, PosixFilePermissions.fromString("rwx------")));

        // 0777 -> bị từ chối
        assertThrows(
                IOException.class,
                () ->
                        LocalIdentityHelper.validatePosixDirectoryPermissions(
                                tempDir, owner, owner, PosixFilePermissions.fromString("rwxrwxrwx")));

        // 0750 (group accessible) -> bị từ chối
        assertThrows(
                IOException.class,
                () ->
                        LocalIdentityHelper.validatePosixDirectoryPermissions(
                                tempDir, owner, owner, PosixFilePermissions.fromString("rwxr-x---")));

        // Sai owner -> bị từ chối
        assertThrows(
                IOException.class,
                () ->
                        LocalIdentityHelper.validatePosixDirectoryPermissions(
                                tempDir, otherUser, owner, PosixFilePermissions.fromString("rwx------")));

        // File 0600 đúng owner -> hợp lệ; 0644 -> bị từ chối
        assertDoesNotThrow(
                () ->
                        LocalIdentityHelper.validatePosixFilePermissions(
                                tempDir.resolve("a.pass"),
                                owner,
                                owner,
                                PosixFilePermissions.fromString("rw-------")));
        assertThrows(
                IOException.class,
                () ->
                        LocalIdentityHelper.validatePosixFilePermissions(
                                tempDir.resolve("a.pass"),
                                owner,
                                owner,
                                PosixFilePermissions.fromString("rw-r--r--")));
    }

    @Test
    void testWindowsAclValidationRejectsUntrustedPrincipalsAndWrongOwner(@TempDir Path tempDir) {
        UserPrincipal currentUser = () -> "DESKTOP\\Alice";
        UserPrincipal otherUser = () -> "DESKTOP\\Bob";
        UserPrincipal system = () -> "NT AUTHORITY\\SYSTEM";
        UserPrincipal admins = () -> "BUILTIN\\Administrators";
        UserPrincipal creatorOwner = () -> "CREATOR OWNER";
        UserPrincipal everyone = () -> "Everyone";
        UserPrincipal guests = () -> "BUILTIN\\Guests";

        AclEntry aliceFull =
                AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(currentUser)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
        AclEntry everyoneRead =
                AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(everyone)
                        .setPermissions(AclEntryPermission.READ_DATA)
                        .build();
        AclEntry guestsInheritedWrite =
                AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(guests)
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.INHERIT_ONLY)
                        .setPermissions(AclEntryPermission.WRITE_DATA)
                        .build();

        // Owner hợp lệ + chỉ currentUser -> hợp lệ
        assertDoesNotThrow(
                () ->
                        LocalIdentityHelper.validateWindowsAcl(
                                tempDir,
                                true,
                                currentUser,
                                currentUser,
                                system,
                                admins,
                                creatorOwner,
                                List.of(aliceFull)));

        // Có Everyone -> bị từ chối
        assertThrows(
                IOException.class,
                () ->
                        LocalIdentityHelper.validateWindowsAcl(
                                tempDir,
                                true,
                                currentUser,
                                currentUser,
                                system,
                                admins,
                                creatorOwner,
                                List.of(aliceFull, everyoneRead)));

        // Có Guests qua ACE kế thừa (INHERIT_ONLY) -> bị từ chối
        assertThrows(
                IOException.class,
                () ->
                        LocalIdentityHelper.validateWindowsAcl(
                                tempDir,
                                true,
                                currentUser,
                                currentUser,
                                system,
                                admins,
                                creatorOwner,
                                List.of(aliceFull, guestsInheritedWrite)));

        // Thư mục thuộc chủ sở hữu khác -> bị từ chối
        assertThrows(
                IOException.class,
                () ->
                        LocalIdentityHelper.validateWindowsAcl(
                                tempDir,
                                true,
                                otherUser,
                                currentUser,
                                system,
                                admins,
                                creatorOwner,
                                List.of(aliceFull)));
    }

    @Test
    void testFailsClosedWhenPermissionRestrictionFails(@TempDir Path tempDir) throws IOException {
        Path storePath = tempDir.resolve("perm-fail-dir").resolve("perm-fail.p12");
        Path passFile = LocalIdentityHelper.resolvePasswordFile(storePath);

        LocalIdentityHelper.PermissionEnforcer failingEnforcer =
                new LocalIdentityHelper.PermissionEnforcer() {
                    @Override
                    public void restrictFile(Path file) throws IOException {
                        throw new IOException("Simulated OS permission enforcement failure");
                    }

                    @Override
                    public void restrictDirectory(Path directory) throws IOException {}

                    @Override
                    public void verifyExistingFile(Path file) throws IOException {}

                    @Override
                    public void verifyExistingDirectory(Path directory) throws IOException {}
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
        Path secureDir = Files.createDirectory(tempDir.resolve("corrupt-tests"));
        LocalIdentityHelper.restrictDirectoryToCurrentUser(secureDir);

        // Trường hợp 1: Keystore tồn tại nhưng thiếu tệp .pass
        Path missingPassStore = secureDir.resolve("orphan.p12");
        Files.writeString(missingPassStore, "dummy-keystore-bytes", StandardCharsets.UTF_8);
        LocalIdentityHelper.restrictFileToCurrentUser(missingPassStore);

        IOException missingPassEx =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(missingPassStore));
        assertTrue(missingPassEx.getMessage().contains("không tìm thấy tệp mật khẩu"));

        // Trường hợp 2: Keystore bị hỏng dữ liệu nhưng có .pass
        Path corruptedStore = secureDir.resolve("corrupted.p12");
        Path corruptedPass = LocalIdentityHelper.resolvePasswordFile(corruptedStore);
        Files.writeString(corruptedStore, "not-a-valid-pkcs12-file", StandardCharsets.UTF_8);
        Files.writeString(corruptedPass, "0123456789abcdef0123456789abcdef", StandardCharsets.UTF_8);
        LocalIdentityHelper.restrictFileToCurrentUser(corruptedStore);
        LocalIdentityHelper.restrictFileToCurrentUser(corruptedPass);

        assertThrows(Exception.class, () -> LocalIdentityHelper.getOrCreateIdentity(corruptedStore));

        // Trường hợp 3: Tệp .pass tồn tại sẵn nhưng chưa có keystore -> từ chối ghi đè
        Path orphanPassStore = secureDir.resolve("preplanted.p12");
        Path orphanPass = LocalIdentityHelper.resolvePasswordFile(orphanPassStore);
        Files.writeString(orphanPass, "preplanted-secret", StandardCharsets.UTF_8);
        LocalIdentityHelper.restrictFileToCurrentUser(orphanPass);

        IOException overwriteEx =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(orphanPassStore));
        assertTrue(overwriteEx.getMessage().contains("từ chối ghi đè"));
    }

    @Test
    void testKeytoolFailureCleansUpAndFailsSafely(@TempDir Path tempDir) throws IOException {
        Path storePath = tempDir.resolve("keytool-fail-dir").resolve("keytool-fail.p12");
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

        Path missingKeytool = tempDir.resolve("non-existent-keytool");
        Path anotherStore = tempDir.resolve("missing-keytool-dir").resolve("missing-keytool.p12");
        IOException missingToolEx =
                assertThrows(
                        IOException.class,
                        () ->
                                LocalIdentityHelper.runKeytoolProcess(
                                        missingKeytool, anotherStore, "0123456789abcdef".toCharArray(), "peer"));
        assertTrue(missingToolEx.getMessage().contains("Không tìm thấy tiện ích JDK keytool"));
    }

    @Test
    void testResolvePasswordFileNeverCollidesWithNonP12StoreName(@TempDir Path tempDir)
            throws IOException {
        Path customStore = tempDir.resolve("peer.keystore");
        Path passFile = LocalIdentityHelper.resolvePasswordFile(customStore);

        assertFalse(customStore.equals(passFile), "Tệp mật khẩu tuyệt đối không được trùng đường dẫn keystore");
        assertEquals("peer.keystore.pass", passFile.getFileName().toString());
    }

    @Test
    void testRejectsSymbolicLinkForDirectoryAndSecretFiles(@TempDir Path tempDir) throws IOException {
        Path realDir = Files.createDirectory(tempDir.resolve("real-target-dir"));
        Path target = tempDir.resolve("victim.txt");
        Files.writeString(target, "do-not-touch", StandardCharsets.UTF_8);

        Path symlinkDir = tempDir.resolve("symlink-identity-dir");
        Path symlinkStore = tempDir.resolve("symlink-peer.p12");
        try {
            Files.createSymbolicLink(symlinkDir, realDir);
            Files.createSymbolicLink(symlinkStore, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(
                    false, "OS hiện tại không cấp quyền tạo symbolic link cho test: " + e.getMessage());
        }

        // Thư mục symlink bị từ chối
        IOException dirEx =
                assertThrows(
                        IOException.class,
                        () -> LocalIdentityHelper.getOrCreateIdentity(symlinkDir.resolve("alice.p12")));
        assertTrue(dirEx.getMessage().contains("symbolic link"));

        // Tệp keystore symlink bị từ chối và không ghi đè victim
        IOException fileEx =
                assertThrows(
                        IOException.class, () -> LocalIdentityHelper.getOrCreateIdentity(symlinkStore));
        assertTrue(fileEx.getMessage().contains("symbolic link"));
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
