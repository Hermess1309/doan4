package vn.edu.p2p.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Bounded, plaintext routing metadata. Never wrap these streams in a prefetching buffer. */
public final class RelayProtocol {
    public static final int CREATE = 1;
    public static final int JOIN = 2;
    public static final int CREATED = 1;
    public static final int PAIRED = 2;
    public static final int UNAVAILABLE = 3;
    public static final int BUSY = 4;
    public static final int CODE_LENGTH = 12;
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAGIC = 0x50325231; // P2R1, separate from encrypted file frames

    private RelayProtocol() {}

    public static void writeHeader(OutputStream output, int value) throws IOException {
        var out = new DataOutputStream(output);
        out.writeInt(MAGIC);
        out.writeByte(value);
        out.flush();
    }

    public static int readHeader(InputStream input) throws IOException {
        var in = new DataInputStream(input);
        if (in.readInt() != MAGIC) {
            throw new ProtocolException("Invalid relay version");
        }
        return in.readUnsignedByte();
    }

    public static void writeCode(OutputStream output, String code) throws IOException {
        validateCode(code);
        output.write(code.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    public static String readCode(InputStream input) throws IOException {
        byte[] bytes = new byte[CODE_LENGTH];
        new DataInputStream(input).readFully(bytes);
        String code = new String(bytes, StandardCharsets.US_ASCII);
        validateCode(code);
        return code;
    }

    public static void validateCode(String code) {
        if (code == null || code.length() != CODE_LENGTH
                || !code.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Invalid pairing code format");
        }
    }
}
