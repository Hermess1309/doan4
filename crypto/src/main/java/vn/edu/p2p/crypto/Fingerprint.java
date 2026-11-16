package vn.edu.p2p.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Objects;

/** Full SHA-256 of certificate DER, not of its text or public key alone. */
public final class Fingerprint {
    private Fingerprint() {}

    public static String sha256(X509Certificate certificate) throws CertificateEncodingException {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String canonical(String value) {
        return HexFormat.of().withUpperCase().formatHex(parse(value));
    }

    /** Both inputs must be full fingerprints; comparison uses fixed 32-byte values. */
    public static boolean matches(String first, String second) {
        return MessageDigest.isEqual(parse(first), parse(second));
    }

    private static byte[] parse(String value) {
        String text = Objects.requireNonNull(value, "fingerprint").strip();
        if (!text.matches("[0-9a-fA-F]{64}")
                && !text.matches("([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}")) {
            throw new IllegalArgumentException("Expected a full SHA-256 certificate fingerprint");
        }
        return HexFormat.of().parseHex(text.replace(":", ""));
    }
}
