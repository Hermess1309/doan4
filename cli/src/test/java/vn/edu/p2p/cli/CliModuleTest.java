package vn.edu.p2p.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CliModuleTest {
    @Test
    void moduleLoadsOnJava21() {
        assertEquals("cli", CliModule.NAME);
        assertEquals(21, Runtime.version().feature(), "Smoke tests must run on Java 21");
    }
}
