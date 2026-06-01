package com.qcharge.openadr.service.event;

import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import org.springframework.stereotype.Service;

@Service
public class EventOptDecisionService {

    public OptTypeType determineOptType(OadrEvent event) {
        // TODO: check charger/location availability, maintenance mode, manual override, OCPP state.
        return OptTypeType.OPT_IN;
    }
}