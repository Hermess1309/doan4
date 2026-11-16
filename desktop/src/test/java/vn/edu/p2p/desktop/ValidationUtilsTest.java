package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.desktop.util.ValidationUtils;

class ValidationUtilsTest {

    @Test
    void testValidHostOrIp() {
        // IPv4 hợp lệ
        assertTrue(ValidationUtils.isValidHostOrIp("127.0.0.1"));
        assertTrue(ValidationUtils.isValidHostOrIp("192.168.1.1"));
        assertTrue(ValidationUtils.isValidHostOrIp("10.0.0.254"));
        assertTrue(ValidationUtils.isValidHostOrIp("255.255.255.255"));

        // Hostname hợp lệ
        assertTrue(ValidationUtils.isValidHostOrIp("localhost"));
        assertTrue(ValidationUtils.isValidHostOrIp("laptop.local"));
        assertTrue(ValidationUtils.isValidHostOrIp("peer-desktop"));
        assertTrue(ValidationUtils.isValidHostOrIp("node1.subnet.internal"));
    }

    @Test
    void testInvalidHostOrIp() {
        // IPv4 sai cú pháp
        assertFalse(ValidationUtils.isValidHostOrIp("256.0.0.1"));
        assertFalse(ValidationUtils.isValidHostOrIp("192.168.1"));
        assertFalse(ValidationUtils.isValidHostOrIp("192.168.1.1.1"));
        assertFalse(ValidationUtils.isValidHostOrIp("..."));

        // Hostname sai cú pháp
        assertFalse(ValidationUtils.isValidHostOrIp("-invalid"));
        assertFalse(ValidationUtils.isValidHostOrIp("invalid-"));
        assertFalse(ValidationUtils.isValidHostOrIp("host..name"));
        assertFalse(ValidationUtils.isValidHostOrIp("host name"));

        // Giá trị null hoặc rỗng
        assertFalse(ValidationUtils.isValidHostOrIp(null));
        assertFalse(ValidationUtils.isValidHostOrIp(""));
        assertFalse(ValidationUtils.isValidHostOrIp("   "));
    }

    @Test
    void testValidPort() {
        assertTrue(ValidationUtils.isValidPort("1"));
        assertTrue(ValidationUtils.isValidPort("80"));
        assertTrue(ValidationUtils.isValidPort("52001"));
        assertTrue(ValidationUtils.isValidPort("65535"));
    }

    @Test
    void testInvalidPort() {
        assertFalse(ValidationUtils.isValidPort("0"));
        assertFalse(ValidationUtils.isValidPort("-1"));
        assertFalse(ValidationUtils.isValidPort("65536"));
        assertFalse(ValidationUtils.isValidPort("99999"));
        assertFalse(ValidationUtils.isValidPort("abc"));
        assertFalse(ValidationUtils.isValidPort("52001a"));
        assertFalse(ValidationUtils.isValidPort(null));
        assertFalse(ValidationUtils.isValidPort(""));
    }

    @Test
    void testValidPairingCode() {
        assertTrue(ValidationUtils.isValidPairingCode("782901"));
        assertTrue(ValidationUtils.isValidPairingCode("782-901"));
        assertTrue(ValidationUtils.isValidPairingCode("ABC-123"));
        assertTrue(ValidationUtils.isValidPairingCode("a1b2c3d4"));
    }

    @Test
    void testInvalidPairingCode() {
        assertFalse(ValidationUtils.isValidPairingCode("123")); // Quá ngắn
        assertFalse(ValidationUtils.isValidPairingCode("123456789012345")); // Quá dài
        assertFalse(ValidationUtils.isValidPairingCode("782--901"));
        assertFalse(ValidationUtils.isValidPairingCode("!@#$%^"));
        assertFalse(ValidationUtils.isValidPairingCode(null));
        assertFalse(ValidationUtils.isValidPairingCode(""));
    }

    @Test
    void testDirectoryValidation(@TempDir Path tempDir) {
        File dir = tempDir.toFile();
        assertTrue(ValidationUtils.isValidWritableDirectory(dir));

        File nonExistent = new File(dir, "does_not_exist");
        assertFalse(ValidationUtils.isValidWritableDirectory(nonExistent));
        assertFalse(ValidationUtils.isValidWritableDirectory(null));
    }

    @Test
    void testFileValidation(@TempDir Path tempDir) throws IOException {
        Path tempFile = Files.createFile(tempDir.resolve("test_send.txt"));
        File file = tempFile.toFile();
        assertTrue(ValidationUtils.isValidReadableFile(file));

        // Thư mục không phải là file gửi được
        assertFalse(ValidationUtils.isValidReadableFile(tempDir.toFile()));

        File nonExistent = new File(tempDir.toFile(), "missing.bin");
        assertFalse(ValidationUtils.isValidReadableFile(nonExistent));
        assertFalse(ValidationUtils.isValidReadableFile(null));
    }
}
