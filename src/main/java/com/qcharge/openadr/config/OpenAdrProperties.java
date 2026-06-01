package com.qcharge.openadr.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "openadr")
public class OpenAdrProperties {

    @Valid
    private Ven ven = new Ven();

    @Valid
    private Vtn vtn = new Vtn();

    @Valid
    private Transport transport = new Transport();

    @Valid
    private Security security = new Security();

    @Valid
    private Report report = new Report();

    @Valid
    private Xml xml = new Xml();

    @Getter
    @Setter
    public static class Xml {
        private boolean validate = false;
        private String xsdFolderPath;
    }

    @Getter
    @Setter
    public static class Ven {
        /**
         * For initial registration this may be preconfigured.
         * If VTN assigns venID dynamically, make this nullable and persist returned venID.
         */
        @NotBlank
        private String id;

        private String name;

        @NotBlank
        private String profile = "2.0b";

        /**
         * Runtime default: false.
         * Certification / diagnostics: true.
         */
        private boolean queryRegistrationOnStartup = false;
    }

    @Getter
    @Setter
    public static class Vtn {
        /**
         * Recommended value:
         * https://host[:port][/prefix]
         *
         * Do not include /OpenADR2/Simple/2.0b here unless you intentionally want full base URL.
         */
        @NotBlank
        private String url;

        private String id;
    }

    @Getter
    @Setter
    public static class Transport {
        @Min(1)
        private int pollIntervalSeconds = 60;

        @Min(1)
        private int connectTimeoutSeconds = 5;

        @Min(5)
        private int readTimeoutSeconds = 30;

        @Min(1)
        private int maxPollDrainIterations = 50;

        @Min(0)
        private int maxPollJitterSeconds = 5;

        @Min(1)
        private int retryMaxAttempts = 3;

        @Min(1)
        private int retryInitialDelayMillis = 1000;

        @Min(0)
        private int retryMaxDelayMillis = 60000;
    }

    @Getter
    @Setter
    public static class Security {
        private String keystorePath;
        private String keystorePassword;
        private String truststorePath;
        private String truststorePassword;
    }

    @Getter
    @Setter
    public static class Report {
        @NotBlank
        private String resourceId;

        @Min(1)
        private int telemetryIntervalSeconds = 60;
    }
}