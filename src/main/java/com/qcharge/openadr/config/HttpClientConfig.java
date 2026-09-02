package com.qcharge.openadr.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.reactor.ssl.SSLBufferMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.util.Arrays;
import java.util.Set;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {

    static final String OPENADR_SSL_BUNDLE = "openadr";
    static final String OPENADR_TLS_PROTOCOL = "TLSv1.2";
    static final String OPENADR_ECC_TLS_CIPHER_SUITE =
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256";

    private final OpenAdrProperties properties;
    private final SslBundles sslBundles;

    @Bean(destroyMethod = "close")
    public CloseableHttpClient openAdrHttpClient() {
        SslBundle sslBundle = sslBundles.getBundle(OPENADR_SSL_BUNDLE);
        SSLContext sslContext = sslBundle.createSslContext();
        SSLParameters sslParameters = openAdrSslParameters(sslBundle, sslContext);
        HostnameVerifier hostnameVerifier = openAdrHostnameVerifier();

        TlsSocketStrategy tlsSocketStrategy = new DefaultClientTlsStrategy(
                sslContext,
                sslParameters.getProtocols(),
                sslParameters.getCipherSuites(),
                SSLBufferMode.STATIC,
                openAdrHostnameVerificationPolicy(),
                hostnameVerifier
        );

        Timeout readTimeout = Timeout.ofSeconds(
                properties.getTransport().getReadTimeoutSeconds());
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(
                        properties.getTransport().getConnectTimeoutSeconds()))
                .setSocketTimeout(readTimeout)
                .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setTlsSocketStrategy(tlsSocketStrategy)
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(readTimeout)
                .build();

        log.info(
                "OpenADR mTLS configured from Spring SSL bundle '{}': protocol={}, cipher={} (rules 67 and 68)",
                OPENADR_SSL_BUNDLE,
                OPENADR_TLS_PROTOCOL,
                OPENADR_ECC_TLS_CIPHER_SUITE
        );
        log.info("HTTP transport: chunked transfer disabled (spec 9.1.9), Content-Type=application/xml");

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    public RestClient restClient(
            RestClient.Builder builder,
            @Qualifier("openAdrHttpClient") CloseableHttpClient openAdrHttpClient
    ) {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(openAdrHttpClient);

        return builder
                .requestFactory(factory)
                .build();
    }

    HostnameVerifier openAdrHostnameVerifier() {
        if (properties.getSecurity().isDisableHostnameVerification()) {
            // Required by the Test Harness because its server certificate uses CN/SAN=vtn.
            // Trust chain, validity, client authentication and cipher checks remain enabled.
            log.warn("TLS hostname verification DISABLED — use only for Test Harness testing");
            return NoopHostnameVerifier.INSTANCE;
        }

        return new DefaultHostnameVerifier();
    }

    HostnameVerificationPolicy openAdrHostnameVerificationPolicy() {
        // CLIENT prevents JSSE from performing its own endpoint-identification check.
        // Apache HttpClient then applies the selected verifier after the TLS handshake.
        return properties.getSecurity().isDisableHostnameVerification()
                ? HostnameVerificationPolicy.CLIENT
                : HostnameVerificationPolicy.BOTH;
    }

    SSLParameters openAdrSslParameters(SslBundle sslBundle, SSLContext sslContext) {
        String[] configuredProtocols = sslBundle.getOptions().getEnabledProtocols();
        String[] configuredCiphers = sslBundle.getOptions().getCiphers();

        requireOnly("protocol", configuredProtocols, OPENADR_TLS_PROTOCOL);
        requireOnly("cipher suite", configuredCiphers, OPENADR_ECC_TLS_CIPHER_SUITE);
        requireSupported(
                "protocol",
                OPENADR_TLS_PROTOCOL,
                Set.of(sslContext.getSupportedSSLParameters().getProtocols())
        );
        requireSupported(
                "cipher suite",
                OPENADR_ECC_TLS_CIPHER_SUITE,
                Set.of(sslContext.getSupportedSSLParameters().getCipherSuites())
        );

        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(configuredProtocols);
        parameters.setCipherSuites(configuredCiphers);
        return parameters;
    }

    private void requireOnly(String option, String[] configuredValues, String expectedValue) {
        if (configuredValues == null
                || configuredValues.length != 1
                || !expectedValue.equals(configuredValues[0])) {
            throw new IllegalStateException(
                    "OpenADR ECC-only TLS requires exactly one %s: %s; configured=%s"
                            .formatted(option, expectedValue, Arrays.toString(configuredValues))
            );
        }
    }

    private void requireSupported(String option, String requiredValue, Set<String> supportedValues) {
        if (!supportedValues.contains(requiredValue)) {
            throw new IllegalStateException(
                    "OpenADR %s is not supported by the current JDK/security policy: %s"
                            .formatted(option, requiredValue)
            );
        }
    }
}
