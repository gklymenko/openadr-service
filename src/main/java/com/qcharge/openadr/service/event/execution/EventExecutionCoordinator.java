package com.qcharge.openadr.service.event.execution;

import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.entity.DrEventInterval;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.service.event.store.EventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/** Coordinates persisted event state with the downstream execution adapter. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventExecutionCoordinator {

    private static final EnumSet<DrEvent.ExecutionStatus> RECOVERABLE_STATUSES = EnumSet.of(
            DrEvent.ExecutionStatus.SCHEDULED,
            DrEvent.ExecutionStatus.APPLIED,
            DrEvent.ExecutionStatus.CANCEL_PENDING
    );

    private final EventStore eventStore;
    private final EventExecutionPort executionPort;
    private final EventTimelineCalculator timeline;

    @Transactional
    public void processAt(Instant now) {
        List<DrEvent> events = eventStore.findByExecutionStatusIn(RECOVERABLE_STATUSES);
        events.forEach(event -> processSafely(event, now));
    }

    private void processSafely(DrEvent event, Instant now) {
        try {
            advance(event, now);
        } catch (RuntimeException exception) {
            if (event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCEL_PENDING) {
                log.error("OpenADR cancellation termination failed and will be retried. eventId={}",
                        event.getEventId(), exception);
                return;
            }
            event.setExecutionStatus(DrEvent.ExecutionStatus.FAILED);
            eventStore.save(event);
            log.error("OpenADR event lifecycle execution failed. eventId={}",
                    event.getEventId(), exception);
        }
    }

    private void advance(DrEvent event, Instant now) {
        if (event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCEL_PENDING
                && !now.isBefore(event.getCancellationEffectiveAt())) {
            terminateCancellation(event, now);
            return;
        }

        DrEvent.EventStatus calculatedStatus = timeline.statusAt(event, now);
        boolean statusChanged = event.getStatus() != calculatedStatus;
        if (statusChanged) {
            log.info("OpenADR event status transition. eventId={}, from={}, to={}",
                    event.getEventId(), event.getStatus(), calculatedStatus);
            event.setStatus(calculatedStatus);
        }
        if (calculatedStatus == DrEvent.EventStatus.COMPLETED) {
            complete(event, now);
            return;
        }
        if (calculatedStatus != DrEvent.EventStatus.ACTIVE) {
            if (statusChanged) {
                eventStore.save(event);
            }
            return;
        }

        DrEventSignal signal = timeline.selectedSignal(event);
        int intervalIndex = timeline.activeIntervalIndex(event, signal, now);
        boolean cancellationPending = event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCEL_PENDING;
        if ((event.getExecutionStatus() == DrEvent.ExecutionStatus.APPLIED || cancellationPending)
                && event.getLastAppliedInterval() == intervalIndex) {
            return;
        }

        DrEventInterval interval = signal.getIntervals().get(intervalIndex);
        if (event.isTestEvent()) {
            log.info("Skipping operational signal application for OpenADR test event. "
                            + "eventId={}, signalId={}, intervalUid={}",
                    event.getEventId(), signal.getSignalId(), interval.getIntervalUid());
        } else {
            executionPort.applyInterval(
                    event.getEventId(), event.getModificationNumber(), signal.getSignalId(),
                    interval.getIntervalUid(), signal.getSignalName(), signal.getSignalType(),
                    interval.getPayloadValue(), signal.getItemUnits(), signal.getSiScaleCode(),
                    intervalIndex, timeline.intervalStart(event, signal, intervalIndex));
        }

        event.setExecutionStatus(cancellationPending
                ? DrEvent.ExecutionStatus.CANCEL_PENDING : DrEvent.ExecutionStatus.APPLIED);
        event.setLastAppliedInterval(intervalIndex);
        event.setAppliedAt(now);
        eventStore.save(event);
    }

    private void terminateCancellation(DrEvent event, Instant now) {
        if (!event.isTestEvent() && (event.getLastAppliedInterval() >= 0 || event.getAppliedAt() != null)) {
            EventExecutionPort.ClearReason reason =
                    event.getCancellationType() == DrEvent.CancellationType.IMPLICIT
                            ? EventExecutionPort.ClearReason.IMPLICIT_CANCELLATION
                            : EventExecutionPort.ClearReason.CANCELLED;
            executionPort.clearEvent(event.getEventId(), reason);
        }
        event.setStatus(DrEvent.EventStatus.CANCELLED);
        event.setVtnStatus(DrEvent.EventStatus.CANCELLED);
        event.setExecutionStatus(DrEvent.ExecutionStatus.CANCELLED);
        event.setCompletedAt(now);
        eventStore.save(event);
    }

    private void complete(DrEvent event, Instant now) {
        if (!event.isTestEvent() && (event.getLastAppliedInterval() >= 0 || event.getAppliedAt() != null)) {
            executionPort.clearEvent(event.getEventId(), EventExecutionPort.ClearReason.COMPLETED);
        }
        event.setExecutionStatus(DrEvent.ExecutionStatus.COMPLETED);
        event.setCompletedAt(now);
        eventStore.save(event);
    }
}
