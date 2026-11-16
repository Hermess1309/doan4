package vn.edu.p2p.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProtocolModuleTest {
    @Test
    void moduleLoadsOnJava21() {
        assertEquals("protocol", ProtocolModule.NAME);
        assertEquals(21, Runtime.version().feature(), "Smoke tests must run on Java 21");
    }
}
