package com.qcharge.openadr.service.event;

import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.qcharge.openadr.LogMessage.REQUEST_EVENT_EMPTY;
import static com.qcharge.openadr.LogMessage.SEND_OPENADR_REQUEST;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventRequestService {

    private final VtnTransportService transportService;
    private final EventProtocolAdapter eventProtocolAdapter;

    public void requestAllEvents(OpenAdrSessionSnapshot session, String requestId) {
        log.info(SEND_OPENADR_REQUEST, OpenAdrOperations.REQUEST_EVENT.name(), session.venId(), session.registrationId());

        OadrRequestEventType request = Oadr20bEiEventBuilders
                .newOadrRequestEventBuilder(session.venId(), requestId)
                .build();

        Object response = transportService.send(
                OpenAdrOperations.REQUEST_EVENT, request, session
        );

        if (response instanceof OadrDistributeEventType distributeEvent) {
            eventProtocolAdapter.receive(distributeEvent, session);
        }

        if (response instanceof OadrResponseType) {
            log.info(REQUEST_EVENT_EMPTY);
        }
    }
}