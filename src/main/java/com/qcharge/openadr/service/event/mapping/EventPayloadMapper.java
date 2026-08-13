package com.qcharge.openadr.service.event.mapping;

import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.entity.DrEventResource;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.service.event.EventValidationService.ParsedSignal;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedResource;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Duration;
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
            OadrEvent source,
            OptTypeType optType,
            List<ParsedSignal> signals,
            String selectedSignalId,
            List<ResolvedResource> resources
    ) {
        EventDescriptorType descriptor = source.getEiEvent().getEventDescriptor();
        EventTiming timing = timing(source, target);

        target.setEventId(descriptor.getEventID());
        target.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        DrEvent.EventStatus status = status(descriptor);
        target.setStatus(status);
        target.setVtnStatus(status);
        target.setOptType(optType(optType));
        target.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        target.setTestEvent(isTestEvent(descriptor));
        applyTiming(target, timing);
        target.setDurationSeconds(duration(source));
        target.setExecutionStatus(optType == OptTypeType.OPT_IN
                ? DrEvent.ExecutionStatus.SCHEDULED
                : DrEvent.ExecutionStatus.RECEIVED);
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

    public void initializeTerminalEvent(DrEvent target, OadrEvent source) {
        EventTiming timing = timing(source, null);
        applyTiming(target, timing);
        target.setDurationSeconds(duration(source));
    }

    public EventTiming timing(OadrEvent event, DrEvent existingEvent) {
        var properties = event.getEiEvent().getEiActivePeriod().getProperties();
        XMLGregorianCalendar dateTime = properties.getDtstart().getDateTime();
        Instant requestedStart = OpenAdrTimeUtils.fromXmlDateTime(dateTime);
        String startAfter = properties.getTolerance() != null
                && properties.getTolerance().getTolerate() != null
                ? properties.getTolerance().getTolerate().getStartafter()
                : null;
        long startAfterSeconds = durationSeconds(startAfter, 0L);
        if (startAfterSeconds < 0) {
            throw new IllegalArgumentException("startafter must not be negative");
        }

        long offset = existingEvent != null
                && Objects.equals(existingEvent.getStartAfterSeconds(), startAfterSeconds)
                ? existingEvent.getRandomOffsetSeconds()
                : randomOffset(startAfterSeconds);

        return new EventTiming(
                requestedStart,
                startAfterSeconds,
                offset,
                requestedStart.plusSeconds(offset),
                durationSeconds(properties.getXEiRampUp() != null
                        ? properties.getXEiRampUp().getDuration() : null, null),
                durationSeconds(properties.getXEiRecovery() != null
                        ? properties.getXEiRecovery().getDuration() : null, null)
        );
    }

    public DrEvent.EventStatus status(EventDescriptorType descriptor) {
        if (descriptor.getEventStatus() == null || descriptor.getEventStatus().value() == null) {
            throw new IllegalArgumentException("eventStatus is required");
        }
        return switch (descriptor.getEventStatus().value().toUpperCase()) {
            case "FAR" -> DrEvent.EventStatus.FAR;
            case "NEAR" -> DrEvent.EventStatus.NEAR;
            case "ACTIVE" -> DrEvent.EventStatus.ACTIVE;
            case "COMPLETED" -> DrEvent.EventStatus.COMPLETED;
            case "CANCELLED" -> DrEvent.EventStatus.CANCELLED;
            default -> throw new IllegalArgumentException(
                    "Unsupported eventStatus: " + descriptor.getEventStatus().value());
        };
    }

    public boolean isTestEvent(EventDescriptorType descriptor) {
        String value = descriptor.getTestEvent();
        return value != null && !"false".equals(value);
    }

    private List<DrEventSignal> toSignals(List<ParsedSignal> sources, String selectedSignalId) {
        return IntStream.range(0, sources.size()).mapToObj(sequence -> {
            ParsedSignal source = sources.get(sequence);
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

    private DrEvent.OptType optType(OptTypeType value) {
        return value == OptTypeType.OPT_OUT ? DrEvent.OptType.OPT_OUT : DrEvent.OptType.OPT_IN;
    }

    private Long duration(OadrEvent event) {
        var value = event.getEiEvent().getEiActivePeriod().getProperties().getDuration();
        return value == null ? null : durationSeconds(value.getDuration(), null);
    }

    private Long durationSeconds(String value, Long defaultValue) {
        return OpenAdrTimeUtils.parseOpenAdrDuration(value)
                .map(Duration::getSeconds)
                .orElse(defaultValue);
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
