package com.qcharge.openadr.service.event.execution;

import java.math.BigDecimal;
import java.time.Instant;

/** Stable application command passed to a downstream event execution adapter. */
public record EventIntervalExecution(
        String eventId,
        int modificationNumber,
        String signalId,
        String intervalUid,
        String signalName,
        String signalType,
        BigDecimal value,
        String units,
        String siScaleCode,
        int intervalIndex,
        Instant effectiveFrom
) {
}
