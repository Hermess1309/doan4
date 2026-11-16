package vn.edu.p2p.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FilePayloadsTest {
    private final UUID id = new UUID(1, 2);
    private final String hash = "ab".repeat(32);

    @Test
    void offerAndCompletionRoundTripWithExplicitWireLengths() throws Exception {
        var offer = new FilePayloads.Offer(id, "văn-bản.txt", 123, hash);
        Message encoded = FilePayloads.offer(offer);
        assertEquals(58 + offer.name().getBytes(StandardCharsets.UTF_8).length, encoded.payloadLength());
        assertEquals(offer, FilePayloads.parseOffer(encoded));
        var complete = new FilePayloads.Completion(id, 123, hash);
        assertEquals(56, FilePayloads.complete(complete).payloadLength());
        assertEquals(complete, FilePayloads.parseComplete(FilePayloads.complete(complete)));
        for (MessageType type : new MessageType[] {MessageType.ACCEPT, MessageType.REJECT, MessageType.ERROR}) {
            assertEquals(id, FilePayloads.controlId(FilePayloads.control(type, id)));
        }
    }

    @Test
    void chunksAreBoundedAndImmutableAndOffsetsAreLong() throws Exception {
        byte[] data = new byte[FilePayloads.CHUNK_BYTES];
        Arrays.fill(data, (byte) 42);
        var chunk = new FilePayloads.Chunk(id, 3_000_000_000L, data);
        data[0] = 0;
        chunk.data()[0] = 0;
        var decoded = FilePayloads.parseChunk(FilePayloads.chunk(chunk));
        assertEquals(id, decoded.id());
        assertEquals(3_000_000_000L, decoded.offset());
        assertArrayEquals(chunk.data(), decoded.data());
        assertEquals(42, decoded.data()[0]);
        assertThrows(IllegalArgumentException.class, () -> new FilePayloads.Chunk(id, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new FilePayloads.Chunk(id, -1, new byte[1]));
        assertThrows(IllegalArgumentException.class,
                () -> new FilePayloads.Chunk(id, 0, new byte[FilePayloads.CHUNK_BYTES + 1]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "..\\escape", "/tmp/escape", "C:\\escape", "data:stream",
            ".", "..", "CON.txt", "LPT1", "tail.", "tail ", "bad\nname", ""})
    void rejectsNonPortableOrTraversalNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> new FilePayloads.Offer(id, name, 0, hash));
    }

    @Test
    void rejectsSizeHashAndTextViolations() {
        assertThrows(IllegalArgumentException.class, () -> new FilePayloads.Offer(id, "ok", -1, hash));
        assertThrows(IllegalArgumentException.class,
                () -> new FilePayloads.Offer(id, "ok", FilePayloads.MAX_FILE_BYTES + 1, hash));
        assertThrows(IllegalArgumentException.class, () -> new FilePayloads.Offer(id, "ok", 0, "short"));
        assertThrows(IllegalArgumentException.class,
                () -> new FilePayloads.Offer(id, "a".repeat(256), 0, hash));
        assertThrows(IllegalArgumentException.class,
                () -> new FilePayloads.Offer(id, "\uD800", 0, hash));
    }

    @Test
    void rejectsTruncatedTrailingAndMalformedUtf8Payloads() throws Exception {
        byte[] valid = FilePayloads.offer(new FilePayloads.Offer(id, "ok", 0, hash)).payload();
        assertThrows(ProtocolException.class,
                () -> FilePayloads.parseOffer(new Message(MessageType.OFFER, Arrays.copyOf(valid, valid.length - 1))));
        assertThrows(ProtocolException.class,
                () -> FilePayloads.parseOffer(new Message(MessageType.OFFER, Arrays.copyOf(valid, valid.length + 1))));
        valid[58] = (byte) 0xc3;
        valid[59] = 0x28;
        assertThrows(ProtocolException.class, () -> FilePayloads.parseOffer(new Message(MessageType.OFFER, valid)));
        assertThrows(ProtocolException.class,
                () -> FilePayloads.parseComplete(new Message(MessageType.COMPLETE, new byte[55])));
        assertThrows(ProtocolException.class,
                () -> FilePayloads.controlId(new Message(MessageType.ACCEPT, new byte[17])));
        assertThrows(ProtocolException.class,
                () -> FilePayloads.parseChunk(new Message(MessageType.CHUNK, new byte[24])));
        byte[] badOffset = ByteBuffer.allocate(25).putLong(1).putLong(2).putLong(-1).put((byte) 1).array();
        assertThrows(ProtocolException.class,
                () -> FilePayloads.parseChunk(new Message(MessageType.CHUNK, badOffset)));
    }
}
