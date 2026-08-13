package com.qcharge.openadr.service.event.execution;

import java.math.BigDecimal;
import java.time.Instant;

/** Downstream port used by the VEN event application layer. */
public interface EventExecutionPort {

    void applyInterval(
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
    );

    void clearEvent(String eventId, ClearReason reason);

    enum ClearReason {
        CANCELLED,
        COMPLETED,
        IMPLICIT_CANCELLATION
    }
}
