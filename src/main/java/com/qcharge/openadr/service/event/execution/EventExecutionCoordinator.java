package com.qcharge.openadr.service.event.execution;

import com.qcharge.openadr.model.enums.event.EventCancellationType;
import com.qcharge.openadr.model.enums.event.EventExecutionStatus;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.entity.DrEventInterval;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.service.event.store.EventService;
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

    private static final EnumSet<EventExecutionStatus> RECOVERABLE_STATUSES = EnumSet.of(
            EventExecutionStatus.SCHEDULED,
            EventExecutionStatus.APPLIED,
            EventExecutionStatus.CANCEL_PENDING
    );

    private final EventService eventService;
    private final EventExecutionPort executionPort;
    private final EventTimelineCalculator timeline;

    @Transactional
    public void processAt(Instant now) {
        List<DrEvent> events = eventService.findByExecutionStatusIn(RECOVERABLE_STATUSES);
        events.forEach(event -> processSafely(event, now));
    }

    /** Removes every persisted event and any downstream effect owned by this registration. */
    public void clearDownstreamForRegistrationCancellation() {
        List<DrEvent> events = eventService.findAll();

        events.stream()
                .filter(this::hasAppliedDownstreamEffect)
                .forEach(event -> executionPort.clearEvent(
                        event.getEventId(),
                        EventExecutionPort.ClearReason.REGISTRATION_CANCELLED
                ));

        log.info(
                "Cleared downstream effects for {} OpenADR event(s) after registration cancellation",
                events.size()
        );
    }

    private boolean hasAppliedDownstreamEffect(DrEvent event) {
        return !event.isTestEvent()
                && (event.getLastAppliedInterval() >= 0 || event.getAppliedAt() != null)
                && event.getExecutionStatus() != EventExecutionStatus.COMPLETED
                && event.getExecutionStatus() != EventExecutionStatus.CANCELLED;
    }

    private void processSafely(DrEvent event, Instant now) {
        try {
            advance(event, now);
        } catch (RuntimeException exception) {
            if (event.getExecutionStatus() == EventExecutionStatus.CANCEL_PENDING) {
                log.error("OpenADR cancellation termination failed and will be retried. eventId={}",
                        event.getEventId(), exception);
                return;
            }
            event.setExecutionStatus(EventExecutionStatus.FAILED);
            eventService.save(event);
            log.error("OpenADR event lifecycle execution failed. eventId={}",
                    event.getEventId(), exception);
        }
    }

    private void advance(DrEvent event, Instant now) {
        if (event.getExecutionStatus() == EventExecutionStatus.CANCEL_PENDING
                && !now.isBefore(event.getCancellationEffectiveAt())) {
            terminateCancellation(event, now);
            return;
        }

        EventStatus calculatedStatus = timeline.statusAt(event, now);
        boolean statusChanged = event.getVenStatus() != calculatedStatus;
        if (statusChanged) {
            log.info("OpenADR event status transition. eventId={}, from={}, to={}",
                    event.getEventId(), event.getVenStatus(), calculatedStatus);
            event.setVenStatus(calculatedStatus);
        }
        if (calculatedStatus == EventStatus.COMPLETED) {
            complete(event, now);
            return;
        }
        if (calculatedStatus != EventStatus.ACTIVE) {
            if (statusChanged) {
                eventService.save(event);
            }
            return;
        }

        DrEventSignal signal = timeline.selectedSignal(event);
        int intervalIndex = timeline.activeIntervalIndex(event, signal, now);
        boolean cancellationPending = event.getExecutionStatus() == EventExecutionStatus.CANCEL_PENDING;
        if ((event.getExecutionStatus() == EventExecutionStatus.APPLIED || cancellationPending)
                && event.getLastAppliedInterval() == intervalIndex) {
            return;
        }

        DrEventInterval interval = signal.getIntervals().get(intervalIndex);
        if (event.isTestEvent()) {
            log.info("Skipping operational signal application for OpenADR test event. "
                            + "eventId={}, signalId={}, intervalUid={}",
                    event.getEventId(), signal.getSignalId(), interval.getIntervalUid());
        } else {
            executionPort.applyInterval(new EventIntervalExecution(
                    event.getEventId(), event.getModificationNumber(), signal.getSignalId(),
                    interval.getIntervalUid(), signal.getSignalName(), signal.getSignalType(),
                    interval.getPayloadValue(), signal.getItemUnits(), signal.getSiScaleCode(),
                    intervalIndex, timeline.intervalStart(event, signal, intervalIndex)));
        }

        event.setExecutionStatus(cancellationPending
                ? EventExecutionStatus.CANCEL_PENDING : EventExecutionStatus.APPLIED);
        event.setLastAppliedInterval(intervalIndex);
        event.setAppliedAt(now);
        eventService.save(event);
    }

    private void terminateCancellation(DrEvent event, Instant now) {
        if (!event.isTestEvent() && (event.getLastAppliedInterval() >= 0 || event.getAppliedAt() != null)) {
            EventExecutionPort.ClearReason reason =
                    event.getCancellationType() == EventCancellationType.IMPLICIT
                            ? EventExecutionPort.ClearReason.IMPLICIT_CANCELLATION
                            : EventExecutionPort.ClearReason.CANCELLED;
            executionPort.clearEvent(event.getEventId(), reason);
        }
        event.setVenStatus(EventStatus.CANCELLED);
        event.setVtnStatus(EventStatus.CANCELLED);
        event.setExecutionStatus(EventExecutionStatus.CANCELLED);
        event.setCompletedAt(now);
        eventService.save(event);
    }

    private void complete(DrEvent event, Instant now) {
        if (!event.isTestEvent() && (event.getLastAppliedInterval() >= 0 || event.getAppliedAt() != null)) {
            executionPort.clearEvent(event.getEventId(), EventExecutionPort.ClearReason.COMPLETED);
        }
        event.setExecutionStatus(EventExecutionStatus.COMPLETED);
        event.setCompletedAt(now);
        eventService.save(event);
    }
}
