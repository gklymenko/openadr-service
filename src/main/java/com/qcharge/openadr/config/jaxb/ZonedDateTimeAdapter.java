package com.qcharge.openadr.config.jaxb;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class ZonedDateTimeAdapter extends XmlAdapter<String, ZonedDateTime> {

    // OpenADR requires UTC Zulu time: 2013-04-22T15:26:44.123Z
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public ZonedDateTime unmarshal(String value) {
        if (value == null) return null;
        return ZonedDateTime.parse(value, FORMATTER);
    }

    @Override
    public String marshal(ZonedDateTime value) {
        if (value == null) return null;
        // Conformance rule 1: MUST use UTC Zulu time, NOT +0000
        return value
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
    }
}