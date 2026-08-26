package com.qcharge.openadr.utility;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable half-open time range: {@code [start, end)}. */
public record TimeRange(Instant start, Instant endExclusive) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!endExclusive.isAfter(start)) {
            throw new IllegalArgumentException("endExclusive must be after start");
        }
    }

    public static TimeRange of(Instant start, Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        return new TimeRange(start, start.plus(duration));
    }

    public Duration duration() {
        return Duration.between(start, endExclusive);
    }

    public boolean contains(Instant value) {
        return !value.isBefore(start) && value.isBefore(endExclusive);
    }

    public boolean hasEndedAt(Instant value) {
        return !value.isBefore(endExclusive);
    }
}
