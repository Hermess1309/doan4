package vn.edu.p2p.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class RelayProtocolTest {
    @Test
    void readsPartialMetadataWithoutConsumingTunnelBytes() throws Exception {
        var output = new ByteArrayOutputStream();
        RelayProtocol.writeHeader(output, RelayProtocol.JOIN);
        RelayProtocol.writeCode(output, "ABCDEFGH2345");
        output.write(0x16); // First TLS byte must remain unread.
        var input = new FilterInputStream(new ByteArrayInputStream(output.toByteArray())) {
            @Override
            public int read(byte[] data, int offset, int count) throws IOException {
                return super.read(data, offset, Math.min(1, count));
            }
        };
        assertEquals(RelayProtocol.JOIN, RelayProtocol.readHeader(input));
        assertEquals("ABCDEFGH2345", RelayProtocol.readCode(input));
        assertEquals(0x16, input.read());
        assertEquals(-1, input.read());
    }

    @Test
    void rejectsEveryTruncatedHeaderAndCode() throws Exception {
        var output = new ByteArrayOutputStream();
        RelayProtocol.writeHeader(output, RelayProtocol.JOIN);
        RelayProtocol.writeCode(output, "ABCDEFGH2345");
        byte[] bytes = output.toByteArray();
        for (int length = 0; length < bytes.length; length++) {
            var input = new ByteArrayInputStream(java.util.Arrays.copyOf(bytes, length));
            assertThrows(EOFException.class, () -> {
                RelayProtocol.readHeader(input);
                RelayProtocol.readCode(input);
            });
        }
    }

    @Test
    void rejectsVersionAndNonCanonicalOrUnboundedCodes() {
        assertThrows(ProtocolException.class,
                () -> RelayProtocol.readHeader(new ByteArrayInputStream(new byte[5])));
        for (String code : new String[] {"", "a".repeat(12), "A".repeat(13), "I".repeat(12), "../secret"}) {
            assertThrows(IllegalArgumentException.class, () -> RelayProtocol.validateCode(code));
        }
    }
}
