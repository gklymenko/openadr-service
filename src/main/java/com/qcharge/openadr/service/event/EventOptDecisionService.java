package com.qcharge.openadr.service.event;

import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.service.event.EventValidationService.ParsedSignal;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class EventOptDecisionService {

    public OptTypeType determineOptType(OadrEvent event, ParsedSignal signal) {
        // SIMPLE level=0 means no DR action — still opt in (chargers unaffected)
        if (EventValidationService.SIGNAL_SIMPLE.equals(signal.signalName())) {
            BigDecimal value = signal.currentValue();
            if (value != null && value.compareTo(BigDecimal.ZERO) == 0) {
                return OptTypeType.OPT_IN;
            }
        }

        // LOAD_DISPATCH: we can always curtail EV charging
        if (EventValidationService.SIGNAL_LOAD_DISPATCH.equals(signal.signalName())) {
            return OptTypeType.OPT_IN;
        }

        // ELECTRICITY_PRICE: log and respond; real decision deferred to Phase 9
        return OptTypeType.OPT_IN;
    }
}