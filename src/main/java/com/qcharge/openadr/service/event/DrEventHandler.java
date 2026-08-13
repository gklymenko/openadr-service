package com.qcharge.openadr.service.event;

import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Inbound facade used by polling and registration flows. */
@Component
@RequiredArgsConstructor
public class DrEventHandler {

    private final EventProtocolAdapter protocolAdapter;
    private final OpenAdrSessionProvider sessionProvider;

    public void handle(OadrDistributeEventType distributeEvent) {
        handle(distributeEvent, sessionProvider.current());
    }

    public void handle(OadrDistributeEventType distributeEvent, OpenAdrSessionSnapshot session) {
        protocolAdapter.receive(distributeEvent, session);
    }
}
