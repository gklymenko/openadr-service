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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPoller {

    private final OpenAdrProperties properties;
    private final VtnTransportService transportService;
    private final DrEventHandler eventHandler;

    @Scheduled(fixedDelayString =
            "${openadr.transport.poll-interval-seconds:10}000")
    public void poll() {
        String venId = properties.getVen().getId();
        log.debug("Polling VTN for venId: {}", venId);

        try {
            OadrPollType pollPayload = Oadr20bPollBuilders
                    .newOadr20bPollBuilder(venId)
                    .build();

            Object response = transportService.send(
                    Oadr20bUrlPath.OADR_POLL_SERVICE,
                    pollPayload
            );

            handlePollResponse(response);

        } catch (Exception e) {
            log.error("Poll failed for venId: {}, error: {}", venId, e.getMessage());
        }
    }

    private void handlePollResponse(Object response) {
        if (response instanceof OadrDistributeEventType distributeEvent) {
            log.info("Received OadrDistributeEvent with {} events",
                    distributeEvent.getOadrEvent().size());
            eventHandler.handle(distributeEvent);

        } else if (response instanceof OadrResponseType oadrResponse) {
            String code = oadrResponse.getEiResponse().getResponseCode();
            if (!"200".equals(code)) {
                log.warn("Poll returned non-200 response: {}", code);
            } else {
                log.debug("Poll OK — no new events");
            }
        } else {
            log.warn("Unexpected poll response type: {}",
                    response != null ? response.getClass().getName() : "null");
        }
    }
}
