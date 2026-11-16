package vn.edu.p2p.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** M3 payload schema inside the unchanged M1 envelope. All integers are big-endian. */
public final class FilePayloads {
    public static final int CHUNK_BYTES = 64 * 1024;
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024 * 1024;

    private FilePayloads() {}

    public record Offer(UUID id, String name, long size, String sha256) {
        public Offer {
            Objects.requireNonNull(id, "id");
            validateName(name);
            if (size < 0 || size > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("File size outside limit");
            }
            sha256 = canonicalHash(sha256);
        }
    }

    public record Completion(UUID id, long size, String sha256) {
        public Completion {
            Objects.requireNonNull(id, "id");
            if (size < 0 || size > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("File size outside limit");
            }
            sha256 = canonicalHash(sha256);
        }
    }

    public record Chunk(UUID id, long offset, byte[] data) {
        public Chunk {
            Objects.requireNonNull(id, "id");
            if (offset < 0 || offset > MAX_FILE_BYTES || data.length < 1 || data.length > CHUNK_BYTES) {
                throw new IllegalArgumentException("Invalid chunk bounds");
            }
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    /** Portable display basename only; never use it as a trusted destination path. */
    public static void validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.equals(".") || name.equals("..")
                || name.endsWith(".") || name.endsWith(" ")
                || name.chars().anyMatch(c -> c < 32 || c == 127 || "<>:\"/\\|?*".indexOf(c) >= 0)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(name)
                || name.getBytes(StandardCharsets.UTF_8).length > 255
                || name.split("\\.", 2)[0].toUpperCase(Locale.ROOT)
                        .matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            throw new IllegalArgumentException("Invalid portable file basename");
        }
    }

    public static Message offer(Offer value) {
        byte[] name = value.name().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(58 + name.length);
        putId(buffer, value.id());
        buffer.putLong(value.size()).put(HexFormat.of().parseHex(value.sha256()));
        buffer.putShort((short) name.length).put(name);
        return new Message(MessageType.OFFER, buffer.array());
    }

    public static Offer parseOffer(Message message) throws ProtocolException {
        ByteBuffer buffer = payload(message, MessageType.OFFER, 59, 313);
        UUID id = getId(buffer);
        long size = buffer.getLong();
        byte[] hash = new byte[32];
        buffer.get(hash);
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length < 1 || length > 255 || length != buffer.remaining()) {
            throw new ProtocolException("Invalid offer name length");
        }
        try {
            String name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString();
            return new Offer(id, name, size, HexFormat.of().formatHex(hash));
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new ProtocolException("Invalid file offer metadata");
        }
    }

    public static Message control(MessageType type, UUID id) {
        if (type != MessageType.ACCEPT && type != MessageType.REJECT && type != MessageType.ERROR) {
            throw new IllegalArgumentException("Invalid control type");
        }
        return new Message(type, putId(ByteBuffer.allocate(16), id).array());
    }

    public static UUID controlId(Message message) throws ProtocolException {
        if (message.type() != MessageType.ACCEPT && message.type() != MessageType.REJECT
                && message.type() != MessageType.ERROR) {
            throw new ProtocolException("Unexpected control type");
        }
        return getId(payload(message, message.type(), 16, 16));
    }

    public static Message chunk(Chunk chunk) {
        byte[] data = chunk.data();
        return new Message(MessageType.CHUNK,
                putId(ByteBuffer.allocate(24 + data.length), chunk.id())
                        .putLong(chunk.offset()).put(data).array());
    }

    public static Chunk parseChunk(Message message) throws ProtocolException {
        ByteBuffer buffer = payload(message, MessageType.CHUNK, 25, 24 + CHUNK_BYTES);
        UUID id = getId(buffer);
        long offset = buffer.getLong();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        try {
            return new Chunk(id, offset, data);
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException("Invalid chunk offset");
        }
    }

    public static Message complete(Completion value) {
        return new Message(MessageType.COMPLETE, putId(ByteBuffer.allocate(56), value.id())
                .putLong(value.size()).put(HexFormat.of().parseHex(value.sha256())).array());
    }

    public static Completion parseComplete(Message message) throws ProtocolException {
        ByteBuffer buffer = payload(message, MessageType.COMPLETE, 56, 56);
        UUID id = getId(buffer);
        long size = buffer.getLong();
        byte[] hash = new byte[32];
        buffer.get(hash);
        try {
            return new Completion(id, size, HexFormat.of().formatHex(hash));
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException("Invalid completion metadata");
        }
    }

    private static ByteBuffer payload(Message message, MessageType type, int min, int max)
            throws ProtocolException {
        if (message.type() != type || message.payloadLength() < min || message.payloadLength() > max) {
            throw new ProtocolException("Invalid file message type or length");
        }
        return ByteBuffer.wrap(message.payload());
    }

    private static ByteBuffer putId(ByteBuffer buffer, UUID id) {
        return buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }

    private static UUID getId(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static String canonicalHash(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Expected full SHA-256");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
