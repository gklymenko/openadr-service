package com.qcharge.openadr.service.report;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public record ValidatedReportRequest(
        String reportRequestId,
        String reportSpecifierId,
        String reportName,
        Set<String> requestedRids,
        Duration granularity,
        Duration reportBackDuration,
        Instant requestedStart,
        Duration requestedDuration,
        boolean metadata
) {
    public ValidatedReportRequest {
        requestedRids = Set.copyOf(requestedRids);
    }
}
