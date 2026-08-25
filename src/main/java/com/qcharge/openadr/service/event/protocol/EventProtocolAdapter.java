package com.qcharge.openadr.service.event.protocol;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.oadr.ResponseRequiredType;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.model.enums.event.EventOptType;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import com.qcharge.openadr.service.event.processing.EventCancellationService;
import com.qcharge.openadr.service.event.processing.EventProcessingResult;
import com.qcharge.openadr.service.event.processing.EventProcessor;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.service.validation.EventEntryValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static com.qcharge.openadr.LogMessage.EVENT_ENTRY_DUPLICATED;
import static com.qcharge.openadr.LogMessage.EVENT_ENTRY_REJECTED_UNEXPECTEDLY;
import static com.qcharge.openadr.LogMessage.EVENT_RESPONSE_SKIPPED_INVALID_IDENTITY;
import static com.qcharge.openadr.LogMessage.IGNORE_EMPTY_EVENT_ENTRY;
import static com.qcharge.openadr.LogMessage.EVENT_ENTRY_REJECTED;

/** OpenADR XML boundary: converts oadrDistributeEvent into application commands and responses. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventProtocolAdapter {
    private final EventProcessor eventProcessor;
    private final EventCancellationService cancellationService;
    private final VtnTransportService transportService;
    private final EventEntryValidator eventEntryValidator;
    private final OpenAdrEventCommandMapper commandMapper;

    @Transactional
    public void receive(OadrDistributeEventType distributeEvent, OpenAdrSessionSnapshot session) {
        VtnEventLogger.logReceivedEvents(distributeEvent);
        String requestId = distributeEvent.getRequestID() != null ? distributeEvent.getRequestID() : "";

        // Rule 42: root requestID is empty when eventResponses are present.
        var eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder("", OpenADRResponseCode.OK)
                .build();
        var responseBuilder = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, session.venId());

        int responseCount = 0;
        Set<String> receivedEventIds = new HashSet<>();

        for (OadrEvent event : distributeEvent.getOadrEvent()) {
            if (event == null) {
                log.warn(IGNORE_EMPTY_EVENT_ENTRY, requestId);
                continue;
            }

            Optional<EventProcessingResult> result = processEvent(event, receivedEventIds, session.venId());

            if (!requiresResponse(event) || result.isEmpty()) {
                continue;
            }

            EventProcessingResult processedEvent = result.orElseThrow();

            EventResponse eventResponse =
                    Oadr20bEiEventBuilders
                            .newOadr20bCreatedEventEventResponseBuilder(
                                    processedEvent.eventId(),
                                    processedEvent.modificationNumber(),
                                    requestId,
                                    processedEvent.responseCode(),
                                    protocolOptType(processedEvent.optType())
                            )
                            .build();

            responseBuilder.addEventResponse(eventResponse);
            responseCount++;
        }

        cancellationService.reconcileSnapshot(receivedEventIds);
        if (responseCount == 0) {
            log.info("No oadrCreatedEvent sent because no correlatable event response was produced");
            return;
        }

        OadrCreatedEventType response = responseBuilder.build();
        transportService.send(OpenAdrOperations.CREATED_EVENT, response, session);
        log.info("Sent oadrCreatedEvent. eventResponses={}", responseCount);
    }

    private boolean requiresResponse(OadrEvent event) {
        return ResponseRequiredType.ALWAYS == event.getOadrResponseRequired();
    }

    private Optional<EventProcessingResult> processEvent(
            OadrEvent source, Set<String> receivedEventIds, String venId
    ) {
        try {
            eventEntryValidator.validate(source);

            EventDescriptorType descriptor = source.getEiEvent().getEventDescriptor();
            String eventId = descriptor.getEventID();
            long modificationNumber = descriptor.getModificationNumber();

            if (!receivedEventIds.add(eventId)) {
                log.warn(EVENT_ENTRY_DUPLICATED, eventId);
                return Optional.of(new EventProcessingResult(
                        eventId,
                        modificationNumber,
                        OpenADRResponseCode.INVALID_ID,
                        EventOptType.OPT_OUT
                ));
            }

            ReceiveEventCommand eventCommand = commandMapper.map(source);
            return Optional.of(eventProcessor.processSafely(eventCommand, venId));
        } catch (EventValidationException exception) {
            return processingFailure(source, exception);
        }
    }

    private Optional<EventProcessingResult> processingFailure(
            OadrEvent source, RuntimeException exception
    ) {
        String eventId = eventIdOf(source);
        Long modificationNumber = modificationNumberOf(source);
        int responseCode;

        if (exception instanceof EventValidationException validationException) {
            responseCode = validationException.getResponseCode();
            log.warn(EVENT_ENTRY_REJECTED, eventId, modificationNumber, responseCode, exception.getMessage());

        } else {
            responseCode = OpenADRResponseCode.INVALID_DATA;
            log.error(EVENT_ENTRY_REJECTED_UNEXPECTEDLY, eventId, modificationNumber, exception);
        }

        if (Strings.isBlank(eventId) || modificationNumber == null) {
            log.warn(EVENT_RESPONSE_SKIPPED_INVALID_IDENTITY, eventId, modificationNumber);
            return Optional.empty();
        }

        return Optional.of(new EventProcessingResult(
                eventId, modificationNumber, responseCode, EventOptType.OPT_OUT
        ));
    }

    private String eventIdOf(OadrEvent source) {
        EventDescriptorType descriptor = descriptorOf(source);
        return descriptor != null ? descriptor.getEventID() : null;
    }

    private Long modificationNumberOf(OadrEvent source) {
        EventDescriptorType descriptor = descriptorOf(source);
        return descriptor != null ? descriptor.getModificationNumber() : null;
    }

    private EventDescriptorType descriptorOf(OadrEvent source) {
        return source != null && source.getEiEvent() != null
                ? source.getEiEvent().getEventDescriptor()
                : null;
    }

    private OptTypeType protocolOptType(EventOptType optType) {
        return optType == EventOptType.OPT_OUT ? OptTypeType.OPT_OUT : OptTypeType.OPT_IN;
    }
}
