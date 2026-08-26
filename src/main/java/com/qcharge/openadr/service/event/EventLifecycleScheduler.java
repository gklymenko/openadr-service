package com.qcharge.openadr.service.event;

import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.enums.event.EventExecutionStatus;
import com.qcharge.openadr.service.event.execution.EventExecutionCoordinator;
import com.qcharge.openadr.service.event.store.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/** Time-based trigger only; lifecycle state changes are coordinated elsewhere. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventLifecycleScheduler {

    private final EventExecutionCoordinator coordinator;
    private final EventService eventService;
    private final Clock clock;

    private static final EnumSet<EventExecutionStatus> RECOVERABLE_STATUSES = EnumSet.of(
            EventExecutionStatus.SCHEDULED,
            EventExecutionStatus.APPLIED,
            EventExecutionStatus.CANCEL_PENDING
    );

    @Scheduled(fixedDelayString = "${openadr.event.scheduler-delay-millis:1000}")
    @Transactional
    public void processDueEvents() {
        List<DrEvent> events = eventService.findByExecutionStatusIn(RECOVERABLE_STATUSES);
        events.forEach(event -> processSafely(event, clock.instant()));
    }


    public void processSafely(DrEvent event, Instant now) {
        try {
            coordinator.process(event, now);

        } catch (RuntimeException exception) {
            if (event.getExecutionStatus() == EventExecutionStatus.CANCEL_PENDING) {
                log.error("OpenADR cancellation termination failed and will be retried. eventId={}",
                        event.getEventId(), exception);
                return;
            }
            event.setExecutionStatus(EventExecutionStatus.FAILED);
            eventService.save(event);
            log.error("OpenADR event lifecycle execution failed. eventId={}", event.getEventId(), exception);
        }
    }

}
