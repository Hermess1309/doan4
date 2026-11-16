package vn.edu.p2p.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoreModuleTest {
    @Test
    void moduleLoadsOnJava21() {
        assertEquals("core", CoreModule.NAME);
        assertEquals(21, Runtime.version().feature(), "Smoke tests must run on Java 21");
    }
}
