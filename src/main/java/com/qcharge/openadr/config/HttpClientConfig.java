package com.qcharge.openadr.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.http.HttpClient;
import java.time.Duration;
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

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        SslBundle sslBundle = sslBundles.getBundle(OPENADR_SSL_BUNDLE);
        SSLContext sslContext = sslBundle.createSslContext();
        SSLParameters sslParameters = openAdrSslParameters(sslBundle, sslContext);

        if (properties.getSecurity().isDisableHostnameVerification()) {
            // Required only for Test Harness certificates whose CN/SAN does not match 127.0.0.1.
            sslParameters.setEndpointIdentificationAlgorithm("");
            log.warn("TLS hostname verification DISABLED — use only for Test Harness testing");
        } else {
            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .sslParameters(sslParameters)
                .connectTimeout(Duration.ofSeconds(
                        properties.getTransport().getConnectTimeoutSeconds()))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(
                properties.getTransport().getReadTimeoutSeconds()));

        log.info(
                "OpenADR mTLS configured from Spring SSL bundle '{}': protocol={}, cipher={} (rules 67 and 68)",
                OPENADR_SSL_BUNDLE,
                OPENADR_TLS_PROTOCOL,
                OPENADR_ECC_TLS_CIPHER_SUITE
        );
        log.info("HTTP transport: chunked transfer disabled (spec 9.1.9), Content-Type=application/xml");

        return builder
                .requestFactory(factory)
                .build();
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
