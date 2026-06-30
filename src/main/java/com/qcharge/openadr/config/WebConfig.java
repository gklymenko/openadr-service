package com.qcharge.openadr.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigure() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                registry
                        .addMapping("/**")
                        .allowedOrigins()
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET","POST","DELETE","PUT", "HEAD", "OPTIONS", "PATCH")
                        .allowedHeaders("*");
            }
        };
    }

    @Bean
    public ConfigurableServletWebServerFactory webServerFactory() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty("relaxedPathChars", "&#x5B;&#x5D;&#x7C;");
            connector.setProperty("relaxedQueryChars", "&#x5B;&#x5D;&#x7C;&#x7B;&#x7D;&#x5E;&#x5C;&#x60;&#x22;&#x3C;&#x3E;");
        });
        return factory;
    }

}
