package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.TargetMismatchException;
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
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DrEventHandler {

    private static final String RESPONSE_REQUIRED_ALWAYS = "always";

    private final OpenAdrProperties properties;
    private final DrEventRepository drEventRepository;
    private final VtnTransportService transportService;
    private final EventOptDecisionService eventOptDecisionService;
    private final EventValidationService eventValidationService;
    private final OcppIntegrationService ocppIntegrationService;
    private final ObjectProvider<RegistrationService> registrationServiceProvider;

    @Transactional
    public void handle(OadrDistributeEventType distributeEvent) {
        String venId = registrationServiceProvider.getObject().currentVenId();
        String distributeRequestId = safeRequestId(distributeEvent.getRequestID());

        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder(distributeRequestId, ApplicationLayerErrorCodes.OK)
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
        } catch (TargetMismatchException e) {
            EventDescriptorType descriptor = descriptorOf(oadrEvent);

            String eventId = descriptor != null && descriptor.getEventID() != null
                    ? descriptor.getEventID()
                    : "unknown";
            long modificationNumber = descriptor != null ? descriptor.getModificationNumber() : 0L;

            log.warn(
                    "OpenADR event target mismatch. eventId={}, modificationNumber={}, reason={}",
                    eventId,
                    modificationNumber,
                    e.getMessage()
            );

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.TARGET_MISMATCH,
                    OptTypeType.OPT_OUT
            );
        } catch (IllegalArgumentException e) {
            EventDescriptorType descriptor = descriptorOf(oadrEvent);

            String eventId = descriptor != null && descriptor.getEventID() != null
                    ? descriptor.getEventID()
                    : "unknown";
            long modificationNumber = descriptor != null ? descriptor.getModificationNumber() : 0L;

            log.warn(
                    "Invalid OpenADR event data. eventId={}, modificationNumber={}, reason={}",
                    eventId,
                    modificationNumber,
                    e.getMessage()
            );

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.INVALID_DATA,
                    OptTypeType.OPT_OUT
            );
        } catch (Exception e) {
            EventDescriptorType descriptor = descriptorOf(oadrEvent);

            String eventId = descriptor != null && descriptor.getEventID() != null
                    ? descriptor.getEventID()
                    : "unknown";
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
        EventDescriptorType descriptor = requireDescriptor(oadrEvent);

        String eventId = requireEventId(descriptor);
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

        eventValidationService.validateTargetAndMarketContext(oadrEvent);

        if (isCancelled(descriptor)) {
            saveCancelledEvent(oadrEvent);
            ocppIntegrationService.clearEvent(eventId);

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.OK,
                    OptTypeType.OPT_OUT
            );
        }

        if (isCompleted(descriptor)) {
            saveCompletedEvent(oadrEvent);
            ocppIntegrationService.clearEvent(eventId);

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.OK,
                    OptTypeType.OPT_OUT
            );
        }

        Optional<ParsedSignal> parsedSignal = eventValidationService.parseSignal(oadrEvent);

        if (parsedSignal.isEmpty()) {
            log.warn("Unsupported event signal. eventId={} -> responseCode={}", eventId,
                    ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED);

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

        if (optType == OptTypeType.OPT_IN) {
            ocppIntegrationService.applySignal(eventId, signal);
        } else {
            log.info("Event was not applied to OCPP because VEN opted out. eventId={}", eventId);
        }

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
        EventDescriptorType descriptor = requireDescriptor(oadrEvent);
        String eventId = requireEventId(descriptor);

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
        drEvent.setUpdatedAt(Instant.now());

        drEventRepository.save(drEvent);
    }

    private void saveCancelledEvent(OadrEvent oadrEvent) {
        EventDescriptorType descriptor = requireDescriptor(oadrEvent);
        String eventId = requireEventId(descriptor);

        DrEvent drEvent = drEventRepository.findByEventId(eventId)
                .orElseGet(DrEvent::new);

        drEvent.setEventId(eventId);
        drEvent.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        drEvent.setStatus(DrEvent.EventStatus.CANCELLED);
        drEvent.setOptType(DrEvent.OptType.OPT_OUT);
        drEvent.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        drEvent.setStartTime(extractStartTime(oadrEvent));
        drEvent.setDurationSeconds(extractDurationSeconds(oadrEvent));
        drEvent.setSignalName(null);
        drEvent.setSignalType(null);
        drEvent.setSignalValue(null);
        drEvent.setUpdatedAt(Instant.now());

        drEventRepository.save(drEvent);

        log.info("Saved cancelled OpenADR event. eventId={}", eventId);
    }

    private void saveCompletedEvent(OadrEvent oadrEvent) {
        EventDescriptorType descriptor = requireDescriptor(oadrEvent);
        String eventId = requireEventId(descriptor);

        DrEvent drEvent = drEventRepository.findByEventId(eventId)
                .orElseGet(DrEvent::new);

        drEvent.setEventId(eventId);
        drEvent.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        drEvent.setStatus(DrEvent.EventStatus.COMPLETED);
        drEvent.setOptType(DrEvent.OptType.OPT_OUT);
        drEvent.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        drEvent.setStartTime(extractStartTime(oadrEvent));
        drEvent.setDurationSeconds(extractDurationSeconds(oadrEvent));
        drEvent.setSignalName(null);
        drEvent.setSignalType(null);
        drEvent.setSignalValue(null);
        drEvent.setUpdatedAt(Instant.now());

        drEventRepository.save(drEvent);

        log.info("Saved completed OpenADR event. eventId={}", eventId);
    }

    private boolean requiresCreatedEventResponse(OadrEvent oadrEvent) {
        if (oadrEvent == null || oadrEvent.getOadrResponseRequired() == null) {
            return false;
        }

        return RESPONSE_REQUIRED_ALWAYS.equalsIgnoreCase(
                String.valueOf(oadrEvent.getOadrResponseRequired())
        );
    }

    private boolean isCancelled(EventDescriptorType descriptor) {
        return descriptor.getEventStatus() != null
                && DrEvent.EventStatus.CANCELLED.name()
                .equalsIgnoreCase(descriptor.getEventStatus().value());
    }

    private boolean isCompleted(EventDescriptorType descriptor) {
        return descriptor.getEventStatus() != null
                && DrEvent.EventStatus.COMPLETED.name()
                .equalsIgnoreCase(descriptor.getEventStatus().value());
    }

    private DrEvent.EventStatus mapStatus(EventDescriptorType descriptor) {
        if (descriptor.getEventStatus() == null || descriptor.getEventStatus().value() == null) {
            throw new IllegalArgumentException("eventStatus is required");
        }

        String status = descriptor.getEventStatus().value();

        return switch (status.toUpperCase()) {
            case "FAR" -> DrEvent.EventStatus.FAR;
            case "NEAR" -> DrEvent.EventStatus.NEAR;
            case "ACTIVE" -> DrEvent.EventStatus.ACTIVE;
            case "COMPLETED" -> DrEvent.EventStatus.COMPLETED;
            case "CANCELLED" -> DrEvent.EventStatus.CANCELLED;
            default -> throw new IllegalArgumentException("Unsupported eventStatus: " + status);
        };
    }

    private DrEvent.OptType mapOptType(OptTypeType optType) {
        return optType == OptTypeType.OPT_OUT
                ? DrEvent.OptType.OPT_OUT
                : DrEvent.OptType.OPT_IN;
    }

    private Instant extractStartTime(OadrEvent oadrEvent) {
        XMLGregorianCalendar dateTime = oadrEvent
                .getEiEvent()
                .getEiActivePeriod()
                .getProperties()
                .getDtstart()
                .getDateTime();

        Instant dtstart = OpenAdrTimeUtils.fromXmlDateTime(dateTime);

        String startAfter = null;

        var tolerance = oadrEvent
                .getEiEvent()
                .getEiActivePeriod()
                .getProperties()
                .getTolerance();

        if (tolerance != null && tolerance.getTolerate() != null) {
            startAfter = tolerance.getTolerate().getStartafter();
        }

        return OpenAdrTimeUtils.applyStartAfterJitter(dtstart, startAfter);
    }

    private Long extractDurationSeconds(OadrEvent oadrEvent) {
        var duration = oadrEvent
                .getEiEvent()
                .getEiActivePeriod()
                .getProperties()
                .getDuration();

        if (duration == null || duration.getDuration() == null) {
            return null;
        }

        return OpenAdrTimeUtils.parseOpenAdrDuration(duration.getDuration())
                .map(Duration::getSeconds)
                .orElse(null);
    }

    private EventDescriptorType requireDescriptor(OadrEvent oadrEvent) {
        EventDescriptorType descriptor = descriptorOf(oadrEvent);

        if (descriptor == null) {
            throw new IllegalArgumentException("eventDescriptor is required");
        }

        return descriptor;
    }

    private EventDescriptorType descriptorOf(OadrEvent oadrEvent) {
        if (oadrEvent == null || oadrEvent.getEiEvent() == null) {
            return null;
        }

        return oadrEvent.getEiEvent().getEventDescriptor();
    }

    private String requireEventId(EventDescriptorType descriptor) {
        if (descriptor.getEventID() == null || descriptor.getEventID().isBlank()) {
            throw new IllegalArgumentException("eventID is required");
        }

        return descriptor.getEventID();
    }

    private String safeRequestId(String requestId) {
        return requestId != null ? requestId : "";
    }

    private record EventProcessingResult(
            String eventId,
            long modificationNumber,
            int responseCode,
            OptTypeType optType
    ) {
    }
}