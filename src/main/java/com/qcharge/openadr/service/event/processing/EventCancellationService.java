package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.service.event.store.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Owns explicit and snapshot-based implicit cancellation state transitions. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventCancellationService {

    private static final EnumSet<DrEvent.ExecutionStatus> RECONCILABLE_STATUSES = EnumSet.of(
            DrEvent.ExecutionStatus.RECEIVED,
            DrEvent.ExecutionStatus.SCHEDULED,
            DrEvent.ExecutionStatus.APPLIED,
            DrEvent.ExecutionStatus.FAILED
    );

    private final EventService eventService;
    private final Clock clock;

    public void request(DrEvent event, DrEvent.CancellationType type) {
        if (event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCEL_PENDING
                || event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCELLED) {
            event.setVtnStatus(DrEvent.EventStatus.CANCELLED);
            if (event.getCancellationType() == null) {
                event.setCancellationType(type);
            }
            eventService.save(event);
            return;
        }

        Instant requestedAt = clock.instant();
        boolean active = event.getExecutionStatus() == DrEvent.ExecutionStatus.APPLIED
                || event.getLastAppliedInterval() >= 0
                || event.getAppliedAt() != null;
        long terminationOffset = active ? randomOffset(event.getStartAfterSeconds()) : 0L;

        event.setVtnStatus(DrEvent.EventStatus.CANCELLED);
        event.setCancellationType(type);
        event.setCancellationRequestedAt(requestedAt);
        event.setCancellationEffectiveAt(requestedAt.plusSeconds(terminationOffset));
        event.setUpdatedAt(requestedAt);

        if (active) {
            event.setExecutionStatus(DrEvent.ExecutionStatus.CANCEL_PENDING);
        } else {
            event.setStatus(DrEvent.EventStatus.CANCELLED);
            event.setExecutionStatus(DrEvent.ExecutionStatus.CANCELLED);
            event.setCompletedAt(requestedAt);
        }
        eventService.save(event);
    }

    /** Rule 61: reconcile the complete event snapshot delivered by the VTN. */
    public void reconcileSnapshot(Set<String> receivedEventIds) {
        List<DrEvent> knownEvents = eventService.findByExecutionStatusIn(RECONCILABLE_STATUSES);
        if (knownEvents == null) {
            return;
        }
        knownEvents.stream()
                .filter(event -> !receivedEventIds.contains(event.getEventId()))
                .forEach(event -> {
                    request(event, DrEvent.CancellationType.IMPLICIT);
                    log.info(
                            "Implicitly cancelled OpenADR event omitted from snapshot. eventId={}, effectiveAt={}",
                            event.getEventId(), event.getCancellationEffectiveAt());
                });
    }

    private long randomOffset(Long windowSeconds) {
        return windowSeconds == null || windowSeconds <= 0L
                ? 0L
                : ThreadLocalRandom.current().nextLong(windowSeconds + 1L);
    }
}
