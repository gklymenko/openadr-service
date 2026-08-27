package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils.IdentityCertificateChain;
import org.springframework.stereotype.Component;

import javax.security.auth.x500.X500Principal;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Locale;

@Component
public class OpenAdrCertificatePolicyValidator {

    private static final int X509_VERSION_3 = 3;
    private static final int MIN_RSA_KEY_SIZE_BITS = 2048;

    public void validateRsaIdentity(IdentityCertificateChain identity)
            throws GeneralSecurityException {
        List<X509Certificate> chain = identity.certificates();

        if (chain.size() < 2) {
            throw new GeneralSecurityException(
                    "OpenADR client identity must contain the device certificate and its intermediate certificate"
            );
        }

        for (X509Certificate certificate : chain) {
            validateRsaCertificate(certificate);
        }

        verifyCertificateChain(chain);
    }

    private void validateRsaCertificate(X509Certificate certificate)
            throws GeneralSecurityException {
        if (certificate.getVersion() != X509_VERSION_3) {
            throw new GeneralSecurityException(
                    "OpenADR requires X.509v3 certificates; subject="
                            + certificate.getSubjectX500Principal()
            );
        }

        if (!(certificate.getPublicKey() instanceof RSAPublicKey rsaPublicKey)) {
            throw new GeneralSecurityException(
                    "OpenADR VEN is configured for RSA but certificate key is not RSA; subject="
                            + certificate.getSubjectX500Principal()
            );
        }

        int keySizeBits = rsaPublicKey.getModulus().bitLength();
        if (keySizeBits < MIN_RSA_KEY_SIZE_BITS) {
            throw new GeneralSecurityException(
                    "OpenADR RSA certificate key must be at least 2048 bits; actual="
                            + keySizeBits + ", subject=" + certificate.getSubjectX500Principal()
            );
        }

        String signatureAlgorithm = certificate.getSigAlgName()
                .toUpperCase(Locale.ROOT)
                .replace("-", "");
        if (!signatureAlgorithm.matches(".*SHA(224|256|384|512).*")) {
            throw new GeneralSecurityException(
                    "OpenADR SHA2 security requires a SHA-2 certificate signature; actual="
                            + certificate.getSigAlgName() + ", subject="
                            + certificate.getSubjectX500Principal()
            );
        }
    }

    private void verifyCertificateChain(List<X509Certificate> chain)
            throws GeneralSecurityException {
        for (int index = 0; index < chain.size() - 1; index++) {
            X509Certificate certificate = chain.get(index);
            X509Certificate issuer = chain.get(index + 1);

            X500Principal expectedIssuer = certificate.getIssuerX500Principal();
            X500Principal actualIssuer = issuer.getSubjectX500Principal();
            if (!expectedIssuer.equals(actualIssuer)) {
                throw new GeneralSecurityException(
                        "OpenADR certificate chain issuer mismatch; certificate="
                                + certificate.getSubjectX500Principal()
                                + ", expectedIssuer=" + expectedIssuer
                                + ", actualIssuer=" + actualIssuer
                );
            }

            certificate.verify(issuer.getPublicKey());
        }
    }
}
