package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils.IdentityCertificateChain;
import org.springframework.stereotype.Component;

import javax.security.auth.x500.X500Principal;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Locale;

@Component
public class OpenAdrCertificatePolicyValidator {

    private static final int X509_VERSION_3 = 3;
    private static final int MIN_ECC_KEY_SIZE_BITS = 256;
    private static final int MIN_RSA_KEY_SIZE_BITS = 2048;

    public void validateEccIdentity(IdentityCertificateChain identity)
            throws GeneralSecurityException {
        List<X509Certificate> chain = identity.certificates();

        if (chain.size() < 2) {
            throw new GeneralSecurityException(
                    "OpenADR client identity must contain the device certificate and its intermediate certificate"
            );
        }

        X509Certificate deviceCertificate = identity.clientCertificate();
        if (!(deviceCertificate.getPublicKey() instanceof ECPublicKey)) {
            throw new GeneralSecurityException(
                    "OpenADR VEN is configured for ECC but the device certificate key is not EC; subject="
                            + deviceCertificate.getSubjectX500Principal()
            );
        }

        for (X509Certificate certificate : chain) {
            validateCertificateProfile(certificate);
        }

        verifyCertificateChain(chain);
    }

    private void validateCertificateProfile(X509Certificate certificate)
            throws GeneralSecurityException {
        if (certificate.getVersion() != X509_VERSION_3) {
            throw new GeneralSecurityException(
                    "OpenADR requires X.509v3 certificates; subject="
                            + certificate.getSubjectX500Principal()
            );
        }

        validatePublicKey(certificate);
        validateSha2Signature(certificate);
    }

    private void validatePublicKey(X509Certificate certificate)
            throws GeneralSecurityException {
        PublicKey publicKey = certificate.getPublicKey();

        if (publicKey instanceof ECPublicKey ecPublicKey) {
            int keySizeBits = ecPublicKey.getParams().getCurve().getField().getFieldSize();
            if (keySizeBits < MIN_ECC_KEY_SIZE_BITS) {
                throw new GeneralSecurityException(
                        "OpenADR ECC certificate key must be at least 256 bits; actual="
                                + keySizeBits + ", subject=" + certificate.getSubjectX500Principal()
                );
            }
            return;
        }

        // OpenADR permits an ECC device certificate to have an RSA CA in a hybrid chain.
        if (publicKey instanceof RSAPublicKey rsaPublicKey) {
            int keySizeBits = rsaPublicKey.getModulus().bitLength();
            if (keySizeBits < MIN_RSA_KEY_SIZE_BITS) {
                throw new GeneralSecurityException(
                        "OpenADR RSA CA certificate key must be at least 2048 bits; actual="
                                + keySizeBits + ", subject=" + certificate.getSubjectX500Principal()
                );
            }
            return;
        }

        throw new GeneralSecurityException(
                "OpenADR certificate chain contains an unsupported public key algorithm: "
                        + publicKey.getAlgorithm() + ", subject="
                        + certificate.getSubjectX500Principal()
        );
    }

    private void validateSha2Signature(X509Certificate certificate)
            throws GeneralSecurityException {
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
