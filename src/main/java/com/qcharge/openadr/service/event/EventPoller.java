package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bPollBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPoller {

    private final OpenAdrProperties properties;
    private final VtnTransportService transportService;
    private final DrEventHandler drEventHandler;

    @Scheduled(fixedDelayString = "${openadr.transport.poll-interval-seconds:10}")
    public void scheduledPoll() {
        log.debug("Starting poll cycle for venId: {}", properties.getVen().getId());
        pollUntilQueueEmpty();
    }

    /**
     * Conformance rule 500:
     * VEN SHOULD continue sending oadrPoll messages without waiting
     * until the next polling interval, until oadrResponse is returned
     * to signal an empty VTN message queue.
     */
    public void pollUntilQueueEmpty() {
        int maxIterations = 50;
        int iteration = 0;

        while (iteration++ < maxIterations) {
            try {
                Object response = sendPoll();
                boolean queueEmpty = handlePollResponse(response);
                if (queueEmpty) {
                    log.debug("VTN queue empty after {} polls", iteration);
                    return;
                }
            } catch (Exception e) {
                log.error("Poll failed on iteration {}: {}", iteration, e.getMessage());
                return;
            }
        }
        log.warn("Reached max poll iterations ({})", maxIterations);
    }

    private Object sendPoll() {
        String venId = properties.getVen().getId();
        OadrPollType pollPayload = Oadr20bPollBuilders
                .newOadr20bPollBuilder(venId)
                .build();

        return transportService.send(
                Oadr20bUrlPath.OADR_POLL_SERVICE, pollPayload);
    }

    /**
     * @return true if VTN queue is empty (received oadrResponse)
     */
    private boolean handlePollResponse(Object response) {
        switch (response) {
            case OadrDistributeEventType distributeEvent -> {
                log.info("Received OadrDistributeEvent with {} events", distributeEvent.getOadrEvent().size());
                drEventHandler.handle(distributeEvent);
                return false;

            }
            case OadrResponseType oadrResponse -> {
                String code = oadrResponse.getEiResponse().getResponseCode();
                if ("200".equals(code)) {
                    log.debug("Poll OK — queue empty");
                    return true;
                } else {
                    log.warn("Poll returned non-200: {}", code);
                    return true; //stop on error
                }
            }
            default -> {
                log.warn("Unexpected poll response type: {}", response.getClass().getName());
                return true; //stop on not known response
            }
        }
    }
}