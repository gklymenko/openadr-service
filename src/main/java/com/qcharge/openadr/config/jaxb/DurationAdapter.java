package com.qcharge.openadr.config.jaxb;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import java.time.Duration;

public class DurationAdapter extends XmlAdapter<String, Duration> {

    @Override
    public Duration unmarshal(String value) {
        if (value == null) return null;
        return Duration.parse(value);
    }

    @Override
    public String marshal(Duration value) {
        if (value == null) return null;
        // Conformance rule 1: decimal values MUST NOT be used in OpenADR
        return value.toString(); // PT10M, PT1H etc
    }
}