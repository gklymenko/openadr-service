package com.qcharge.openadr.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {

    private final OpenAdrProperties properties;
    private final ResourceLoader resourceLoader;

    @Bean
    public RestClient restClient() throws Exception {
        SSLContext sslContext = buildSslContext();

        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(
                        properties.getTransport().getConnectTimeoutSeconds()))
                .build();

        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(
                properties.getTransport().getReadTimeoutSeconds()));

        log.info("RestClient configured with mTLS, keystore: {}",
                properties.getSecurity().getKeystorePath());

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
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = resourceLoader.getResource(path).getInputStream()) {
            keyStore.load(is, password.toCharArray());
        }
        log.debug("Loaded keystore from: {}", path);
        return keyStore;
    }
}