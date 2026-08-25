package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.model.enums.event.EventCancellationType;
import com.qcharge.openadr.model.enums.event.EventExecutionStatus;
import com.qcharge.openadr.model.enums.event.EventStatus;
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

    private static final EnumSet<EventExecutionStatus> RECONCILABLE_STATUSES = EnumSet.of(
            EventExecutionStatus.RECEIVED,
            EventExecutionStatus.SCHEDULED,
            EventExecutionStatus.APPLIED,
            EventExecutionStatus.FAILED
    );

    private final EventService eventService;
    private final Clock clock;

    public void request(DrEvent event, EventCancellationType type) {
        if (event.getExecutionStatus() == EventExecutionStatus.CANCEL_PENDING
                || event.getExecutionStatus() == EventExecutionStatus.CANCELLED) {
            event.setVtnStatus(EventStatus.CANCELLED);
            if (event.getCancellationType() == null) {
                event.setCancellationType(type);
            }
            eventService.save(event);
            return;
        }

        Instant requestedAt = clock.instant();
        boolean active = event.getExecutionStatus() == EventExecutionStatus.APPLIED
                || event.getLastAppliedInterval() >= 0
                || event.getAppliedAt() != null;
        long terminationOffset = active ? randomOffset(event.getStartAfterSeconds()) : 0L;

        event.setVtnStatus(EventStatus.CANCELLED);
        event.setCancellationType(type);
        event.setCancellationRequestedAt(requestedAt);
        event.setCancellationEffectiveAt(requestedAt.plusSeconds(terminationOffset));
        event.setUpdatedAt(requestedAt);

        if (active) {
            event.setExecutionStatus(EventExecutionStatus.CANCEL_PENDING);
        } else {
            event.setStatus(EventStatus.CANCELLED);
            event.setExecutionStatus(EventExecutionStatus.CANCELLED);
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
                    request(event, EventCancellationType.IMPLICIT);
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
