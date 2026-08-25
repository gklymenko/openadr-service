package com.qcharge.openadr.service.event.mapping;

import com.qcharge.openadr.model.enums.event.EventExecutionStatus;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.entity.DrEventResource;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.model.enums.event.EventOptType;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/** Builds the persisted event aggregate from a validated OpenADR payload. */
@Component
@RequiredArgsConstructor
public class EventPayloadMapper {

    private final EventEntityMapper entityMapper;
    private final Clock clock;

    public void applyExecutableEvent(
            DrEvent target,
            ReceiveEventCommand source,
            EventOptType optType,
            List<EventSignalCommand> signals,
            String selectedSignalId,
            List<ResolvedResource> resources
    ) {
        EventTiming timing = timing(source, target);

        target.setEventId(source.eventId());
        target.setModificationNumber(Math.toIntExact(source.modificationNumber()));
        EventStatus status = source.status();
        target.setVenStatus(status);
        target.setVtnStatus(status);
        target.setOptType(optType);
        target.setPriority(source.priority());
        target.setTestEvent(source.testEvent());
        applyTiming(target, timing);
        target.setDurationSeconds(source.timing().durationSeconds());
        target.setExecutionStatus(optType == EventOptType.OPT_IN
                ? EventExecutionStatus.SCHEDULED
                : EventExecutionStatus.RECEIVED);
        target.setLastAppliedInterval(-1);
        target.setAppliedAt(null);
        target.setCompletedAt(null);
        target.setCancellationType(null);
        target.setCancellationRequestedAt(null);
        target.setCancellationEffectiveAt(null);
        target.replaceSignals(toSignals(signals, selectedSignalId));
        target.replaceResources(toResources(resources));
        target.setUpdatedAt(clock.instant());
    }

    public void initializeTerminalEvent(DrEvent target, ReceiveEventCommand source) {
        EventTiming timing = timing(source, null);
        applyTiming(target, timing);
        target.setDurationSeconds(source.timing().durationSeconds());
    }

    public EventTiming timing(ReceiveEventCommand event, DrEvent existingEvent) {
        var source = event.timing();
        Instant requestedStart = source.requestedStartTime();
        long startAfterSeconds = source.startAfterSeconds();

        long offset = existingEvent != null
                && Objects.equals(existingEvent.getStartAfterSeconds(), startAfterSeconds)
                ? existingEvent.getRandomOffsetSeconds()
                : randomOffset(startAfterSeconds);

        return new EventTiming(
                requestedStart,
                startAfterSeconds,
                offset,
                requestedStart.plusSeconds(offset),
                source.rampUpSeconds(),
                source.recoverySeconds()
        );
    }

    private List<DrEventSignal> toSignals(List<EventSignalCommand> sources, String selectedSignalId) {
        return IntStream.range(0, sources.size()).mapToObj(sequence -> {
            EventSignalCommand source = sources.get(sequence);
            DrEventSignal signal = entityMapper.toSignal(source);
            signal.setSequenceNumber(sequence);
            signal.setSelectedForExecution(Objects.equals(source.signalId(), selectedSignalId));
            source.intervals().forEach(interval -> signal.addInterval(entityMapper.toInterval(interval)));
            return signal;
        }).toList();
    }

    private List<DrEventResource> toResources(List<ResolvedResource> sources) {
        return IntStream.range(0, sources.size()).mapToObj(sequence -> {
            DrEventResource resource = entityMapper.toResource(sources.get(sequence));
            resource.setSequenceNumber(sequence);
            return resource;
        }).toList();
    }

    private void applyTiming(DrEvent target, EventTiming timing) {
        target.setRequestedStartTime(timing.requestedStartTime());
        target.setStartAfterSeconds(timing.startAfterSeconds());
        target.setRandomOffsetSeconds(timing.randomOffsetSeconds());
        target.setStartTime(timing.actualStartTime());
        target.setRampUpSeconds(timing.rampUpSeconds());
        target.setRecoverySeconds(timing.recoverySeconds());
    }

    private long randomOffset(long windowSeconds) {
        return windowSeconds == 0L
                ? 0L
                : ThreadLocalRandom.current().nextLong(windowSeconds + 1L);
    }

    public record EventTiming(
            Instant requestedStartTime,
            long startAfterSeconds,
            long randomOffsetSeconds,
            Instant actualStartTime,
            Long rampUpSeconds,
            Long recoverySeconds
    ) {
    }
}
