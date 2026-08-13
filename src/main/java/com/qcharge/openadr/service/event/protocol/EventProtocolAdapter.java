package com.qcharge.openadr.service.event.protocol;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.service.event.VtnEventLogger;
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

/** OpenADR XML boundary: converts oadrDistributeEvent into application commands and responses. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventProtocolAdapter {

    private static final String RESPONSE_REQUIRED_ALWAYS = "always";

    private final EventProcessor eventProcessor;
    private final EventCancellationService cancellationService;
    private final VtnTransportService transportService;

    @Transactional
    public void receive(OadrDistributeEventType distributeEvent, OpenAdrSessionSnapshot session) {
        VtnEventLogger.logReceivedEvents(distributeEvent);
        String requestId = distributeEvent.getRequestID() != null ? distributeEvent.getRequestID() : "";

        // Rule 42: root requestID is empty when eventResponses are present.
        var eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder("", ApplicationLayerErrorCodes.OK)
                .build();
        var responseBuilder = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, session.venId());

        int responseCount = 0;
        Set<String> receivedEventIds = new HashSet<>();

        for (OadrEvent event : distributeEvent.getOadrEvent()) {
            EventProcessingResult result = eventProcessor.processSafely(
                    event, receivedEventIds, session.venId()
            );
            if (!requiresResponse(event)) {
                continue;
            }
            EventResponse eventResponse = Oadr20bEiEventBuilders
                    .newOadr20bCreatedEventEventResponseBuilder(
                            result.eventId(), result.modificationNumber(), requestId,
                            result.responseCode(), result.optType())
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
        return event != null
                && event.getOadrResponseRequired() != null
                && RESPONSE_REQUIRED_ALWAYS.equalsIgnoreCase(
                        String.valueOf(event.getOadrResponseRequired()));
    }
}
