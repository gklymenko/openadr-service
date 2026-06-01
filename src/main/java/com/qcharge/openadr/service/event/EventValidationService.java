package com.qcharge.openadr.service.event;

import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import org.springframework.stereotype.Service;

@Service
public class EventValidationService {

    public void validateSupportedEvent(OadrEvent event) {
        // TODO: Validate marketContext, eiTarget, signalName, signalType, units, intervals duration sum.
        // For certification, at least SIMPLE, ELECTRICITY_PRICE price, and LOAD_DISPATCH setpoint should be handled.
    }
}