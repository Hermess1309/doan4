package vn.edu.p2p.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RelayModuleTest {
    @Test
    void moduleLoadsOnJava21() {
        assertEquals("relay", RelayModule.NAME);
        assertEquals(21, Runtime.version().feature(), "Smoke tests must run on Java 21");
    }
}
