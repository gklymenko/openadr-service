package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils.CertificateInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ssl.SslBundles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CertificateExpiryCheckerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Mock
    private SslBundles sslBundles;

    @Mock
    private OpenAdrCertificatePolicyValidator certificatePolicyValidator;

    private OpenAdrProperties properties;
    private OpenAdrCertificateHealthCheck checker;

    @BeforeEach
    void setUp() {
        properties = new OpenAdrProperties();
        properties.getSecurity().setCertExpiryWarnDays(30);
        properties.getSecurity().setCertExpiryCriticalDays(7);
        checker = new OpenAdrCertificateHealthCheck(
                properties,
                sslBundles,
                certificatePolicyValidator,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void validateExpiry_validFor100Days_noException() {
        CertificateInfo cert = certInfo(100);

        assertDoesNotThrow(() -> checker.validateValidity("test-label", cert));
    }

    @Test
    void validateExpiry_expiresIn20Days_logsWarning_noException() {
        CertificateInfo cert = certInfo(20);

        assertDoesNotThrow(() -> checker.validateValidity("test-label", cert));
    }

    @Test
    void validateExpiry_expiresIn5Days_logsCritical_noException() {
        CertificateInfo cert = certInfo(5);

        assertDoesNotThrow(() -> checker.validateValidity("test-label", cert));
    }

    @Test
    void validateExpiry_alreadyExpired_throwsIllegalState() {
        CertificateInfo cert = certInfo(-3);

        assertThrows(IllegalStateException.class,
                () -> checker.validateValidity("test-label", cert));
    }

    @Test
    void validateValidity_expiredOneSecondAgo_throwsIllegalState() {
        CertificateInfo cert = certInfo(
                NOW.minus(365, ChronoUnit.DAYS),
                NOW.minusSeconds(1),
                0
        );

        assertThrows(IllegalStateException.class,
                () -> checker.validateValidity("test-label", cert));
    }

    @Test
    void validateValidity_notYetValid_throwsIllegalState() {
        CertificateInfo cert = certInfo(
                NOW.plusSeconds(1),
                NOW.plus(365, ChronoUnit.DAYS),
                365
        );

        assertThrows(IllegalStateException.class,
                () -> checker.validateValidity("test-label", cert));
    }

    @Test
    void validateExpiry_expiresExactlyAtWarnBoundary_logsWarning_noException() {
        CertificateInfo cert = certInfo(30); // exactly at warnDays

        assertDoesNotThrow(() -> checker.validateValidity("test-label", cert));
    }

    @Test
    void validateExpiry_expiresExactlyAtCriticalBoundary_logsCritical_noException() {
        CertificateInfo cert = certInfo(7); // exactly at criticalDays

        assertDoesNotThrow(() -> checker.validateValidity("test-label", cert));
    }

    @Test
    void validateExpiry_customThresholds_honoured() {
        properties.getSecurity().setCertExpiryWarnDays(60);
        properties.getSecurity().setCertExpiryCriticalDays(14);

        CertificateInfo cert = certInfo(50); // within custom warn range (50 < 60)

        assertDoesNotThrow(() -> checker.validateValidity("test-label", cert));
    }

    @Test
    void certificateInfo_validity_usesExactInstant() {
        assertTrue(certInfo(-1).expired(NOW));
        assertFalse(certInfo(0).expired(NOW));
        assertFalse(certInfo(100).expired(NOW));
        assertFalse(certInfo(100).notYetValid(NOW));
    }

    // --- helper ---

    private CertificateInfo certInfo(long daysUntilExpiry) {
        return certInfo(
                NOW.minus(365, ChronoUnit.DAYS),
                NOW.plus(daysUntilExpiry, ChronoUnit.DAYS),
                daysUntilExpiry
        );
    }

    private CertificateInfo certInfo(
            Instant validFrom,
            Instant expiresAt,
            long daysUntilExpiry
    ) {
        return new CertificateInfo(
                "test-alias",
                "CN=test-ven",
                "CN=test-ca",
                "SHA256withECDSA",
                validFrom,
                expiresAt,
                daysUntilExpiry,
                "AA:BB:CC:DD:EE:FF:00:11:22:33"
        );
    }
}
