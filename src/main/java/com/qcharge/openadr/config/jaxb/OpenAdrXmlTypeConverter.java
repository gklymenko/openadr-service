package com.qcharge.openadr.config.jaxb;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class OpenAdrXmlTypeConverter {

    private static final DateTimeFormatter OPEN_ADR_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private OpenAdrXmlTypeConverter() {
    }

    public static ZonedDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return ZonedDateTime.parse(value);
    }

    public static String printDateTime(ZonedDateTime value) {
        if (value == null) {
            return null;
        }

        return value
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(OPEN_ADR_DATE_TIME_FORMATTER);
    }

    public static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return Duration.parse(value);
    }

    public static String printDuration(Duration value) {
        if (value == null) {
            return null;
        }

        if (value.getNano() != 0) {
            throw new IllegalArgumentException("OpenADR duration must not contain decimal values.");
        }

        return value.toString();
    }
}