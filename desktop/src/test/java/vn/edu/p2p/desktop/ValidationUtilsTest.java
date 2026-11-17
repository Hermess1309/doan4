package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void testValidFingerprint() {
        // Dạng 64 ký tự hex liên tiếp
        String hex64 = "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF";
        assertTrue(ValidationUtils.isValidFingerprint(hex64));
        assertTrue(ValidationUtils.isValidFingerprint(hex64.toLowerCase()));

        // Dạng 32 cặp hex phân tách bằng dấu hai chấm
        String colons32 = "E4:7B:9A:12:8C:3D:F0:55:A1:B2:C3:D4:99:88:77:66:10:2F:4E:6D:AA:BB:CC:DD:01:23:45:67:89:AB:CD:EF";
        assertTrue(ValidationUtils.isValidFingerprint(colons32));
        assertTrue(ValidationUtils.isValidFingerprint(colons32.toLowerCase()));

        // Chuẩn hóa
        assertEquals(hex64, ValidationUtils.canonicalizeFingerprint(colons32));
        assertEquals(hex64, ValidationUtils.canonicalizeFingerprint(hex64.toLowerCase()));
    }

    @Test
    void testInvalidFingerprint() {
        // Rỗng hoặc null
        assertFalse(ValidationUtils.isValidFingerprint(null));
        assertFalse(ValidationUtils.isValidFingerprint(""));
        assertFalse(ValidationUtils.isValidFingerprint("   "));
        assertEquals("", ValidationUtils.canonicalizeFingerprint(""));

        // Quá ngắn (63 ký tự)
        assertFalse(ValidationUtils.isValidFingerprint("E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDE"));

        // Quá dài (65 ký tự)
        assertFalse(ValidationUtils.isValidFingerprint("E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEFA"));

        // Chứa ký tự không phải hex
        assertFalse(ValidationUtils.isValidFingerprint("G47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF"));

        // Dạng hai chấm nhưng sai số cặp (chỉ có 31 cặp)
        String colons31 = "E4:7B:9A:12:8C:3D:F0:55:A1:B2:C3:D4:99:88:77:66:10:2F:4E:6D:AA:BB:CC:DD:01:23:45:67:89:AB:CD";
        assertFalse(ValidationUtils.isValidFingerprint(colons31));
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
