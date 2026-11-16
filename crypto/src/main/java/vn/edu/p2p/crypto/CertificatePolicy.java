package vn.edu.p2p.crypto;

import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/** Deliberately narrow MVP identity profile, matching the documented keytool command. */
final class CertificatePolicy {
    private CertificatePolicy() {}

    static void validate(X509Certificate certificate) throws CertificateException {
        certificate.checkValidity();
        if (certificate.getVersion() != 3 || certificate.getBasicConstraints() != -1
                || certificate.hasUnsupportedCriticalExtension()
                || !certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                || !"1.2.840.113549.1.1.11".equals(certificate.getSigAlgOID())
                || !(certificate.getPublicKey() instanceof RSAPublicKey rsa)
                || rsa.getModulus().bitLength() < 3072 || rsa.getModulus().bitLength() > 8192) {
            throw new CertificateException("Unsupported peer certificate profile");
        }
        boolean[] usage = certificate.getKeyUsage();
        List<String> extended = certificate.getExtendedKeyUsage();
        if (usage == null || usage.length == 0 || !usage[0] || extended == null
                || !extended.containsAll(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"))) {
            throw new CertificateException("Certificate must permit digital signatures and both TLS roles");
        }
        try {
            certificate.verify(certificate.getPublicKey());
        } catch (GeneralSecurityException invalid) {
            throw new CertificateException("Invalid self-signed certificate", invalid);
        }
    }
}
