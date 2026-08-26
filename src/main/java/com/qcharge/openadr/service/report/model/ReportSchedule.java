package com.qcharge.openadr.service.report.model;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.service.report.ValidatedReportRequest;
import com.qcharge.openadr.utility.TimeRange;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Pure scheduling rules for an OpenADR report request. */
public record ReportSchedule(
        Instant start,
        Instant endExclusive,
        Duration granularity,
        Duration reportBackDuration
) {

    public ReportSchedule {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(granularity, "granularity");
        Objects.requireNonNull(reportBackDuration, "reportBackDuration");
        if (granularity.isNegative() || reportBackDuration.isNegative()) {
            throw new IllegalArgumentException("Report durations must not be negative");
        }
        if (endExclusive != null && endExclusive.isBefore(start)) {
            throw new IllegalArgumentException("Report end must not be before start");
        }
    }

    public static ReportSchedule activate(
            ValidatedReportRequest request,
            Instant acceptedAt
    ) {
        Instant start = request.requestedStart() == null
                ? acceptedAt
                : request.requestedStart();
        Instant end = request.requestedDuration() == null
                || request.requestedDuration().isZero()
                ? null
                : start.plus(request.requestedDuration());
        return new ReportSchedule(
                start,
                end,
                request.granularity(),
                request.reportBackDuration()
        );
    }

    public static ReportSchedule restore(ReportRequest request) {
        Instant start = Objects.requireNonNull(request.getRequestedStart(), "requestedStart");
        Long durationSeconds = request.getRequestedDurationSeconds();
        Instant end = durationSeconds == null || durationSeconds == 0
                ? null
                : start.plusSeconds(durationSeconds);
        return new ReportSchedule(
                start,
                end,
                Duration.ofSeconds(request.getGranularitySeconds()),
                Duration.ofSeconds(request.getReportBackDurationSeconds())
        );
    }

    public boolean oneShot() {
        return reportBackDuration.isZero();
    }

    public Instant firstDeliveryAt(Instant acceptedAt) {
        if (oneShot()) {
            return acceptedAt;
        }

        Instant dueAt = laterOf(acceptedAt, start.plus(reportBackDuration));
        return endExclusive == null || dueAt.isBefore(endExclusive)
                ? dueAt
                : endExclusive;
    }

    public TimeRange deliveryWindow(Instant dueAt, Instant lastReportedAt) {
        if (oneShot()) {
            throw new IllegalStateException("One-shot reports do not have a periodic delivery window");
        }

        Instant windowEnd = endExclusive == null || dueAt.isBefore(endExclusive)
                ? dueAt
                : endExclusive;
        Instant defaultStart = windowEnd.minus(reportBackDuration);
        Instant windowStart = lastReportedAt == null
                ? laterOf(start, defaultStart)
                : lastReportedAt;
        return new TimeRange(windowStart, windowEnd);
    }

    public Optional<Instant> nextDeliveryAfter(Instant deliveredThrough) {
        if (oneShot() || hasCompletedThrough(deliveredThrough)) {
            return Optional.empty();
        }

        Instant next = deliveredThrough.plus(reportBackDuration);
        if (endExclusive != null && next.isAfter(endExclusive)) {
            next = endExclusive;
        }
        return Optional.of(next);
    }

    public boolean hasCompletedThrough(Instant deliveredThrough) {
        return endExclusive != null && !deliveredThrough.isBefore(endExclusive);
    }

    private static Instant laterOf(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }
}
