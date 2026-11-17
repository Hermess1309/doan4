package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.desktop.util.Formatters;

class FormattersTest {

    @Test
    void testFormatBytes() {
        assertEquals("0 B", Formatters.formatBytes(-10));
        assertEquals("0 B", Formatters.formatBytes(0));
        assertEquals("512 B", Formatters.formatBytes(512));
        assertTrue(Formatters.formatBytes(1024).contains("KB"));
        assertTrue(Formatters.formatBytes(1024 * 1024 * 5).contains("MB"));
        assertTrue(Formatters.formatBytes(1024L * 1024L * 1024L * 2).contains("GB"));
    }

    @Test
    void testFormatSpeed() {
        assertEquals("0.0 KB/s", Formatters.formatSpeed(-5));
        assertEquals("0.0 KB/s", Formatters.formatSpeed(0));
        assertTrue(Formatters.formatSpeed(500 * 1024).contains("KB/s"));
        assertTrue(Formatters.formatSpeed(5 * 1024 * 1024).contains("MB/s"));
    }

    @Test
    void testCalculateProgressPercentage() {
        assertEquals(0, Formatters.calculateProgressPercentage(0, 0));
        assertEquals(0, Formatters.calculateProgressPercentage(-10, 100));
        assertEquals(0, Formatters.calculateProgressPercentage(0, 100));
        assertEquals(50, Formatters.calculateProgressPercentage(50, 100));
        assertEquals(100, Formatters.calculateProgressPercentage(100, 100));
        assertEquals(100, Formatters.calculateProgressPercentage(150, 100)); // Vượt quá max
        assertEquals(25, Formatters.calculateProgressPercentage(250, 1000));
    }

    @Test
    void testFormatEta() {
        assertEquals("--:--", Formatters.formatEta(0, 0));
        assertEquals("--:--", Formatters.formatEta(1000, 0));
        assertEquals("--:--", Formatters.formatEta(-10, 100));

        // 45 giây -> 00:45
        assertEquals("00:45", Formatters.formatEta(4500, 100));

        // 90 giây -> 01:30
        assertEquals("01:30", Formatters.formatEta(9000, 100));

        // 3665 giây (1 giờ 1 phút 5 giây) -> 01:01:05
        assertEquals("01:01:05", Formatters.formatEta(36650, 10));
    }

    @Test
    void testFormatFingerprint() {
        // Chuỗi null hoặc rỗng
        assertEquals("Chưa có thông tin chứng chỉ", Formatters.formatFingerprint(null));
        assertEquals("Chưa có thông tin chứng chỉ", Formatters.formatFingerprint(""));
        assertEquals("Chưa có thông tin chứng chỉ", Formatters.formatFingerprint("   "));

        // Chuỗi hex chuẩn 64 ký tự
        String raw = "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF";
        String formatted = Formatters.formatFingerprint(raw);
        assertTrue(formatted.contains("E4:7B:9A:12"));
        assertTrue(formatted.contains(":"));

        // Chuỗi có ký tự rác
        String dirty = "e4:7b-9a 12";
        String cleanFormatted = Formatters.formatFingerprint(dirty);
        assertTrue(cleanFormatted.contains("E4:7B:9A:12"));
    }
}
