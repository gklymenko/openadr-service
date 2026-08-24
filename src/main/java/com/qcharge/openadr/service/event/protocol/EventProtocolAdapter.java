package com.qcharge.openadr.service.event.protocol;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.oadr.ResponseRequiredType;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.command.EventOptType;
import com.qcharge.openadr.service.event.processing.EventCancellationService;
import com.qcharge.openadr.service.event.processing.EventProcessingResult;
import com.qcharge.openadr.service.event.processing.EventProcessor;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static com.qcharge.openadr.LogMessage.IGNORE_EMPTY_EVENT_ENTRY;

/** OpenADR XML boundary: converts oadrDistributeEvent into application commands and responses. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventProtocolAdapter {
    private final EventProcessor eventProcessor;
    private final EventCancellationService cancellationService;
    private final VtnTransportService transportService;
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

            EventProcessingResult result = processEvent(event, receivedEventIds, session.venId());

            if (!requiresResponse(event)) {
                continue;
            }

            EventResponse eventResponse =
                    Oadr20bEiEventBuilders
                            .newOadr20bCreatedEventEventResponseBuilder(
                                    result.eventId(),
                                    result.modificationNumber(),
                                    requestId,
                                    result.responseCode(),
                                    protocolOptType(result.optType())
                            )
                            .build();

            responseBuilder.addEventResponse(eventResponse);
            responseCount++;
        }

        cancellationService.reconcileSnapshot(receivedEventIds);
        if (responseCount == 0) {
            log.info("No oadrCreatedEvent sent because no event required application response");
            return;
        }

        OadrCreatedEventType response = responseBuilder.build();
        transportService.send(OpenAdrOperations.CREATED_EVENT, response, session);
        log.info("Sent oadrCreatedEvent. eventResponses={}", responseCount);
    }

    private boolean requiresResponse(OadrEvent event) {
        return ResponseRequiredType.ALWAYS == event.getOadrResponseRequired();
    }

    private EventProcessingResult processEvent(
            OadrEvent source, Set<String> receivedEventIds, String venId
    ) {
        String eventId = commandMapper.eventIdOf(source);
        long modificationNumber = commandMapper.modificationNumberOf(source);
        if (eventId != null && !eventId.isBlank() && !receivedEventIds.add(eventId)) {
            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    OpenADRResponseCode.INVALID_ID,
                    EventOptType.OPT_OUT
            );
        }
        try {
            return eventProcessor.processSafely(commandMapper.map(source), venId);
        } catch (EventValidationException exception) {
            log.warn("OpenADR event mapping rejected. eventId={}, modificationNumber={}, "
                            + "responseCode={}, reason={}",
                    safeEventId(eventId), modificationNumber,
                    exception.getResponseCode(), exception.getMessage());
            return new EventProcessingResult(
                    safeEventId(eventId), modificationNumber,
                    exception.getResponseCode(), EventOptType.OPT_OUT);
        } catch (IllegalArgumentException exception) {
            return mappingFailure(eventId, modificationNumber, exception);
        } catch (RuntimeException exception) {
            log.error("Failed to map OpenADR event. eventId={}, modificationNumber={}",
                    safeEventId(eventId), modificationNumber, exception);
            return mappingFailure(eventId, modificationNumber, exception);
        }
    }

    private EventProcessingResult mappingFailure(
            String eventId,
            long modificationNumber,
            RuntimeException exception
    ) {
        log.warn("Invalid OpenADR event mapping. eventId={}, modificationNumber={}, reason={}",
                safeEventId(eventId), modificationNumber, exception.getMessage());
        return new EventProcessingResult(
                safeEventId(eventId), modificationNumber,
                OpenADRResponseCode.INVALID_DATA, EventOptType.OPT_OUT);
    }

    private String safeEventId(String eventId) {
        return eventId != null && !eventId.isBlank() ? eventId : "unknown";
    }

    private OptTypeType protocolOptType(EventOptType optType) {
        return optType == EventOptType.OPT_OUT ? OptTypeType.OPT_OUT : OptTypeType.OPT_IN;
    }
}
