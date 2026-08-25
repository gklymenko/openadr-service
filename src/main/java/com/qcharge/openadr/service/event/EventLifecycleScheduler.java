package com.qcharge.openadr.service.event;

import com.qcharge.openadr.service.event.execution.EventExecutionCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Time-based trigger only; lifecycle state changes are coordinated elsewhere. */
@Component
@RequiredArgsConstructor
public class EventLifecycleScheduler {

    private final EventExecutionCoordinator coordinator;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${openadr.event.scheduler-delay-millis:1000}")
    public void processDueEvents() {
        processAt(clock.instant());
    }

    void processAt(Instant now) {
        coordinator.processAt(now);
    }
}
