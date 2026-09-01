package com.qcharge.openadr.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslOptions;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpClientConfigTest {

    private HttpClientConfig configuration;
    private SslBundle sslBundle;
    private SslOptions sslOptions;
    private SSLContext sslContext;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new HttpClientConfig(
                new OpenAdrProperties(),
                mock(SslBundles.class)
        );
        sslBundle = mock(SslBundle.class);
        sslOptions = mock(SslOptions.class);
        when(sslBundle.getOptions()).thenReturn(sslOptions);

        sslContext = SSLContext.getInstance(HttpClientConfig.OPENADR_TLS_PROTOCOL);
        sslContext.init(null, null, null);
    }

    @Test
    void openAdrSslParameters_acceptsOnlyRequiredEccConfiguration() {
        when(sslOptions.getEnabledProtocols()).thenReturn(
                new String[]{HttpClientConfig.OPENADR_TLS_PROTOCOL}
        );
        when(sslOptions.getCiphers()).thenReturn(
                new String[]{HttpClientConfig.OPENADR_ECC_TLS_CIPHER_SUITE}
        );

        SSLParameters parameters = configuration.openAdrSslParameters(sslBundle, sslContext);

        assertArrayEquals(
                new String[]{HttpClientConfig.OPENADR_TLS_PROTOCOL},
                parameters.getProtocols()
        );
        assertArrayEquals(
                new String[]{HttpClientConfig.OPENADR_ECC_TLS_CIPHER_SUITE},
                parameters.getCipherSuites()
        );
    }

    @Test
    void openAdrSslParameters_rejectsRsaCipher() {
        when(sslOptions.getEnabledProtocols()).thenReturn(
                new String[]{HttpClientConfig.OPENADR_TLS_PROTOCOL}
        );
        when(sslOptions.getCiphers()).thenReturn(
                new String[]{"TLS_RSA_WITH_AES_128_CBC_SHA256"}
        );

        assertThrows(
                IllegalStateException.class,
                () -> configuration.openAdrSslParameters(sslBundle, sslContext)
        );
    }

    @Test
    void openAdrSslParameters_rejectsAdditionalTlsVersion() {
        when(sslOptions.getEnabledProtocols()).thenReturn(
                new String[]{HttpClientConfig.OPENADR_TLS_PROTOCOL, "TLSv1.3"}
        );
        when(sslOptions.getCiphers()).thenReturn(
                new String[]{HttpClientConfig.OPENADR_ECC_TLS_CIPHER_SUITE}
        );

        assertThrows(
                IllegalStateException.class,
                () -> configuration.openAdrSslParameters(sslBundle, sslContext)
        );
    }
}
