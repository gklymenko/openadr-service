package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils;
import com.qcharge.openadr.utility.OpenAdrCertificateUtils.CertificateInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.security.KeyStore;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAdrCertificateHealthCheck {

    private final OpenAdrProperties properties;
    private final ResourceLoader resourceLoader;

    @EventListener(ApplicationReadyEvent.class)
    public void checkOpenAdrCertificates() {
        checkClientCertificate();
        checkTrustStoreCertificates();
    }

    private void checkClientCertificate() {
        try {
            KeyStore keyStore = OpenAdrCertificateUtils.loadPkcs12(
                    resourceLoader,
                    properties.getSecurity().getKeystorePath(),
                    properties.getSecurity().getKeystorePassword()
            );

            CertificateInfo clientCertificate = OpenAdrCertificateUtils.findClientCertificate(
                    keyStore,
                    properties.getSecurity().getKeystoreAlias()
            );

            log.info(
                    "OpenADR VEN client certificate loaded. alias={}, subject={}, issuer={}, sigAlg={}, expiresAt={}, daysUntilExpiry={}, fingerprint={}",
                    clientCertificate.alias(),
                    clientCertificate.subject(),
                    clientCertificate.issuer(),
                    clientCertificate.signatureAlgorithm(),
                    clientCertificate.expiresAt(),
                    clientCertificate.daysUntilExpiry(),
                    clientCertificate.openAdrFingerprint()
            );

            validateExpiry("OpenADR VEN client certificate", clientCertificate);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect OpenADR VEN client certificate", e);
        }
    }

    private void checkTrustStoreCertificates() {
        try {
            KeyStore trustStore = OpenAdrCertificateUtils.loadPkcs12(
                    resourceLoader,
                    properties.getSecurity().getTruststorePath(),
                    properties.getSecurity().getTruststorePassword()
            );

            List<CertificateInfo> certificates = OpenAdrCertificateUtils.listX509Certificates(trustStore);

            if (certificates.isEmpty()) {
                log.warn("OpenADR truststore does not contain X.509 certificates");
                return;
            }

            log.info("OpenADR truststore loaded. certificates={}", certificates.size());

            for (CertificateInfo certificate : certificates) {
                log.debug(
                        "OpenADR trusted certificate. alias={}, subject={}, issuer={}, expiresAt={}, daysUntilExpiry={}",
                        certificate.alias(),
                        certificate.subject(),
                        certificate.issuer(),
                        certificate.expiresAt(),
                        certificate.daysUntilExpiry()
                );

                validateExpiry("OpenADR trusted certificate alias=" + certificate.alias(), certificate);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect OpenADR truststore certificates", e);
        }
    }

    private void validateExpiry(String label, CertificateInfo certificate) {
        if (certificate.expired()) {
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