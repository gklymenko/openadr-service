package com.qcharge.openadr.service.event.protocol;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.enums.event.EventOptType;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.oadr.ResponseRequiredType;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import com.qcharge.openadr.service.event.processing.EventBatchProcessor;
import com.qcharge.openadr.service.event.processing.EventProcessingResult;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.service.validation.EventEntryValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.qcharge.openadr.LogMessage.EVENT_ENTRY_DUPLICATED;
import static com.qcharge.openadr.LogMessage.EVENT_ENTRY_REJECTED;
import static com.qcharge.openadr.LogMessage.EVENT_RESPONSE_SKIPPED_INVALID_IDENTITY;
import static com.qcharge.openadr.LogMessage.IGNORE_EMPTY_EVENT_ENTRY;

/**
 * OpenADR XML boundary: converts oadrDistributeEvent into application commands and responses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventProtocolAdapter {
    private final EventBatchProcessor eventBatchProcessor;
    private final VtnTransportService transportService;
    private final EventEntryValidator eventEntryValidator;
    private final OpenAdrEventCommandMapper commandMapper;

    public void receive(OadrDistributeEventType distributeEvent, OpenAdrSessionSnapshot session) {
        VtnEventLogger.logReceivedEvents(distributeEvent);
        String requestId = distributeEvent.getRequestID() != null ? distributeEvent.getRequestID() : "";

        List<PreparedEvent> preparedEvents = new ArrayList<>();
        Set<String> receivedEventIds = new HashSet<>();
        Set<String> acceptedEventIds = new HashSet<>();

        // Rules 19 and 48: reject malformed entries individually before opening the snapshot
        // transaction; valid siblings must still be processed and acknowledged.
        for (OadrEvent event : distributeEvent.getOadrEvent()) {
            if (event == null) {
                log.warn(IGNORE_EMPTY_EVENT_ENTRY, requestId);
                continue;
            }

            preparedEvents.add(prepareEvent(event, receivedEventIds, acceptedEventIds));
        }

        List<ReceiveEventCommand> commands = preparedEvents.stream()
                .map(PreparedEvent::command)
                .flatMap(Optional::stream)
                .toList();

        List<EventProcessingResult> processedEvents = eventBatchProcessor.process(
                commands,
                receivedEventIds,
                session.venId()
        );

        Map<String, EventProcessingResult> processedByEventId =
                processedEvents.stream()
                        .collect(Collectors.toMap(
                                EventProcessingResult::eventId,
                                Function.identity()
                        ));

        if (processedByEventId.size() != commands.size()) {
            throw new IllegalStateException(
                    "Expected %d processing results but received %d"
                            .formatted(commands.size(), processedByEventId.size())
            );
        }

        List<EventResponse> eventResponses = preparedEvents.stream()
                .filter(PreparedEvent::responseRequired)
                .map(prepared -> prepared.result(processedByEventId))
                .flatMap(Optional::stream)
                .map(result -> eventResponse(result, requestId))
                .toList();

        if (eventResponses.isEmpty()) {
            log.info(
                    "No oadrCreatedEvent sent because no correlatable event response was produced"
            );
            return;
        }

        // Rule 42: root requestID is empty when eventResponses are present.
        var eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder("", OpenADRResponseCode.OK)
                .build();

        var responseBuilder = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, session.venId());

        eventResponses.forEach(responseBuilder::addEventResponse);

        transportService.send(
                OpenAdrOperations.CREATED_EVENT,
                responseBuilder.build(),
                session
        );

        log.info("Sent oadrCreatedEvent. eventResponses={}", eventResponses.size());
    }

    private PreparedEvent prepareEvent(
            OadrEvent source,
            Set<String> receivedEventIds,
            Set<String> acceptedEventIds
    ) {
        String eventId = eventIdOf(source);
        if (Strings.isNotBlank(eventId)) {
            // Rule 61: an identifiable entry is present in the snapshot even when its new
            // version is rejected; rejecting an update must not imply-cancel the stored event.
            receivedEventIds.add(eventId);
        }

        try {
            eventEntryValidator.validate(source);

            EventDescriptorType descriptor = source.getEiEvent().getEventDescriptor();
            long modificationNumber = descriptor.getModificationNumber();

            if (!acceptedEventIds.add(eventId)) {
                log.warn(EVENT_ENTRY_DUPLICATED, eventId);
                return PreparedEvent.rejected(source, new EventProcessingResult(
                        eventId,
                        modificationNumber,
                        OpenADRResponseCode.INVALID_ID,
                        EventOptType.OPT_OUT
                ));
            }

            ReceiveEventCommand eventCommand = commandMapper.map(source);
            return PreparedEvent.valid(source, eventCommand);
        } catch (EventValidationException exception) {
            return PreparedEvent.rejected(source, processingFailure(source, exception));
        }
    }

    private Optional<EventProcessingResult> processingFailure(
            OadrEvent source, EventValidationException exception
    ) {
        String eventId = eventIdOf(source);
        Long modificationNumber = modificationNumberOf(source);
        int responseCode = exception.getResponseCode();
        log.warn(
                EVENT_ENTRY_REJECTED,
                eventId,
                modificationNumber,
                responseCode,
                exception.getMessage()
        );

        if (Strings.isBlank(eventId) || modificationNumber == null) {
            log.warn(EVENT_RESPONSE_SKIPPED_INVALID_IDENTITY, eventId, modificationNumber);
            return Optional.empty();
        }

        return Optional.of(new EventProcessingResult(
                eventId, modificationNumber, responseCode, EventOptType.OPT_OUT
        ));
    }

    private EventResponse eventResponse(EventProcessingResult processedEvent, String requestId) {
        return Oadr20bEiEventBuilders
                .newOadr20bCreatedEventEventResponseBuilder(
                        processedEvent.eventId(),
                        processedEvent.modificationNumber(),
                        requestId,
                        processedEvent.responseCode(),
                        protocolOptType(processedEvent.optType())
                )
                .build();
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

    private record PreparedEvent(
            Optional<ReceiveEventCommand> command,
            Optional<EventProcessingResult> rejection,
            boolean responseRequired
    ) {
        private static PreparedEvent valid(OadrEvent source, ReceiveEventCommand command) {
            return new PreparedEvent(
                    Optional.of(command),
                    Optional.empty(),
                    ResponseRequiredType.ALWAYS == source.getOadrResponseRequired()
            );
        }

        private static PreparedEvent rejected(
                OadrEvent source,
                EventProcessingResult rejection
        ) {
            return rejected(source, Optional.of(rejection));
        }

        private static PreparedEvent rejected(
                OadrEvent source,
                Optional<EventProcessingResult> rejection
        ) {
            return new PreparedEvent(
                    Optional.empty(),
                    rejection,
                    ResponseRequiredType.ALWAYS == source.getOadrResponseRequired()
            );
        }

        private Optional<EventProcessingResult> result(
                Map<String, EventProcessingResult> processedByEventId
        ) {
            if (rejection.isPresent()) {
                return rejection;
            }

            return command.map(event -> Objects.requireNonNull(
                    processedByEventId.get(event.eventId()),
                    () -> "Missing processing result for eventId=" + event.eventId()
            ));
        }

    }
}
