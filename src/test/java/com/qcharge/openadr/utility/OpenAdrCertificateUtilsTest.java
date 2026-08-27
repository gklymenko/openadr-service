package com.qcharge.openadr.utility;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAdrCertificateUtilsTest {

    @Test
    void resolvePrivateKeyAlias_usesConfiguredAlias() throws Exception {
        KeyStore keyStore = mock(KeyStore.class);
        when(keyStore.containsAlias("openadr-ven")).thenReturn(true);
        when(keyStore.isKeyEntry("openadr-ven")).thenReturn(true);

        assertEquals(
                "openadr-ven",
                OpenAdrCertificateUtils.resolvePrivateKeyAlias(keyStore, "openadr-ven")
        );
    }

    @Test
    void resolvePrivateKeyAlias_usesOnlyPrivateKeyEntry() throws Exception {
        KeyStore keyStore = mock(KeyStore.class);
        when(keyStore.aliases()).thenReturn(Collections.enumeration(List.of("ca", "openadr-ven")));
        when(keyStore.isKeyEntry("ca")).thenReturn(false);
        when(keyStore.isKeyEntry("openadr-ven")).thenReturn(true);

        assertEquals(
                "openadr-ven",
                OpenAdrCertificateUtils.resolvePrivateKeyAlias(keyStore, null)
        );
    }

    @Test
    void resolvePrivateKeyAlias_rejectsAmbiguousPrivateKeyEntries() throws Exception {
        KeyStore keyStore = mock(KeyStore.class);
        when(keyStore.aliases()).thenReturn(Collections.enumeration(List.of("ven-one", "ven-two")));
        when(keyStore.isKeyEntry("ven-one")).thenReturn(true);
        when(keyStore.isKeyEntry("ven-two")).thenReturn(true);

        assertThrows(
                GeneralSecurityException.class,
                () -> OpenAdrCertificateUtils.resolvePrivateKeyAlias(keyStore, null)
        );
    }

    @Test
    void resolvePrivateKeyAlias_rejectsConfiguredCertificateEntry() throws Exception {
        KeyStore keyStore = mock(KeyStore.class);
        when(keyStore.containsAlias("root-ca")).thenReturn(true);
        when(keyStore.isKeyEntry("root-ca")).thenReturn(false);

        assertThrows(
                GeneralSecurityException.class,
                () -> OpenAdrCertificateUtils.resolvePrivateKeyAlias(keyStore, "root-ca")
        );
    }
}
