package com.qcharge.openadr.service.event.execution;

import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.entity.DrEventInterval;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.utility.TimeRange;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Pure timeline and interval selection rules; contains no persistence or OCPP effects. */
@Component
public class EventTimelineCalculator {

    public EventStatus statusAt(DrEvent event, Instant now) {
        Instant actualStart = event.getStartTime();
        long rampUpSeconds = event.getRampUpSeconds() != null
                ? Math.abs(event.getRampUpSeconds()) : 0L;
        Instant nearStart = actualStart.minusSeconds(rampUpSeconds);
        if (now.isBefore(nearStart)) {
            return EventStatus.FAR;
        }
        if (now.isBefore(actualStart)) {
            return rampUpSeconds > 0 ? EventStatus.NEAR : EventStatus.FAR;
        }
        Long durationSeconds = event.getDurationSeconds();
        if (durationSeconds == null || durationSeconds == 0L) {
            return EventStatus.ACTIVE;
        }
        TimeRange activePeriod = TimeRange.of(actualStart, Duration.ofSeconds(durationSeconds));
        return activePeriod.hasEndedAt(now) ? EventStatus.COMPLETED : EventStatus.ACTIVE;
    }

    public DrEventSignal selectedSignal(DrEvent event) {
        Optional<DrEventSignal> selected = event.getSignals().stream()
                .filter(DrEventSignal::isSelectedForExecution)
                .findFirst();
        if (selected.isPresent()) {
            return selected.get();
        }
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

    public int activeIntervalIndex(DrEvent event, DrEventSignal signal, Instant now) {
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
        if (event.getDurationSeconds() != null && event.getDurationSeconds() == 0L) {
            return signal.getIntervals().size() - 1;
        }
        throw new IllegalStateException("No active interval for event: " + event.getEventId());
    }

    public Instant intervalStart(DrEvent event, DrEventSignal signal, int intervalIndex) {
        long offsetSeconds = signal.getIntervals().stream()
                .limit(intervalIndex)
                .mapToLong(DrEventInterval::getDurationSeconds)
                .sum();
        return event.getStartTime().plusSeconds(offsetSeconds);
    }
}
