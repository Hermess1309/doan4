package vn.edu.p2p.protocol;

import java.util.Objects;

/** Immutable envelope. Payload schemas and transfer-state validation belong to later milestones. */
public final class Message {
    private final MessageType type;
    private final byte[] payload;

    public Message(MessageType type, byte[] payload) {
        this.type = Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > FrameCodec.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Payload exceeds frame limit");
        }
        this.payload = payload.clone();
    }

    public MessageType type() {
        return type;
    }

    public int payloadLength() {
        return payload.length;
    }

    public byte[] payload() {
        return payload.clone();
    }
}
