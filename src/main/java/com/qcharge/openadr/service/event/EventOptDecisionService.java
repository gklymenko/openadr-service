package com.qcharge.openadr.service.event;

import com.qcharge.openadr.service.event.command.EventOptType;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class EventOptDecisionService {

    public EventOptType determineOptType(EventSignalCommand signal) {
        // SIMPLE level=0 means no DR action — still opt in (chargers unaffected)
        if (EventValidationService.SIGNAL_SIMPLE.equals(signal.signalName())) {
            BigDecimal value = signal.currentValue();
            if (value != null && value.compareTo(BigDecimal.ZERO) == 0) {
                return EventOptType.OPT_IN;
            }
        }

        // LOAD_DISPATCH: we can always curtail EV charging
        if (EventValidationService.SIGNAL_LOAD_DISPATCH.equals(signal.signalName())) {
            return EventOptType.OPT_IN;
        }

        // ELECTRICITY_PRICE: log and respond; real decision deferred to Phase 9
        return EventOptType.OPT_IN;
    }
}
