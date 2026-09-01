package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils.IdentityCertificateChain;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECField;
import java.security.spec.ECParameterSpec;
import java.security.spec.EllipticCurve;
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
    void validateEccIdentity_acceptsX509v3Sha2Ecc256Chain() throws Exception {
        X509Certificate leaf = ecCertificate(DEVICE, INTERMEDIATE, 256, "SHA256withECDSA", 3);
        X509Certificate issuer = ecCertificate(INTERMEDIATE, ROOT, 384, "SHA384withECDSA", 3);

        assertDoesNotThrow(() -> validator.validateEccIdentity(identity(leaf, issuer)));
    }

    @Test
    void validateEccIdentity_acceptsRsaIntermediateForHybridChain() throws Exception {
        X509Certificate leaf = ecCertificate(DEVICE, INTERMEDIATE, 256, "SHA256withRSA", 3);
        X509Certificate issuer = rsaCertificate(INTERMEDIATE, ROOT, 2048, "SHA256withRSA", 3);

        assertDoesNotThrow(() -> validator.validateEccIdentity(identity(leaf, issuer)));
    }

    @Test
    void validateEccIdentity_rejectsMissingIntermediateCertificate() throws Exception {
        X509Certificate leaf = ecCertificate(DEVICE, INTERMEDIATE, 256, "SHA256withECDSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateEccIdentity(identity(leaf))
        );
    }

    @Test
    void validateEccIdentity_rejectsRsaDeviceCertificate() throws Exception {
        X509Certificate leaf = rsaCertificate(DEVICE, INTERMEDIATE, 2048, "SHA256withRSA", 3);
        X509Certificate issuer = rsaCertificate(INTERMEDIATE, ROOT, 2048, "SHA256withRSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateEccIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateEccIdentity_rejectsEcKeySmallerThan256Bits() throws Exception {
        X509Certificate leaf = ecCertificate(DEVICE, INTERMEDIATE, 224, "SHA256withECDSA", 3);
        X509Certificate issuer = ecCertificate(INTERMEDIATE, ROOT, 256, "SHA256withECDSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateEccIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateEccIdentity_rejectsRsaIntermediateSmallerThan2048Bits() throws Exception {
        X509Certificate leaf = ecCertificate(DEVICE, INTERMEDIATE, 256, "SHA256withRSA", 3);
        X509Certificate issuer = rsaCertificate(INTERMEDIATE, ROOT, 1024, "SHA256withRSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateEccIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateEccIdentity_rejectsSha1CertificateSignature() throws Exception {
        X509Certificate leaf = ecCertificate(DEVICE, INTERMEDIATE, 256, "SHA1withECDSA", 3);
        X509Certificate issuer = ecCertificate(INTERMEDIATE, ROOT, 256, "SHA256withECDSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateEccIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateEccIdentity_rejectsCertificateBeforeX509v3() throws Exception {
        X509Certificate leaf = ecCertificate(DEVICE, INTERMEDIATE, 256, "SHA256withECDSA", 2);
        X509Certificate issuer = ecCertificate(INTERMEDIATE, ROOT, 256, "SHA256withECDSA", 3);

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateEccIdentity(identity(leaf, issuer))
        );
    }

    @Test
    void validateEccIdentity_rejectsIssuerMismatch() throws Exception {
        X509Certificate leaf = ecCertificate(DEVICE, INTERMEDIATE, 256, "SHA256withECDSA", 3);
        X509Certificate wrongIssuer = ecCertificate(
                new X500Principal("CN=other-intermediate"),
                ROOT,
                256,
                "SHA256withECDSA",
                3
        );

        assertThrows(
                GeneralSecurityException.class,
                () -> validator.validateEccIdentity(identity(leaf, wrongIssuer))
        );
    }

    private IdentityCertificateChain identity(X509Certificate... certificates) {
        return new IdentityCertificateChain("openadr-ven", List.of(certificates));
    }

    private X509Certificate ecCertificate(
            X500Principal subject,
            X500Principal issuer,
            int keySize,
            String signatureAlgorithm,
            int version
    ) {
        ECPublicKey key = mock(ECPublicKey.class);
        ECParameterSpec parameters = mock(ECParameterSpec.class);
        EllipticCurve curve = mock(EllipticCurve.class);
        ECField field = mock(ECField.class);
        when(key.getAlgorithm()).thenReturn("EC");
        when(key.getParams()).thenReturn(parameters);
        when(parameters.getCurve()).thenReturn(curve);
        when(curve.getField()).thenReturn(field);
        when(field.getFieldSize()).thenReturn(keySize);
        return certificate(subject, issuer, key, signatureAlgorithm, version);
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
