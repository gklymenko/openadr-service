package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils.IdentityCertificateChain;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAdrCertificatePolicyValidatorTest {

    private static final X500Principal DEVICE = new X500Principal("CN=test-ven");
    private static final X500Principal INTERMEDIATE = new X500Principal("CN=test-intermediate");
    private static final X500Principal ROOT = new X500Principal("CN=test-root");

    private final OpenAdrCertificatePolicyValidator validator =
            new OpenAdrCertificatePolicyValidator();

    @Test
    void validateRsaIdentity_acceptsX509v3Sha2Rsa2048Chain() throws Exception {
        X509Certificate leaf = rsaCertificate(DEVICE, INTERMEDIATE, 2048, "SHA256withRSA", 3);
        X509Certificate issuer = rsaCertificate(INTERMEDIATE, ROOT, 4096, "SHA384withRSA", 3);

        assertDoesNotThrow(() -> validator.validateRsaIdentity(identity(leaf, issuer)));
    }

    @Test
    void validateRsaIdentity_rejectsMissingIntermediateCertificate() throws Exception {
        X509Certificate leaf = rsaCertificate(DEVICE, INTERMEDIATE, 2048, "SHA256withRSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateRsaIdentity(identity(leaf))
        );
    }

    @Test
    void validateRsaIdentity_rejectsRsaKeySmallerThan2048Bits() throws Exception {
        X509Certificate leaf = rsaCertificate(DEVICE, INTERMEDIATE, 1024, "SHA256withRSA", 3);
        X509Certificate issuer = rsaCertificate(INTERMEDIATE, ROOT, 4096, "SHA384withRSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateRsaIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateRsaIdentity_rejectsNonRsaCertificate() throws Exception {
        PublicKey ecKey = mock(PublicKey.class);
        when(ecKey.getAlgorithm()).thenReturn("EC");
        X509Certificate leaf = certificate(DEVICE, INTERMEDIATE, ecKey, "SHA256withECDSA", 3);
        X509Certificate issuer = rsaCertificate(INTERMEDIATE, ROOT, 4096, "SHA384withRSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateRsaIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateRsaIdentity_rejectsSha1CertificateSignature() throws Exception {
        X509Certificate leaf = rsaCertificate(DEVICE, INTERMEDIATE, 2048, "SHA1withRSA", 3);
        X509Certificate issuer = rsaCertificate(INTERMEDIATE, ROOT, 4096, "SHA384withRSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateRsaIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateRsaIdentity_rejectsCertificateBeforeX509v3() throws Exception {
        X509Certificate leaf = rsaCertificate(DEVICE, INTERMEDIATE, 2048, "SHA256withRSA", 2);
        X509Certificate issuer = rsaCertificate(INTERMEDIATE, ROOT, 4096, "SHA384withRSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateRsaIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateRsaIdentity_rejectsIssuerMismatch() throws Exception {
        X509Certificate leaf = rsaCertificate(DEVICE, INTERMEDIATE, 2048, "SHA256withRSA", 3);
        X509Certificate wrongIssuer = rsaCertificate(
                new X500Principal("CN=other-intermediate"),
                ROOT,
                4096,
                "SHA384withRSA",
                3
        );

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateRsaIdentity(identity(leaf, wrongIssuer))
        );
    }

    private IdentityCertificateChain identity(X509Certificate... certificates) {
        return new IdentityCertificateChain("openadr-ven", List.of(certificates));
    }

    private X509Certificate rsaCertificate(
            X500Principal subject,
            X500Principal issuer,
            int keySize,
            String signatureAlgorithm,
            int version
    ) {
        RSAPublicKey key = mock(RSAPublicKey.class);
        when(key.getAlgorithm()).thenReturn("RSA");
        when(key.getModulus()).thenReturn(BigInteger.ONE.shiftLeft(keySize - 1));
        return certificate(subject, issuer, key, signatureAlgorithm, version);
    }

    private X509Certificate certificate(
            X500Principal subject,
            X500Principal issuer,
            PublicKey key,
            String signatureAlgorithm,
            int version
    ) {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectX500Principal()).thenReturn(subject);
        when(certificate.getIssuerX500Principal()).thenReturn(issuer);
        when(certificate.getPublicKey()).thenReturn(key);
        when(certificate.getSigAlgName()).thenReturn(signatureAlgorithm);
        when(certificate.getVersion()).thenReturn(version);
        return certificate;
    }
}
