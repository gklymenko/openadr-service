package com.qcharge.openadr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openadr")
public class OpenAdrProperties {

    private Ven ven = new Ven();
    private Vtn vtn = new Vtn();
    private Transport transport = new Transport();
    private Security security = new Security();
    private Report report = new Report();

    @Data
    public static class Ven {
        private String id;
        private String name;
        private String profile;
    }

    @Data
    public static class Vtn {
        private String url;
        private String id;
    }

    @Data
    public static class Transport {
        private int pollIntervalSeconds = 10;
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 30;
    }

    @Data
    public static class Security {
        private String keystorePath;
        private String keystorePassword;
        private String truststorePath;
        private String truststorePassword;
    }

    @Data
    public static class Report {
        private String resourceId;
        private int telemetryIntervalSeconds = 60;
    }
}