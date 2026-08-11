package com.qcharge.openadr.service.event;

import com.qcharge.openadr.integration.ocpp.OcppIntegrationService;
import com.qcharge.openadr.integration.ocpp.OcppIntegrationService.ClearReason;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.entity.DrEventInterval;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.repository.DrEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventLifecycleScheduler {

    private static final EnumSet<DrEvent.ExecutionStatus> RECOVERABLE_STATUSES = EnumSet.of(
            DrEvent.ExecutionStatus.SCHEDULED,
            DrEvent.ExecutionStatus.APPLIED,
            DrEvent.ExecutionStatus.CANCEL_PENDING
    );

    private final DrEventRepository drEventRepository;
    private final OcppIntegrationService ocppIntegrationService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${openadr.event.scheduler-delay-millis:1000}")
    @Transactional
    public void processDueEvents() {
        processAt(clock.instant());
    }

    void processAt(Instant now) {
        List<DrEvent> events = drEventRepository.findAllByExecutionStatusIn(RECOVERABLE_STATUSES);
        events.forEach(event -> processSafely(event, now));
    }

    private void processSafely(DrEvent event, Instant now) {
        try {
            advance(event, now);
        } catch (RuntimeException exception) {
            if (event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCEL_PENDING) {
                log.error(
                        "OpenADR cancellation termination failed and will be retried. eventId={}",
                        event.getEventId(), exception
                );
                return;
            }
            event.setExecutionStatus(DrEvent.ExecutionStatus.FAILED);
            drEventRepository.save(event);
            log.error("OpenADR event lifecycle execution failed. eventId={}", event.getEventId(), exception);
        }
    }

    private void advance(DrEvent event, Instant now) {
        if (event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCEL_PENDING
                && !now.isBefore(event.getCancellationEffectiveAt())) {
            terminateCancellation(event, now);
            return;
        }

        DrEvent.EventStatus calculatedStatus = calculateStatus(event, now);
        boolean statusChanged = event.getStatus() != calculatedStatus;
        if (statusChanged) {
            log.info(
                    "OpenADR event status transition. eventId={}, from={}, to={}",
                    event.getEventId(), event.getStatus(), calculatedStatus
            );
            event.setStatus(calculatedStatus);
        }

        if (calculatedStatus == DrEvent.EventStatus.COMPLETED) {
            complete(event, now);
            return;
        }

        if (calculatedStatus != DrEvent.EventStatus.ACTIVE) {
            if (statusChanged) {
                drEventRepository.save(event);
            }
            return;
        }

        DrEventSignal signal = selectedSignal(event);
        int intervalIndex = activeIntervalIndex(event, signal, now);
        boolean cancellationPending = event.getExecutionStatus()
                == DrEvent.ExecutionStatus.CANCEL_PENDING;
        if ((event.getExecutionStatus() == DrEvent.ExecutionStatus.APPLIED || cancellationPending)
                && event.getLastAppliedInterval() == intervalIndex) {
            return;
        }

        DrEventInterval interval = signal.getIntervals().get(intervalIndex);
        if (event.isTestEvent()) {
            log.info(
                    "Skipping operational signal application for OpenADR test event. "
                            + "eventId={}, signalId={}, intervalUid={}",
                    event.getEventId(), signal.getSignalId(), interval.getIntervalUid()
            );
        } else {
            ocppIntegrationService.applySignalInterval(
                    event.getEventId(),
                    event.getModificationNumber(),
                    signal.getSignalId(),
                    interval.getIntervalUid(),
                    signal.getSignalName(),
                    signal.getSignalType(),
                    interval.getPayloadValue(),
                    signal.getItemUnits(),
                    signal.getSiScaleCode(),
                    intervalIndex,
                    intervalStart(event, signal, intervalIndex)
            );
        }

        event.setExecutionStatus(cancellationPending
                ? DrEvent.ExecutionStatus.CANCEL_PENDING
                : DrEvent.ExecutionStatus.APPLIED);
        event.setLastAppliedInterval(intervalIndex);
        event.setAppliedAt(now);
        drEventRepository.save(event);
    }

    private void terminateCancellation(DrEvent event, Instant now) {
        if (!event.isTestEvent()
                && (event.getLastAppliedInterval() >= 0 || event.getAppliedAt() != null)) {
            ClearReason reason = event.getCancellationType() == DrEvent.CancellationType.IMPLICIT
                    ? ClearReason.IMPLICIT_CANCELLATION
                    : ClearReason.CANCELLED;
            ocppIntegrationService.clearEvent(event.getEventId(), reason);
        }
        event.setStatus(DrEvent.EventStatus.CANCELLED);
        event.setVtnStatus(DrEvent.EventStatus.CANCELLED);
        event.setExecutionStatus(DrEvent.ExecutionStatus.CANCELLED);
        event.setCompletedAt(now);
        drEventRepository.save(event);
        log.info(
                "OpenADR randomized cancellation completed. eventId={}, type={}, requestedAt={}, effectiveAt={}",
                event.getEventId(), event.getCancellationType(),
                event.getCancellationRequestedAt(), event.getCancellationEffectiveAt()
        );
    }

    private void complete(DrEvent event, Instant now) {
        if (!event.isTestEvent()
                && (event.getLastAppliedInterval() >= 0 || event.getAppliedAt() != null)) {
            ocppIntegrationService.clearEvent(event.getEventId(), ClearReason.COMPLETED);
        }
        event.setExecutionStatus(DrEvent.ExecutionStatus.COMPLETED);
        event.setCompletedAt(now);
        drEventRepository.save(event);
    }

    DrEvent.EventStatus calculateStatus(DrEvent event, Instant now) {
        Instant actualStart = event.getStartTime();
        long rampUpSeconds = event.getRampUpSeconds() != null
                ? Math.abs(event.getRampUpSeconds())
                : 0L;
        Instant nearStart = actualStart.minusSeconds(rampUpSeconds);

        if (now.isBefore(nearStart)) {
            return DrEvent.EventStatus.FAR;
        }
        if (now.isBefore(actualStart)) {
            return rampUpSeconds > 0
                    ? DrEvent.EventStatus.NEAR
                    : DrEvent.EventStatus.FAR;
        }

        Long durationSeconds = event.getDurationSeconds();
        if (durationSeconds == null || durationSeconds == 0L) {
            return DrEvent.EventStatus.ACTIVE;
        }

        return now.isBefore(actualStart.plusSeconds(durationSeconds))
                ? DrEvent.EventStatus.ACTIVE
                : DrEvent.EventStatus.COMPLETED;
    }

    private DrEventSignal selectedSignal(DrEvent event) {
        Optional<DrEventSignal> explicitlySelected = event.getSignals().stream()
                .filter(DrEventSignal::isSelectedForExecution)
                .findFirst();
        if (explicitlySelected.isPresent()) {
            return explicitlySelected.get();
        }

        // Recovery compatibility for events persisted before V3 introduced the marker.
        for (String preferredName : List.of("LOAD_DISPATCH", "ELECTRICITY_PRICE", "SIMPLE")) {
            Optional<DrEventSignal> recovered = event.getSignals().stream()
                    .filter(signal -> preferredName.equalsIgnoreCase(signal.getSignalName()))
                    .findFirst();
            if (recovered.isPresent()) {
                recovered.get().setSelectedForExecution(true);
                return recovered.get();
            }
        }

        throw new IllegalStateException(
                "Event has no supported signal for execution: " + event.getEventId());
    }

    private int activeIntervalIndex(DrEvent event, DrEventSignal signal, Instant now) {
        if (signal.getIntervals().isEmpty()) {
            throw new IllegalStateException("Selected signal has no intervals: " + signal.getSignalId());
        }

        long elapsedSeconds = Math.max(0L, now.getEpochSecond() - event.getStartTime().getEpochSecond());
        long intervalEnd = 0L;
        for (int index = 0; index < signal.getIntervals().size(); index++) {
            intervalEnd += signal.getIntervals().get(index).getDurationSeconds();
            if (elapsedSeconds < intervalEnd) {
                return index;
            }
        }

        // Rule 47: an open-ended event remains active until cancellation. Once its
        // finite interval plan is exhausted, keep the final requested value active.
        if (event.getDurationSeconds() != null && event.getDurationSeconds() == 0L) {
            return signal.getIntervals().size() - 1;
        }

        throw new IllegalStateException("No active interval for event: " + event.getEventId());
    }

    private Instant intervalStart(DrEvent event, DrEventSignal signal, int intervalIndex) {
        long offsetSeconds = signal.getIntervals().stream()
                .limit(intervalIndex)
                .mapToLong(DrEventInterval::getDurationSeconds)
                .sum();
        return event.getStartTime().plusSeconds(offsetSeconds);
    }
}
