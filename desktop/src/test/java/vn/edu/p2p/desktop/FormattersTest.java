package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.desktop.util.Formatters;

class FormattersTest {

    @Test
    void testFormatBytes() {
        assertEquals("0 B", Formatters.formatBytes(0));
        assertEquals("512 B", Formatters.formatBytes(512));
        assertTrue(Formatters.formatBytes(1024).contains("KB"));
        assertTrue(Formatters.formatBytes(1024 * 1024 * 5).contains("MB"));
        assertTrue(Formatters.formatBytes(1024L * 1024L * 1024L * 2).contains("GB"));
    }

    @Test
    void testFormatSpeed() {
        assertEquals("0.0 KB/s", Formatters.formatSpeed(0));
        assertTrue(Formatters.formatSpeed(500 * 1024).contains("KB/s"));
        assertTrue(Formatters.formatSpeed(5 * 1024 * 1024).contains("MB/s"));
    }

    @Test
    void testFormatFingerprint() {
        String raw = "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF";
        String formatted = Formatters.formatFingerprint(raw);
        assertTrue(formatted.contains("E4:7B:9A:12"));
        assertTrue(formatted.contains(":"));
    }
}
