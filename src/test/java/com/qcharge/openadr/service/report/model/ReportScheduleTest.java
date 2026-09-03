package com.qcharge.openadr.service.report.model;

import com.qcharge.openadr.service.report.ValidatedReportRequest;
import com.qcharge.openadr.utility.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportScheduleTest {

    private static final Instant START = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    void firstDeliveryWaitsForReportBackDurationFromDtstart() {
        ReportSchedule schedule = schedule(START, Duration.ofMinutes(5));

        assertEquals(
                START.plus(Duration.ofMinutes(2)),
                schedule.firstDeliveryAt(START)
        );
    }

    @Test
    void r1_3025_futureDtstartControlsFirstDelivery() {
        Instant delayedStart = START.plusSeconds(15);
        ReportSchedule schedule = schedule(delayedStart, Duration.ofMinutes(5));

        assertEquals(
                delayedStart.plus(Duration.ofMinutes(2)),
                schedule.firstDeliveryAt(START)
        );
    }

    @Test
    void r1_3027_shortDurationEndsAtRequestedBoundary() {
        ReportSchedule schedule = schedule(START, Duration.ofSeconds(90));

        Instant onlyDue = schedule.firstDeliveryAt(START);
        assertEquals(START.plusSeconds(90), onlyDue);

        TimeRange onlyWindow = schedule.deliveryWindow(onlyDue, null);
        assertEquals(START, onlyWindow.start());
        assertEquals(Duration.ofSeconds(90), onlyWindow.duration());
        assertTrue(schedule.hasCompletedThrough(onlyWindow.endExclusive()));
        assertTrue(schedule.nextDeliveryAfter(onlyWindow.endExclusive()).isEmpty());
    }

    @Test
    void deliveryWindowsDoNotDriftAndFinalWindowEndsAtRequestedDuration() {
        ReportSchedule schedule = schedule(START, Duration.ofMinutes(5));
        Instant firstDue = START.plus(Duration.ofMinutes(2));

        TimeRange first = schedule.deliveryWindow(firstDue, null);
        assertEquals(START, first.start());
        assertEquals(firstDue, first.endExclusive());

        Instant secondDue = schedule.nextDeliveryAfter(first.endExclusive()).orElseThrow();
        assertEquals(START.plus(Duration.ofMinutes(4)), secondDue);

        TimeRange second = schedule.deliveryWindow(secondDue, first.endExclusive());
        Instant finalDue = schedule.nextDeliveryAfter(second.endExclusive()).orElseThrow();
        assertEquals(START.plus(Duration.ofMinutes(5)), finalDue);

        TimeRange last = schedule.deliveryWindow(finalDue, second.endExclusive());
        assertEquals(Duration.ofMinutes(1), last.duration());
        assertTrue(schedule.hasCompletedThrough(last.endExclusive()));
        assertTrue(schedule.nextDeliveryAfter(last.endExclusive()).isEmpty());
    }

    @Test
    void zeroDurationCreatesUnboundedPeriodicSchedule() {
        ReportSchedule schedule = schedule(START, Duration.ZERO);

        assertFalse(schedule.hasCompletedThrough(START.plus(Duration.ofDays(365))));
        assertTrue(schedule.nextDeliveryAfter(START.plus(Duration.ofMinutes(2))).isPresent());
    }

    private ReportSchedule schedule(Instant start, Duration requestedDuration) {
        return ReportSchedule.activate(
                new ValidatedReportRequest(
                        "REPORT-1",
                        "SPEC-1",
                        "TELEMETRY_USAGE",
                        "RESOURCE-1",
                        Set.of("RID-1"),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(2),
                        start,
                        requestedDuration,
                        false
                ),
                START
        );
    }
}
