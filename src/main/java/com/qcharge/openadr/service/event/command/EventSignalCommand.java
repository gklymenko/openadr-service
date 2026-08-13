package com.qcharge.openadr.service.event.command;

import java.math.BigDecimal;
import java.util.List;

/** Normalized OpenADR signal used by validation, opt policy and persistence mapping. */
public record EventSignalCommand(
        String signalId,
        String signalName,
        String signalType,
        BigDecimal currentValue,
        String itemBaseElement,
        String itemBaseType,
        String itemUnits,
        String siScaleCode,
        List<EventIntervalCommand> intervals,
        SignalTargetCommand target
) {
    public EventSignalCommand {
        intervals = List.copyOf(intervals);
    }
}
