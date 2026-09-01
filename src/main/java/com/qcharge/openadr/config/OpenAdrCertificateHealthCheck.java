package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils;
import com.qcharge.openadr.utility.OpenAdrCertificateUtils.CertificateInfo;
import com.qcharge.openadr.utility.OpenAdrCertificateUtils.IdentityCertificateChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.security.KeyStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAdrCertificateHealthCheck {

    private final OpenAdrProperties properties;
    private final SslBundles sslBundles;
    private final OpenAdrCertificatePolicyValidator certificatePolicyValidator;
    private final Clock clock;

    @EventListener(ApplicationReadyEvent.class)
    public void checkOpenAdrCertificates() {
        SslBundle sslBundle = sslBundles.getBundle(HttpClientConfig.OPENADR_SSL_BUNDLE);
        checkClientCertificate(sslBundle);
        checkTrustStoreCertificates(sslBundle);
    }

    private void checkClientCertificate(SslBundle sslBundle) {
        try {
            KeyStore keyStore = requireStore(
                    "keystore",
                    sslBundle.getStores().getKeyStore()
            );
            String configuredAlias = sslBundle.getKey().getAlias();
            IdentityCertificateChain identity = OpenAdrCertificateUtils.findClientIdentity(
                    keyStore,
                    configuredAlias
            );
            certificatePolicyValidator.validateEccIdentity(identity);

            CertificateInfo clientCertificate = OpenAdrCertificateUtils.certificateInfo(
                    identity.alias(),
                    identity.clientCertificate(),
                    clock
            );

            log.info(
                    "OpenADR ECC VEN certificate loaded from Spring SSL bundle. alias={}, subject={}, issuer={}, sigAlg={}, expiresAt={}, daysUntilExpiry={}, fingerprint={}",
                    clientCertificate.alias(),
                    clientCertificate.subject(),
                    clientCertificate.issuer(),
                    clientCertificate.signatureAlgorithm(),
                    clientCertificate.expiresAt(),
                    clientCertificate.daysUntilExpiry(),
                    clientCertificate.openAdrFingerprint()
            );

            validateValidity("OpenADR VEN client certificate", clientCertificate);

            for (int index = 1; index < identity.certificates().size(); index++) {
                CertificateInfo issuerCertificate = OpenAdrCertificateUtils.certificateInfo(
                        identity.alias() + "#chain-" + index,
                        identity.certificates().get(index),
                        clock
                );
                validateValidity("OpenADR VEN certificate chain", issuerCertificate);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect OpenADR ECC VEN identity", e);
        }
    }

    private void checkTrustStoreCertificates(SslBundle sslBundle) {
        try {
            KeyStore trustStore = requireStore(
                    "truststore",
                    sslBundle.getStores().getTrustStore()
            );
            List<CertificateInfo> certificates = OpenAdrCertificateUtils.listX509Certificates(
                    trustStore,
                    clock
            );

            if (certificates.isEmpty()) {
                throw new IllegalStateException(
                        "OpenADR truststore does not contain X.509 trust anchors"
                );
            }

            log.info("OpenADR truststore loaded from Spring SSL bundle. certificates={}",
                    certificates.size());

            for (CertificateInfo certificate : certificates) {
                log.debug(
                        "OpenADR trusted certificate. alias={}, subject={}, issuer={}, expiresAt={}, daysUntilExpiry={}",
                        certificate.alias(),
                        certificate.subject(),
                        certificate.issuer(),
                        certificate.expiresAt(),
                        certificate.daysUntilExpiry()
                );

                validateValidity("OpenADR trusted certificate alias=" + certificate.alias(), certificate);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect OpenADR truststore certificates", e);
        }
    }

    private KeyStore requireStore(String label, KeyStore keyStore) {
        if (keyStore == null) {
            throw new IllegalStateException(
                    "Spring SSL bundle '%s' does not contain an OpenADR %s"
                            .formatted(HttpClientConfig.OPENADR_SSL_BUNDLE, label)
            );
        }
        return keyStore;
    }

    void validateValidity(String label, CertificateInfo certificate) {
        Instant now = clock.instant();

        if (certificate.notYetValid(now)) {
            throw new IllegalStateException(
                    "%s is not yet valid. alias=%s, validFrom=%s"
                            .formatted(label, certificate.alias(), certificate.validFrom())
            );
        }

        if (certificate.expired(now)) {
            throw new IllegalStateException(
                    "%s is expired. alias=%s, expiresAt=%s"
                            .formatted(label, certificate.alias(), certificate.expiresAt())
            );
        }

        int criticalDays = properties.getSecurity().getCertExpiryCriticalDays();
        int warnDays = properties.getSecurity().getCertExpiryWarnDays();

        if (certificate.daysUntilExpiry() <= criticalDays) {
            log.error(
                    "{} expires very soon. alias={}, expiresAt={}, daysUntilExpiry={}",
                    label,
                    certificate.alias(),
                    certificate.expiresAt(),
                    certificate.daysUntilExpiry()
            );
            return;
        }

        if (certificate.daysUntilExpiry() <= warnDays) {
            log.warn(
                    "{} expires soon. alias={}, expiresAt={}, daysUntilExpiry={}",
                    label,
                    certificate.alias(),
                    certificate.expiresAt(),
                    certificate.daysUntilExpiry()
            );
        }
    }
}
