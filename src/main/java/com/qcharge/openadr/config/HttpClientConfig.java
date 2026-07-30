package com.qcharge.openadr.config;

import com.qcharge.openadr.utility.OpenAdrCertificateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {

    private final OpenAdrProperties properties;
    private final ResourceLoader resourceLoader;

    private static final String[] OPENADR_TLS_CIPHER_SUITES = {
            "TLS_RSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"
    };

    @Bean
    public RestClient restClient() throws Exception {
        SSLContext sslContext = buildSslContext();

        // Rule 67: VEN MUST offer TLS_RSA_WITH_AES_128_CBC_SHA256 over TLS 1.2
        SSLParameters sslParams = new SSLParameters();
        sslParams.setCipherSuites(supportedOpenAdrCipherSuites(sslContext));
        sslParams.setProtocols(new String[]{"TLSv1.2"});

        if (properties.getSecurity().isDisableHostnameVerification()) {
            // Disable CN/SAN hostname check — needed for TH cert (CN=vtn vs 127.0.0.1)
            sslParams.setEndpointIdentificationAlgorithm("");
            log.warn("TLS hostname verification DISABLED — use only for Test Harness testing");
        } else {
            sslParams.setEndpointIdentificationAlgorithm("HTTPS");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .sslParameters(sslParams)
                .connectTimeout(Duration.ofSeconds(
                        properties.getTransport().getConnectTimeoutSeconds()))
                .build();

        log.info("TLS configured: protocol=TLSv1.2, cipher=TLS_RSA_WITH_AES_128_CBC_SHA256 (OpenADR rule 67)");

        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(
                properties.getTransport().getReadTimeoutSeconds()));

        log.info("RestClient configured with mTLS, keystore: {}",
                properties.getSecurity().getKeystorePath());
        log.info("HTTP transport: chunked transfer disabled (spec 9.1.9), Content-Type=application/xml");

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    private SSLContext buildSslContext() throws Exception {
        KeyStore keyStore = loadKeyStore(
                properties.getSecurity().getKeystorePath(),
                properties.getSecurity().getKeystorePassword()
        );

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore,
                properties.getSecurity().getKeystorePassword().toCharArray());

        KeyStore trustStore = loadKeyStore(
                properties.getSecurity().getTruststorePath(),
                properties.getSecurity().getTruststorePassword()
        );

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        log.info("SSLContext initialized with TLS 1.2");
        return sslContext;
    }

    private KeyStore loadKeyStore(String path, String password) throws Exception {
        KeyStore keyStore = OpenAdrCertificateUtils.loadPkcs12(resourceLoader, path, password);
        log.debug("Loaded keystore from: {}", path);
        return keyStore;
    }

    private String[] supportedOpenAdrCipherSuites(SSLContext sslContext) {
        Set<String> supported = Set.of(sslContext.getSupportedSSLParameters().getCipherSuites());

        String[] selected = Arrays.stream(OPENADR_TLS_CIPHER_SUITES)
                .filter(supported::contains)
                .toArray(String[]::new);

        if (selected.length == 0) {
            throw new IllegalStateException(
                    "No OpenADR mandatory TLS 1.2 cipher suites are supported by current JDK/security policy"
            );
        }

        log.info("OpenADR TLS cipher suites enabled: {}", Arrays.toString(selected));
        return selected;
    }
}