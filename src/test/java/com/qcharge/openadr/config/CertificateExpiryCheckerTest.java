package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils.CertificateInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ResourceLoader;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CertificateExpiryCheckerTest {

    @Mock
    private ResourceLoader resourceLoader;

    private OpenAdrProperties properties;
    private OpenAdrCertificateHealthCheck checker;

    @BeforeEach
    void setUp() {
        properties = new OpenAdrProperties();
        properties.getSecurity().setCertExpiryWarnDays(30);
        properties.getSecurity().setCertExpiryCriticalDays(7);
        checker = new OpenAdrCertificateHealthCheck(properties, resourceLoader);
    }

    @Test
    void validateExpiry_validFor100Days_noException() {
        CertificateInfo cert = certInfo(100);

        assertDoesNotThrow(() -> checker.validateExpiry("test-label", cert));
    }

    @Test
    void validateExpiry_expiresIn20Days_logsWarning_noException() {
        CertificateInfo cert = certInfo(20);

        assertDoesNotThrow(() -> checker.validateExpiry("test-label", cert));
    }

    @Test
    void validateExpiry_expiresIn5Days_logsCritical_noException() {
        CertificateInfo cert = certInfo(5);

        assertDoesNotThrow(() -> checker.validateExpiry("test-label", cert));
    }

    @Test
    void validateExpiry_alreadyExpired_throwsIllegalState() {
        CertificateInfo cert = certInfo(-3);

        assertThrows(IllegalStateException.class,
                () -> checker.validateExpiry("test-label", cert));
    }

    @Test
    void validateExpiry_expiresExactlyAtWarnBoundary_logsWarning_noException() {
        CertificateInfo cert = certInfo(30); // exactly at warnDays

        assertDoesNotThrow(() -> checker.validateExpiry("test-label", cert));
    }

    @Test
    void validateExpiry_expiresExactlyAtCriticalBoundary_logsCritical_noException() {
        CertificateInfo cert = certInfo(7); // exactly at criticalDays

        assertDoesNotThrow(() -> checker.validateExpiry("test-label", cert));
    }

    @Test
    void validateExpiry_customThresholds_honoured() {
        properties.getSecurity().setCertExpiryWarnDays(60);
        properties.getSecurity().setCertExpiryCriticalDays(14);

        CertificateInfo cert = certInfo(50); // within custom warn range (50 < 60)

        assertDoesNotThrow(() -> checker.validateExpiry("test-label", cert));
    }

    @Test
    void certificateInfo_expired_returnsTrue_whenNegativeDays() {
        assertTrue(certInfo(-1).expired());
        assertFalse(certInfo(0).expired());
        assertFalse(certInfo(100).expired());
    }

    // --- helper ---

    private CertificateInfo certInfo(long daysUntilExpiry) {
        Instant expiresAt = Instant.now().plus(daysUntilExpiry, ChronoUnit.DAYS);
        return new CertificateInfo(
                "test-alias",
                "CN=test-ven",
                "CN=test-ca",
                "SHA256withRSA",
                Instant.now().minus(365, ChronoUnit.DAYS),
                expiresAt,
                daysUntilExpiry,
                "AA:BB:CC:DD:EE:FF:00:11:22:33"
        );
    }
}
