package vn.edu.p2p.protocol;

/** Stable wire IDs; never derive an ID from enum ordinal(). */
public enum MessageType {
    OFFER(1), ACCEPT(2), REJECT(3), CHUNK(4), COMPLETE(5), CANCEL(6),
    RESUME_REQUEST(7), RESUME_RESPONSE(8), ERROR(9);

    private final int id;

    MessageType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static MessageType fromId(int id) throws ProtocolException {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new ProtocolException("Unknown message type: " + id);
    }
}
