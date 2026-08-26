package com.qcharge.openadr.service.report.model;

import com.qcharge.openadr.utility.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportIntervalPlannerTest {

    private final ReportIntervalPlanner planner = new ReportIntervalPlanner();

    @Test
    void splitsReportBackWindowByGranularity() {
        Instant start = Instant.parse("2026-08-26T00:00:00Z");

        List<TimeRange> intervals = planner.split(
                TimeRange.of(start, Duration.ofMinutes(2)),
                Duration.ofMinutes(1)
        );

        assertEquals(2, intervals.size());
        assertEquals(start, intervals.getFirst().start());
        assertEquals(start.plusSeconds(60), intervals.get(1).start());
        assertEquals(start.plusSeconds(120), intervals.getLast().endExclusive());
    }

    @Test
    void keepsShortFinalIntervalInsteadOfExtendingPastRequestedDuration() {
        Instant start = Instant.parse("2026-08-26T00:00:00Z");

        List<TimeRange> intervals = planner.split(
                TimeRange.of(start, Duration.ofSeconds(90)),
                Duration.ofMinutes(1)
        );

        assertEquals(2, intervals.size());
        assertEquals(Duration.ofSeconds(30), intervals.getLast().duration());
    }
}
