package com.qcharge.openadr.utility;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class OpenAdrTimeUtils {

    private OpenAdrTimeUtils() {
    }

    public static Instant fromXmlDateTime(XMLGregorianCalendar value) {
        if (value == null) {
            throw new IllegalArgumentException("dateTime is required");
        }

        return value.toGregorianCalendar().toInstant();
    }

    public static Optional<Duration> parseOpenAdrDuration(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        if ("0".equals(value)) {
            return Optional.of(Duration.ZERO);
        }

        try {
            return Optional.of(Duration.parse(value));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 OpenADR duration: " + value, e);
        }
    }

    /**
     * OpenADR Rule 30: randomize actual start within [dtstart, dtstart + startafter].
     */
    public static Instant applyStartAfterJitter(Instant dtstart, String startAfter) {
        Duration tolerance = parseOpenAdrDuration(startAfter).orElse(Duration.ZERO);

        if (tolerance.isZero() || tolerance.isNegative()) {
            return dtstart;
        }

        long offsetSeconds = ThreadLocalRandom.current()
                .nextLong(0, tolerance.getSeconds() + 1);

        return dtstart.plusSeconds(offsetSeconds);
    }
}