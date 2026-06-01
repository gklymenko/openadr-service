package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.integration.ocpp.OcppIntegrationService;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.EventValidationService.ParsedSignal;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class DrEventHandler {

    private final OpenAdrProperties properties;
    private final DrEventRepository drEventRepository;
    private final VtnTransportService transportService;
    private final EventOptDecisionService eventOptDecisionService;
    private final EventValidationService eventValidationService;
    private final OcppIntegrationService ocppIntegrationService;

    @Transactional
    public void handle(OadrDistributeEventType distributeEvent) {
        String venId = properties.getVen().getId();
        String distributeRequestId = distributeEvent.getRequestID();

        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder("", ApplicationLayerErrorCodes.OK)
                .build();

        var createdEventBuilder = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, venId);

        int eventResponseCount = 0;

        for (OadrEvent oadrEvent : distributeEvent.getOadrEvent()) {
            EventProcessingResult result = processEventSafely(oadrEvent);

            if (!requiresCreatedEventResponse(oadrEvent)) {
                continue;
            }

            EventResponse eventResponse = Oadr20bEiEventBuilders
                    .newOadr20bCreatedEventEventResponseBuilder(
                            result.eventId(),
                            result.modificationNumber(),
                            distributeRequestId,
                            result.responseCode(),
                            result.optType()
                    )
                    .build();

            createdEventBuilder.addEventResponse(eventResponse);
            eventResponseCount++;
        }

        if (eventResponseCount == 0) {
            log.info("No oadrCreatedEvent sent because no event required application response");
            return;
        }

        OadrCreatedEventType createdEvent = createdEventBuilder.build();

        transportService.createdEvent(createdEvent);

        log.info("Sent oadrCreatedEvent. eventResponses={}", eventResponseCount);
    }

    private EventProcessingResult processEventSafely(OadrEvent oadrEvent) {
        try {
            return processEvent(oadrEvent);
        } catch (Exception e) {
            EventDescriptorType descriptor = descriptorOf(oadrEvent);

            String eventId = descriptor != null ? descriptor.getEventID() : null;
            long modificationNumber = descriptor != null ? descriptor.getModificationNumber() : 0L;

            log.error(
                    "Failed to process OpenADR event. eventId={}, modificationNumber={}",
                    eventId,
                    modificationNumber,
                    e
            );

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.INVALID_DATA,
                    OptTypeType.OPT_OUT
            );
        }
    }

    private EventProcessingResult processEvent(OadrEvent oadrEvent) {
        EventDescriptorType descriptor = oadrEvent.getEiEvent().getEventDescriptor();

        String eventId = descriptor.getEventID();
        long modificationNumber = descriptor.getModificationNumber();

        log.info(
                "Processing OpenADR event. eventId={}, status={}, modificationNumber={}",
                eventId,
                descriptor.getEventStatus(),
                modificationNumber
        );

        DrEvent existingEvent = drEventRepository.findByEventId(eventId).orElse(null);

        if (isOutOfSequence(existingEvent, modificationNumber)) {
            log.warn(
                    "Out-of-sequence OpenADR event. eventId={}, currentModificationNumber={}, receivedModificationNumber={}",
                    eventId,
                    existingEvent.getModificationNumber(),
                    modificationNumber
            );

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.OUT_OF_SEQUENCE,
                    OptTypeType.OPT_OUT
            );
        }

        Optional<ParsedSignal> parsedSignal = eventValidationService.parseSignal(oadrEvent);

        if (parsedSignal.isEmpty()) {
            log.warn("Unsupported signal type in event. eventId={} → responding 460", eventId);
            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED,
                    OptTypeType.OPT_OUT
            );
        }

        ParsedSignal signal = parsedSignal.get();
        OptTypeType optType = eventOptDecisionService.determineOptType(oadrEvent, signal);

        saveOrUpdateEvent(oadrEvent, optType, signal);
        ocppIntegrationService.applySignal(eventId, signal);

        return new EventProcessingResult(
                eventId,
                modificationNumber,
                ApplicationLayerErrorCodes.OK,
                optType
        );
    }

    private boolean isOutOfSequence(DrEvent existingEvent, long receivedModificationNumber) {
        return existingEvent != null
                && existingEvent.getModificationNumber() != null
                && receivedModificationNumber < existingEvent.getModificationNumber();
    }

    private void saveOrUpdateEvent(OadrEvent oadrEvent, OptTypeType optType, ParsedSignal signal) {
        EventDescriptorType descriptor = oadrEvent.getEiEvent().getEventDescriptor();
        String eventId = descriptor.getEventID();

        DrEvent drEvent = drEventRepository.findByEventId(eventId)
                .orElseGet(DrEvent::new);

        drEvent.setEventId(eventId);
        drEvent.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        drEvent.setStatus(mapStatus(descriptor));
        drEvent.setOptType(mapOptType(optType));
        drEvent.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        drEvent.setStartTime(extractStartTime(oadrEvent));
        drEvent.setDurationSeconds(extractDurationSeconds(oadrEvent));
        drEvent.setSignalName(signal.signalName());
        drEvent.setSignalType(signal.signalType());
        drEvent.setSignalValue(signal.currentValue());
        drEvent.setUpdatedAt(nowUtc());

        if (drEvent.getCreatedAt() == null) {
            drEvent.setCreatedAt(nowUtc());
        }

        drEventRepository.save(drEvent);
    }

    private boolean requiresCreatedEventResponse(OadrEvent oadrEvent) {
        Object responseRequired = oadrEvent.getOadrResponseRequired();

        if (responseRequired == null) {
            return false;
        }

        return "always".equalsIgnoreCase(String.valueOf(responseRequired))
                || "ALWAYS".equalsIgnoreCase(String.valueOf(responseRequired));
    }

    private DrEvent.EventStatus mapStatus(EventDescriptorType descriptor) {
        String status = descriptor.getEventStatus().value();

        return switch (status.toUpperCase()) {
            case "FAR" -> DrEvent.EventStatus.FAR;
            case "NEAR" -> DrEvent.EventStatus.NEAR;
            case "ACTIVE" -> DrEvent.EventStatus.ACTIVE;
            case "COMPLETED" -> DrEvent.EventStatus.COMPLETED;
            case "CANCELLED" -> DrEvent.EventStatus.CANCELLED;
            default -> {
                log.warn("Unsupported eventStatus='{}'. Defaulting to FAR.", status);
                yield DrEvent.EventStatus.FAR;
            }
        };
    }

    private DrEvent.OptType mapOptType(OptTypeType optType) {
        return optType == OptTypeType.OPT_OUT
                ? DrEvent.OptType.OPT_OUT
                : DrEvent.OptType.OPT_IN;
    }

    private LocalDateTime extractStartTime(OadrEvent oadrEvent) {
        try {
            XMLGregorianCalendar dateTime = oadrEvent
                    .getEiEvent()
                    .getEiActivePeriod()
                    .getProperties()
                    .getDtstart()
                    .getDateTime();

            LocalDateTime dtstart = LocalDateTime.ofInstant(
                    dateTime.toGregorianCalendar().toInstant(), ZoneOffset.UTC);

            String startAfter = null;
            try {
                var tolerance = oadrEvent.getEiEvent()
                        .getEiActivePeriod()
                        .getProperties()
                        .getTolerance();

                if (tolerance != null
                        && tolerance.getTolerate() != null) {
                    startAfter = tolerance.getTolerate().getStartafter();
                }
            } catch (Exception e) {
                log.warn("Could not read tolerance/startafter. Skipping Rule 30 randomization.", e);
            }

            return applyStartAfterJitter(dtstart, startAfter);

        } catch (Exception e) {
            log.warn("Could not extract event start time. Falling back to current UTC time.", e);
            return nowUtc();
        }
    }

    /**
     * Rule 30: randomize actual start within [dtstart, dtstart + startafter].
     * Returns dtstart unchanged if startafter is null, blank, or zero.
     */
    public static LocalDateTime applyStartAfterJitter(LocalDateTime dtstart, String startAfter) {
        if (startAfter == null || startAfter.isBlank()) {
            return dtstart;
        }

        try {
            long startAfterSeconds = Duration.parse(startAfter).getSeconds();

            if (startAfterSeconds <= 0) {
                return dtstart;
            }

            long offsetSeconds = ThreadLocalRandom.current().nextLong(0, startAfterSeconds + 1);
            LocalDateTime randomized = dtstart.plusSeconds(offsetSeconds);

            log.debug("Rule 30: randomized dtstart from {} to {} (startafter={})",
                    dtstart, randomized, startAfter);

            return randomized;
        } catch (Exception e) {
            log.warn("Could not apply Rule 30 startafter randomization (startafter={}). Using dtstart.", startAfter, e);
            return dtstart;
        }
    }

    private Long extractDurationSeconds(OadrEvent oadrEvent) {
        try {
            var duration = oadrEvent
                    .getEiEvent()
                    .getEiActivePeriod()
                    .getProperties()
                    .getDuration();

            if (duration == null || duration.getDuration() == null) {
                return null;
            }

            String value = duration.getDuration();

            if ("0".equals(value)) {
                return 0L;
            }

            return Duration.parse(value).getSeconds();
        } catch (Exception e) {
            log.warn("Could not extract event duration. durationSeconds will be null.", e);
            return null;
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record EventProcessingResult(
            String eventId,
            long modificationNumber,
            int responseCode,
            OptTypeType optType
    ) {
    }

    private EventDescriptorType descriptorOf(OadrEvent oadrEvent) {
        if (oadrEvent == null || oadrEvent.getEiEvent() == null) {
            return null;
        }

        return oadrEvent.getEiEvent().getEventDescriptor();
    }
}
