package com.qcharge.openadr.utility;

import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;

public final class OpenAdrCertificateUtils {

    private static final int OPENADR_FINGERPRINT_BYTES = 10;

    private OpenAdrCertificateUtils() {
    }

    public static KeyStore loadPkcs12(
            ResourceLoader resourceLoader, String path, String password
    ) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");

        char[] passwordChars = password != null ? password.toCharArray() : new char[0];

        try (InputStream inputStream = resourceLoader.getResource(path).getInputStream()) {
            keyStore.load(inputStream, passwordChars);
        }

        return keyStore;
    }

    public static CertificateInfo findClientCertificate(
            KeyStore keyStore,
            String preferredAlias,
            Clock clock
    ) throws GeneralSecurityException {
        IdentityCertificateChain identity = findClientIdentity(keyStore, preferredAlias);
        return certificateInfo(identity.alias(), identity.clientCertificate(), clock);
    }

    public static IdentityCertificateChain findClientIdentity(
            KeyStore keyStore,
            String preferredAlias
    ) throws GeneralSecurityException {
        String alias = resolvePrivateKeyAlias(keyStore, preferredAlias);
        Certificate[] certificateChain = keyStore.getCertificateChain(alias);

        if (certificateChain == null || certificateChain.length == 0) {
            throw new GeneralSecurityException(
                    "No certificate chain found for client identity alias: " + alias
            );
        }

        List<X509Certificate> x509Chain = new ArrayList<>(certificateChain.length);
        for (Certificate certificate : certificateChain) {
            if (!(certificate instanceof X509Certificate x509Certificate)) {
                throw new GeneralSecurityException(
                        "Certificate chain contains a non-X.509 certificate for alias: " + alias
                );
            }
            x509Chain.add(x509Certificate);
        }

        return new IdentityCertificateChain(alias, x509Chain);
    }

    public static String resolvePrivateKeyAlias(
            KeyStore keyStore,
            String preferredAlias
    ) throws GeneralSecurityException {
        if (preferredAlias != null && !preferredAlias.isBlank()) {
            if (!keyStore.containsAlias(preferredAlias)) {
                throw new GeneralSecurityException("Keystore alias not found: " + preferredAlias);
            }

            if (!keyStore.isKeyEntry(preferredAlias)) {
                throw new GeneralSecurityException("Keystore alias is not a key entry: " + preferredAlias);
            }

            return preferredAlias;
        }

        Enumeration<String> aliases = keyStore.aliases();
        List<String> keyAliases = new ArrayList<>();

        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();

            if (keyStore.isKeyEntry(alias)) {
                keyAliases.add(alias);
            }
        }

        if (keyAliases.isEmpty()) {
            throw new GeneralSecurityException("No client certificate PrivateKeyEntry found in keystore");
        }

        if (keyAliases.size() > 1) {
            throw new GeneralSecurityException(
                    "Multiple PrivateKeyEntry aliases found in keystore; configure openadr.security.keystore-alias: "
                            + keyAliases
            );
        }

        return keyAliases.getFirst();
    }

    public static KeyStore selectClientIdentity(
            KeyStore sourceKeyStore,
            String preferredAlias,
            String password
    ) throws GeneralSecurityException, IOException {
        String alias = resolvePrivateKeyAlias(sourceKeyStore, preferredAlias);
        char[] passwordChars = password != null ? password.toCharArray() : new char[0];

        if (!(sourceKeyStore.getKey(alias, passwordChars) instanceof PrivateKey privateKey)) {
            throw new GeneralSecurityException("Alias is not a private key entry: " + alias);
        }

        Certificate[] certificateChain = sourceKeyStore.getCertificateChain(alias);
        if (certificateChain == null || certificateChain.length == 0) {
            throw new GeneralSecurityException(
                    "No certificate chain found for client identity alias: " + alias
            );
        }

        KeyStore selectedIdentity = KeyStore.getInstance("PKCS12");
        selectedIdentity.load(null, passwordChars);
        selectedIdentity.setKeyEntry(alias, privateKey, passwordChars, certificateChain);
        return selectedIdentity;
    }

    public static List<CertificateInfo> listX509Certificates(
            KeyStore keyStore,
            Clock clock
    ) throws GeneralSecurityException {
        List<CertificateInfo> certificates = new ArrayList<>();
        Enumeration<String> aliases = keyStore.aliases();

        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();

            if (keyStore.getCertificate(alias) instanceof X509Certificate certificate) {
                certificates.add(certificateInfo(alias, certificate, clock));
            }
        }

        return certificates;
    }

    public static String openAdrFingerprint(X509Certificate certificate)
            throws GeneralSecurityException {
        byte[] derEncoded = certificate.getEncoded();
        byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(derEncoded);

        int from = sha256.length - OPENADR_FINGERPRINT_BYTES;
        byte[] lastTenBytes = new byte[OPENADR_FINGERPRINT_BYTES];

        System.arraycopy(sha256, from, lastTenBytes, 0, OPENADR_FINGERPRINT_BYTES);

        return HexFormat.ofDelimiter(":")
                .withUpperCase()
                .formatHex(lastTenBytes);
    }

    public static CertificateInfo certificateInfo(
            String alias,
            X509Certificate certificate,
            Clock clock
    )
            throws GeneralSecurityException {
        Instant expiresAt = certificate.getNotAfter().toInstant();
        long daysUntilExpiry = ChronoUnit.DAYS.between(clock.instant(), expiresAt);

        return new CertificateInfo(
                alias,
                certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(),
                certificate.getSigAlgName(),
                certificate.getNotBefore().toInstant(),
                expiresAt,
                daysUntilExpiry,
                openAdrFingerprint(certificate)
        );
    }

    public record CertificateInfo(
            String alias,
            String subject,
            String issuer,
            String signatureAlgorithm,
            Instant validFrom,
            Instant expiresAt,
            long daysUntilExpiry,
            String openAdrFingerprint
    ) {
        public boolean notYetValid(Instant instant) {
            return instant.isBefore(validFrom);
        }

        public boolean expired(Instant instant) {
            return instant.isAfter(expiresAt);
        }
    }

    public record IdentityCertificateChain(
            String alias,
            List<X509Certificate> certificates
    ) {
        public IdentityCertificateChain {
            certificates = List.copyOf(certificates);
            if (certificates.isEmpty()) {
                throw new IllegalArgumentException("Certificate chain must not be empty");
            }
        }

        public X509Certificate clientCertificate() {
            return certificates.getFirst();
        }
    }
}
