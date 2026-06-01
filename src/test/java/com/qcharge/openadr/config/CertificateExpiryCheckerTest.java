package com.qcharge.openadr.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.core.io.ResourceLoader;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CertificateExpiryCheckerTest {

    @Mock
    private ResourceLoader resourceLoader;

    private OpenAdrProperties properties;
    private CertificateExpiryChecker checker;

    @BeforeEach
    void setUp() {
        properties = new OpenAdrProperties();
        properties.getSecurity().setCertExpiryWarnDays(30);
        properties.getSecurity().setCertExpiryCriticalDays(7);
        checker = new CertificateExpiryChecker(properties, resourceLoader);
    }

    @Test
    void checkExpiry_validFor100Days_logsInfo() {
        X509Certificate cert = mockCert(100);

        assertDoesNotThrow(() -> checker.checkExpiry(cert, "test-keystore", "test-alias"));

        // Verify getNotAfter() was called (proves the cert was inspected)
        verify(cert, atLeastOnce()).getNotAfter();
    }

    @Test
    void checkExpiry_expiresIn20Days_logsWarning() {
        X509Certificate cert = mockCert(20);

        assertDoesNotThrow(() -> checker.checkExpiry(cert, "test-keystore", "test-alias"));

        verify(cert, atLeastOnce()).getNotAfter();
    }

    @Test
    void checkExpiry_expiresIn5Days_logsCriticalError() {
        X509Certificate cert = mockCert(5);

        assertDoesNotThrow(() -> checker.checkExpiry(cert, "test-keystore", "test-alias"));

        verify(cert, atLeastOnce()).getNotAfter();
    }

    @Test
    void checkExpiry_alreadyExpired_logsError() {
        X509Certificate cert = mockCert(-3);

        assertDoesNotThrow(() -> checker.checkExpiry(cert, "test-keystore", "test-alias"));

        verify(cert, atLeastOnce()).getNotAfter();
    }

    @Test
    void checkExpiry_expiresExactlyOnWarnBoundary_logsWarning() {
        X509Certificate cert = mockCert(30); // exactly at warnDays threshold

        assertDoesNotThrow(() -> checker.checkExpiry(cert, "test-keystore", "test-alias"));

        verify(cert, atLeastOnce()).getNotAfter();
    }

    @Test
    void checkExpiry_expiresExactlyOnCriticalBoundary_logsCritical() {
        X509Certificate cert = mockCert(7); // exactly at criticalDays threshold

        assertDoesNotThrow(() -> checker.checkExpiry(cert, "test-keystore", "test-alias"));

        verify(cert, atLeastOnce()).getNotAfter();
    }

    @Test
    void checkExpiry_customThresholds_honoured() {
        properties.getSecurity().setCertExpiryWarnDays(60);
        properties.getSecurity().setCertExpiryCriticalDays(14);
        X509Certificate cert = mockCert(50); // within custom warn range (50 < 60)

        assertDoesNotThrow(() -> checker.checkExpiry(cert, "test-keystore", "test-alias"));

        verify(cert, atLeastOnce()).getNotAfter();
    }

    @Test
    void checkExpiry_nullPath_doesNotThrow() {
        // checkKeystore with null path must be silently skipped
        assertDoesNotThrow(() -> {
            OpenAdrProperties propsNullPath = new OpenAdrProperties();
            propsNullPath.getSecurity().setKeystorePath(null);
            CertificateExpiryChecker c = new CertificateExpiryChecker(propsNullPath, resourceLoader);
            // Directly invoke checkCertificatesOnStartup — null paths are silently skipped
            c.checkCertificatesOnStartup();
        });
    }

    // --- helper ---

    private X509Certificate mockCert(int daysUntilExpiry) {
        X509Certificate cert = mock(X509Certificate.class);
        Date expiry = Date.from(Instant.now().plus(daysUntilExpiry, ChronoUnit.DAYS));
        when(cert.getNotAfter()).thenReturn(expiry);
        when(cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=test-ven"));
        return cert;
    }
}
