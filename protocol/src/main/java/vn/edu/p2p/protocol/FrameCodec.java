package vn.edu.p2p.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Blocking stream codec: [u32 body length, u8 version, u8 type, payload], big-endian.
 * Stateless; callers serialize access to shared streams and configure socket timeouts.
 * Does not close or flush caller-owned streams. This codec provides no authentication.
 */
public final class FrameCodec {
    public static final int VERSION = 1;
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;
    public static final int MAX_PAYLOAD_LENGTH = MAX_FRAME_LENGTH - 2;

    private FrameCodec() {}

    public static byte[] encode(Message message) throws IOException {
        Objects.requireNonNull(message, "message");
        var output = new ByteArrayOutputStream(6 + message.payloadLength());
        write(message, output);
        return output.toByteArray();
    }

    /** Writes one bounded frame; blocking OutputStream writes all requested bytes or throws. */
    public static void write(Message message, OutputStream output) throws IOException {
        Objects.requireNonNull(message, "message");
        var data = new DataOutputStream(Objects.requireNonNull(output, "output"));
        byte[] payload = message.payload();
        data.writeInt(2 + payload.length);
        data.writeByte(VERSION);
        data.writeByte(message.type().id());
        data.write(payload);
    }

    /** Decodes exactly one frame, rejecting empty input and trailing bytes. */
    public static Message decode(byte[] bytes) throws IOException {
        var input = new ByteArrayInputStream(Objects.requireNonNull(bytes, "bytes"));
        Message message = read(input).orElseThrow(() -> new EOFException("Missing frame"));
        if (input.available() != 0) {
            throw new ProtocolException("Trailing bytes after frame");
        }
        return message;
    }

    /**
     * Reads exactly one frame. Empty means clean EOF before its first byte; EOF inside a
     * frame throws EOFException. Any I/O/protocol failure is terminal for that connection.
     * Supports partial reads on blocking InputStream, not nonblocking NIO channels.
     */
    public static Optional<Message> read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        int first = input.read();
        if (first == -1) {
            return Optional.empty();
        }
        var data = new DataInputStream(input);
        // Use long so unsigned u32 values cannot wrap into negative allocation lengths.
        long length = ((long) first << 24) | ((long) data.readUnsignedByte() << 16)
                | ((long) data.readUnsignedByte() << 8) | data.readUnsignedByte();
        if (length < 2 || length > MAX_FRAME_LENGTH) {
            throw new ProtocolException("Frame length outside allowed bounds: " + length);
        }
        int version = data.readUnsignedByte();
        if (version != VERSION) {
            throw new ProtocolException("Unsupported protocol version: " + version);
        }
        MessageType type = MessageType.fromId(data.readUnsignedByte());
        // Never allocate the payload until length, version and type have been validated.
        byte[] payload = new byte[(int) length - 2];
        data.readFully(payload);
        return Optional.of(new Message(type, payload));
    }
}
