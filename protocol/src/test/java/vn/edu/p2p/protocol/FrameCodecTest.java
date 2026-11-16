package vn.edu.p2p.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class FrameCodecTest {
    @ParameterizedTest
    @EnumSource(MessageType.class)
    void roundTripsEveryTypeWithBinaryPayload(MessageType type) throws IOException {
        var original = new Message(type, new byte[] {0, 1, 127, -128, -1});
        assertMessage(original, FrameCodec.decode(FrameCodec.encode(original)));
    }

    @Test
    void wireVectorFixesByteOrderLengthVersionAndTypeIds() throws IOException {
        assertArrayEquals(new byte[] {0, 0, 0, 4, 1, 1, 65, 66},
                FrameCodec.encode(new Message(MessageType.OFFER, new byte[] {65, 66})));
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9},
                Arrays.stream(MessageType.values()).mapToInt(MessageType::id).toArray());
        assertMessage(new Message(MessageType.CHUNK, new byte[] {-1}),
                FrameCodec.decode(new byte[] {0, 0, 0, 3, 1, 4, -1}));
    }

    @ParameterizedTest
    @EnumSource(MessageType.class)
    void framingAllowsEmptyPayloadsWithoutInterpretingThem(MessageType type) throws IOException {
        assertMessage(new Message(type, new byte[0]),
                FrameCodec.decode(FrameCodec.encode(new Message(type, new byte[0]))));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7})
    void fragmentedAndCoalescedFramesRemainSeparate(int fragmentSize) throws IOException {
        var first = new Message(MessageType.CHUNK, new byte[37]);
        var second = new Message(MessageType.CANCEL, new byte[] {42});
        var output = new ByteArrayOutputStream();
        FrameCodec.write(first, output);
        FrameCodec.write(second, output);
        var input = new ByteArrayInputStream(output.toByteArray()) {
            @Override
            public synchronized int available() {
                return 0; // Network availability is not a message boundary.
            }

            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, fragmentSize));
            }
        };
        assertMessage(first, FrameCodec.read(input).orElseThrow());
        assertMessage(second, FrameCodec.read(input).orElseThrow());
        assertTrue(FrameCodec.read(input).isEmpty());
    }

    @Test
    void distinguishesCleanEofFromTruncationAtEveryByte() throws IOException {
        assertTrue(FrameCodec.read(new ByteArrayInputStream(new byte[0])).isEmpty());
        byte[] frame = FrameCodec.encode(new Message(MessageType.OFFER, new byte[] {1, 2, 3}));
        for (int length = 1; length < frame.length; length++) {
            byte[] truncated = Arrays.copyOf(frame, length);
            assertThrows(EOFException.class, () -> FrameCodec.read(new ByteArrayInputStream(truncated)),
                    "Truncation at byte " + length);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 1048577, 2147483647L, 2147483648L, 4294967295L})
    void rejectsInvalidUnsignedLengthBeforeReadingBody(long length) {
        byte[] prefix = Arrays.copyOf(header(length, 1, 1), 4);
        assertThrows(ProtocolException.class, () -> FrameCodec.read(headerOnly(prefix)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 255})
    void rejectsVersionBeforeReadingTypeOrPayload(int version) {
        byte[] prefix = Arrays.copyOf(header(FrameCodec.MAX_FRAME_LENGTH, version, 1), 5);
        assertThrows(ProtocolException.class, () -> FrameCodec.read(headerOnly(prefix)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10, 255})
    void rejectsUnknownTypeBeforeReadingPayload(int type) {
        assertThrows(ProtocolException.class,
                () -> FrameCodec.read(headerOnly(header(FrameCodec.MAX_FRAME_LENGTH, 1, type))));
    }

    @Test
    void acceptsExactLimitAndRejectsOneByteOverLimit() throws IOException {
        byte[] payload = new byte[FrameCodec.MAX_PAYLOAD_LENGTH];
        Arrays.fill(payload, (byte) 0xa5);
        var message = new Message(MessageType.CHUNK, payload);
        byte[] encoded = FrameCodec.encode(message);
        assertEquals(FrameCodec.MAX_FRAME_LENGTH + 4, encoded.length);
        assertMessage(message, FrameCodec.decode(encoded));
        assertThrows(IllegalArgumentException.class,
                () -> new Message(MessageType.CHUNK, new byte[FrameCodec.MAX_PAYLOAD_LENGTH + 1]));
    }

    @Test
    void messageDefensivelyCopiesPayload() {
        byte[] source = {42};
        var message = new Message(MessageType.CHUNK, source);
        source[0] = 0;
        message.payload()[0] = 0;
        assertArrayEquals(new byte[] {42}, message.payload());
        assertEquals(1, message.payloadLength());
        assertThrows(NullPointerException.class, () -> new Message(null, source));
        assertThrows(NullPointerException.class, () -> new Message(MessageType.CHUNK, null));
    }

    @Test
    void byteArrayDecodeRequiresExactlyOneFrame() throws IOException {
        byte[] frame = FrameCodec.encode(new Message(MessageType.ACCEPT, new byte[0]));
        assertThrows(EOFException.class, () -> FrameCodec.decode(new byte[0]));
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(Arrays.copyOf(frame, frame.length + 1)));
    }

    @Test
    void propagatesReadAndWriteFailures() {
        var brokenInput = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read failure");
            }
        };
        var brokenOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("write failure");
            }
        };
        assertEquals("read failure", assertThrows(IOException.class,
                () -> FrameCodec.read(brokenInput)).getMessage());
        assertEquals("write failure", assertThrows(IOException.class,
                () -> FrameCodec.write(new Message(MessageType.ERROR, new byte[0]), brokenOutput)).getMessage());
    }

    @Test
    void codecLeavesStreamOwnershipAndFlushingToCaller() throws IOException {
        boolean[] closed = {false, false};
        boolean[] flushed = {false};
        var output = new ByteArrayOutputStream() {
            @Override
            public void close() {
                closed[0] = true;
            }

            @Override
            public void flush() {
                flushed[0] = true;
            }
        };
        FrameCodec.write(new Message(MessageType.ACCEPT, new byte[0]), output);
        var input = new ByteArrayInputStream(output.toByteArray()) {
            @Override
            public void close() {
                closed[1] = true;
            }
        };
        assertTrue(FrameCodec.read(input).isPresent());
        assertFalse(closed[0]);
        assertFalse(closed[1]);
        assertFalse(flushed[0]);
    }

    private static byte[] header(long length, int version, int type) {
        return ByteBuffer.allocate(6).putInt((int) length).put((byte) version).put((byte) type).array();
    }

    /** Fails the test if a rejected header triggers any attempt to consume its body. */
    private static InputStream headerOnly(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read() {
                if (pos >= count) {
                    throw new AssertionError("Decoder read past invalid header");
                }
                return super.read();
            }

            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                throw new AssertionError("Decoder requested payload for invalid header");
            }
        };
    }

    private static void assertMessage(Message expected, Message actual) {
        assertEquals(expected.type(), actual.type());
        assertArrayEquals(expected.payload(), actual.payload());
    }
}
