package vn.edu.p2p.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CryptoModuleTest {
    @Test
    void moduleLoadsOnJava21() {
        assertEquals("crypto", CryptoModule.NAME);
        assertEquals(21, Runtime.version().feature(), "Smoke tests must run on Java 21");
    }
}
