package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DesktopModuleTest {
    @Test
    void moduleLoadsOnJava21() {
        assertEquals("desktop", DesktopModule.NAME);
        assertEquals(21, Runtime.version().feature(), "Smoke tests must run on Java 21");
    }
}
