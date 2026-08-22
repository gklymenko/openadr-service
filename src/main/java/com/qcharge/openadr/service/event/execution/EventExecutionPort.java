package com.qcharge.openadr.service.event.execution;

/** Downstream port used by the VEN event application layer. */
public interface EventExecutionPort {

    void applyInterval(EventIntervalExecution execution);

    void clearEvent(String eventId, ClearReason reason);

    enum ClearReason {
        CANCELLED,
        COMPLETED,
        IMPLICIT_CANCELLATION
    }
}
