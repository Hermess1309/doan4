package vn.edu.p2p.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;

/** Loads one locally managed identity; never exposes keys, keystore or passwords. */
public final class LocalIdentity {
    private static final int MAX_STORE_BYTES = 1024 * 1024;
    private final KeyManager[] keyManagers;
    private final String fingerprint;

    private LocalIdentity(KeyManager[] keyManagers, String fingerprint) {
        this.keyManagers = keyManagers;
        this.fingerprint = fingerprint;
    }

    /** Caller owns and must clear password; this method clears its private copy. */
    public static LocalIdentity load(Path path, char[] password, String alias)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(alias, "alias");
        char[] secret = Objects.requireNonNull(password, "password").clone();
        byte[] encoded = null;
        try {
            if (secret.length < 6 || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Expected a regular PKCS12 file and a nonempty password");
            }
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                encoded = input.readNBytes(MAX_STORE_BYTES + 1);
            }
            if (encoded.length > MAX_STORE_BYTES) {
                throw new IOException("PKCS12 file exceeds size limit");
            }
            var store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(encoded), secret);
            if (store.size() != 1 || !store.isKeyEntry(alias)
                    || !(store.getKey(alias, secret) instanceof PrivateKey key)
                    || !(store.getCertificate(alias) instanceof X509Certificate certificate)
                    || store.getCertificateChain(alias).length != 1) {
                throw new GeneralSecurityException("Expected exactly one private-key identity");
            }
            CertificatePolicy.validate(certificate);
            if (!"RSA".equals(key.getAlgorithm())) {
                throw new GeneralSecurityException("Unsupported private key algorithm");
            }
            // Standard JCA signature verifies the private key really belongs to the certificate.
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            var proof = Signature.getInstance("SHA256withRSA");
            proof.initSign(key);
            proof.update(challenge);
            byte[] signature = proof.sign();
            proof.initVerify(certificate);
            proof.update(challenge);
            if (!proof.verify(signature)) {
                throw new GeneralSecurityException("Private key and certificate do not match");
            }
            var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, secret);
            return new LocalIdentity(factory.getKeyManagers(), Fingerprint.sha256(certificate));
        } finally {
            Arrays.fill(secret, '\0');
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    public String fingerprint() {
        return fingerprint;
    }

    KeyManager[] keyManagers() {
        return keyManagers.clone();
    }
}
