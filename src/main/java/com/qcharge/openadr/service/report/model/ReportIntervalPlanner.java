package com.qcharge.openadr.service.report.model;

import com.qcharge.openadr.utility.TimeRange;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Splits a delivery window into chronological granularity-sized intervals. */
@Component
public class ReportIntervalPlanner {

    public List<TimeRange> split(TimeRange window, Duration granularity) {
        if (granularity.isNegative()) {
            throw new IllegalArgumentException("granularity must not be negative");
        }
        if (granularity.isZero()) {
            return List.of(window);
        }

        List<TimeRange> intervals = new ArrayList<>();
        Instant intervalStart = window.start();
        while (intervalStart.isBefore(window.endExclusive())) {
            Instant candidateEnd = intervalStart.plus(granularity);
            Instant intervalEnd = candidateEnd.isBefore(window.endExclusive())
                    ? candidateEnd
                    : window.endExclusive();
            intervals.add(new TimeRange(intervalStart, intervalEnd));
            intervalStart = intervalEnd;
        }
        return List.copyOf(intervals);
    }
}
