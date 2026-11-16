package vn.edu.p2p.crypto;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** Only the independently verified certificate pin is eligible to become a trust anchor. */
final class PinnedTrustManager extends X509ExtendedTrustManager {
    private final String expected;

    PinnedTrustManager(String expected) {
        this.expected = Fingerprint.canonical(expected);
    }

    private X509ExtendedTrustManager validator(X509Certificate[] chain, String authType)
            throws CertificateException {
        if (chain == null || chain.length != 1 || chain[0] == null
                || authType == null || authType.isBlank()) {
            throw new CertificateException("Expected one self-signed peer certificate");
        }
        CertificatePolicy.validate(chain[0]);
        if (!Fingerprint.matches(expected, Fingerprint.sha256(chain[0]))) {
            throw new CertificateException("Peer certificate fingerprint mismatch");
        }
        try {
            var anchors = KeyStore.getInstance("PKCS12");
            anchors.load(null, null);
            anchors.setCertificateEntry("verified-peer", chain[0]);
            var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(anchors);
            for (var manager : factory.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager validator) {
                    return validator;
                }
            }
            throw new CertificateException("No platform certificate validator");
        } catch (GeneralSecurityException | java.io.IOException invalid) {
            throw new CertificateException("Cannot validate pinned certificate", invalid);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
        validator(chain, auth).checkClientTrusted(chain, auth);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
        validator(chain, auth).checkServerTrusted(chain, auth);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String auth, Socket socket) throws CertificateException {
        validator(chain, auth).checkClientTrusted(chain, auth, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String auth, Socket socket) throws CertificateException {
        validator(chain, auth).checkServerTrusted(chain, auth, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String auth, SSLEngine engine) throws CertificateException {
        validator(chain, auth).checkClientTrusted(chain, auth, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String auth, SSLEngine engine) throws CertificateException {
        validator(chain, auth).checkServerTrusted(chain, auth, engine);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
